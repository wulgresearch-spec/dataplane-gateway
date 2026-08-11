package io.reliabilityai.gateway.dataplane.cost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure Cost Engine domain primitives (Doc 22 §15/§16.1/§21). */
class CostDomainTest {

  private static final CanonicalModelId MODEL = new CanonicalModelId("m");
  private static final Region REGION = new Region("eu");

  private static PricingDescriptor d(final PricingClass cls, final long in, final long out) {
    return new PricingDescriptor(
        MODEL, REGION, "USD", cls, new UnitRates(in, out, 0, 0), "v-" + cls);
  }

  private static PricingSnapshot snapshot(final Map<String, PricingDescriptor> ds) {
    return new PricingSnapshot(
        "ps", Instant.EPOCH, Instant.MAX, ds, new FxTable("fx", Instant.MAX, "USD", Map.of()));
  }

  @Test
  void anEntitlementCannotBeUpgradedByItsOwnHolder() {
    // The copy was always here; the wrapper was not. EnumSet.copyOf stopped the caller's later
    // edits from leaking in, but the accessor handed that same EnumSet straight back — so anyone
    // holding a ContractEntitlement could add NEGOTIATED to it and price themselves at the highest
    // precedence tier. The engine applies entitlements and never negotiates or infers one
    // (Doc 22 §CE-D7), which it cannot do while the set it was given is writable.
    final EnumSet<PricingClass> declared = EnumSet.of(PricingClass.BURST);
    final ContractEntitlement entitlement = new ContractEntitlement("c", declared);

    // The caller's own set is not a back door either.
    declared.add(PricingClass.NEGOTIATED);

    assertThat(entitlement.entitledClasses()).containsExactly(PricingClass.BURST);
    assertThatThrownBy(() -> entitlement.entitledClasses().add(PricingClass.NEGOTIATED))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> entitlement.entitledClasses().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void anEmptyOrAbsentEntitlementIsStillReadOnly() {
    // The empty branch takes a different path through the constructor (EnumSet.noneOf, because
    // EnumSet.copyOf rejects an empty non-EnumSet collection), so it needs its own check.
    assertThat(ContractEntitlement.listOnly("c").entitledClasses()).isEmpty();
    assertThatThrownBy(
            () -> ContractEntitlement.listOnly("c").entitledClasses().add(PricingClass.LIST))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> new ContractEntitlement("c", null).entitledClasses().add(PricingClass.LIST))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // --- FxRate conservative conversion (§16.1 FX-9) ---

  @Test
  void fxActualUsesRateProjectionUsesWorstRoundedUp() {
    final FxRate fx = new FxRate(1_100_000, 1_250_000); // 1.1 actual, 1.25 worst
    assertThat(fx.toCanonicalMicros(1000, false)).isEqualTo(1100); // 1000*1.1
    assertThat(fx.toCanonicalMicros(1000, true)).isEqualTo(1250); // 1000*1.25 (worst)
    // Round up for projections: 3 * 1.25 = 3.75 → ceil 4
    assertThat(fx.toCanonicalMicros(3, true)).isEqualTo(4);
  }

  @Test
  void identityFxConvertsInPlace() {
    assertThat(FxRate.IDENTITY.toCanonicalMicros(777, false)).isEqualTo(777);
  }

  @Test
  void fxActualNeverRoundsDown() {
    // CE-INV: cost is never optimistically rounded down — actual conversion rounds up on a
    // remainder.
    final FxRate fx = new FxRate(1_100_000, 1_100_000); // rate 1.1
    // 3 * 1.1 = 3.3 → must be 4 (round up), never 3 (round down).
    assertThat(fx.toCanonicalMicros(3, false)).isEqualTo(4);
  }

  @Test
  void fxRejectsWorstBelowRate() {
    assertThatThrownBy(() -> new FxRate(1_200_000, 1_000_000))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- CostCalculator (§21) ---

  @Test
  void calculatorSumsUnitCosts() {
    final CostCalculator.Priced priced =
        CostCalculator.price(
            d(PricingClass.LIST, 2, 5),
            new NormalizedUsage(100, 50, 0, 1, UsageConfidence.AUTHORITATIVE),
            FxRate.IDENTITY,
            false);
    assertThat(priced.canonicalMicros()).isEqualTo(450); // 100*2 + 50*5
    assertThat(priced.breakdown().outputMicros()).isEqualTo(250);
  }

  @Test
  void calculatorOverflowThrows() {
    assertThatThrownBy(
            () ->
                CostCalculator.price(
                    d(PricingClass.LIST, Long.MAX_VALUE, 0),
                    new NormalizedUsage(2, 0, 0, 0, UsageConfidence.AUTHORITATIVE),
                    FxRate.IDENTITY,
                    false))
        .isInstanceOf(ArithmeticException.class);
  }

  // --- PricingResolver precedence (§15) ---

  @Test
  void resolverPicksHighestEntitledClassWithDescriptor() {
    final Map<String, PricingDescriptor> ds =
        Map.of(
            PricingDescriptor.key(MODEL, REGION, PricingClass.LIST), d(PricingClass.LIST, 10, 10),
            PricingDescriptor.key(MODEL, REGION, PricingClass.ENTERPRISE),
                d(PricingClass.ENTERPRISE, 2, 2));
    final PricingResolver.Result r =
        PricingResolver.resolve(
            MODEL,
            REGION,
            new ContractEntitlement("c", EnumSet.of(PricingClass.ENTERPRISE)),
            snapshot(ds));
    assertThat(r.isResolved()).isTrue();
    assertThat(r.resolution().pricingClass()).isEqualTo(PricingClass.ENTERPRISE);
  }

  @Test
  void resolverFallsToListWhenNoContractEntitled() {
    final Map<String, PricingDescriptor> ds =
        Map.of(
            PricingDescriptor.key(MODEL, REGION, PricingClass.LIST), d(PricingClass.LIST, 10, 10));
    final PricingResolver.Result r =
        PricingResolver.resolve(MODEL, REGION, ContractEntitlement.listOnly("c"), snapshot(ds));
    assertThat(r.resolution().pricingClass()).isEqualTo(PricingClass.LIST);
  }

  @Test
  void resolverFailsClosedWhenEntitledClassMissingDescriptor() {
    final Map<String, PricingDescriptor> ds =
        Map.of(
            PricingDescriptor.key(MODEL, REGION, PricingClass.LIST), d(PricingClass.LIST, 10, 10));
    final PricingResolver.Result r =
        PricingResolver.resolve(
            MODEL,
            REGION,
            new ContractEntitlement("c", EnumSet.of(PricingClass.NEGOTIATED)),
            snapshot(ds));
    assertThat(r.isResolved()).isFalse();
    assertThat(r.reason()).isEqualTo(CostUnavailableReason.CONTRACT_UNRESOLVED);
  }

  @Test
  void resolverFailsClosedWhenNoListPrice() {
    final PricingResolver.Result r =
        PricingResolver.resolve(
            MODEL, REGION, ContractEntitlement.listOnly("c"), snapshot(Map.of()));
    assertThat(r.reason()).isEqualTo(CostUnavailableReason.NO_REGIONAL_PRICE);
  }
}
