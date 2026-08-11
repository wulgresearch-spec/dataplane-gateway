package io.reliabilityai.gateway.dataplane.metering.api;

import io.reliabilityai.gateway.canonical.usage.UsageFact;

/**
 * The zero-loss durable-capture seam (Doc 23 §D4/§39.1, the frozen Doc 07 EV-D3 WAL). A usage fact
 * is <b>"durably recorded"</b> the instant it commits to the node-local WAL (UC-1) — that commit,
 * not the ledger acknowledgement, is the RPO=0 boundary. On WAL saturation/unavailability the
 * commit fails and the engine <b>fail-safe-rejects</b> (surfaces {@code UsageUnrecorded}), never a
 * silent drop (UC-12). The WAL is the frozen owning-path mechanism — <b>not a Metering-private
 * store</b> (UC-2).
 *
 * <p>Deliberately <b>not implemented in this module</b>: the concrete WAL is the frozen Doc 07
 * EV-D3 durable mechanism (the infra dependency this seam abstracts).
 */
public interface DurableUsageWalPort {

  /**
   * Durably commits a usage fact to the node-local WAL (Doc 23 §39.1 UC-1) — the RPO=0 boundary.
   *
   * @param fact the immutable usage fact
   * @return {@code true} if durably committed; {@code false} ⇒ fail-safe reject (UC-12)
   */
  boolean commit(UsageFact fact);
}
