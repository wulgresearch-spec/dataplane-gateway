package io.reliabilityai.gateway.dataplane.agent;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.agent.api.AgentToolPort;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.PlanId;
import io.reliabilityai.gateway.dataplane.agent.api.RestartIntensity;
import io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunBudget;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunSecurityContext;
import io.reliabilityai.gateway.dataplane.agent.api.RunVersion;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisionStrategy;
import io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Shared builders and controllable fakes for the Agent Runtime's tests. */
public final class AgentFixtures {

  /** A fixed origin, so every assertion about time is about a difference and not about today. */
  public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  /** The tenant most tests run in. */
  public static final TenantScope TENANT = TenantScope.of("acme", "core");

  /** A second tenant, for isolation tests. */
  public static final TenantScope OTHER_TENANT = TenantScope.of("globex", "core");

  private AgentFixtures() {
    throw new AssertionError("no instances");
  }

  /** A clock the test drives by hand. Nothing in the runtime may read time any other way. */
  public static final class TestClock implements ClockPort {
    private final AtomicReference<Instant> now;

    public TestClock() {
      this(T0);
    }

    public TestClock(final Instant start) {
      this.now = new AtomicReference<>(start);
    }

    @Override
    public Instant now() {
      return now.get();
    }

    public void advance(final Duration by) {
      now.updateAndGet(instant -> instant.plus(by));
    }

    public void set(final Instant instant) {
      now.set(instant);
    }
  }

  /**
   * A session seam whose answers the test chooses.
   *
   * <p>Counts pipeline executions so a test can assert AD-025's central rule directly: that a run
   * of N model steps produced N complete pipeline executions and never reused one.
   */
  public static final class FakeSessions implements ToolSessionPort {
    private final List<SessionRequest> requests = new ArrayList<>();
    private final Map<String, SessionOutcome> recorded = new ConcurrentHashMap<>();
    private final AtomicInteger pipelineExecutions = new AtomicInteger();
    private final List<String> cancelled = new ArrayList<>();
    private Function<SessionRequest, SessionOutcome> responder =
        request -> new Completed("ok:" + request.instruction(), 100L, true, 1, false);
    private boolean rememberOutcomes = true;
    private RuntimeException toThrow;

    public FakeSessions respondWith(final Function<SessionRequest, SessionOutcome> answer) {
      this.responder = answer;
      return this;
    }

    public FakeSessions alwaysFail(final FailureClass failure, final String reason) {
      return respondWith(request -> new Failed(failure, reason, 5L, true, 1));
    }

    public FakeSessions throwing(final RuntimeException failure) {
      this.toThrow = failure;
      return this;
    }

    /** Makes {@link #lookup} return empty, as a node that died and lost its map would. */
    public FakeSessions forgetful() {
      this.rememberOutcomes = false;
      return this;
    }

    @Override
    public synchronized SessionOutcome openSession(final SessionRequest request) {
      requests.add(request);
      if (toThrow != null) {
        throw toThrow;
      }
      final SessionOutcome outcome = responder.apply(request);
      pipelineExecutions.addAndGet(outcome.pipelineExecutions());
      if (rememberOutcomes) {
        recorded.put(request.sessionRef(), outcome);
      }
      return outcome;
    }

    @Override
    public synchronized void cancelSession(final String sessionRef) {
      cancelled.add(sessionRef);
    }

    @Override
    public Optional<SessionOutcome> lookup(final String sessionRef) {
      return Optional.ofNullable(recorded.get(sessionRef));
    }

    public synchronized List<SessionRequest> requests() {
      return List.copyOf(requests);
    }

    public int pipelineExecutions() {
      return pipelineExecutions.get();
    }

    public synchronized List<String> cancelled() {
      return List.copyOf(cancelled);
    }

    /** Pre-loads an outcome as though a now-dead node had produced it. */
    public FakeSessions remember(final String sessionRef, final SessionOutcome outcome) {
      recorded.put(sessionRef, outcome);
      return this;
    }
  }

  /** A tool seam whose answers the test chooses. */
  public static final class FakeTools implements AgentToolPort {
    private final List<ToolCall> calls = new ArrayList<>();
    private final Map<String, ToolOutcome> recorded = new ConcurrentHashMap<>();
    private Function<ToolCall, ToolOutcome> responder =
        call -> new Produced("tool:" + call.capability(), 7L, false);

    public FakeTools respondWith(final Function<ToolCall, ToolOutcome> answer) {
      this.responder = answer;
      return this;
    }

    @Override
    public synchronized ToolOutcome invoke(final ToolCall call) {
      calls.add(call);
      final ToolOutcome outcome = responder.apply(call);
      recorded.put(call.invocationRef(), outcome);
      return outcome;
    }

    @Override
    public void cancel(final String invocationRef) {
      // Nothing to interrupt in a fake.
    }

    @Override
    public Optional<ToolOutcome> lookup(final String invocationRef) {
      return Optional.ofNullable(recorded.get(invocationRef));
    }

    public synchronized List<ToolCall> calls() {
      return List.copyOf(calls);
    }
  }

  /** A run's security context, with every capability a test plan asks for. */
  public static RunSecurityContext security(final String... capabilities) {
    return new RunSecurityContext(
        new PrincipalId("p-1"), TENANT, new CorrelationId("corr-1"), Set.of(capabilities));
  }

  /** A security context in a named tenant. */
  public static RunSecurityContext securityIn(
      final TenantScope tenant, final String correlation, final String... capabilities) {
    return new RunSecurityContext(
        new PrincipalId("p-1"), tenant, new CorrelationId(correlation), Set.of(capabilities));
  }

  /** A model step with a generous budget and deadline. */
  public static Step.Pipeline model(final String name, final String... inputRefs) {
    return new Step.Pipeline(
        name,
        "do " + name,
        List.of(inputRefs),
        Set.of("chat"),
        512L,
        1_000L,
        Duration.ofMinutes(1),
        true);
  }

  /** A model step declared non-idempotent, so the supervisor must refuse to auto-retry it. */
  public static Step.Pipeline irreversibleModel(final String name) {
    return new Step.Pipeline(
        name, "do " + name, List.of(), Set.of("chat"), 512L, 1_000L, Duration.ofMinutes(1), false);
  }

  /** A tool step. */
  public static Step.Plugin tool(final String name, final String capability) {
    return new Step.Plugin(name, capability, "{}", List.of(), 500L, Duration.ofSeconds(30), true);
  }

  /** A plan of the given steps, with default bounds and a strict supervision policy. */
  public static Plan plan(final Step... steps) {
    return plan(1, RunBounds.DEFAULT, RestartPolicy.STRICT, steps);
  }

  /** A plan with an explicit version, bounds and policy. */
  public static Plan plan(
      final int version, final RunBounds bounds, final RestartPolicy policy, final Step... steps) {
    return new Plan(PlanId.of("p"), version, List.of(steps), bounds, policy);
  }

  /** A policy that retries a step a fixed number of times with no delay. */
  public static RestartPolicy retrying(final int attempts, final int intensity) {
    return new RestartPolicy(
        SupervisionStrategy.RETRY_STEP,
        io.reliabilityai.gateway.dataplane.agent.api.RetryMode.IMMEDIATE,
        attempts,
        Duration.ZERO,
        Duration.ofSeconds(1),
        new RestartIntensity(intensity, Duration.ofMinutes(1)),
        "",
        "");
  }

  /** A policy that absorbs failures and carries on. */
  public static RestartPolicy skipping() {
    return new RestartPolicy(
        SupervisionStrategy.SKIP_STEP,
        io.reliabilityai.gateway.dataplane.agent.api.RetryMode.NONE,
        1,
        Duration.ZERO,
        Duration.ZERO,
        RestartIntensity.NONE,
        "",
        "");
  }

  /** The creation event for a run of a plan. */
  public static RunEvent.RunCreated created(final Plan plan, final RunSecurityContext security) {
    return new RunEvent.RunCreated(
        RunVersion.pin(plan.id(), plan.version()),
        plan.bounds(),
        RunBudget.of(1_000_000L),
        security,
        Optional.empty(),
        0,
        T0);
  }

  /** The creation event for a run with an explicit budget. */
  public static RunEvent.RunCreated created(
      final Plan plan, final RunSecurityContext security, final long grantMicros) {
    return new RunEvent.RunCreated(
        RunVersion.pin(plan.id(), plan.version()),
        plan.bounds(),
        RunBudget.of(grantMicros),
        security,
        Optional.empty(),
        0,
        T0);
  }

  /** A step identity within a run. */
  public static StepId step(final String runId, final int index) {
    return StepId.of(RunId.of(runId), index);
  }
}
