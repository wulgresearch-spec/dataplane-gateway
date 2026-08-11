package io.reliabilityai.gateway.dataplane.metering.api;

import io.reliabilityai.gateway.canonical.usage.UsageFact;

/**
 * The Governance quota/budget counter seam (Doc 23 §31, UME-D10). The engine <b>provides</b> usage
 * increments to the frozen bounded-staleness quota/budget counters (Doc 21 §28.1) so Governance can
 * <b>enforce</b> caps at admission — the engine never enforces. Each increment is idempotency-keyed
 * by {@code (idempotencyKey, attemptId, counterKey)} so a retried/replayed emission increments the
 * counter <b>at-most-once</b> for a given attempt (Doc 23 §31, the counter's atomic
 * compare-and-increment dedups) — a replay never double-increments a tenant's quota/budget usage.
 */
public interface QuotaCounterPort {

  /**
   * Idempotently increments quota/budget usage for the fact's tenant (Doc 23 §31).
   *
   * @param fact the durably-recorded usage fact (carries the idempotency key + attempt id + tenant)
   * @param counterKey the counter identity (e.g. {@code tokens}, {@code requests})
   */
  void increment(UsageFact fact, String counterKey);
}
