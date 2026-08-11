package io.reliabilityai.gateway.dataplane.metering.application;

import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.metering.api.CostUsagePort;
import io.reliabilityai.gateway.dataplane.metering.api.DurableUsageWalPort;
import io.reliabilityai.gateway.dataplane.metering.api.LedgerSinkPort;
import io.reliabilityai.gateway.dataplane.metering.api.MeteringOutcomeSink;
import io.reliabilityai.gateway.dataplane.metering.api.MeteringPolicy;
import io.reliabilityai.gateway.dataplane.metering.api.QuotaCounterPort;
import io.reliabilityai.gateway.dataplane.metering.api.UsageDescriptorPort;
import io.reliabilityai.gateway.dataplane.metering.api.UsageMeteringEnginePort;
import io.reliabilityai.gateway.dataplane.metering.domain.ExecutionFact;
import io.reliabilityai.gateway.dataplane.metering.domain.MeteringResult;
import io.reliabilityai.gateway.dataplane.metering.domain.RequestUsageManifest;
import io.reliabilityai.gateway.dataplane.metering.domain.UnrecordedReason;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageAggregation;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageDescriptor;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageNormalizer;
import io.reliabilityai.gateway.ports.ClockPort;
import java.util.Optional;

/**
 * The Usage Metering Engine (Doc 23 §7/§11) — the stateless, provider-neutral, <b>record-only</b>
 * runtime usage-fact engine realizing UME-INV (never lose / duplicate / fabricate / estimate /
 * silently discard usage). It normalizes execution facts into immutable canonical usage facts
 * (never fabricating or estimating, §D5/§24), <b>durably WAL-commits</b> before treating usage as
 * accounted (RPO=0 boundary at WAL commit, §39.1 UC-1), then idempotency-keyed emits to the ledger,
 * Governance quota counters, and the Cost Engine (§29/§30/§31). On any uncertainty — missing usage,
 * estimated-by-default, descriptor gap, ambiguous delivered, WAL saturation — it fails closed to
 * {@code UsageUnrecorded} (§38).
 *
 * <p>Records both provider usage (Σ all attempt facts, §26) and the single delivered customer usage
 * (§27), and finalizes with a completeness manifest that is complete <b>only</b> when every attempt
 * was durably recorded and none was unrecorded/ambiguous (§17.1). It <b>never prices</b> (Cost
 * Engine's), <b>never enforces</b> quota (Governance's), <b>never persists a ledger</b> (C5-CP's),
 * and <b>never branches on provider identity</b> (AD-007). Deterministic given the port results
 * (§36); no wall-clock/random in the core (time via {@link ClockPort}, R-063). Per-request state is
 * transient and isolated (AD-021).
 */
public final class UsageMeteringService implements UsageMeteringEnginePort {

  private final UsageDescriptorPort descriptorPort;
  private final DurableUsageWalPort wal;
  private final LedgerSinkPort ledger;
  private final QuotaCounterPort quota;
  private final CostUsagePort cost;
  private final MeteringOutcomeSink sink;
  private final ClockPort clock;
  private final MeteringPolicy policy;

  private final int maxTrackedRequests;

  // Bounded LRU of in-flight per-request meters (Doc 23 §41 — no unbounded structure). Guarded by
  // {@code this}: access is O(1), off the response critical path (§40), no I/O under the lock
  // (R-049).
  // Evicting an abandoned (never-finalized) meter is safe — its facts are already WAL-durable, so
  // no
  // usage is lost; the missing {@code RequestFinalized} is caught by the Doc 08 §10 loss detector
  // (FC-10).
  private final java.util.Map<RequestId, RequestMeter> requests;

  /**
   * Creates the engine against its injected ports (AD-002).
   *
   * @param descriptorPort the usage-descriptor snapshot source (Doc 23 §9)
   * @param wal the zero-loss durable WAL seam (Doc 23 §39.1)
   * @param ledger the C5-CP ledger sink (Doc 23 §29)
   * @param quota the Governance quota-counter seam (Doc 23 §31)
   * @param cost the Cost Engine seam (Doc 23 §30)
   * @param sink the content-free metering-outcome sink (Doc 23 §44); use {@link
   *     MeteringOutcomeSink#NO_OP}
   * @param clock the deterministic time seam (Doc 23 §36)
   * @param policy the injected metering policy (Doc 23 §24)
   * @param maxTrackedRequests the bounded cap on concurrently-tracked in-flight requests
   *     (operational baseline, Doc 23 §41); {@code >= 1}
   */
  public UsageMeteringService(
      final UsageDescriptorPort descriptorPort,
      final DurableUsageWalPort wal,
      final LedgerSinkPort ledger,
      final QuotaCounterPort quota,
      final CostUsagePort cost,
      final MeteringOutcomeSink sink,
      final ClockPort clock,
      final MeteringPolicy policy,
      final int maxTrackedRequests) {
    this.descriptorPort = Preconditions.requireNonNull(descriptorPort, "descriptorPort");
    this.wal = Preconditions.requireNonNull(wal, "wal");
    this.ledger = Preconditions.requireNonNull(ledger, "ledger");
    this.quota = Preconditions.requireNonNull(quota, "quota");
    this.cost = Preconditions.requireNonNull(cost, "cost");
    this.sink = Preconditions.requireNonNull(sink, "sink");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.policy = Preconditions.requireNonNull(policy, "policy");
    if (maxTrackedRequests < 1) {
      throw new IllegalArgumentException("maxTrackedRequests must be >= 1");
    }
    this.maxTrackedRequests = maxTrackedRequests;
    this.requests =
        new java.util.LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(
              final java.util.Map.Entry<RequestId, RequestMeter> eldest) {
            return size() > UsageMeteringService.this.maxTrackedRequests;
          }
        };
  }

  private synchronized RequestMeter meterFor(final RequestId requestId) {
    return requests.computeIfAbsent(requestId, k -> new RequestMeter());
  }

  private synchronized RequestMeter removeMeter(final RequestId requestId) {
    return requests.remove(requestId);
  }

  @Override
  public MeteringResult meterAttempt(final ExecutionFact fact) {
    Preconditions.requireNonNull(fact, "fact");
    final RequestMeter meter = meterFor(fact.requestId());
    try {
      return record(fact, meter);
    } catch (final RuntimeException unexpected) {
      // Fail-closed backstop: any internal error surfaces as unrecorded, never a silent success
      // (§38).
      meter.latch(UnrecordedReason.INTERNAL_ERROR);
      safeUnrecorded(UnrecordedReason.INTERNAL_ERROR);
      return new MeteringResult.UsageUnrecorded(UnrecordedReason.INTERNAL_ERROR, fact.attemptId());
    }
  }

  private MeteringResult record(final ExecutionFact fact, final RequestMeter meter) {
    final Optional<UsageDescriptor> descriptor =
        descriptorPort.descriptorFor(fact.canonicalModelId());
    if (descriptor.isEmpty()) {
      return unrecorded(meter, UnrecordedReason.MISSING_DESCRIPTOR, fact);
    }

    final MeteringResult normalized =
        UsageNormalizer.normalize(fact, descriptor.get(), clock.now(), policy.allowEstimated());
    if (normalized instanceof MeteringResult.UsageUnrecorded unrec) {
      meter.latch(unrec.reason());
      safeUnrecorded(unrec.reason());
      return normalized; // fail closed (missing/estimated usage, §D5/§24)
    }

    final UsageFact usageFact = ((MeteringResult.Recorded) normalized).fact();

    // Durable capture is the RPO=0 boundary (§39.1 UC-1); saturation ⇒ fail-safe reject (UC-12).
    final boolean committed;
    try {
      committed = wal.commit(usageFact);
    } catch (final RuntimeException walError) {
      return unrecorded(meter, UnrecordedReason.DURABLE_CAPTURE_FAILED, fact);
    }
    if (!committed) {
      return unrecorded(meter, UnrecordedReason.DURABLE_CAPTURE_FAILED, fact);
    }

    // Post-WAL: the fact is durable. Downstream emission is best-effort (outbox/replay makes it
    // effectively-once, §39.1 UC-6/UC-7) and never un-does the durable record.
    meter.recordFact(usageFact);
    safeLedger(usageFact);
    safeQuota(usageFact);
    safeCost(usageFact);
    return normalized;
  }

  @Override
  public RequestUsageManifest finalizeRequest(final RequestId requestId) {
    Preconditions.requireNonNull(requestId, "requestId");
    final RequestMeter meter = removeMeter(requestId); // bounded: transient per-request state
    if (meter == null) {
      // Nothing tracked ⇒ cannot assert completeness ⇒ fail closed (never a silent complete,
      // FC-11).
      final RequestUsageManifest incomplete =
          new RequestUsageManifest(
              requestId, false, UsageAggregation.ZERO, null, 0, UnrecordedReason.INTERNAL_ERROR);
      safeFinalized(incomplete);
      return incomplete;
    }
    final UnrecordedReason reason = meter.incompleteReason();
    final RequestUsageManifest manifest;
    if (reason != null) {
      // Any unrecorded/ambiguous attempt ⇒ INCOMPLETE, never a silent complete (FC-7/FC-12).
      manifest =
          new RequestUsageManifest(
              requestId, false, meter.providerTotal(), null, meter.attemptCount(), reason);
    } else {
      manifest =
          new RequestUsageManifest(
              requestId,
              true,
              meter.providerTotal(),
              meter.deliveredUsage(),
              meter.attemptCount(),
              null);
    }
    safeFinalized(manifest);
    return manifest;
  }

  private MeteringResult unrecorded(
      final RequestMeter meter, final UnrecordedReason reason, final ExecutionFact fact) {
    meter.latch(reason);
    safeUnrecorded(reason);
    return new MeteringResult.UsageUnrecorded(reason, fact.attemptId());
  }

  // --- Best-effort emission/telemetry: durability is already guaranteed at WAL (§39.1 UC-5).

  private void safeLedger(final UsageFact fact) {
    try {
      ledger.emitFact(fact);
    } catch (final RuntimeException ignored) {
      // WAL-committed ⇒ outbox replay re-forwards; never un-does the durable record (UC-6)
    }
  }

  private void safeQuota(final UsageFact fact) {
    try {
      quota.increment(fact, "tokens"); // idempotent (idempotencyKey, attemptId, counterKey), §31
    } catch (final RuntimeException ignored) {
      // best-effort; replay re-increments at-most-once
    }
  }

  private void safeCost(final UsageFact fact) {
    try {
      cost.submit(fact);
    } catch (final RuntimeException ignored) {
      // best-effort; the Cost Engine reads durable facts
    }
  }

  private void safeFinalized(final RequestUsageManifest manifest) {
    try {
      ledger.emitManifest(manifest);
    } catch (final RuntimeException ignored) {
      // best-effort manifest emission
    }
    try {
      sink.onFinalized(manifest);
    } catch (final RuntimeException ignored) {
      // content-free sink is best-effort
    }
  }

  private void safeUnrecorded(final UnrecordedReason reason) {
    try {
      sink.onUnrecorded(reason);
    } catch (final RuntimeException ignored) {
      // content-free sink is best-effort
    }
  }
}
