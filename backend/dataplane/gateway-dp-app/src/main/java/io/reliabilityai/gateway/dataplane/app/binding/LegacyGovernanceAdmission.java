package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceAdmissionPort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.ResolvedPolicyContext;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.GovernancePort;
import java.time.Instant;
import java.util.List;

/**
 * Presents a pre-V2 {@link GovernancePort} through the richer admission seam, so the pipeline has
 * one governance call site regardless of which enforcement point a deployment runs.
 *
 * <p>The adapter is deliberately lossy in one direction only. It hands the old port the three
 * things its signature accepts and discards the rest of the question — the model, the tools, the
 * token bound, the projected spend — because the old port has nowhere to put them. What it does
 * <b>not</b> do is pretend the discarded facts were evaluated: the decision it returns carries no
 * violations and an empty resolved context, so nothing downstream can mistake a legacy permit for a
 * fully-governed one.
 *
 * <p>This exists so migration is a wiring change rather than a flag day. A deployment still on the
 * legacy evaluator keeps working through the new pipeline unchanged; switching to the full engine
 * is then a change of which object is constructed, not a change to the request path.
 *
 * <p>A permit here means only "the legacy port permitted". Its narrower coverage is a property of
 * that port, documented on it, and is precisely why V2 exists.
 */
public final class LegacyGovernanceAdmission implements GovernanceAdmissionPort {

  private final GovernancePort delegate;
  private final ClockPort clock;

  /**
   * Wraps a legacy enforcement point.
   *
   * @param delegate the pre-V2 governance port
   * @param clock the injected clock, used only to stamp the context's validity instant
   */
  public LegacyGovernanceAdmission(final GovernancePort delegate, final ClockPort clock) {
    this.delegate = Preconditions.requireNonNull(delegate, "delegate");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  @Override
  public PolicyDecision admit(final PolicyRequest request) {
    Preconditions.requireNonNull(request, "request");
    final Instant now = now();
    final GovernanceDecision decision;
    try {
      decision =
          delegate.authorize(request.requestContext(), request.principal(), request.tenant());
    } catch (final RuntimeException failure) {
      // An enforcement point that throws has decided nothing, so nothing is admitted.
      return PolicyDecision.failClosed(
          io.reliabilityai.gateway.dataplane.governance.api.DenialReason.POLICY_UNAVAILABLE,
          PolicyVersion.NONE,
          0L,
          now);
    }
    if (decision == null) {
      return PolicyDecision.failClosed(
          io.reliabilityai.gateway.dataplane.governance.api.DenialReason.POLICY_UNAVAILABLE,
          PolicyVersion.NONE,
          0L,
          now);
    }
    if (!decision.permit()) {
      return new PolicyDecision(
          Verdict.DENY,
          decision.reason(),
          List.of(),
          PolicyVersion.NONE,
          0L,
          ResolvedPolicyContext.denied(PolicyVersion.NONE, now));
    }
    return new PolicyDecision(
        Verdict.ALLOW,
        PolicyDecision.PERMITTED,
        List.of(),
        PolicyVersion.NONE,
        0L,
        // Empty rather than fabricated: the legacy port resolved no scope, and inventing one here
        // would hand the Router constraints nobody authored.
        new ResolvedPolicyContext(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            ResolvedPolicyContext.UNBOUNDED,
            ResolvedPolicyContext.UNBOUNDED,
            ResolvedPolicyContext.UNBOUNDED,
            false,
            false,
            PolicyVersion.NONE,
            now));
  }

  private Instant now() {
    try {
      final Instant reading = clock.now();
      return reading == null ? Instant.EPOCH : reading;
    } catch (final RuntimeException broken) {
      return Instant.EPOCH;
    }
  }
}
