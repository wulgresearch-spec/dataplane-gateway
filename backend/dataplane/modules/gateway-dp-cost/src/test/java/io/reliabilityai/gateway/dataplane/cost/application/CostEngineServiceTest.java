package io.reliabilityai.gateway.dataplane.cost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.cost.api.ComputationOutcome;
import io.reliabilityai.gateway.dataplane.cost.api.ContractSnapshotPort;
import io.reliabilityai.gateway.dataplane.cost.api.CostLedgerSinkPort;
import io.reliabilityai.gateway.dataplane.cost.api.CostOutcomeSink;
import io.reliabilityai.gateway.dataplane.cost.api.CostProjection;
import io.reliabilityai.gateway.dataplane.cost.api.CostRequest;
import io.reliabilityai.gateway.dataplane.cost.api.CostResult;
import io.reliabilityai.gateway.dataplane.cost.api.CostUnavailable;
import io.reliabilityai.gateway.dataplane.cost.api.PricingSnapshotPort;
import io.reliabilityai.gateway.dataplane.cost.api.ProjectionOutcome;
import io.reliabilityai.gateway.dataplane.cost.domain.ContractEntitlement;
import io.reliabilityai.gateway.dataplane.cost.domain.CostUnavailableReason;
import io.reliabilityai.gateway.dataplane.cost.domain.FxRate;
import io.reliabilityai.gateway.dataplane.cost.domain.FxTable;
import io.reliabilityai.gateway.dataplane.cost.domain.NormalizedUsage;
import io.reliabilityai.gateway.dataplane.cost.domain.Phase;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingClass;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingDescriptor;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingSnapshot;
import io.reliabilityai.gateway.dataplane.cost.domain.UnitRates;
import io.reliabilityai.gateway.dataplane.cost.domain.UsageConfidence;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fail-closed, never-underestimated cost calculation tests for the Cost Engine (Doc 22). */
class CostEngineServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");
  private static final Instant VALID = NOW.plusSeconds(3600);
  private static final CanonicalModelId MODEL = new CanonicalModelId("m");
  private static final Region REGION = new Region("eu-west-1");
  private static final TenantScope TENANT = TenantScope.of("org", "t1");

  private final FakePricing pricing = new FakePricing();
  private final FakeContract contract = new FakeContract();
  private final FakeLedger ledger = new FakeLedger();
  private final ClockPort clock = () -> NOW;

  private CostEngineService service() {
    return new CostEngineService(pricing, contract, ledger, CostOutcomeSink.NO_OP, clock);
  }

  private static UnitRates rates(final long in, final long out) {
    return new UnitRates(in, out, 0, 0);
  }

  private static PricingDescriptor descriptor(
      final PricingClass cls, final long in, final long out) {
    return new PricingDescriptor(MODEL, REGION, "USD", cls, rates(in, out), "pd-" + cls);
  }

  private PricingSnapshot snapshot(final Map<String, PricingDescriptor> descriptors) {
    final FxTable fx =
        new FxTable("fx-v1", VALID, "USD", Map.of("EUR", new FxRate(1_100_000, 1_200_000)));
    return new PricingSnapshot("ps-v1", NOW.minusSeconds(60), VALID, descriptors, fx);
  }

  private void listPricing(final long in, final long out) {
    final PricingDescriptor d = descriptor(PricingClass.LIST, in, out);
    pricing.snapshot = snapshot(Map.of(PricingDescriptor.key(MODEL, REGION, PricingClass.LIST), d));
  }

  private static CostRequest request(
      final Phase phase,
      final long inputTokens,
      final OptionalLong maxOutput,
      final boolean estimatedOk) {
    return new CostRequest(
        TENANT,
        MODEL,
        REGION,
        new CorrelationId("c"),
        new IdempotencyKey("k"),
        new AttemptId("a1"),
        phase,
        inputTokens,
        maxOutput,
        true,
        estimatedOk);
  }

  @Test
  void computesActualCostExactlyFromAuthoritativeUsage() {
    listPricing(2, 5); // 2 micros/input token, 5 micros/output token (USD)
    final NormalizedUsage usage = new NormalizedUsage(100, 50, 0, 1, UsageConfidence.AUTHORITATIVE);
    final ComputationOutcome outcome =
        service().compute(request(Phase.ACTUAL, 100, OptionalLong.empty(), false), usage);
    final CostResult result = (CostResult) outcome;
    // 100*2 + 50*5 = 450 USD micros; USD is canonical (identity FX).
    assertThat(result.amount().amountMicros()).isEqualTo(450);
    assertThat(result.amount().currency()).isEqualTo("USD");
    assertThat(result.pricingClass()).isEqualTo(PricingClass.LIST);
    assertThat(result.versions()).containsEntry("pricing", "ps-v1").containsEntry("fx", "fx-v1");
    assertThat(ledger.facts).hasSize(1);
  }

  @Test
  void projectionIsNeverUnderestimatedUpperBound() {
    listPricing(2, 5);
    final ProjectionOutcome outcome =
        service().project(request(Phase.PROJECTION, 100, OptionalLong.of(1000), false));
    final CostProjection projection = (CostProjection) outcome;
    // 100*2 + 1000*5 = 5200 (upper bound with max output), >> the 450 actual for 50 output tokens.
    assertThat(projection.upperBound().amountMicros()).isEqualTo(5200);
    assertThat(projection.boundBasis()).isEqualTo("declared_max_output");
  }

  @Test
  void projectionGreaterThanOrEqualActualForForeignCurrencyFx() {
    // EUR pricing: actual uses rate 1.1, projection uses worst-case 1.2 → projection FX never
    // underestimates.
    final PricingDescriptor eur =
        new PricingDescriptor(MODEL, REGION, "EUR", PricingClass.LIST, rates(10, 10), "pd-eur");
    pricing.snapshot =
        snapshot(Map.of(PricingDescriptor.key(MODEL, REGION, PricingClass.LIST), eur));
    final NormalizedUsage usage = new NormalizedUsage(10, 10, 0, 1, UsageConfidence.AUTHORITATIVE);
    final CostResult actual =
        (CostResult)
            service().compute(request(Phase.ACTUAL, 10, OptionalLong.empty(), false), usage);
    final CostProjection proj =
        (CostProjection)
            service().project(request(Phase.PROJECTION, 10, OptionalLong.of(10), false));
    // actual: (10*10+10*10)=200 EUR micros * 1.1 = 220; projection: 200 * 1.2 (worst) = 240.
    assertThat(actual.amount().amountMicros()).isEqualTo(220);
    assertThat(proj.upperBound().amountMicros()).isEqualTo(240);
    assertThat(proj.upperBound().amountMicros())
        .isGreaterThanOrEqualTo(actual.amount().amountMicros());
  }

  @Test
  void negotiatedContractWinsPrecedenceOverList() {
    final Map<String, PricingDescriptor> ds = new HashMap<>();
    ds.put(
        PricingDescriptor.key(MODEL, REGION, PricingClass.LIST),
        descriptor(PricingClass.LIST, 10, 10));
    ds.put(
        PricingDescriptor.key(MODEL, REGION, PricingClass.NEGOTIATED),
        descriptor(PricingClass.NEGOTIATED, 1, 1));
    pricing.snapshot = snapshot(ds);
    contract.entitled = EnumSet.of(PricingClass.NEGOTIATED);
    final CostResult result =
        (CostResult)
            service()
                .compute(
                    request(Phase.ACTUAL, 100, OptionalLong.empty(), false),
                    new NormalizedUsage(100, 0, 0, 0, UsageConfidence.AUTHORITATIVE));
    assertThat(result.pricingClass()).isEqualTo(PricingClass.NEGOTIATED);
    assertThat(result.amount().amountMicros()).isEqualTo(100); // 100*1 negotiated, not 100*10 list
  }

  @Test
  void entitledContractWithoutDescriptorFailsClosedNoListFallback() {
    listPricing(10, 10); // only LIST present
    contract.entitled =
        EnumSet.of(PricingClass.NEGOTIATED); // entitled but no negotiated descriptor
    final ComputationOutcome outcome =
        service()
            .compute(
                request(Phase.ACTUAL, 1, OptionalLong.empty(), false),
                new NormalizedUsage(1, 0, 0, 0, UsageConfidence.AUTHORITATIVE));
    assertThat(((CostUnavailable) outcome).reason())
        .isEqualTo(CostUnavailableReason.CONTRACT_UNRESOLVED);
  }

  @Test
  void missingPricingSnapshotFailsClosed() {
    pricing.snapshot = null;
    assertThat(
            ((CostUnavailable)
                    service()
                        .compute(
                            request(Phase.ACTUAL, 1, OptionalLong.empty(), false),
                            new NormalizedUsage(1, 0, 0, 0, UsageConfidence.AUTHORITATIVE)))
                .reason())
        .isEqualTo(CostUnavailableReason.PRICING_MISSING);
  }

  @Test
  void stalePricingSnapshotFailsClosed() {
    listPricing(1, 1);
    final CostEngineService staleSvc =
        new CostEngineService(
            pricing,
            contract,
            ledger,
            CostOutcomeSink.NO_OP,
            () -> VALID.plusSeconds(1)); // now beyond validUntil
    assertThat(
            ((CostUnavailable)
                    staleSvc.compute(
                        request(Phase.ACTUAL, 1, OptionalLong.empty(), false),
                        new NormalizedUsage(1, 0, 0, 0, UsageConfidence.AUTHORITATIVE)))
                .reason())
        .isEqualTo(CostUnavailableReason.PRICING_STALE);
  }

  @Test
  void missingFxPairFailsClosed() {
    final PricingDescriptor gbp =
        new PricingDescriptor(MODEL, REGION, "GBP", PricingClass.LIST, rates(1, 1), "pd-gbp");
    pricing.snapshot =
        snapshot(Map.of(PricingDescriptor.key(MODEL, REGION, PricingClass.LIST), gbp));
    assertThat(
            ((CostUnavailable)
                    service()
                        .compute(
                            request(Phase.ACTUAL, 1, OptionalLong.empty(), false),
                            new NormalizedUsage(1, 0, 0, 0, UsageConfidence.AUTHORITATIVE)))
                .reason())
        .isEqualTo(CostUnavailableReason.FX_MISSING);
  }

  @Test
  void unboundedProjectionFailsClosed() {
    listPricing(1, 1);
    assertThat(
            ((CostUnavailable)
                    service().project(request(Phase.PROJECTION, 100, OptionalLong.empty(), false)))
                .reason())
        .isEqualTo(CostUnavailableReason.USAGE_UNBOUNDED);
  }

  @Test
  void estimatedUsageFailsClosedByDefaultButChargesUnderPolicy() {
    listPricing(2, 5);
    final NormalizedUsage estimated = new NormalizedUsage(10, 10, 0, 1, UsageConfidence.ESTIMATED);
    assertThat(
            ((CostUnavailable)
                    service()
                        .compute(request(Phase.ACTUAL, 10, OptionalLong.empty(), false), estimated))
                .reason())
        .isEqualTo(CostUnavailableReason.USAGE_ESTIMATED_NOT_PERMITTED);
    // With upstream policy permitting an estimated charge, it is priced and flagged estimated.
    final CostResult result =
        (CostResult)
            service().compute(request(Phase.ACTUAL, 10, OptionalLong.of(0), true), estimated);
    assertThat(result.confidence()).isEqualTo(UsageConfidence.ESTIMATED);
  }

  @Test
  void computeRejectsProjectedUsageNeverChargesAProjection() {
    listPricing(2, 5);
    // A projection must never be emitted as an actual charge (Doc 22 §24) — fail closed.
    final NormalizedUsage projected = new NormalizedUsage(10, 10, 0, 1, UsageConfidence.PROJECTED);
    final ComputationOutcome outcome =
        service().compute(request(Phase.ACTUAL, 10, OptionalLong.empty(), true), projected);
    assertThat(outcome).isInstanceOf(CostUnavailable.class);
    assertThat(ledger.facts).isEmpty(); // never emitted a projected figure as a charge
  }

  @Test
  void noRegionalListPriceFailsClosed() {
    pricing.snapshot = snapshot(Map.of()); // no descriptors at all
    assertThat(
            ((CostUnavailable)
                    service()
                        .compute(
                            request(Phase.ACTUAL, 1, OptionalLong.empty(), false),
                            new NormalizedUsage(1, 0, 0, 0, UsageConfidence.AUTHORITATIVE)))
                .reason())
        .isEqualTo(CostUnavailableReason.NO_REGIONAL_PRICE);
  }

  @Test
  void rejectsNullArguments() {
    final CostEngineService svc = service();
    assertThatThrownBy(() -> svc.project(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> svc.compute(request(Phase.ACTUAL, 1, OptionalLong.empty(), false), null))
        .isInstanceOf(NullPointerException.class);
  }

  private static final class FakePricing implements PricingSnapshotPort {
    private PricingSnapshot snapshot;

    @Override
    public Optional<PricingSnapshot> current() {
      return Optional.ofNullable(snapshot);
    }
  }

  private static final class FakeContract implements ContractSnapshotPort {
    private Set<PricingClass> entitled = EnumSet.noneOf(PricingClass.class);

    @Override
    public ContractEntitlement entitlementFor(
        final TenantScope tenantScope, final CanonicalModelId model, final Region region) {
      return new ContractEntitlement("ct-v1", entitled);
    }
  }

  private static final class FakeLedger implements CostLedgerSinkPort {
    private final List<CostResult> facts = new ArrayList<>();

    @Override
    public void emitCostFact(final CostResult result) {
      facts.add(result);
    }
  }
}
