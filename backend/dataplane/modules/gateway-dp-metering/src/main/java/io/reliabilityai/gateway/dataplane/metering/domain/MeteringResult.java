package io.reliabilityai.gateway.dataplane.metering.domain;

import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The terminal outcome of recording one attempt (Doc 23 §6). Sealed: either a durably-recorded,
 * immutable {@link UsageFact}, or a fail-closed {@link UsageUnrecorded} that surfaces an accounting
 * incident (Doc 23 §38, UME-INV) — never a silent success on uncertain usage.
 */
public sealed interface MeteringResult
    permits MeteringResult.Recorded, MeteringResult.UsageUnrecorded {

  /**
   * A durably-recorded usage fact (WAL-committed, Doc 23 §39.1 UC-1).
   *
   * @param fact the immutable canonical usage fact
   */
  record Recorded(UsageFact fact) implements MeteringResult {
    /** Compact constructor validating the fact. */
    public Recorded {
      Preconditions.requireNonNull(fact, "fact");
    }
  }

  /**
   * A fail-closed unrecorded outcome (Doc 23 §38) — surfaced, never silently dropped.
   *
   * @param reason the neutral unrecorded reason
   * @param attemptId the attempt that could not be recorded
   */
  record UsageUnrecorded(UnrecordedReason reason, AttemptId attemptId) implements MeteringResult {
    /** Compact constructor validating fields. */
    public UsageUnrecorded {
      Preconditions.requireNonNull(reason, "reason");
      Preconditions.requireNonNull(attemptId, "attemptId");
    }
  }
}
