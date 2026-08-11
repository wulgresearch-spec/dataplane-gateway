package io.reliabilityai.gateway.canonical.context;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * The execution deadline context (Doc 33 §10.1). The execution/request deadline is owned solely by
 * Reliability (Doc 20, Doc 25 §17.1); the gateway owns only transport timeouts (Doc 30 §DOC).
 * Immutable.
 *
 * @param executionDeadline the absolute execution deadline (owned by Doc 20)
 */
public record DeadlineContext(Instant executionDeadline) {

  /** Compact constructor validating the deadline presence. */
  public DeadlineContext {
    Preconditions.requireNonNull(executionDeadline, "executionDeadline");
  }
}
