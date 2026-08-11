/**
 * Cost Engine application (C5, Doc 22 §7) — the calculation-only engine ({@code CostEngineService}:
 * resolve versioned pricing + FX + precedence → never-underestimated projection or exact actual
 * cost → emit content-free facts; fail-closed on any uncertainty, CE-INV). Never charges/persists a
 * ledger (CE-D1), enforces budgets (Governance's), or authors pricing (C5-CP's); deterministic
 * given the stamped snapshot/FX/contract versions (§37); no wall-clock/random in the core
 * (freshness only, §13.1).
 */
package io.reliabilityai.gateway.dataplane.cost.application;
