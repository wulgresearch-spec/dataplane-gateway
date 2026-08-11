package io.reliabilityai.gateway.dataplane.metering.api;

import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.dataplane.metering.domain.RequestUsageManifest;

/**
 * The C5 control-plane ledger seam (Doc 23 §29, UME-D10). After WAL commit (durability boundary),
 * the engine emits immutable usage facts and the completeness manifest into the frozen outbox
 * forwarding to the C5-CP <b>authoritative</b> ledger (RPO=0, exactly-once via idempotency-keyed
 * dedup) — the engine <b>never persists the ledger</b> (UME-D1). Emission is asynchronous relative
 * to the response (durability is at the WAL, §39.1 UC-5); the ledger dedups replays by {@code
 * (idempotencyKey, attemptId)} / {@code idempotencyKey} (§14.1).
 */
public interface LedgerSinkPort {

  /**
   * Emits an immutable usage fact to the ledger outbox (Doc 23 §29) — idempotency-keyed,
   * exactly-once.
   *
   * @param fact the durably-recorded usage fact
   */
  void emitFact(UsageFact fact);

  /**
   * Emits the {@code RequestFinalized} completeness manifest to the ledger (Doc 23 §17.1
   * FC-8/FC-9).
   *
   * @param manifest the request usage manifest
   */
  void emitManifest(RequestUsageManifest manifest);
}
