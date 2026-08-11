package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Idempotency key — client-supplied per the frozen API contract (Doc 12 §14, Doc 30 §RIC).
 *
 * <p>Never invented by the runtime (Doc 30 RIC-7); the dedup/exactly-once anchor consumed by
 * Reliability (Doc 20), Usage Metering (Doc 23 §14.1), and Cost (Doc 22). Threaded, single
 * definition (Doc 33 §TIC).
 *
 * @param value the non-blank client-supplied idempotency key
 */
public record IdempotencyKey(String value) {

  /** Compact constructor validating the key (never invented; Doc 30 RIC-7). */
  public IdempotencyKey {
    Preconditions.requireNonBlank(value, "idempotencyKey");
  }

  @Override
  public String toString() {
    return value;
  }
}
