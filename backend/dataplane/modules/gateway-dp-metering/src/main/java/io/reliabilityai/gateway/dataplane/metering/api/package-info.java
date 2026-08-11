/**
 * Usage Metering API (C5, Doc 23 §7) — the inbound {@code UsageMeteringEnginePort} and the
 * outbound, fact-only seams: the {@code UsageDescriptorPort} snapshot source (§9), the zero-loss
 * {@code DurableUsageWalPort} (§39.1, the frozen Doc 07 EV-D3 WAL), the {@code LedgerSinkPort}
 * (C5-CP ledger, §29), the idempotent {@code QuotaCounterPort} (Governance, §31), the {@code
 * CostUsagePort} (Cost Engine, §30), the content-free {@code MeteringOutcomeSink}, and {@code
 * MeteringPolicy}. No store, no credentials, no provider SDK, no HTTP (UME-D12).
 */
package io.reliabilityai.gateway.dataplane.metering.api;
