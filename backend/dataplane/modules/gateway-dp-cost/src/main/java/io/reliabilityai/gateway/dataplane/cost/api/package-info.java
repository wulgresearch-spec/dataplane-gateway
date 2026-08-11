/**
 * Cost Engine API (C5, Doc 22 §7) — the inbound {@code CostEnginePort} (project/compute), the
 * sealed {@code ProjectionOutcome}/{@code ComputationOutcome} results ({@code
 * CostProjection}/{@code CostResult}/ {@code CostUnavailable}), the {@code CostRequest} context,
 * and the outbound cached-snapshot seams: the {@code PricingSnapshotPort} (§13.1), the {@code
 * ContractSnapshotPort} (§16), the content-free {@code CostLedgerSinkPort} (§28) and {@code
 * CostOutcomeSink}. No store, no credentials, no provider SDK, no HTTP, no billing/payment types
 * (CE-D12).
 */
package io.reliabilityai.gateway.dataplane.cost.api;
