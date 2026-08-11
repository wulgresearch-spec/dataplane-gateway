package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Identifies a plan, independent of its version (AD-025 §22).
 *
 * @param value the opaque plan identifier, never blank
 */
public record PlanId(String value) {

  /**
   * Validates the identifier.
   *
   * @param value the opaque plan identifier
   */
  public PlanId {
    Preconditions.requireNonBlank(value, "planId");
  }

  /**
   * Creates a plan identifier.
   *
   * @param value the opaque plan identifier
   * @return the identifier
   */
  public static PlanId of(final String value) {
    return new PlanId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
