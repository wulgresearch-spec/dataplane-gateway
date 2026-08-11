package io.reliabilityai.gateway.dataplane.app.pipeline;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;

/**
 * A stage's decision to stop the request, carrying the canonical error the caller receives
 * (AD-018).
 *
 * <p>Naming the refusing stage is the point: a caller that is told only "denied" cannot tell an
 * expired credential from a policy denial from an unroutable model, and neither can an operator
 * reading the audit trail. The {@code reason} is content-free — it names the decision, never the
 * request payload or any credential.
 *
 * @param stage the mandatory stage that refused
 * @param reason the short, content-free refusal reason
 * @param error the canonical error surfaced to the caller
 */
public record StageRefusal(MandatoryStage stage, String reason, CanonicalError error) {

  /** Validates the refusal. */
  public StageRefusal {
    Preconditions.requireNonNull(stage, "stage");
    Preconditions.requireNonBlank(reason, "reason");
    Preconditions.requireNonNull(error, "error");
  }
}
