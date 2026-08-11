package io.reliabilityai.gateway.dataplane.secrets.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.dataplane.secrets.api.CredentialMaterialSource;
import io.reliabilityai.gateway.dataplane.secrets.api.MaterializationRecord;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretSnapshotPort;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretsAuditPort;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretsMetricsPort;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.SecretsProviderPort.MaterializationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Fail-closed behaviour tests for the materialization use-case (Doc 26 §21, SP-INV, §17.1). */
class MaterializationServiceTest {

  private static final TenantScope TENANT = TenantScope.of("org-1", "tenant-1");
  private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
  private static final Duration MARGIN = Duration.ofSeconds(30);
  private static final int MAX_LEN = 4096;
  private static final CredentialRequest REQUEST =
      new CredentialRequest(
          TENANT,
          new RouteTarget(new CanonicalModelId("m1"), "route-ref"),
          new CorrelationId("corr-1"));

  private final FixedClock clock = new FixedClock(NOW);
  private final FakeSnapshotPort snapshotPort = new FakeSnapshotPort();
  private final RecordingAudit audit = new RecordingAudit();
  private final CountingMetrics metrics = new CountingMetrics();
  private final MaterializationService service =
      new MaterializationService(snapshotPort, clock, audit, metrics, MARGIN, MAX_LEN);

  private static CredentialSnapshotRef ref(final TenantScope tenant, final Instant notAfter) {
    return new CredentialSnapshotRef(new SnapshotVersion("credential", "v3"), tenant, notAfter);
  }

  @Test
  void materializesLeaseWhenSnapshotIsValid() {
    snapshotPort.source =
        new FakeSource(ref(TENANT, NOW.plusSeconds(3600)), "sk-live".toCharArray(), false);
    final MaterializationResult result = service.materialize(REQUEST);

    assertThat(result).isInstanceOf(MaterializationResult.Leased.class);
    final var lease = ((MaterializationResult.Leased) result).lease();
    final char[][] observed = new char[1][];
    lease.use(m -> observed[0] = m.clone());
    assertThat(observed[0]).containsExactly('s', 'k', '-', 'l', 'i', 'v', 'e');
    lease.close();
    assertThat(metrics.materialized).isEqualTo(1);
    assertThat(audit.lastOutcome).isEqualTo("leased");
    assertThat(audit.lastVersion.version()).isEqualTo("v3");
  }

  @Test
  void failsClosedWhenSnapshotMissing() {
    snapshotPort.source = null;
    assertUnavailable(service.materialize(REQUEST), "snapshot-missing");
    assertThat(audit.lastOutcome).isEqualTo("unavailable:snapshot-missing");
    assertThat(audit.lastVersion).isNull();
  }

  @Test
  void failsClosedOnTenantScopeMismatch() {
    snapshotPort.source =
        new FakeSource(
            ref(TenantScope.of("org-9", "other"), NOW.plusSeconds(3600)), "x".toCharArray(), false);
    assertUnavailable(service.materialize(REQUEST), "tenant-scope-mismatch");
  }

  @Test
  void failsClosedOnExpiredCredential() {
    snapshotPort.source =
        new FakeSource(ref(TENANT, NOW.minusSeconds(1)), "x".toCharArray(), false);
    assertUnavailable(service.materialize(REQUEST), "credential-expired");
  }

  @Test
  void failsClosedOnNearExpiryCredential() {
    snapshotPort.source =
        new FakeSource(ref(TENANT, NOW.plusSeconds(10)), "x".toCharArray(), false);
    assertUnavailable(service.materialize(REQUEST), "credential-near-expiry");
  }

  @Test
  void failsClosedOnEmptyMaterial() {
    snapshotPort.source = new FakeSource(ref(TENANT, NOW.plusSeconds(3600)), new char[0], false);
    assertUnavailable(service.materialize(REQUEST), "credential-empty");
  }

  @Test
  void failsClosedAndSanitizesOnMaterializationError() {
    snapshotPort.source =
        new FakeSource(ref(TENANT, NOW.plusSeconds(3600)), "x".toCharArray(), true);
    assertUnavailable(service.materialize(REQUEST), "materialization-error");
    assertThat(metrics.sanitizations).isEqualTo(1);
    assertThat(metrics.materialized).isZero();
  }

  @Test
  void failsClosedOnUnexpectedInternalError() {
    // Doc 26 §21 / SP-D11: any unknown/internal error (here, the snapshot source throwing) fails
    // closed to CredentialUnavailable — the exception never escapes.
    snapshotPort.throwOnResolve = true;
    assertUnavailable(service.materialize(REQUEST), "internal-error");
  }

  @Test
  void auditSinkFailureDoesNotBlockMaterialization() {
    // Doc 26 SP-D12: an audit-sink fault never blocks the flow or discards a valid lease.
    snapshotPort.source =
        new FakeSource(ref(TENANT, NOW.plusSeconds(3600)), "sk".toCharArray(), false);
    audit.throwOnRecord = true;
    final MaterializationResult result = service.materialize(REQUEST);
    assertThat(result).isInstanceOf(MaterializationResult.Leased.class);
    assertThat(metrics.materialized).isEqualTo(1);
    ((MaterializationResult.Leased) result).lease().close();
  }

  @Test
  void throwingMetricsDoesNotOrphanLeaseOnSuccess() {
    // H-1: a throwing metrics sink must not break the flow (OT-A1) — the lease is still returned,
    // never orphaned/leaked.
    snapshotPort.source =
        new FakeSource(ref(TENANT, NOW.plusSeconds(3600)), "sk".toCharArray(), false);
    metrics.throwing = true;
    final MaterializationResult result = service.materialize(REQUEST);
    assertThat(result).isInstanceOf(MaterializationResult.Leased.class);
    ((MaterializationResult.Leased) result).lease().close();
  }

  @Test
  void throwingMetricsStillFailsClosedOnUnavailable() {
    // H-1: even if metrics throws on the fail-closed path, materialize() returns
    // CredentialUnavailable
    // rather than letting the exception escape.
    snapshotPort.source = null;
    metrics.throwing = true;
    assertThat(service.materialize(REQUEST))
        .isInstanceOf(MaterializationResult.CredentialUnavailable.class);
  }

  @Test
  void failsClosedOnOversizedCredentialWithoutAllocating() {
    // H-1: a snapshot source reporting a length beyond the injected max must fail closed BEFORE any
    // allocation — no OOM. length() = Integer.MAX_VALUE would OOM if allocated.
    snapshotPort.source =
        new OversizedSource(ref(TENANT, NOW.plusSeconds(3600)), Integer.MAX_VALUE);
    assertUnavailable(service.materialize(REQUEST), "credential-oversized");
    assertThat(metrics.materialized).isZero();
  }

  @Test
  void acceptsCredentialExactlyAtTheMaximumLength() {
    // maxCredentialLength is a ceiling, not an exclusive limit. failsClosedOnOversizedCredential...
    // proves only that Integer.MAX_VALUE is refused; it says nothing about the boundary itself, so
    // a `>` that drifted to `>=` would silently refuse every credential sitting exactly on the
    // configured baseline and look like a fail-closed policy rather than an off-by-one.
    final char[] atLimit = new char[MAX_LEN];
    Arrays.fill(atLimit, 'k');
    snapshotPort.source = new FakeSource(ref(TENANT, NOW.plusSeconds(3600)), atLimit, false);

    assertThat(service.materialize(REQUEST)).isInstanceOf(MaterializationResult.Leased.class);
    assertThat(metrics.materialized).isEqualTo(1);
  }

  @Test
  void sanitizesPartiallyCopiedMaterialWhenLeaseConstructionFails() {
    // F-2 (Doc 26 MSC-10/SP-INV): a secret never escapes sanitization. The existing
    // failsClosedAndSanitizesOnMaterializationError cannot prove this — its source throws BEFORE
    // writing anything, so the buffer it would inspect is still all zeros and the Arrays.fill has
    // nothing to erase. It asserts the metric, not the wipe. This source populates the service's
    // own buffer with real secret material, keeps the reference, and only then fails, which makes
    // the difference between "wiped" and "not wiped" observable.
    final PopulateThenThrowSource source =
        new PopulateThenThrowSource(
            ref(TENANT, NOW.plusSeconds(3600)), "super-secret-value".toCharArray());
    snapshotPort.source = source;

    assertUnavailable(service.materialize(REQUEST), "materialization-error");

    assertThat(source.captured).as("the service must hand its buffer to copyInto").isNotNull();
    assertThat(source.captured).containsOnly('\0');
    assertThat(new String(source.captured)).doesNotContain("super-secret-value");
    assertThat(metrics.sanitizations).isEqualTo(1);
    assertThat(metrics.materialized).isZero();
  }

  @Test
  void rejectsNullRequest() {
    assertThatThrownBy(() -> service.materialize(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructorRejectsNegativeMargin() {
    assertThatThrownBy(
            () ->
                new MaterializationService(
                    snapshotPort, clock, audit, metrics, Duration.ofSeconds(-1), MAX_LEN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNonPositiveMaxLength() {
    assertThatThrownBy(
            () -> new MaterializationService(snapshotPort, clock, audit, metrics, MARGIN, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void assertUnavailable(final MaterializationResult result, final String reason) {
    assertThat(result).isInstanceOf(MaterializationResult.CredentialUnavailable.class);
    assertThat(((MaterializationResult.CredentialUnavailable) result).reason()).isEqualTo(reason);
    assertThat(metrics.unavailableReasons).contains(reason);
  }

  private static final class FixedClock implements ClockPort {
    private final Instant fixed;

    private FixedClock(final Instant fixed) {
      this.fixed = fixed;
    }

    @Override
    public Instant now() {
      return fixed;
    }
  }

  private static final class FakeSource implements CredentialMaterialSource {
    private final CredentialSnapshotRef ref;
    private final char[] material;
    private final boolean throwOnCopy;

    private FakeSource(
        final CredentialSnapshotRef ref, final char[] material, final boolean throwOnCopy) {
      this.ref = ref;
      this.material = material;
      this.throwOnCopy = throwOnCopy;
    }

    @Override
    public CredentialSnapshotRef ref() {
      return ref;
    }

    @Override
    public int length() {
      return material.length;
    }

    @Override
    public void copyInto(final char[] destination) {
      if (throwOnCopy) {
        throw new IllegalStateException("copy failure");
      }
      System.arraycopy(material, 0, destination, 0, material.length);
    }
  }

  /**
   * A source that genuinely populates the destination with secret material and only then fails,
   * retaining the destination reference so a test can assert the buffer was wiped afterwards.
   */
  private static final class PopulateThenThrowSource implements CredentialMaterialSource {
    private final CredentialSnapshotRef ref;
    private final char[] material;
    private char[] captured;

    private PopulateThenThrowSource(final CredentialSnapshotRef ref, final char[] material) {
      this.ref = ref;
      this.material = material;
    }

    @Override
    public CredentialSnapshotRef ref() {
      return ref;
    }

    @Override
    public int length() {
      return material.length;
    }

    @Override
    public void copyInto(final char[] destination) {
      captured = destination;
      System.arraycopy(material, 0, destination, 0, material.length);
      throw new IllegalStateException("failure after the buffer was populated");
    }
  }

  /** A source that lies about its length; its material must never be allocated/copied. */
  private static final class OversizedSource implements CredentialMaterialSource {
    private final CredentialSnapshotRef ref;
    private final int length;

    private OversizedSource(final CredentialSnapshotRef ref, final int length) {
      this.ref = ref;
      this.length = length;
    }

    @Override
    public CredentialSnapshotRef ref() {
      return ref;
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public void copyInto(final char[] destination) {
      throw new AssertionError("copyInto must not be reached for an oversized credential");
    }
  }

  private static final class FakeSnapshotPort implements SecretSnapshotPort {
    private CredentialMaterialSource source;
    private boolean throwOnResolve;

    @Override
    public Optional<CredentialMaterialSource> resolve(
        final TenantScope tenantScope, final RouteTarget routeTarget) {
      if (throwOnResolve) {
        throw new IllegalStateException("resolve failure");
      }
      return Optional.ofNullable(source);
    }
  }

  private static final class RecordingAudit implements SecretsAuditPort {
    private String lastOutcome;
    private SnapshotVersion lastVersion;
    private boolean throwOnRecord;

    @Override
    public void record(final MaterializationRecord record) {
      if (throwOnRecord) {
        throw new IllegalStateException("audit sink down");
      }
      lastOutcome = record.outcome();
      lastVersion = record.snapshotVersion();
    }
  }

  private static final class CountingMetrics implements SecretsMetricsPort {
    private int materialized;
    private int sanitizations;
    private int leaks;
    private boolean throwing;
    private final java.util.List<String> unavailableReasons = new java.util.ArrayList<>();

    @Override
    public void materialized() {
      if (throwing) {
        throw new IllegalStateException("metrics backend down");
      }
      materialized++;
    }

    @Override
    public void credentialUnavailable(final String reason) {
      if (throwing) {
        throw new IllegalStateException("metrics backend down");
      }
      unavailableReasons.add(reason);
    }

    @Override
    public void sanitization(final boolean complete) {
      if (throwing) {
        throw new IllegalStateException("metrics backend down");
      }
      sanitizations++;
    }

    @Override
    public void leaseLeakDetected() {
      leaks++;
    }
  }
}
