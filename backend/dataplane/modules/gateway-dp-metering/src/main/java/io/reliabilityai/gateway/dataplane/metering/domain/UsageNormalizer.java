package io.reliabilityai.gateway.dataplane.metering.domain;

import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Map;

/**
 * The pure, deterministic normalization core (Doc 23 §11, UME-D8) — turns an {@link ExecutionFact}
 * into an immutable canonical {@link UsageFact}, or fails closed. It records <b>only</b> usage
 * reported by execution (Doc 23 §D5): it <b>never fabricates</b> a token count and <b>never
 * estimates</b> — missing usage ⇒ {@code MISSING_USAGE}; provider-flagged estimated usage ⇒
 * recorded only if policy permits, always flagged, else {@code ESTIMATED_NOT_PERMITTED} (Doc 23
 * §24). Every fact stamps the descriptor version for replay (Doc 23 §36). Deterministic: a pure
 * function of {@code (fact, descriptor, clock)} — no wall-clock/random (R-063).
 */
public final class UsageNormalizer {

  private UsageNormalizer() {}

  /**
   * Normalizes an execution fact into a canonical usage fact, or a fail-closed unrecorded result
   * (Doc 23 §11/§24/§D5).
   *
   * @param fact the runtime execution fact
   * @param descriptor the pinned usage descriptor (version stamped on the fact)
   * @param timestamp the injected clock time (Doc 23 §36)
   * @param allowEstimated whether upstream policy permits recording a flagged-estimated fact (Doc
   *     23 §24)
   * @return {@code Recorded(UsageFact)} or {@code UsageUnrecorded(reason)}
   */
  public static MeteringResult normalize(
      final ExecutionFact fact,
      final UsageDescriptor descriptor,
      final Instant timestamp,
      final boolean allowEstimated) {
    Preconditions.requireNonNull(fact, "fact");
    Preconditions.requireNonNull(descriptor, "descriptor");
    Preconditions.requireNonNull(timestamp, "timestamp");

    final CanonicalUsage usage = fact.reportedUsage();
    if (usage == null || fact.reportedUsageClass() == null) {
      // Missing/ambiguous usage ⇒ fail closed; never fabricate, never silent-zero (Doc 23 §D5/§38).
      return new MeteringResult.UsageUnrecorded(UnrecordedReason.MISSING_USAGE, fact.attemptId());
    }
    if (fact.reportedUsageClass() == UsageClass.ESTIMATED && !allowEstimated) {
      // Estimated by default fails closed; a flagged estimated fact only under explicit policy
      // (§24).
      return new MeteringResult.UsageUnrecorded(
          UnrecordedReason.ESTIMATED_NOT_PERMITTED, fact.attemptId());
    }

    final UsageFact usageFact =
        new UsageFact(
            fact.idempotencyKey(),
            fact.attemptId(),
            fact.tenantScope(),
            fact.canonicalModelId(),
            fact.region(),
            fact.reportedUsageClass(),
            usage,
            fact.attemptClass(),
            fact.delivered(),
            timestamp,
            Map.of("usageDescriptor", descriptor.version()));
    return new MeteringResult.Recorded(usageFact);
  }
}
