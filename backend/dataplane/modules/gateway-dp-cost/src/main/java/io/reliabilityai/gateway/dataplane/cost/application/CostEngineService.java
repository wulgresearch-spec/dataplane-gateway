package io.reliabilityai.gateway.dataplane.cost.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.api.ComputationOutcome;
import io.reliabilityai.gateway.dataplane.cost.api.ContractSnapshotPort;
import io.reliabilityai.gateway.dataplane.cost.api.CostEnginePort;
import io.reliabilityai.gateway.dataplane.cost.api.CostLedgerSinkPort;
import io.reliabilityai.gateway.dataplane.cost.api.CostOutcomeSink;
import io.reliabilityai.gateway.dataplane.cost.api.CostProjection;
import io.reliabilityai.gateway.dataplane.cost.api.CostRequest;
import io.reliabilityai.gateway.dataplane.cost.api.CostResult;
import io.reliabilityai.gateway.dataplane.cost.api.CostUnavailable;
import io.reliabilityai.gateway.dataplane.cost.api.PricingSnapshotPort;
import io.reliabilityai.gateway.dataplane.cost.domain.ContractEntitlement;
import io.reliabilityai.gateway.dataplane.cost.domain.CostCalculator;
import io.reliabilityai.gateway.dataplane.cost.domain.CostUnavailableReason;
import io.reliabilityai.gateway.dataplane.cost.domain.FxRate;
import io.reliabilityai.gateway.dataplane.cost.domain.FxTable;
import io.reliabilityai.gateway.dataplane.cost.domain.Money;
import io.reliabilityai.gateway.dataplane.cost.domain.NormalizedUsage;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingResolution;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingResolver;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingSnapshot;
import io.reliabilityai.gateway.dataplane.cost.domain.UsageConfidence;
import io.reliabilityai.gateway.ports.ClockPort;
import java.util.Map;
import java.util.Optional;

/**
 * The Cost Engine (Doc 22 §7) — the authoritative, deterministic, <b>calculation-only</b> runtime
 * cost engine realizing CE-INV (never expose an incorrect cost, never underestimate, never
 * guess/estimate without authoritative pricing). It resolves the versioned pricing snapshot (fail
 * closed on missing/stale, §13.1), the FX table (fail closed on missing/stale, §16.1), and the
 * deterministic pricing precedence (§15, no silent list fallback), then computes a
 * <b>never-underestimated projection</b> (conservative FX + max usage, §23) or the <b>exact
 * actual</b> cost (§21). On any uncertainty it returns {@code CostUnavailable} (fail closed, §39) —
 * never a fabricated, defaulted, or under-estimated cost.
 *
 * <p>It <b>never</b> charges/invoices/persists a ledger (Cost is calculation-only, CE-D1), enforces
 * budgets (Governance's), authors pricing (C5-CP's), or branches on provider identity (AD-007).
 * Deterministic given the snapshot/FX/contract versions (§CE-D8), each stamped on the result for
 * replay (§37); no wall-clock/random in the core (time via {@link ClockPort} for freshness only,
 * R-063). Stateless and virtual-thread-safe (AD-021).
 */
public final class CostEngineService implements CostEnginePort {

  private final PricingSnapshotPort pricingPort;
  private final ContractSnapshotPort contractPort;
  private final CostLedgerSinkPort ledger;
  private final CostOutcomeSink sink;
  private final ClockPort clock;

  /**
   * Creates the engine against its injected ports (AD-002).
   *
   * @param pricingPort the pricing-snapshot seam (Doc 22 §13.1)
   * @param contractPort the contract-snapshot seam (Doc 22 §16)
   * @param ledger the C5-CP ledger sink (Doc 22 §28)
   * @param sink the content-free cost-outcome sink (Doc 22 §44); use {@link CostOutcomeSink#NO_OP}
   * @param clock the deterministic time seam (freshness only, Doc 22 §13.1)
   */
  public CostEngineService(
      final PricingSnapshotPort pricingPort,
      final ContractSnapshotPort contractPort,
      final CostLedgerSinkPort ledger,
      final CostOutcomeSink sink,
      final ClockPort clock) {
    this.pricingPort = Preconditions.requireNonNull(pricingPort, "pricingPort");
    this.contractPort = Preconditions.requireNonNull(contractPort, "contractPort");
    this.ledger = Preconditions.requireNonNull(ledger, "ledger");
    this.sink = Preconditions.requireNonNull(sink, "sink");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  @Override
  public io.reliabilityai.gateway.dataplane.cost.api.ProjectionOutcome project(
      final CostRequest request) {
    Preconditions.requireNonNull(request, "request");
    try {
      if (request.declaredMaxOutputTokens().isEmpty()) {
        // No authoritative max-output ceiling ⇒ cannot upper-bound safely (§23.1).
        return unavailableP(CostUnavailableReason.USAGE_UNBOUNDED);
      }
      final InputsOrReason resolved = resolveInputs(request);
      if (resolved.reason() != null) {
        return unavailableP(resolved.reason());
      }
      final Inputs in = resolved.inputs();
      // Max possible usage: known input + declared max output; no cache discount (conservative,
      // higher cost).
      final NormalizedUsage maxUsage =
          new NormalizedUsage(
              request.inputTokens(),
              request.declaredMaxOutputTokens().getAsLong(),
              0,
              1,
              UsageConfidence.PROJECTED);
      final CostCalculator.Priced priced =
          CostCalculator.price(in.resolution().descriptor(), maxUsage, in.fx(), true);
      final CostProjection projection =
          new CostProjection(
              new Money(priced.canonicalMicros(), in.canonicalCurrency()),
              in.resolution().pricingClass(),
              "declared_max_output",
              in.versions());
      safeProjection(projection);
      return projection;
    } catch (final RuntimeException e) {
      return unavailableP(
          CostUnavailableReason.INTERNAL_ERROR); // overflow / internal ⇒ fail closed
    }
  }

  @Override
  public ComputationOutcome compute(final CostRequest request, final NormalizedUsage usage) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(usage, "usage");
    try {
      if (usage.confidence() == UsageConfidence.PROJECTED) {
        // A projection is never emitted as an actual charge — the three usage classes are never
        // conflated (Doc 22 §24, CE-A16). Fail closed rather than charge a projected figure.
        return unavailableC(CostUnavailableReason.INTERNAL_ERROR);
      }
      if (usage.confidence() == UsageConfidence.ESTIMATED && !request.allowEstimatedCharge()) {
        // Estimated usage fails closed by default; a flagged estimated charge needs explicit policy
        // (§24).
        return unavailableC(CostUnavailableReason.USAGE_ESTIMATED_NOT_PERMITTED);
      }
      final InputsOrReason resolved = resolveInputs(request);
      if (resolved.reason() != null) {
        return unavailableC(resolved.reason());
      }
      final Inputs in = resolved.inputs();
      // Estimated usage is rounded conservatively (cost-increasing, §24); authoritative is exact.
      final boolean conservative = usage.confidence() == UsageConfidence.ESTIMATED;
      final CostCalculator.Priced priced =
          CostCalculator.price(in.resolution().descriptor(), usage, in.fx(), conservative);
      final CostResult result =
          new CostResult(
              new Money(priced.canonicalMicros(), in.canonicalCurrency()),
              priced.breakdown(),
              in.resolution().pricingClass(),
              io.reliabilityai.gateway.dataplane.cost.domain.Phase.ACTUAL,
              usage.confidence(),
              request.idempotencyKey(),
              request.attemptId(),
              request.delivered(),
              in.versions());
      safeLedger(result);
      safeCost(result);
      return result;
    } catch (final RuntimeException e) {
      return unavailableC(CostUnavailableReason.INTERNAL_ERROR);
    }
  }

  private InputsOrReason resolveInputs(final CostRequest request) {
    final Optional<PricingSnapshot> maybeSnapshot = pricingPort.current();
    if (maybeSnapshot.isEmpty()) {
      return InputsOrReason.failed(CostUnavailableReason.PRICING_MISSING);
    }
    final PricingSnapshot snapshot = maybeSnapshot.get();
    final java.time.Instant now = clock.now();
    if (!now.isBefore(snapshot.validUntil())) {
      return InputsOrReason.failed(
          CostUnavailableReason.PRICING_STALE); // beyond validUntil ⇒ untrusted
    }
    final FxTable fx = snapshot.fx();
    if (!now.isBefore(fx.validUntil())) {
      return InputsOrReason.failed(CostUnavailableReason.FX_STALE);
    }
    final ContractEntitlement entitlement =
        contractPort.entitlementFor(
            request.tenantScope(), request.canonicalModelId(), request.region());
    final PricingResolver.Result resolution =
        PricingResolver.resolve(
            request.canonicalModelId(), request.region(), entitlement, snapshot);
    if (!resolution.isResolved()) {
      return InputsOrReason.failed(resolution.reason());
    }
    final PricingResolution resolved = resolution.resolution();
    final Optional<FxRate> fxRate = fx.rateFor(resolved.descriptor().currency());
    if (fxRate.isEmpty()) {
      return InputsOrReason.failed(CostUnavailableReason.FX_MISSING); // never guess FX (§FX-8)
    }
    final Map<String, String> versions =
        Map.of(
            "pricing", snapshot.version(),
            "fx", fx.version(),
            "contract", entitlement.version(),
            "descriptor", resolved.descriptor().version());
    return InputsOrReason.ok(new Inputs(resolved, fxRate.get(), fx.canonicalCurrency(), versions));
  }

  private CostUnavailable unavailableP(final CostUnavailableReason reason) {
    safeUnavailable(reason);
    return new CostUnavailable(reason);
  }

  private CostUnavailable unavailableC(final CostUnavailableReason reason) {
    safeUnavailable(reason);
    return new CostUnavailable(reason);
  }

  private void safeLedger(final CostResult result) {
    try {
      ledger.emitCostFact(result);
    } catch (final RuntimeException ignored) {
      // async ledger emission is best-effort; never alters the computed cost
    }
  }

  private void safeCost(final CostResult result) {
    try {
      sink.onCost(result);
    } catch (final RuntimeException ignored) {
      // content-free sink is best-effort
    }
  }

  private void safeProjection(final CostProjection projection) {
    try {
      sink.onProjection(projection);
    } catch (final RuntimeException ignored) {
      // best-effort
    }
  }

  private void safeUnavailable(final CostUnavailableReason reason) {
    try {
      sink.onUnavailable(reason);
    } catch (final RuntimeException ignored) {
      // best-effort
    }
  }

  /** Resolved authoritative inputs for a cost calculation. */
  private record Inputs(
      PricingResolution resolution,
      FxRate fx,
      String canonicalCurrency,
      Map<String, String> versions) {}

  /** Either resolved inputs or a fail-closed reason (exactly one non-null). */
  private record InputsOrReason(Inputs inputs, CostUnavailableReason reason) {
    static InputsOrReason ok(final Inputs inputs) {
      return new InputsOrReason(inputs, null);
    }

    static InputsOrReason failed(final CostUnavailableReason reason) {
      return new InputsOrReason(null, reason);
    }
  }
}
