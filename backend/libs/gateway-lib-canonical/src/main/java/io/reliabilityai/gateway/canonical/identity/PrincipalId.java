package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Principal id — the authenticated principal identity (C6, Doc 37). A non-identifying scoped id in
 * audit/telemetry (Doc 37 §PIM), never raw PII.
 *
 * @param value the non-blank principal id
 */
public record PrincipalId(String value) {

  /** Compact constructor validating the id. */
  public PrincipalId {
    Preconditions.requireNonBlank(value, "principalId");
  }

  @Override
  public String toString() {
    return value;
  }
}
