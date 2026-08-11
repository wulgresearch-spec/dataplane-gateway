package io.reliabilityai.gateway.dataplane.reliability.application;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.reliability.api.AttemptRecord;
import io.reliabilityai.gateway.dataplane.reliability.api.CircuitPort;
import io.reliabilityai.gateway.dataplane.reliability.api.InvocationPlan;
import io.reliabilityai.gateway.dataplane.reliability.api.InvocationResult;
import io.reliabilityai.gateway.dataplane.reliability.api.OutcomeClass;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityEnginePort;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityFailureReason;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityMetricsPort;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicy;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicyPort;
import io.reliabilityai.gateway.dataplane.reliability.api.RetryBudgetPort;
import io.reliabilityai.gateway.dataplane.reliability.api.Sleeper;
import io.reliabilityai.gateway.dataplane.reliability.domain.BackoffCalculator;
import io.reliabilityai.gateway.dataplane.reliability.domain.OutcomeClassifier;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Reliability Engine (C2, Doc 20 §8). A deterministic, forward-only execution driver over the
 * Router's immutable candidate list: per candidate it skips an open circuit, invokes the adapter
 * within a deadline-bounded per-attempt budget, classifies the neutral outcome, and either
 * succeeds, retries the same candidate (deterministic backoff+jitter, charging the shared retry
 * budget), fails over to the next candidate, or surfaces — never duplicating a successful execution
 * and never inventing a candidate (RE-INV/RE-D3). Bounded by the per-request attempt ceiling, the
 * shared retry budget, the total deadline, and the finite candidate list, so retry storms are
 * structurally impossible (RE-D8).
 *
 * <p>Deterministic (RE-D9): decisions are a pure function of the plan, the observed outcomes, the
 * policy snapshot, and the injected clock; the only "randomness" is deterministically-seeded
 * jitter. No wall-clock/random in the core (time via {@link ClockPort}). Stateless and
 * virtual-thread-safe (the only blocking is the injected {@link Sleeper}). Credential-free,
 * SDK-free, HTTP-free — all provider I/O goes through {@link ProviderAdapterPort} (RE-D10).
 *
 * <p><b>Scope note (honest):</b> this implements the deterministic <b>sequential</b> retry/failover
 * core (RE-D1/D2/D3/D4/D5/D8/D9). Concurrent hedging (RE-D6) races two attempts and is
 * timing-dependent; it is executed as a bounded, first-success-wins race over the same {@link
 * ProviderAdapterPort} using virtual threads at the composition layer and is not part of this
 * deterministic, unit-tested core.
 */
public final class ReliabilityEngineService implements ReliabilityEnginePort {

  private final ProviderAdapterPort adapter;
  private final ReliabilityPolicyPort policyPort;
  private final RetryBudgetPort retryBudget;
  private final CircuitPort circuit;
  private final Sleeper sleeper;
  private final ClockPort clock;
  private final ReliabilityMetricsPort metrics;

  /**
   * Creates the engine against its injected ports (AD-002).
   *
   * @param adapter the provider adapter (one invoke == one attempt, Doc 25)
   * @param policyPort the resolved reliability policy source (Doc 20 §16)
   * @param retryBudget the shared retry-budget accounting (Doc 20 §14)
   * @param circuit the per-route circuit state (Doc 20 §10)
   * @param sleeper the backoff delay seam (Doc 20 §15)
   * @param clock the deterministic time seam (Doc 20 §9)
   * @param metrics the content-free reliability metrics seam (Doc 20 §33)
   */
  public ReliabilityEngineService(
      final ProviderAdapterPort adapter,
      final ReliabilityPolicyPort policyPort,
      final RetryBudgetPort retryBudget,
      final CircuitPort circuit,
      final Sleeper sleeper,
      final ClockPort clock,
      final ReliabilityMetricsPort metrics) {
    this.adapter = Preconditions.requireNonNull(adapter, "adapter");
    this.policyPort = Preconditions.requireNonNull(policyPort, "policyPort");
    this.retryBudget = Preconditions.requireNonNull(retryBudget, "retryBudget");
    this.circuit = Preconditions.requireNonNull(circuit, "circuit");
    this.sleeper = Preconditions.requireNonNull(sleeper, "sleeper");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
  }

  @Override
  public InvocationResult execute(final InvocationPlan plan) {
    Preconditions.requireNonNull(plan, "plan");
    final List<AttemptRecord> history = new ArrayList<>();
    CanonicalError lastError = null;
    try {
      return run(plan, history);
    } catch (final RuntimeException fault) {
      // Fail-closed backstop (Doc 20 §RE-INV / AD-016): a misbehaving injected port must never
      // throw
      // out of the engine — surface, never propagate. `lastError`/`history` captured before the
      // fault.
      return surfaced(ReliabilityFailureReason.NON_RETRYABLE, lastError, history);
    }
  }

  private InvocationResult run(final InvocationPlan plan, final List<AttemptRecord> history) {
    final Optional<ReliabilityPolicy> maybePolicy = policyPort.current();
    if (maybePolicy.isEmpty()) {
      return surfaced(ReliabilityFailureReason.POLICY_UNAVAILABLE, null, history);
    }
    final ReliabilityPolicy policy = maybePolicy.get();

    // The total deadline is the hardest terminal constraint — a past-deadline request is over
    // regardless of routing freshness (Doc 20 §9), so it is checked first.
    if (!clock.now().isBefore(plan.deadline())) {
      return surfaced(ReliabilityFailureReason.DEADLINE_EXCEEDED, null, history);
    }
    // Never execute a routing decision whose TTL has lapsed (Doc 19 §30.1 RC-6, PR-A15): surface so
    // the pipeline requests a fresh, re-hard-filtered decision (compliance/residency can never
    // drift).
    if (!clock.now().isBefore(plan.validUntil())) {
      return surfaced(ReliabilityFailureReason.ROUTING_DECISION_STALE, null, history);
    }

    final String correlationId = plan.correlationId().value();
    int totalAttempts = 0;
    CanonicalError lastError = null;

    for (final RouteTarget candidate : plan.candidates()) {
      final String routeRef = candidate.providerRouteRef();
      if (circuit.isOpen(routeRef)) {
        history.add(
            new AttemptRecord(history.size() + 1, routeRef, OutcomeClass.CIRCUIT_OPEN, null));
        safeMetric(metrics::circuitOpen);
        continue; // fail over to the next candidate (Doc 20 §10)
      }

      int candidateAttempt = 0;
      boolean failover = false;
      while (!failover) {
        if (!clock.now().isBefore(plan.deadline())) {
          return surfaced(ReliabilityFailureReason.DEADLINE_EXCEEDED, lastError, history);
        }
        if (totalAttempts >= policy.maxAttempts()) {
          return surfaced(ReliabilityFailureReason.ATTEMPT_CEILING_REACHED, lastError, history);
        }

        if (candidateAttempt > 0) {
          // A retry of the same candidate: back off within the deadline, then charge the shared
          // budget
          // ONLY once the attempt is actually going to be issued — never leak a budget token on a
          // deadline/interrupt abort (Doc 20 §14).
          final Duration backoff =
              BackoffCalculator.backoff(correlationId, totalAttempts + 1, policy);
          if (!clock.now().plus(backoff).isBefore(plan.deadline())) {
            return surfaced(ReliabilityFailureReason.DEADLINE_EXCEEDED, lastError, history);
          }
          try {
            sleeper.sleep(backoff);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // cancellation propagation (Doc 20 §12)
            return surfaced(ReliabilityFailureReason.DEADLINE_EXCEEDED, lastError, history);
          }
          if (!retryBudget.tryConsumeRetry()) {
            return surfaced(ReliabilityFailureReason.RETRY_BUDGET_EXHAUSTED, lastError, history);
          }
          safeMetric(metrics::retry);
        }

        // Per-attempt transport budget from a single clock read; a non-positive remaining means the
        // deadline was crossed between checks → surface, never construct a non-positive
        // AttemptBudget
        // (which would throw and escape the engine, Doc 20 §9).
        final Duration remaining = Duration.between(clock.now(), plan.deadline());
        if (remaining.isNegative() || remaining.isZero()) {
          return surfaced(ReliabilityFailureReason.DEADLINE_EXCEEDED, lastError, history);
        }
        final Duration attemptTimeout =
            policy.perAttemptTimeout().compareTo(remaining) <= 0
                ? policy.perAttemptTimeout()
                : remaining;

        final ProviderInvocationResult result;
        try {
          result = adapter.invoke(plan.request(), candidate, new AttemptBudget(attemptTimeout));
        } catch (final RuntimeException adapterFault) {
          // A misbehaving adapter must never abort the request — fail over to the next candidate
          // (AD-016 reliability-first). If every candidate faults, the loop surfaces
          // CANDIDATES_EXHAUSTED.
          totalAttempts++;
          lastError = new CanonicalError(ErrorCategory.UNKNOWN, null, "adapter_fault", false);
          history.add(
              new AttemptRecord(
                  history.size() + 1, routeRef, OutcomeClass.FATAL, ErrorCategory.UNKNOWN));
          safeMetric(metrics::failover);
          break; // exit the retry loop → advance to the next candidate
        }
        totalAttempts++;
        candidateAttempt++;

        if (result instanceof ProviderInvocationResult.Failed failed) {
          final CanonicalError error = failed.error();
          lastError = error;
          final OutcomeClassifier.Classification classification =
              OutcomeClassifier.classify(error.category());
          history.add(
              new AttemptRecord(
                  history.size() + 1, routeRef, classification.outcomeClass(), error.category()));
          switch (classification.action()) {
            case SURFACE -> {
              return surfaced(ReliabilityFailureReason.NON_RETRYABLE, error, history);
            }
            case FAILOVER -> {
              safeMetric(metrics::failover);
              failover = true;
            }
            case RETRY -> {
              if (error.category() == ErrorCategory.TIMEOUT && !plan.idempotent()) {
                // An ambiguous-execution TIMEOUT on a non-idempotent, non-suppressible operation
                // may
                // have executed a side effect upstream — retrying/failing over would duplicate it.
                // Surface on first failure, fail closed over duplicate (Doc 20 §21, RE-INV).
                return surfaced(ReliabilityFailureReason.NON_RETRYABLE, error, history);
              }
              if (candidateAttempt - 1 >= policy.maxRetriesPerCandidate()) {
                safeMetric(metrics::failover); // this candidate's retries are exhausted → fail over
                failover = true;
              }
              // else: loop and retry the same candidate
            }
            default -> {
              return surfaced(ReliabilityFailureReason.NON_RETRYABLE, error, history);
            }
          }
        } else {
          // Unary or Streaming: the single winning attempt (never duplicated, RE-INV).
          history.add(new AttemptRecord(history.size() + 1, routeRef, OutcomeClass.SUCCESS, null));
          safeMetric(metrics::succeeded);
          return new InvocationResult.Succeeded(result, candidate, List.copyOf(history));
        }
      }
    }
    return surfaced(ReliabilityFailureReason.CANDIDATES_EXHAUSTED, lastError, history);
  }

  private InvocationResult surfaced(
      final ReliabilityFailureReason reason,
      final CanonicalError lastError,
      final List<AttemptRecord> history) {
    safeMetric(() -> metrics.surfaced(reason.code()));
    return new InvocationResult.Surfaced(reason, lastError, List.copyOf(history));
  }

  /**
   * Emits a content-free metric best-effort: a misbehaving metrics port must never affect or fail
   * the invocation outcome, and never throw out of the engine (Doc 27 OT-A1/OT-INV, Doc 20 §33).
   */
  private static void safeMetric(final Runnable emit) {
    try {
      emit.run();
    } catch (final RuntimeException ignored) {
      // observability is side-effect-free — never affects the decision
    }
  }
}
