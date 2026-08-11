package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Identifies one agent run: one execution of one plan version (AD-025 §24.1).
 *
 * <p>Deliberately <em>not</em> called a session. {@code Session} is C13's word for its bounded,
 * ephemeral, one-task unit (AD-024 §9), and reusing it here would make every log line and audit
 * record ambiguous about which layer produced it.
 *
 * @param value the opaque run identifier, never blank
 */
public record RunId(String value) {

  /**
   * Validates the identifier.
   *
   * @param value the opaque run identifier
   */
  public RunId {
    Preconditions.requireNonBlank(value, "runId");
  }

  /**
   * Creates a run identifier.
   *
   * @param value the opaque run identifier
   * @return the identifier
   */
  public static RunId of(final String value) {
    return new RunId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
