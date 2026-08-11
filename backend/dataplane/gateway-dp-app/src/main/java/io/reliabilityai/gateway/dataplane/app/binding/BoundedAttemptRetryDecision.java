package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.api.RetryDecisionPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPolicy;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;

/**
 * Bounds structured-output re-generation by the schema-lock policy's attempt ceiling (Doc 17).
 *
 * <p>Whether a failure is worth another attempt is already decided by the domain: {@link
 * FailureClass} carries its own {@code recoverable()} flag, so this binding defers to it rather
 * than restating the classification. A deterministic failure — a schema the provider structurally
 * cannot satisfy, or output that blew the size ceiling — is never retried, because a further
 * attempt would burn the customer's latency and money to produce the same answer.
 */
public final class BoundedAttemptRetryDecision implements RetryDecisionPort {

  private final int maxAttempts;

  /**
   * Creates the decision port from the schema-lock policy.
   *
   * @param policy the schema-lock policy supplying the attempt ceiling
   */
  public BoundedAttemptRetryDecision(final SchemaLockPolicy policy) {
    Preconditions.requireNonNull(policy, "policy");
    this.maxAttempts = policy.maxAttempts();
  }

  @Override
  public boolean shouldRetry(final int nextAttempt, final FailureClass failureClass) {
    if (nextAttempt > maxAttempts) {
      return false;
    }
    return failureClass != null && failureClass.recoverable();
  }
}
