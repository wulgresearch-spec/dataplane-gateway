package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Region — the residency region of a request (AD-014, Doc 14 §18.1). Config/identity/usage are
 * region-confined; cross-region correlation is id-only (Doc 27 §10.1).
 *
 * @param value the non-blank region identifier
 */
public record Region(String value) {

  /** Compact constructor validating the region. */
  public Region {
    Preconditions.requireNonBlank(value, "region");
  }

  @Override
  public String toString() {
    return value;
  }
}
