package io.reliabilityai.gateway.dataplane.app.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.PlanId;
import io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy;
import io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunSecurityContext;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort;
import io.reliabilityai.gateway.dataplane.agent.application.AgentRuntime;
import io.reliabilityai.gateway.dataplane.agent.application.RunExecutor;
import io.reliabilityai.gateway.dataplane.agent.application.ToolSessionRunner;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryPlanRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryRunRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InProcessAgentMetrics;
import io.reliabilityai.gateway.dataplane.app.binding.PipelineToolSessionAdapter;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The Agent Runtime driving the real {@code RequestPipeline}, end to end.
 *
 * <p>Everything above this point has used a fake session seam. These tests use the actual pipeline
 * assembly, so what they demonstrate is the milestone's central claim rather than a restatement of
 * it: an agent step enters at INGRESS and passes through every mandatory stage, one complete
 * execution per model invocation, with nothing carried over from the step before.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AgentPipelineIntegrationTest {

  private static final ClockPort CLOCK = () -> RequestPipelineTest.Harness.NOW;

  private final RequestPipelineTest.Harness harness = new RequestPipelineTest.Harness();
  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
  private final InProcessAgentMetrics metrics = new InProcessAgentMetrics();

  private final PipelineToolSessionAdapter.SessionContext context =
      new PipelineToolSessionAdapter.SessionContext(
          RequestPipelineTest.Harness.MODEL,
          new ForwardedTransportIdentity("Bearer", "token", Map.of()),
          new Region("us-east-1"),
          new CausationId("cause-1"),
          1024,
          Set.of());

  private PipelineToolSessionAdapter adapter() {
    return new PipelineToolSessionAdapter(
        harness.pipeline(), correlation -> Optional.of(context), CLOCK);
  }

  private RunSecurityContext security() {
    return new RunSecurityContext(
        new PrincipalId("p-1"),
        RequestPipelineTest.Harness.TENANT,
        new CorrelationId("corr-1"),
        Set.of("chat"));
  }

  private static Step.Pipeline step(final String name) {
    return new Step.Pipeline(
        name, "do " + name, List.of(), Set.of("chat"), 256L, 10_000L, Duration.ofMinutes(1), true);
  }

  private Plan publish(final Step... steps) {
    final Plan plan =
        new Plan(
            PlanId.of("integration"), 1, List.of(steps), RunBounds.DEFAULT, RestartPolicy.STRICT);
    plans.publish(plan);
    return plan;
  }

  private RunExecutor executorOver(final PipelineToolSessionAdapter sessions) {
    return new RunExecutor(
        runs,
        plans,
        new ToolSessionRunner(sessions, new NoTools()),
        CLOCK,
        metrics,
        RunAuditPort.NOOP);
  }

  private static RunExecutor.Advance drain(final RunExecutor executor, final RunId run) {
    RunExecutor.Advance last = new RunExecutor.Advance.Idle();
    for (int i = 0; i < 30; i++) {
      last = executor.advance(run);
      if (last instanceof RunExecutor.Advance.Terminated
          || last instanceof RunExecutor.Advance.Idle
          || last instanceof RunExecutor.Advance.Stalled) {
        return last;
      }
    }
    return last;
  }

  // ---- The central claim, against the real pipeline ---------------------------------------------

  @Test
  void aThreeStepAgentRunPerformsThreeCompletePipelineExecutions() {
    final Plan plan = publish(step("a"), step("b"), step("c"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);

    assertThat(drain(executor, run)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(harness.providerInvocations.get()).isEqualTo(3);
  }

  @Test
  void governanceRunsAgainForEveryStepWithNothingCachedBetweenThem() {
    // The invariant that makes an agent run safe: step 40 is authorized exactly as rigorously as
    // step 1, because the pipeline has no idea the steps are related.
    final Plan plan = publish(step("a"), step("b"), step("c"), step("d"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);
    drain(executor, run);

    assertThat(harness.governanceCalls.get()).isEqualTo(4);
  }

  @Test
  void everyStepIsMeteredAndFinalizedSeparately() {
    final Plan plan = publish(step("a"), step("b"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);
    drain(executor, run);

    assertThat(harness.meterAttempts.get()).isEqualTo(2);
    assertThat(harness.finalizations.get()).isEqualTo(2);
    assertThat(harness.publishes.get()).isEqualTo(2);
  }

  @Test
  void aCredentialIsMintedAndClosedPerStepRatherThanHeldAcrossTheRun() {
    // Key material must not outlive the invocation it was minted for, and an agent run is many
    // invocations rather than one long one.
    final Plan plan = publish(step("a"), step("b"), step("c"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);
    drain(executor, run);

    assertThat(harness.credentialRequests).hasSize(3);
  }

  @Test
  void eachStepCarriesADistinctRequestIdentitySoMeteringDoesNotSeeDuplicates() {
    final Plan plan = publish(step("a"), step("b"), step("c"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);
    drain(executor, run);

    assertThat(harness.meteredFacts.stream().map(fact -> fact.requestId().value()))
        .doesNotHaveDuplicates();
  }

  @Test
  void allStepsOfARunShareItsCorrelationSoTheTraceHangsTogether() {
    final Plan plan = publish(step("a"), step("b"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);
    drain(executor, run);

    assertThat(harness.credentialRequests)
        .allMatch(request -> "corr-1".equals(request.correlationId().value()));
  }

  // ---- Refusals map onto the agent's taxonomy
  // ----------------------------------------------------

  @Test
  void aGovernanceRefusalStopsTheRunAndIsClassifiedAsDeniedRatherThanTransient() {
    harness.permitted = false;
    final Plan plan = publish(step("a"), step("b"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);

    final RunExecutor.Advance outcome = drain(executor, run);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(((RunExecutor.Advance.Terminated) outcome).reason())
        .isEqualTo(TerminalReason.FAILED_STEP);
    assertThat(harness.providerInvocations.get()).isZero();
  }

  @Test
  void anAuthenticationRefusalNeverReachesAProvider() {
    harness.authenticated = false;
    final Plan plan = publish(step("a"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);
    drain(executor, run);

    assertThat(harness.providerInvocations.get()).isZero();
    assertThat(harness.governanceCalls.get()).isZero();
  }

  @Test
  void aRoutingFailureIsClassifiedAsTransientSoARetryingPolicyCanHelp() {
    harness.routable = false;
    final PipelineToolSessionAdapter sessions = adapter();

    final ToolSessionPort.SessionOutcome outcome =
        sessions.openSession(
            new ToolSessionPort.SessionRequest(
                "ref-1",
                "hello",
                List.of(),
                Set.of("chat"),
                RequestPipelineTest.Harness.TENANT,
                new CorrelationId("corr-1"),
                1_000L,
                Duration.ofSeconds(30),
                false));

    assertThat(outcome).isInstanceOf(ToolSessionPort.Failed.class);
    assertThat(((ToolSessionPort.Failed) outcome).failure()).isEqualTo(FailureClass.STEP_TRANSIENT);
  }

  @Test
  void aProviderFailureIsClassifiedAsTransient() {
    harness.providerSucceeds = false;
    final PipelineToolSessionAdapter sessions = adapter();

    final ToolSessionPort.SessionOutcome outcome =
        sessions.openSession(
            new ToolSessionPort.SessionRequest(
                "ref-1",
                "hello",
                List.of(),
                Set.of("chat"),
                RequestPipelineTest.Harness.TENANT,
                new CorrelationId("corr-1"),
                1_000L,
                Duration.ofSeconds(30),
                false));

    assertThat(outcome).isInstanceOf(ToolSessionPort.Failed.class);
    assertThat(((ToolSessionPort.Failed) outcome).failure()).isEqualTo(FailureClass.STEP_TRANSIENT);
  }

  @Test
  void aMissingSessionContextFailsClosedRatherThanInventingAModelOrACredential() {
    final PipelineToolSessionAdapter sessions =
        new PipelineToolSessionAdapter(harness.pipeline(), correlation -> Optional.empty(), CLOCK);

    final ToolSessionPort.SessionOutcome outcome =
        sessions.openSession(
            new ToolSessionPort.SessionRequest(
                "ref-1",
                "hello",
                List.of(),
                Set.of("chat"),
                RequestPipelineTest.Harness.TENANT,
                new CorrelationId("unknown"),
                1_000L,
                Duration.ofSeconds(30),
                false));

    assertThat(outcome).isInstanceOf(ToolSessionPort.Failed.class);
    assertThat(((ToolSessionPort.Failed) outcome).failure()).isEqualTo(FailureClass.STEP_DENIED);
    assertThat(harness.providerInvocations.get()).isZero();
  }

  // ---- Cost honesty
  // --------------------------------------------------------------------------------

  @Test
  void theAdapterReportsCostAsUnknownRatherThanAsZero() {
    // PipelineOutcome carries no priced figure. Reporting zero would make every budget check pass,
    // so the run's spend bound would be evaluated and never bind.
    final PipelineToolSessionAdapter sessions = adapter();
    final ToolSessionPort.SessionOutcome outcome =
        sessions.openSession(
            new ToolSessionPort.SessionRequest(
                "ref-1",
                "hello",
                List.of(),
                Set.of("chat"),
                RequestPipelineTest.Harness.TENANT,
                new CorrelationId("corr-1"),
                1_000L,
                Duration.ofSeconds(30),
                false));

    assertThat(outcome.costKnown()).isFalse();
  }

  @Test
  void anUnknownCostCausesTheRunToBeChargedTheStepsAllotment() {
    final Plan plan = publish(step("a"));
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final AgentRuntime runtime =
        new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP);
    final RunId run = RunId.of("r-1");
    runtime.start(run, plan, security(), plan.bounds(), 1_000_000L);
    drain(executor, run);

    assertThat(runtime.snapshot(run).orElseThrow().budget().consumedMicros())
        .isEqualTo(plan.stepAt(0).budgetMicros());
  }

  // ---- Recovery through the adapter
  // -----------------------------------------------------------------

  @Test
  void theAdapterRemembersOutcomesSoAnInterruptedStepCanBeResolvedOnTheSameNode() {
    final PipelineToolSessionAdapter sessions = adapter();
    sessions.openSession(
        new ToolSessionPort.SessionRequest(
            "ref-1",
            "hello",
            List.of(),
            Set.of("chat"),
            RequestPipelineTest.Harness.TENANT,
            new CorrelationId("corr-1"),
            1_000L,
            Duration.ofSeconds(30),
            false));

    assertThat(sessions.lookup("ref-1")).isPresent();
    assertThat(sessions.rememberedOutcomes()).isEqualTo(1);
  }

  @Test
  void anUnknownSessionReferenceIsSimplyUnknown() {
    assertThat(adapter().lookup("never-happened")).isEmpty();
  }

  @Test
  void cancellingAnUnknownSessionIsHarmless() {
    adapter().cancelSession("never-happened");
  }

  @Test
  void aRunsPriorResultsReachTheModelAsOrdinaryRequestContent() {
    // AGT-9 in the concrete: an earlier step's output enters the next step as message content, so
    // it
    // passes through StreamGuard, SchemaLock and every policy that inspects a request.
    final Step.Pipeline first = step("a");
    final Step.Pipeline second =
        new Step.Pipeline(
            "b",
            "summarise",
            List.of("a"),
            Set.of("chat"),
            256L,
            10_000L,
            Duration.ofMinutes(1),
            true);
    final Plan plan = publish(first, second);
    final PipelineToolSessionAdapter sessions = adapter();
    final RunExecutor executor = executorOver(sessions);
    final RunId run = RunId.of("r-1");
    new AgentRuntime(runs, plans, executor, CLOCK, metrics, RunAuditPort.NOOP)
        .start(run, plan, security(), plan.bounds(), 1_000_000L);
    drain(executor, run);

    // Two full executions: the second one is where the first one's output was seen by a model.
    assertThat(harness.providerInvocations.get()).isEqualTo(2);
    assertThat(harness.governanceCalls.get()).isEqualTo(2);
  }

  /** A tool seam that refuses everything: these tests are about the pipeline path. */
  private static final class NoTools
      implements io.reliabilityai.gateway.dataplane.agent.api.AgentToolPort {

    @Override
    public ToolOutcome invoke(final ToolCall call) {
      return new Rejected(FailureClass.STEP_PERMANENT, "no tools in this harness", 0L);
    }

    @Override
    public void cancel(final String invocationRef) {
      // Nothing to cancel.
    }

    @Override
    public Optional<ToolOutcome> lookup(final String invocationRef) {
      return Optional.empty();
    }
  }
}
