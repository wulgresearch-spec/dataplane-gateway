/**
 * Usage Metering domain (C5, Doc 23 §6) — the pure, deterministic, record-only metering primitives:
 * the raw {@code ExecutionFact} input, the fail-closed {@code UnrecordedReason} (§38), the
 * versioned {@code UsageDescriptor} (§9), the {@code MeteringResult}/{@code RequestUsageManifest}
 * outcomes (§17.1), the {@code UsageNormalizer} (never fabricates/estimates, §D5/§24), and exact
 * provider-usage {@code UsageAggregation} (§26). Produces the canonical {@code UsageFact}. No
 * wall-clock/random (R-063); no pricing/billing/ledger/persistence/provider identity
 * (UME-D1/D6/D12).
 */
package io.reliabilityai.gateway.dataplane.metering.domain;
