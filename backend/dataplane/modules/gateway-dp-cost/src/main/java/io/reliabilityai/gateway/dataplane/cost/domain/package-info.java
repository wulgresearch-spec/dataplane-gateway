/**
 * Cost Engine domain (C5, Doc 22 §6) — the pure, deterministic, calculation-only cost primitives:
 * the {@code PricingClass}/{@code Phase}/{@code UsageConfidence} taxonomies, the fail-closed {@code
 * CostUnavailableReason} (§39), {@code Money}/{@code UnitRates}/{@code CostBreakdown}, the
 * immutable versioned {@code PricingDescriptor}/{@code PricingSnapshot}/{@code FxTable}/{@code
 * FxRate} (§13.1/§16.1), {@code ContractEntitlement}, deterministic {@code PricingResolver}
 * precedence (§15), and the never-underestimating {@code CostCalculator} (§21/§23). No
 * wall-clock/random (R-063); no billing/payment/ledger/persistence/provider identity
 * (CE-D1/D2/D12).
 */
package io.reliabilityai.gateway.dataplane.cost.domain;
