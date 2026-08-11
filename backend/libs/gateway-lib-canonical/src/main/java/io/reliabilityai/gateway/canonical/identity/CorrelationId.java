package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Correlation id — threads metrics/traces/logs/events/audit for one request (Doc 33 §5, Doc 27 §9,
 * Doc 07 §6). Single authoritative definition; carried by reference across models (Doc 33 §TIC).
 *
 * @param value the non-blank correlation id (minted at Ingress, Doc 30 §9.1)
 */
public record CorrelationId(String value) {

  /** Compact constructor validating the id (Doc 33 §TIC-3). */
  public CorrelationId {
    Preconditions.requireNonBlank(value, "correlationId");
  }

  @Override
  public String toString() {
    return value;
  }
}
