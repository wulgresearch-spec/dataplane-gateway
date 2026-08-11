package io.reliabilityai.gateway.dataplane.metering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.decision.AttemptClass;
import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.dataplane.metering.api.CostUsagePort;
import io.reliabilityai.gateway.dataplane.metering.api.DurableUsageWalPort;
import io.reliabilityai.gateway.dataplane.metering.api.LedgerSinkPort;
import io.reliabilityai.gateway.dataplane.metering.api.MeteringOutcomeSink;
import io.reliabilityai.gateway.dataplane.metering.api.MeteringPolicy;
import io.reliabilityai.gateway.dataplane.metering.api.QuotaCounterPort;
import io.reliabilityai.gateway.dataplane.metering.api.UsageDescriptorPort;
import io.reliabilityai.gateway.dataplane.metering.domain.ExecutionFact;
import io.reliabilityai.gateway.dataplane.metering.domain.MeteringResult;
import io.reliabilityai.gateway.dataplane.metering.domain.RequestUsageManifest;
import io.reliabilityai.gateway.dataplane.metering.domain.UnrecordedReason;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageDescriptor;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Fail-closed, exactly-once, completeness tests for the Usage Metering Engine (Doc 23). */
class UsageMeteringServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");
  private static final RequestId REQ = new RequestId("req-1");
  private static final IdempotencyKey KEY = new IdempotencyKey("idem-1");
  private static final CanonicalModelId MODEL = new CanonicalModelId("m");
  private static final TenantScope TENANT = TenantScope.of("org", "tenant");
  private static final Region REGION = new Region("eu-west-1");

  private final FakeDescriptor descriptor = new FakeDescriptor();
  private final FakeWal wal = new FakeWal();
  private final FakeLedger ledger = new FakeLedger();
  private final FakeQuota quota = new FakeQuota();
  private final FakeCost cost = new FakeCost();
  private final ClockPort clock = () -> NOW;

  private UsageMeteringService service(final MeteringPolicy policy) {
    return new UsageMeteringService(
        descriptor, wal, ledger, quota, cost, MeteringOutcomeSink.NO_OP, clock, policy, 1024);
  }

  private UsageMeteringService service() {
    return service(MeteringPolicy.FAIL_CLOSED);
  }

  private static CanonicalUsage units(
      final long prompt, final long completion, final UsageClass cls) {
    return new CanonicalUsage(prompt, completion, 0, 0, 0, cls);
  }

  private static ExecutionFact fact(
      final String attemptId,
      final AttemptClass attemptClass,
      final boolean delivered,
      final CanonicalUsage usage,
      final UsageClass usageClass) {
    return new ExecutionFact(
        REQ,
        KEY,
        new AttemptId(attemptId),
        TENANT,
        MODEL,
        REGION,
        attemptClass,
        delivered,
        usage,
        usageClass);
  }

  @Test
  void recordsAuthoritativeAttemptDurablyAndEmits() {
    final MeteringResult result =
        service()
            .meterAttempt(
                fact(
                    "a1",
                    AttemptClass.SUCCESSFUL,
                    true,
                    units(10, 5, UsageClass.AUTHORITATIVE),
                    UsageClass.AUTHORITATIVE));
    assertThat(result).isInstanceOf(MeteringResult.Recorded.class);
    final UsageFact f = ((MeteringResult.Recorded) result).fact();
    assertThat(f.units().prompt()).isEqualTo(10);
    assertThat(f.sourceVersions()).containsEntry("usageDescriptor", "ud-v1");
    assertThat(wal.committed).hasSize(1); // durably WAL-committed (RPO=0 boundary)
    assertThat(ledger.facts).hasSize(1);
    assertThat(quota.increments).isEqualTo(1);
    assertThat(cost.submitted).hasSize(1);
  }

  @Test
  void missingUsageFailsClosedNeverFabricates() {
    final MeteringResult result =
        service().meterAttempt(fact("a1", AttemptClass.FAILED, false, null, null));
    assertThat(result).isInstanceOf(MeteringResult.UsageUnrecorded.class);
    assertThat(((MeteringResult.UsageUnrecorded) result).reason())
        .isEqualTo(UnrecordedReason.MISSING_USAGE);
    assertThat(wal.committed).isEmpty(); // nothing durably recorded
  }

  @Test
  void estimatedFailsClosedByDefaultButRecordsUnderPolicy() {
    final ExecutionFact estimated =
        fact(
            "a1",
            AttemptClass.SUCCESSFUL,
            true,
            units(3, 1, UsageClass.ESTIMATED),
            UsageClass.ESTIMATED);
    assertThat(service(MeteringPolicy.FAIL_CLOSED).meterAttempt(estimated))
        .isInstanceOfSatisfying(
            MeteringResult.UsageUnrecorded.class,
            u -> assertThat(u.reason()).isEqualTo(UnrecordedReason.ESTIMATED_NOT_PERMITTED));
    // With policy allowing flagged-estimated, it is recorded but flagged estimated (never
    // authoritative).
    final MeteringResult allowed = service(new MeteringPolicy(true)).meterAttempt(estimated);
    assertThat(allowed).isInstanceOf(MeteringResult.Recorded.class);
    assertThat(((MeteringResult.Recorded) allowed).fact().usageClass())
        .isEqualTo(UsageClass.ESTIMATED);
  }

  @Test
  void missingDescriptorFailsClosed() {
    descriptor.present = false;
    assertThat(
            service()
                .meterAttempt(
                    fact(
                        "a1",
                        AttemptClass.SUCCESSFUL,
                        true,
                        units(1, 1, UsageClass.AUTHORITATIVE),
                        UsageClass.AUTHORITATIVE)))
        .isInstanceOfSatisfying(
            MeteringResult.UsageUnrecorded.class,
            u -> assertThat(u.reason()).isEqualTo(UnrecordedReason.MISSING_DESCRIPTOR));
  }

  @Test
  void walSaturationFailsSafeNeverSilentDrop() {
    wal.accept = false;
    assertThat(
            service()
                .meterAttempt(
                    fact(
                        "a1",
                        AttemptClass.SUCCESSFUL,
                        true,
                        units(1, 1, UsageClass.AUTHORITATIVE),
                        UsageClass.AUTHORITATIVE)))
        .isInstanceOfSatisfying(
            MeteringResult.UsageUnrecorded.class,
            u -> assertThat(u.reason()).isEqualTo(UnrecordedReason.DURABLE_CAPTURE_FAILED));
    assertThat(ledger.facts).isEmpty(); // never emitted un-captured usage
  }

  @Test
  void finalizeSumsProviderUsageAndMarksCustomerDelivered() {
    final UsageMeteringService svc = service();
    svc.meterAttempt(
        fact(
            "a1",
            AttemptClass.FAILOVER,
            false,
            units(10, 0, UsageClass.AUTHORITATIVE),
            UsageClass.AUTHORITATIVE));
    svc.meterAttempt(
        fact(
            "a2",
            AttemptClass.SUCCESSFUL,
            true,
            units(20, 8, UsageClass.AUTHORITATIVE),
            UsageClass.AUTHORITATIVE));
    final RequestUsageManifest manifest = svc.finalizeRequest(REQ);
    assertThat(manifest.complete()).isTrue();
    assertThat(manifest.attemptCount()).isEqualTo(2);
    assertThat(manifest.providerUsageTotal().prompt()).isEqualTo(30); // Σ all attempts (§26)
    assertThat(manifest.customerUsage().prompt()).isEqualTo(20); // delivered only (§27)
  }

  @Test
  void replayedAttemptIsIdempotentNeverDoubleCountsNorFalseAmbiguity() {
    // The same execution fact (same attemptId, delivered) metered twice — a replay. Provider usage
    // must
    // not double-count and the re-delivered same attempt must NOT trip a false AMBIGUOUS_DELIVERED.
    final UsageMeteringService svc = service();
    final ExecutionFact f =
        fact(
            "a1",
            AttemptClass.SUCCESSFUL,
            true,
            units(10, 5, UsageClass.AUTHORITATIVE),
            UsageClass.AUTHORITATIVE);
    svc.meterAttempt(f);
    svc.meterAttempt(f); // replay of the same attempt
    final RequestUsageManifest manifest = svc.finalizeRequest(REQ);
    assertThat(manifest.complete()).isTrue(); // not falsely ambiguous
    assertThat(manifest.attemptCount()).isEqualTo(1); // counted once
    assertThat(manifest.providerUsageTotal().prompt()).isEqualTo(10); // not doubled
    assertThat(manifest.customerUsage().prompt()).isEqualTo(10);
  }

  @Test
  void finalizeFailsClosedWhenAnyAttemptUnrecorded() {
    final UsageMeteringService svc = service();
    svc.meterAttempt(
        fact(
            "a1",
            AttemptClass.SUCCESSFUL,
            true,
            units(10, 0, UsageClass.AUTHORITATIVE),
            UsageClass.AUTHORITATIVE));
    svc.meterAttempt(fact("a2", AttemptClass.FAILED, false, null, null)); // missing usage
    final RequestUsageManifest manifest = svc.finalizeRequest(REQ);
    assertThat(manifest.complete()).isFalse();
    assertThat(manifest.incompleteReason()).isEqualTo(UnrecordedReason.MISSING_USAGE);
    assertThat(manifest.customerUsage()).isNull();
  }

  @Test
  void twoDeliveredAttemptsFinalizeAmbiguous() {
    final UsageMeteringService svc = service();
    svc.meterAttempt(
        fact(
            "a1",
            AttemptClass.SUCCESSFUL,
            true,
            units(1, 1, UsageClass.AUTHORITATIVE),
            UsageClass.AUTHORITATIVE));
    svc.meterAttempt(
        fact(
            "a2",
            AttemptClass.SUCCESSFUL,
            true,
            units(1, 1, UsageClass.AUTHORITATIVE),
            UsageClass.AUTHORITATIVE));
    final RequestUsageManifest manifest = svc.finalizeRequest(REQ);
    assertThat(manifest.complete()).isFalse();
    assertThat(manifest.incompleteReason()).isEqualTo(UnrecordedReason.AMBIGUOUS_DELIVERED);
    // both provider-usage facts were still recorded (never suppressed, AT-1)
    assertThat(manifest.attemptCount()).isEqualTo(2);
  }

  @Test
  void boundedLruEvictsAbandonedRequestsWithoutUsageLoss() {
    // Bounded to 2 in-flight requests (Doc 23 §41). Three distinct requests, none finalized ⇒ the
    // oldest is evicted. Its fact was still durably WAL-committed (no usage loss); finalizing the
    // evicted request can no longer assert completeness ⇒ fail closed (never a silent complete).
    final UsageMeteringService svc =
        new UsageMeteringService(
            descriptor,
            wal,
            ledger,
            quota,
            cost,
            MeteringOutcomeSink.NO_OP,
            clock,
            MeteringPolicy.FAIL_CLOSED,
            2);
    for (final String r : new String[] {"r1", "r2", "r3"}) {
      svc.meterAttempt(
          new ExecutionFact(
              new RequestId(r),
              KEY,
              new AttemptId("a"),
              TENANT,
              MODEL,
              REGION,
              AttemptClass.SUCCESSFUL,
              true,
              units(1, 1, UsageClass.AUTHORITATIVE),
              UsageClass.AUTHORITATIVE));
    }
    assertThat(wal.committed).hasSize(3); // all three durably captured — never lost
    final RequestUsageManifest evicted = svc.finalizeRequest(new RequestId("r1"));
    assertThat(evicted.complete()).isFalse();
    assertThat(evicted.incompleteReason()).isEqualTo(UnrecordedReason.INTERNAL_ERROR);
    // The most-recent request is still tracked and finalizes complete.
    final RequestUsageManifest kept = svc.finalizeRequest(new RequestId("r3"));
    assertThat(kept.complete()).isTrue();
  }

  @Test
  void finalizeUnknownRequestFailsClosed() {
    final RequestUsageManifest manifest = service().finalizeRequest(new RequestId("never"));
    assertThat(manifest.complete()).isFalse();
    assertThat(manifest.incompleteReason()).isEqualTo(UnrecordedReason.INTERNAL_ERROR);
  }

  @Test
  void deterministicSameFactSameUsageFact() {
    final ExecutionFact f =
        fact(
            "a1",
            AttemptClass.SUCCESSFUL,
            true,
            units(7, 3, UsageClass.AUTHORITATIVE),
            UsageClass.AUTHORITATIVE);
    final UsageFact first = ((MeteringResult.Recorded) service().meterAttempt(f)).fact();
    final UsageFact second = ((MeteringResult.Recorded) service().meterAttempt(f)).fact();
    assertThat(first).isEqualTo(second); // deterministic given (fact, descriptor, clock) (§36)
  }

  @Test
  void rejectsNullArguments() {
    final UsageMeteringService svc = service();
    assertThatThrownBy(() -> svc.meterAttempt(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> svc.finalizeRequest(null)).isInstanceOf(NullPointerException.class);
  }

  private static final class FakeDescriptor implements UsageDescriptorPort {
    private boolean present = true;

    @Override
    public Optional<UsageDescriptor> descriptorFor(final CanonicalModelId canonicalModelId) {
      return present
          ? Optional.of(new UsageDescriptor(canonicalModelId, "ud-v1"))
          : Optional.empty();
    }
  }

  private static final class FakeWal implements DurableUsageWalPort {
    private boolean accept = true;
    private final List<UsageFact> committed = new ArrayList<>();

    @Override
    public boolean commit(final UsageFact fact) {
      if (!accept) {
        return false;
      }
      committed.add(fact);
      return true;
    }
  }

  private static final class FakeLedger implements LedgerSinkPort {
    private final List<UsageFact> facts = new ArrayList<>();
    private final List<RequestUsageManifest> manifests = new ArrayList<>();

    @Override
    public void emitFact(final UsageFact fact) {
      facts.add(fact);
    }

    @Override
    public void emitManifest(final RequestUsageManifest manifest) {
      manifests.add(manifest);
    }
  }

  private static final class FakeQuota implements QuotaCounterPort {
    private int increments;

    @Override
    public void increment(final UsageFact fact, final String counterKey) {
      increments++;
    }
  }

  private static final class FakeCost implements CostUsagePort {
    private final List<UsageFact> submitted = new ArrayList<>();

    @Override
    public void submit(final UsageFact fact) {
      submitted.add(fact);
    }
  }
}
