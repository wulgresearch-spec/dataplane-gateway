package io.reliabilityai.gateway.dataplane.metering.domain;

import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The per-request completeness manifest (Doc 23 §6/§17.1, UME-D9) — the payload/evidence of the
 * frozen {@code RequestFinalized} event (Doc 06 §23.11). It rolls up <b>provider usage = Σ all
 * attempt facts</b> and the single <b>customer usage = delivered</b> attempt (Doc 23 §26–28). A
 * request reaches a single terminal state: {@code complete} (all authoritative facts durably
 * recorded) or {@code incomplete} with a surfaced {@link UnrecordedReason} — there is no "silently
 * done" state (FC-11). Feeds the Doc 08 §10 loss detector (FC-10). Immutable; content-free.
 *
 * @param requestId the finalized request
 * @param complete whether all authoritative usage facts were durably recorded (FC-1)
 * @param providerUsageTotal the sum of all attempt facts (provider usage, Doc 23 §26)
 * @param customerUsage the delivered attempt's usage (null if none delivered or incomplete)
 * @param attemptCount the number of durably-recorded attempt facts
 * @param incompleteReason the reason the request is incomplete (null iff complete)
 */
public record RequestUsageManifest(
    RequestId requestId,
    boolean complete,
    CanonicalUsage providerUsageTotal,
    CanonicalUsage customerUsage,
    int attemptCount,
    UnrecordedReason incompleteReason) {

  /** Compact constructor enforcing the complete/incomplete invariants. */
  public RequestUsageManifest {
    Preconditions.requireNonNull(requestId, "requestId");
    Preconditions.requireNonNull(providerUsageTotal, "providerUsageTotal");
    if (attemptCount < 0) {
      throw new IllegalArgumentException("attemptCount must be non-negative");
    }
    if (complete) {
      if (incompleteReason != null) {
        throw new IllegalArgumentException("complete manifest carries no incompleteReason");
      }
    } else if (incompleteReason == null) {
      throw new IllegalArgumentException("incomplete manifest requires an incompleteReason");
    }
  }
}
