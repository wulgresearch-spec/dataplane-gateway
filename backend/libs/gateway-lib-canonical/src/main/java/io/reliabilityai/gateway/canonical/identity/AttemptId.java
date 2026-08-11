package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Attempt id — a stable, deterministic per-attempt identity assigned by Reliability (Doc 20, Doc 23
 * §14.1). Not random; part of {@link ExecutionIdentity}.
 *
 * @param value the non-blank attempt id
 */
public record AttemptId(String value) {

  /** Compact constructor validating the id. */
  public AttemptId {
    Preconditions.requireNonBlank(value, "attemptId");
  }

  @Override
  public String toString() {
    return value;
  }
}
