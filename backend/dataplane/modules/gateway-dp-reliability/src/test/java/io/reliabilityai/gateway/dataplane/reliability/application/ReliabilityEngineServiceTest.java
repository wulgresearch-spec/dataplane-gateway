package io.reliabilityai.gateway.dataplane.reliability.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.io.ProviderMeta;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.dataplane.reliability.api.CircuitPort;
import io.reliabilityai.gateway.dataplane.reliability.api.InvocationPlan;
import io.reliabilityai.gateway.dataplane.reliability.api.InvocationResult;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityFailureReason;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityMetricsPort;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicy;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicyPort;
import io.reliabilityai.gateway.dataplane.reliability.api.RetryBudgetPort;
import io.reliabilityai.gateway.dataplane.reliability.api.Sleeper;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Deterministic retry/failover/circuit/budget/deadline tests for the Reliability Engine (Doc 20).
 */
class ReliabilityEngineServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(3600);
  private static final RouteTarget A = new RouteTarget(new CanonicalModelId("m"), "route/a");
  private static final RouteTarget B = new RouteTarget(new CanonicalModelId("m"), "route/b");
  private static final ReliabilityPolicy POLICY =
      new ReliabilityPolicy(5, 2, 10L, 100L, Duration.ofSeconds(5));

  private final FakeAdapter adapter = new FakeAdapter();
  private final FakeBudget budget = new FakeBudget(100);
  private final FakeCircuit circuit = new FakeCircuit();
  private final RecordingSleeper sleeper = new RecordingSleeper();
  private final CountingMetrics metrics = new CountingMetrics();
  private final ReliabilityPolicyPort policyPort = () -> java.util.Optional.of(POLICY);
  private final ClockPort clock = () -> NOW;

  private ReliabilityEngineService engine() {
    return new ReliabilityEngineService(
        adapter, policyPort, budget, circuit, sleeper, clock, metrics);
  }

  private static InvocationPlan plan(final RouteTarget... candidates) {
    return plan(true, candidates);
  }

  private static InvocationPlan plan(final boolean idempotent, final RouteTarget... candidates) {
    return planWithTtl(DEADLINE, idempotent, candidates);
  }

  private static InvocationPlan planWithTtl(
      final Instant validUntil, final boolean idempotent, final RouteTarget... candidates) {
    return new InvocationPlan(
        new CanonicalRequest(
            new CanonicalModelId("m"), List.of(new Message("user", "hi")), List.of(), Map.of()),
        List.of(candidates),
        new CorrelationId("corr-1"),
        DEADLINE,
        validUntil,
        idempotent);
  }

  private static ProviderInvocationResult success() {
    return new ProviderInvocationResult.Unary(
        new CanonicalResponse(
            "ok",
            List.of(),
            FinishReason.STOP,
            new CanonicalUsage(1, 1, 0, 0, 0, UsageClass.AUTHORITATIVE),
            ProviderMeta.empty()));
  }

  private static ProviderInvocationResult failure(final ErrorCategory category) {
    return new ProviderInvocationResult.Failed(new CanonicalError(category, true, "opaque", true));
  }

  @Test
  void succeedsOnFirstAttempt() {
    adapter.enqueue("route/a", success());
    final InvocationResult result = engine().execute(plan(A));
    assertThat(result).isInstanceOf(InvocationResult.Succeeded.class);
    assertThat(((InvocationResult.Succeeded) result).winner()).isEqualTo(A);
    assertThat(metrics.succeeded).isEqualTo(1);
    assertThat(result.history()).hasSize(1);
  }

  @Test
  void retriesTransientThenSucceeds() {
    adapter.enqueue("route/a", failure(ErrorCategory.TRANSPORT));
    adapter.enqueue("route/a", failure(ErrorCategory.TRANSPORT));
    adapter.enqueue("route/a", success());
    final InvocationResult result = engine().execute(plan(A));
    assertThat(result).isInstanceOf(InvocationResult.Succeeded.class);
    assertThat(metrics.retry).isEqualTo(2); // two retries of the same candidate
    assertThat(budget.consumed).isEqualTo(2); // each retry charges the shared budget
    assertThat(sleeper.sleeps).hasSize(2); // backoff before each retry
  }

  @Test
  void failsOverWhenProviderUnavailableThenSucceeds() {
    adapter.enqueue("route/a", failure(ErrorCategory.PROVIDER_UNAVAILABLE));
    adapter.enqueue("route/b", success());
    final InvocationResult result = engine().execute(plan(A, B));
    assertThat(((InvocationResult.Succeeded) result).winner()).isEqualTo(B);
    assertThat(metrics.failover).isEqualTo(1);
    assertThat(budget.consumed).isZero(); // failover to a new candidate is not a retry
  }

  @Test
  void surfacesNonRetryableWithoutFailover() {
    adapter.enqueue("route/a", failure(ErrorCategory.AUTH_FAILED));
    adapter.enqueue("route/b", success());
    final InvocationResult result = engine().execute(plan(A, B));
    assertThat(result).isInstanceOf(InvocationResult.Surfaced.class);
    assertThat(((InvocationResult.Surfaced) result).reason())
        .isEqualTo(ReliabilityFailureReason.NON_RETRYABLE);
    assertThat(adapter.callsTo("route/b"))
        .isZero(); // never tried B — auth is per-request permanent
  }

  @Test
  void failsOverAfterExhaustingPerCandidateRetries() {
    for (int i = 0; i < 6; i++) {
      adapter.enqueue("route/a", failure(ErrorCategory.TRANSPORT));
    }
    adapter.enqueue("route/b", success());
    final InvocationResult result = engine().execute(plan(A, B));
    assertThat(((InvocationResult.Succeeded) result).winner()).isEqualTo(B);
    // A tried 1 + maxRetriesPerCandidate(2) = 3 times, then failover to B.
    assertThat(adapter.callsTo("route/a")).isEqualTo(3);
  }

  @Test
  void surfacesRetryBudgetExhausted() {
    final ReliabilityEngineService e =
        new ReliabilityEngineService(
            adapter, policyPort, new FakeBudget(0), circuit, sleeper, clock, metrics);
    adapter.enqueue("route/a", failure(ErrorCategory.TRANSPORT));
    adapter.enqueue("route/a", failure(ErrorCategory.TRANSPORT));
    final InvocationResult result = e.execute(plan(A));
    assertThat(((InvocationResult.Surfaced) result).reason())
        .isEqualTo(ReliabilityFailureReason.RETRY_BUDGET_EXHAUSTED);
  }

  @Test
  void surfacesDeadlineExceeded() {
    final ClockPort past = () -> DEADLINE.plusSeconds(1);
    final ReliabilityEngineService e =
        new ReliabilityEngineService(adapter, policyPort, budget, circuit, sleeper, past, metrics);
    assertThat(((InvocationResult.Surfaced) e.execute(plan(A))).reason())
        .isEqualTo(ReliabilityFailureReason.DEADLINE_EXCEEDED);
  }

  @Test
  void skipsOpenCircuitAndFailsOver() {
    circuit.open.add("route/a");
    adapter.enqueue("route/b", success());
    final InvocationResult result = engine().execute(plan(A, B));
    assertThat(((InvocationResult.Succeeded) result).winner()).isEqualTo(B);
    assertThat(metrics.circuitOpen).isEqualTo(1);
    assertThat(adapter.callsTo("route/a")).isZero();
  }

  @Test
  void surfacesCandidatesExhausted() {
    circuit.open.add("route/a");
    circuit.open.add("route/b");
    assertThat(((InvocationResult.Surfaced) engine().execute(plan(A, B))).reason())
        .isEqualTo(ReliabilityFailureReason.CANDIDATES_EXHAUSTED);
  }

  @Test
  void surfacesPolicyUnavailable() {
    final ReliabilityEngineService e =
        new ReliabilityEngineService(
            adapter, () -> java.util.Optional.empty(), budget, circuit, sleeper, clock, metrics);
    assertThat(((InvocationResult.Surfaced) e.execute(plan(A))).reason())
        .isEqualTo(ReliabilityFailureReason.POLICY_UNAVAILABLE);
  }

  @Test
  void rejectsNullPlan() {
    assertThatThrownBy(() -> engine().execute(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void throwingMetricsNeverAffectsOrFailsTheOutcome() {
    // Observability must never affect the decision (OT-A1): a metrics port that throws on every
    // call
    // must not turn a successful invocation into a failure nor escape the engine.
    final ReliabilityMetricsPort boom =
        new ReliabilityMetricsPort() {
          @Override
          public void succeeded() {
            throw new RuntimeException("boom");
          }

          @Override
          public void retry() {
            throw new RuntimeException("boom");
          }

          @Override
          public void failover() {
            throw new RuntimeException("boom");
          }

          @Override
          public void circuitOpen() {
            throw new RuntimeException("boom");
          }

          @Override
          public void surfaced(final String reason) {
            throw new RuntimeException("boom");
          }
        };
    final ReliabilityEngineService e =
        new ReliabilityEngineService(adapter, policyPort, budget, circuit, sleeper, clock, boom);
    adapter.enqueue("route/a", success());
    assertThat(e.execute(plan(A))).isInstanceOf(InvocationResult.Succeeded.class);
    // A surface path where surfaced() throws must also not escape the engine (fail closed, not
    // open):
    final ReliabilityEngineService surfacing =
        new ReliabilityEngineService(
            adapter, () -> java.util.Optional.empty(), budget, circuit, sleeper, clock, boom);
    assertThat(surfacing.execute(plan(A))).isInstanceOf(InvocationResult.Surfaced.class);
  }

  @Test
  void adapterFaultFailsOverToNextCandidateNeverThrows() {
    adapter.throwOn.add("route/a"); // candidate A's adapter throws
    adapter.enqueue("route/b", success());
    final InvocationResult result = engine().execute(plan(A, B));
    assertThat(((InvocationResult.Succeeded) result).winner())
        .isEqualTo(B); // failed over, not aborted
    assertThat(metrics.failover).isEqualTo(1);
  }

  @Test
  void adapterFaultOnAllCandidatesSurfacesFailClosed() {
    adapter.throwOn.add("route/a");
    adapter.throwOn.add("route/b");
    final InvocationResult result = engine().execute(plan(A, B));
    assertThat(result)
        .isInstanceOf(InvocationResult.Surfaced.class); // never throws out of execute()
    assertThat(((InvocationResult.Surfaced) result).reason())
        .isEqualTo(ReliabilityFailureReason.CANDIDATES_EXHAUSTED);
  }

  @Test
  void staleRoutingDecisionIsRefusedNeverExecuted() {
    // The routing-decision TTL has already lapsed (validUntil == NOW) — the engine must refuse to
    // execute a stale decision and surface for a refresh (Doc 19 §30.1 RC-6, PR-A15).
    adapter.enqueue("route/a", success());
    final InvocationResult result = engine().execute(planWithTtl(NOW, true, A));
    assertThat(((InvocationResult.Surfaced) result).reason())
        .isEqualTo(ReliabilityFailureReason.ROUTING_DECISION_STALE);
    assertThat(adapter.callsTo("route/a")).isZero(); // never executed the stale route
  }

  @Test
  void nonIdempotentTimeoutSurfacesNeverRetriesDuplicateExecution() {
    // A TIMEOUT may have executed a side effect upstream; on a non-idempotent op it must surface on
    // first failure, never retry/failover (Doc 20 §21, RE-INV — fail closed over duplicate).
    adapter.enqueue("route/a", failure(ErrorCategory.TIMEOUT));
    adapter.enqueue("route/b", success());
    final InvocationResult result = engine().execute(plan(false, A, B));
    assertThat(result).isInstanceOf(InvocationResult.Surfaced.class);
    assertThat(((InvocationResult.Surfaced) result).reason())
        .isEqualTo(ReliabilityFailureReason.NON_RETRYABLE);
    assertThat(adapter.callsTo("route/a")).isEqualTo(1); // no retry
    assertThat(adapter.callsTo("route/b")).isZero(); // no failover either (would also duplicate)
  }

  @Test
  void idempotentTimeoutIsRetried() {
    adapter.enqueue("route/a", failure(ErrorCategory.TIMEOUT));
    adapter.enqueue("route/a", success());
    final InvocationResult result = engine().execute(plan(true, A));
    assertThat(result)
        .isInstanceOf(InvocationResult.Succeeded.class); // idempotent ⇒ retry permitted
  }

  @Test
  void misbehavingPortFailsClosedViaBackstop() {
    circuit.explode = true; // circuit.isOpen throws → outer backstop must surface, never propagate
    final InvocationResult result = engine().execute(plan(A));
    assertThat(result).isInstanceOf(InvocationResult.Surfaced.class);
    assertThat(((InvocationResult.Surfaced) result).reason())
        .isEqualTo(ReliabilityFailureReason.NON_RETRYABLE);
  }

  private static final class FakeAdapter implements ProviderAdapterPort {
    private final Map<String, Deque<ProviderInvocationResult>> byRoute = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final Set<String> throwOn = new java.util.HashSet<>();

    void enqueue(final String routeRef, final ProviderInvocationResult result) {
      byRoute.computeIfAbsent(routeRef, k -> new ArrayDeque<>()).add(result);
    }

    int callsTo(final String routeRef) {
      return calls.getOrDefault(routeRef, 0);
    }

    @Override
    public ProviderInvocationResult invoke(
        final CanonicalRequest request, final RouteTarget routeTarget, final AttemptBudget budget) {
      final String ref = routeTarget.providerRouteRef();
      calls.merge(ref, 1, Integer::sum);
      if (throwOn.contains(ref)) {
        throw new RuntimeException("adapter boom"); // a misbehaving adapter
      }
      final Deque<ProviderInvocationResult> queue = byRoute.get(ref);
      if (queue == null || queue.isEmpty()) {
        return failure(ErrorCategory.TRANSPORT);
      }
      return queue.size() == 1 ? queue.peek() : queue.poll();
    }
  }

  private static final class FakeBudget implements RetryBudgetPort {
    private int remaining;
    private int consumed;

    private FakeBudget(final int remaining) {
      this.remaining = remaining;
    }

    @Override
    public boolean tryConsumeRetry() {
      if (remaining <= 0) {
        return false;
      }
      remaining--;
      consumed++;
      return true;
    }
  }

  private static final class FakeCircuit implements CircuitPort {
    private final Set<String> open = new java.util.HashSet<>();
    private boolean explode;

    @Override
    public boolean isOpen(final String providerRouteRef) {
      if (explode) {
        throw new RuntimeException("circuit boom"); // a misbehaving injected port
      }
      return open.contains(providerRouteRef);
    }
  }

  private static final class RecordingSleeper implements Sleeper {
    private final List<Duration> sleeps = new ArrayList<>();

    @Override
    public void sleep(final Duration duration) {
      sleeps.add(duration);
    }
  }

  private static final class CountingMetrics implements ReliabilityMetricsPort {
    private int succeeded;
    private int retry;
    private int failover;
    private int circuitOpen;

    @Override
    public void succeeded() {
      succeeded++;
    }

    @Override
    public void retry() {
      retry++;
    }

    @Override
    public void failover() {
      failover++;
    }

    @Override
    public void circuitOpen() {
      circuitOpen++;
    }

    @Override
    public void surfaced(final String reason) {
      // counted implicitly by outcome assertions
    }
  }
}
