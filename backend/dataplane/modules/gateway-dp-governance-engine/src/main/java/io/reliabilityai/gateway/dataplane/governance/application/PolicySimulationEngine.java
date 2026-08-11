package io.reliabilityai.gateway.dataplane.governance.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyCompilationException;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyViolation;
import io.reliabilityai.gateway.dataplane.governance.api.SimulationImpact;
import io.reliabilityai.gateway.dataplane.governance.api.SimulationResult;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyCompiler;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Answers "what would happen if" without anything happening (Doc 21 §44, mission simulation mode).
 *
 * <p>The question a governance change raises is not whether the new policy is valid — the compiler
 * settles that — but who it breaks. Rolling out a tightened model allow-list and finding out from a
 * support queue is an expensive way to learn that one team was using a model nobody remembered
 * approving. This class answers the same question beforehand, by putting real request shapes to a
 * candidate generation and comparing verdict for verdict.
 *
 * <p><b>Nothing here has an effect.</b> No generation is installed, no consumption counter moves,
 * and every audit fact it emits is stamped {@code simulated}. That flag is not decoration: an audit
 * stream where a simulated denial is indistinguishable from a real one is a stream that cannot be
 * used as evidence, because no reviewer can tell which records describe things that actually
 * happened.
 *
 * <p>Simulation deliberately reuses the production evaluator against a real snapshot rather than
 * modelling what the evaluator would do. A simulator with its own copy of the rules is a simulator
 * that drifts, and the day it drifts is the day it says a rollout is safe and it is not.
 *
 * <p>Three generations can be targeted: a <b>past</b> one this node served (from the store's
 * history), the <b>current</b> one, and a <b>candidate</b> compiled from an authored bundle that
 * has never been installed.
 */
public final class PolicySimulationEngine {

  private final GovernanceEngine engine;
  private final PolicyCompiler compiler;
  private final ClockPort clock;

  /**
   * Creates the simulator over a live engine.
   *
   * @param engine the engine whose evaluator and registry are reused
   * @param compiler the compiler used to turn a candidate bundle into a snapshot
   * @param clock the injected clock — the instant a simulation is evaluated as of
   */
  public PolicySimulationEngine(
      final GovernanceEngine engine, final PolicyCompiler compiler, final ClockPort clock) {
    this.engine = Preconditions.requireNonNull(engine, "engine");
    this.compiler = Preconditions.requireNonNull(compiler, "compiler");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  /**
   * Evaluates a request against the generation in force, changing nothing.
   *
   * @param request the governance question
   * @return the decision the current generation would produce
   */
  public PolicyDecision simulateCurrent(final PolicyRequest request) {
    return simulate(request, engine.registry().current());
  }

  /**
   * Evaluates a request against a generation this node previously served.
   *
   * @param request the governance question
   * @param version the past generation
   * @return the decision that generation would have produced, or empty when it is no longer
   *     retained
   */
  public Optional<PolicyDecision> simulatePast(
      final PolicyRequest request, final PolicyVersion version) {
    return engine.registry().snapshotOf(version).map(snapshot -> simulate(request, snapshot));
  }

  /**
   * Evaluates a request against an arbitrary generation.
   *
   * @param request the governance question
   * @param snapshot the generation to evaluate against
   * @return the decision that generation would produce
   */
  public PolicyDecision simulate(final PolicyRequest request, final PolicySnapshot snapshot) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(snapshot, "snapshot");
    return engine.evaluateAgainst(request, snapshot, now());
  }

  /**
   * Compiles an authored bundle into a candidate generation without installing it.
   *
   * <p>Separate from {@link #whatIf} so that a bundle which will not compile fails here, loudly,
   * before anyone starts interpreting simulation results produced from a policy that could never
   * have run.
   *
   * @param bundle the candidate generation, as authored
   * @return the compiled candidate
   * @throws PolicyCompilationException when the bundle is incoherent or demotes a hard tier
   */
  public PolicySnapshot compileCandidate(final PolicySourcePort.PolicyBundle bundle) {
    return compiler.compile(bundle);
  }

  /**
   * Puts one request to both the generation in force and a candidate.
   *
   * @param request the governance question
   * @param candidate the proposed generation
   * @return both decisions, side by side
   */
  public SimulationResult whatIf(final PolicyRequest request, final PolicySnapshot candidate) {
    Preconditions.requireNonNull(candidate, "candidate");
    final Instant at = now();
    return new SimulationResult(
        engine.evaluateAgainst(request, engine.registry().current(), at),
        engine.evaluateAgainst(request, candidate, at));
  }

  /**
   * Puts a sample of requests to a candidate generation and reports the blast radius.
   *
   * <p>All requests are evaluated as of a single instant, read once. Reading the clock per request
   * would let a maintenance window open partway through a batch and produce a report in which some
   * requests were judged under different conditions than others — which is exactly the sort of
   * subtle inconsistency that makes an operator distrust the tool and roll out blind instead.
   *
   * @param requests the sample to evaluate
   * @param candidate the proposed generation
   * @return the aggregate impact
   */
  public SimulationImpact impactOf(
      final List<PolicyRequest> requests, final PolicySnapshot candidate) {
    Preconditions.requireNonNull(requests, "requests");
    Preconditions.requireNonNull(candidate, "candidate");
    final Instant at = now();
    final PolicySnapshot inForce = engine.registry().current();

    int unchanged = 0;
    int newlyDenied = 0;
    int newlyAdmitted = 0;
    final TreeSet<String> causes = new TreeSet<>();
    for (final PolicyRequest request : requests) {
      final SimulationResult result =
          new SimulationResult(
              engine.evaluateAgainst(request, inForce, at),
              engine.evaluateAgainst(request, candidate, at));
      if (!result.changed()) {
        unchanged++;
      }
      if (result.newlyDenied()) {
        newlyDenied++;
        result.candidate().binding().map(PolicyViolation::ruleId).ifPresent(causes::add);
      } else if (result.newlyAdmitted()) {
        newlyAdmitted++;
      }
    }
    return new SimulationImpact(
        requests.size(), unchanged, newlyDenied, newlyAdmitted, new ArrayList<>(causes));
  }

  private Instant now() {
    final Instant reading = clock.now();
    return reading == null ? Instant.EPOCH : reading;
  }
}
