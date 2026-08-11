package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Request id — the per-request identity (Doc 12, Doc 33 §TIC). Threaded, single definition.
 *
 * @param value the non-blank request id
 */
public record RequestId(String value) {

  /** Compact constructor validating the id. */
  public RequestId {
    Preconditions.requireNonBlank(value, "requestId");
  }

  @Override
  public String toString() {
    return value;
  }
}
