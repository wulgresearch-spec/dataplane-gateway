package io.reliabilityai.gateway.dataplane.cost.api;

/**
 * The C5 control-plane ledger seam (Doc 22 §28, CE-D9). The engine emits <b>content-free</b> cost
 * facts (canonical amounts, versions, delivered/attempt flags, idempotency key) to the C5-CP
 * authoritative ledger (effectively-once via idempotency keying) — it <b>never persists the
 * ledger</b> (CE-D1). Every attempt's provider spend is emitted (never suppressed, §CA-2); the
 * delivered attempt is the single customer charge (§CA-11). Async relative to the response (Doc 22
 * §28); best-effort at this seam.
 */
public interface CostLedgerSinkPort {

  /**
   * Emits a content-free cost fact to the ledger (Doc 22 §28) — idempotency-keyed,
   * effectively-once.
   *
   * @param result the immutable cost result (provider-attempt or delivered customer charge)
   */
  void emitCostFact(CostResult result);
}
