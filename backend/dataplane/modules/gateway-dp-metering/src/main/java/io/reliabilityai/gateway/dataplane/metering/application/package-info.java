/**
 * Usage Metering application (C5, Doc 23 §7/§11) — the record-only engine ({@code
 * UsageMeteringService}: normalize → durable WAL commit → ledger/quota/cost emit, fail-closed,
 * UME-INV) and the transient per-request aggregate ({@code RequestMeter}: provider usage = Σ
 * attempts, single delivered, latched completeness). Never prices/enforces/persists (UME-D1);
 * durability at WAL commit (§39.1 UC-1); no wall-clock/random in the core (§36).
 */
package io.reliabilityai.gateway.dataplane.metering.application;
