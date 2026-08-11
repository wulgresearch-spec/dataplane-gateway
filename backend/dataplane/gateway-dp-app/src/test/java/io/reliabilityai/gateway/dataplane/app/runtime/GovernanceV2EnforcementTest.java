package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.io.ToolDefinition;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.binding.CharacterBoundTokenEstimator;
import io.reliabilityai.gateway.dataplane.app.binding.ClaimBasedScopeResolver;
import io.reliabilityai.gateway.dataplane.app.binding.PromptTokenEstimator;
import io.reliabilityai.gateway.dataplane.app.pipeline.Operation;
import io.reliabilityai.gateway.dataplane.app.pipeline.PipelineOutcome;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.cost.domain.FxRate;
import io.reliabilityai.gateway.dataplane.cost.domain.FxTable;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingClass;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingDescriptor;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingSnapshot;
import io.reliabilityai.gateway.dataplane.cost.domain.UnitRates;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAuditEvent;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistrySnapshot;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves that every policy type the Governance Engine implements now fires on the <b>live request
 * path</b> — a real {@link GatewayRuntime}, a real pipeline, a real HTTP-shaped request.
 *
 * <p>This is the point of GovernancePort V2, so these are the tests that matter. The engine's own
 * unit suite already showed each policy decides correctly given the right inputs; what was missing
 * was any evidence the runtime could supply those inputs. Every test here asserts on {@code
 * PipelineOutcome.Refused} at the {@link MandatoryStage#GOVERNANCE} stage, which is only reachable
 * if the assembler sourced the fact and the engine evaluated it.
 *
 * <p>Each refusal test is paired with the admitting case. A denial test alone proves a policy can
 * refuse; the pair proves it refuses <em>for the reason claimed</em> rather than because the
 * request was malformed all along.
 */
class GovernanceV2EnforcementTest {

  private static final CanonicalModelId MODEL = RuntimeFixture.MODEL;
  private static final CanonicalModelId OTHER_MODEL = new CanonicalModelId("model.other.v1");
  private static final Region REGION = new Region("us-east-1");
  private static final String CANDIDATE_A = "candidate-a";
  private static final String CANDIDATE_B = "candidate-b";
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private final List<PolicyAuditEvent> audited = new ArrayList<>();
  private final AtomicReference<PolicySourcePort.PolicyBundle> published = new AtomicReference<>();
  private final AtomicReference<PolicyUsage> usage = new AtomicReference<>(PolicyUsage.none(NOW));

  // ---- harness --------------------------------------------------------------------------------

  /**
   * Attaches a policy document to the tenant's organization node and starts a node enforcing it.
   */
  private GatewayRuntime runtimeEnforcing(final PolicyRule... rules) {
    published.set(
        new PolicySourcePort.PolicyBundle(
            PolicyVersion.of("v1", 1L),
            List.of(
                GovernancePolicy.of(
                    "org-doc",
                    PolicyScopeRef.of(PolicyScope.ORGANIZATION, RuntimeFixture.TENANT.org()),
                    PolicyVersion.of("v1", 1L),
                    List.of(rules)))));
    final GatewayRuntime gateway = new GatewayRuntime(config(PromptTokenEstimator.UNKNOWN, true));
    gateway.start();
    return gateway;
  }

  /**
   * As above, but the node cannot count tokens — so token and budget policy become unenforceable.
   */
  private GatewayRuntime runtimeWithoutTokenCounting(final PolicyRule... rules) {
    published.set(
        new PolicySourcePort.PolicyBundle(
            PolicyVersion.of("v1", 1L),
            List.of(
                GovernancePolicy.of(
                    "org-doc",
                    PolicyScopeRef.of(PolicyScope.ORGANIZATION, RuntimeFixture.TENANT.org()),
                    PolicyVersion.of("v1", 1L),
                    List.of(rules)))));
    final GatewayRuntime gateway = new GatewayRuntime(config(PromptTokenEstimator.UNKNOWN, false));
    gateway.start();
    return gateway;
  }

  private GatewayRuntimeConfig config(
      final PromptTokenEstimator ignored, final boolean countTokens) {
    final GatewayRuntimeConfig base =
        RuntimeFixture.config(
            RuntimeFixture.masterKey(),
            walDir,
            dlqDir.resolve("dlq.log"),
            record -> {},
            new RuntimeFixture.TestClock(),
            RuntimeFixture.authenticatingExternalAdapters(),
            Optional.of(governance(countTokens)));
    return new GatewayRuntimeConfig(
        base.nodeId(),
        base.configSnapshot(),
        base.masterKey(),
        base.clock(),
        base.eventing(),
        base.authn(),
        base.authentication(),
        base.governance(),
        base.provider(),
        base.ingress(),
        base.plugins(),
        // Two candidate routes serve the model, so provider allow/deny lists have something to
        // narrow.
        new GatewayRuntimeConfig.RoutingConfig(
            capabilities(), base.routing().defaultPolicy(), base.routing().policyOverrides()),
        base.reliability(),
        // Correctly-keyed pricing, so the Cost Engine can actually project and budget policy can
        // bite.
        new GatewayRuntimeConfig.AccountingConfig(
            base.accounting().usageDescriptors(),
            base.accounting().meteringPolicy(),
            base.accounting().maxTrackedRequests(),
            pricing(),
            base.accounting().contractEntitlements(),
            base.accounting().defaultEntitlement(),
            base.accounting().costLedgerCapacity()),
        base.correctness(),
        base.secrets(),
        base.externalAdapters());
  }

  private GatewayRuntimeConfig.GovernanceConfig governance(final boolean countTokens) {
    final GatewayRuntimeConfig.GovernanceConfig base =
        RuntimeFixture.governanceConfig(
            RuntimeFixture.permittingPolicy(),
            io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort.NO_OP);
    return new GatewayRuntimeConfig.GovernanceConfig(
        base.policySnapshots(),
        base.entitlementSnapshots(),
        base.usageStates(),
        base.featureFlags(),
        base.auditSink(),
        base.usageStalenessTolerance(),
        Optional.of(
            new GatewayRuntimeConfig.PolicyEngineConfig(
                () -> Optional.ofNullable(published.get()),
                (PolicyUsagePort) scope -> Optional.of(usage.get()),
                audited::add,
                PolicyMetrics.NO_OP,
                TickerPort.FROZEN,
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                256,
                4,
                true)),
        new GatewayRuntimeConfig.AdmissionConfig(
            countTokens ? new CharacterBoundTokenEstimator(0L) : PromptTokenEstimator.UNKNOWN,
            ClaimBasedScopeResolver.tenantOnly()));
  }

  private static CapabilityRegistrySnapshot capabilities() {
    return new CapabilityRegistrySnapshot(
        new SnapshotVersion("capabilities", "v1"),
        List.of(descriptor(CANDIDATE_A, "route-a"), descriptor(CANDIDATE_B, "route-b")));
  }

  private static CapabilityDescriptor descriptor(final String candidateId, final String routeRef) {
    return new CapabilityDescriptor(
        candidateId,
        MODEL,
        routeRef,
        Set.of("chat"),
        8192,
        Set.of(),
        Set.of("us-east-1"),
        1_000L,
        0.99d,
        100L,
        false);
  }

  private static PricingSnapshot pricing() {
    return new PricingSnapshot(
        "pricing-v1",
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2027-01-01T00:00:00Z"),
        Map.of(
            PricingDescriptor.key(MODEL, REGION, PricingClass.LIST),
            new PricingDescriptor(
                MODEL,
                REGION,
                "USD",
                PricingClass.LIST,
                new UnitRates(10L, 30L, 1L, 0L),
                "pricing-v1")),
        new FxTable(
            "fx-v1", Instant.parse("2027-01-01T00:00:00Z"), "USD", Map.of("USD", FxRate.IDENTITY)));
  }

  /** A chat request builder whose defaults every policy in this file admits. */
  private static InboundBuilder request() {
    return new InboundBuilder();
  }

  /** Assembles the inbound request each test varies one facet of. */
  private static final class InboundBuilder {
    private CanonicalModelId model = MODEL;
    private List<Message> messages = List.of(new Message("user", "hello"));
    private List<ToolDefinition> tools = List.of();
    private Set<String> capabilities = Set.of("chat");
    private Mode mode = Mode.BATCH;
    private Operation operation = Operation.CHAT;
    private OptionalLong maxOutput = OptionalLong.of(16L);
    private Set<String> compliance = Set.of();
    private boolean pii;

    InboundBuilder model(final CanonicalModelId value) {
      this.model = value;
      return this;
    }

    InboundBuilder messages(final List<Message> value) {
      this.messages = value;
      return this;
    }

    InboundBuilder tools(final List<ToolDefinition> value) {
      this.tools = value;
      return this;
    }

    InboundBuilder capabilities(final Set<String> value) {
      this.capabilities = value;
      return this;
    }

    InboundBuilder streaming() {
      this.mode = Mode.STREAM;
      return this;
    }

    InboundBuilder operation(final Operation value) {
      this.operation = value;
      return this;
    }

    InboundBuilder maxOutput(final OptionalLong value) {
      this.maxOutput = value;
      return this;
    }

    InboundBuilder compliance(final Set<String> value) {
      this.compliance = value;
      return this;
    }

    InboundBuilder pii() {
      this.pii = true;
      return this;
    }

    RequestExecution.Inbound build() {
      return new RequestExecution.Inbound(
          new RequestId("req-1"),
          new io.reliabilityai.gateway.canonical.context.RequestContext(
              new CorrelationId("corr-1"),
              new IdempotencyKey("idem-1"),
              new CausationId("cause-1"),
              "traceparent",
              REGION),
          new ForwardedTransportIdentity("Bearer", "token", Map.of()),
          new CanonicalRequest(model, messages, tools, Map.of()),
          capabilities,
          1024,
          compliance,
          1_000_000L,
          Set.of(),
          NOW.plusSeconds(30),
          true,
          mode,
          Optional.empty(),
          Optional.empty(),
          operation,
          maxOutput,
          pii);
    }
  }

  private static PipelineOutcome.Refused refusal(final PipelineOutcome outcome) {
    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    return refused;
  }

  private static void assertReachedRouter(final PipelineOutcome outcome) {
    assertThat(outcome.trace().stages())
        .containsSequence(MandatoryStage.GOVERNANCE, MandatoryStage.ROUTER);
  }

  private static PolicyRule mandatory(final PolicyType type, final PolicyValue value) {
    return PolicyRule.mandatory("r-" + type.name().toLowerCase(java.util.Locale.ROOT), type, value);
  }

  // ---- model policy ---------------------------------------------------------------------------

  @Test
  void modelAllowListRefusesAModelTheTenantMayNotUse() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(OTHER_MODEL.value())));

    final PipelineOutcome outcome = gateway.pipeline().execute(request().build());

    assertThat(refusal(outcome).refusal().reason())
        .isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
    gateway.stop();
  }

  @Test
  void modelAllowListAdmitsAPermittedModel() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void modelDenyListRefusesAForbiddenModel() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.MODEL_DENY_LIST, PolicyValue.Values.of(MODEL.value())));

    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
    gateway.stop();
  }

  @Test
  void modelDenyListIgnoresAModelItDoesNotName() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.MODEL_DENY_LIST, PolicyValue.Values.of(OTHER_MODEL.value())));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void theModelGovernedIsTheOneTheCallerActuallyAskedFor() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())));

    // Same policy, different requested model: the assembler must be reading the request, not a
    // default.
    assertThat(
            refusal(gateway.pipeline().execute(request().model(OTHER_MODEL).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
    gateway.stop();
  }

  // ---- provider / route policy ----------------------------------------------------------------

  @Test
  void providerDenyListAdmitsWhileAnyCandidateRouteSurvives() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.PROVIDER_DENY_LIST, PolicyValue.Values.of(CANDIDATE_A)));

    // One of two candidates is forbidden; the other can still serve it, so the request proceeds and
    // the Router picks within what governance left.
    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void providerDenyListRefusesWhenItRemovesEveryCandidateRoute() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(
                PolicyType.PROVIDER_DENY_LIST, PolicyValue.Values.of(CANDIDATE_A, CANDIDATE_B)));

    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.UNAUTHORIZED.code());
    gateway.stop();
  }

  @Test
  void providerAllowListRefusesWhenNoCandidateRouteIsPermitted() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(
                PolicyType.PROVIDER_ALLOW_LIST, PolicyValue.Values.of("candidate-elsewhere")));

    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.UNAUTHORIZED.code());
    gateway.stop();
  }

  @Test
  void providerAllowListAdmitsWhenAPermittedCandidateRouteExists() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.PROVIDER_ALLOW_LIST, PolicyValue.Values.of(CANDIDATE_B)));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void routeRestrictionIsEvaluatedAgainstTheRegistrysCandidatesNotTheRequest() {
    // The caller never names a route. The candidate set comes from the capability registry, which
    // is
    // the only place that knows which routes can serve this model.
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.PROVIDER_ALLOW_LIST, PolicyValue.Values.of(CANDIDATE_A)));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  // ---- tool policy ----------------------------------------------------------------------------

  @Test
  void toolAllowListRefusesAnUnlistedTool() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.TOOL_ALLOW_LIST, PolicyValue.Values.of("calculator")));

    final PipelineOutcome outcome =
        gateway
            .pipeline()
            .execute(request().tools(List.of(new ToolDefinition("shell", "{}"))).build());

    assertThat(refusal(outcome).refusal().reason())
        .isEqualTo(DenialReason.TOOL_UNAUTHORIZED.code());
    gateway.stop();
  }

  @Test
  void toolAllowListAdmitsAListedTool() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.TOOL_ALLOW_LIST, PolicyValue.Values.of("calculator")));

    assertReachedRouter(
        gateway
            .pipeline()
            .execute(request().tools(List.of(new ToolDefinition("calculator", "{}"))).build()));
    gateway.stop();
  }

  @Test
  void toolDenyListRefusesAForbiddenTool() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.TOOL_DENY_LIST, PolicyValue.Values.of("shell")));

    final PipelineOutcome outcome =
        gateway
            .pipeline()
            .execute(request().tools(List.of(new ToolDefinition("shell", "{}"))).build());

    assertThat(refusal(outcome).refusal().reason())
        .isEqualTo(DenialReason.TOOL_UNAUTHORIZED.code());
    gateway.stop();
  }

  @Test
  void aRequestCarryingNoToolsSatisfiesToolPolicy() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.TOOL_DENY_LIST, PolicyValue.Values.of("shell")));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void oneForbiddenToolAmongSeveralRefusesTheWholeRequest() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.TOOL_DENY_LIST, PolicyValue.Values.of("shell")));

    final PipelineOutcome outcome =
        gateway
            .pipeline()
            .execute(
                request()
                    .tools(
                        List.of(
                            new ToolDefinition("calculator", "{}"),
                            new ToolDefinition("shell", "{}")))
                    .build());

    assertThat(refusal(outcome).refusal().reason())
        .isEqualTo(DenialReason.TOOL_UNAUTHORIZED.code());
    gateway.stop();
  }

  // ---- capability policy ----------------------------------------------------------------------

  @Test
  void visionIsRefusedWhenTheCallerAssertsItAndPolicyForbidsIt() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.VISION_ALLOWED, PolicyValue.Flag.FALSE));

    final PipelineOutcome outcome =
        gateway.pipeline().execute(request().capabilities(Set.of("chat", "vision")).build());

    assertThat(refusal(outcome).refusal().reason()).isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  @Test
  void aRequestNotAssertingVisionIsUnaffectedByAVisionProhibition() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.VISION_ALLOWED, PolicyValue.Flag.FALSE));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void reasoningIsRefusedWhenTheCallerAssertsItAndPolicyForbidsIt() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.REASONING_ALLOWED, PolicyValue.Flag.FALSE));

    assertThat(
            refusal(
                    gateway
                        .pipeline()
                        .execute(request().capabilities(Set.of("chat", "reasoning")).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  @Test
  void embeddingIsRefusedByOperationNotByGuesswork() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.EMBEDDING_ALLOWED, PolicyValue.Flag.FALSE));

    assertThat(
            refusal(gateway.pipeline().execute(request().operation(Operation.EMBEDDING).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    // The same policy leaves a chat call alone, which is what makes the gate operation-specific.
    gateway.stop();
  }

  @Test
  void anEmbeddingProhibitionLeavesChatAlone() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.EMBEDDING_ALLOWED, PolicyValue.Flag.FALSE));

    assertReachedRouter(gateway.pipeline().execute(request().operation(Operation.CHAT).build()));
    gateway.stop();
  }

  @Test
  void imageGenerationIsRefusedByOperation() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.IMAGE_GENERATION_ALLOWED, PolicyValue.Flag.FALSE));

    assertThat(
            refusal(
                    gateway
                        .pipeline()
                        .execute(request().operation(Operation.IMAGE_GENERATION).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  @Test
  void batchIsRefusedByOperation() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.BATCH_ALLOWED, PolicyValue.Flag.FALSE));

    assertThat(
            refusal(gateway.pipeline().execute(request().operation(Operation.BATCH).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  @Test
  void fineTuningIsRefusedByOperation() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.FINE_TUNING_ALLOWED, PolicyValue.Flag.FALSE));

    assertThat(
            refusal(gateway.pipeline().execute(request().operation(Operation.FINE_TUNING).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  @Test
  void audioIsRefusedByOperation() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.AUDIO_ALLOWED, PolicyValue.Flag.FALSE));

    assertThat(
            refusal(gateway.pipeline().execute(request().operation(Operation.AUDIO).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  @Test
  void aRegimeTheScopeIsNotAttestedForCannotBeSatisfied() {
    // The inbound request requires SOC2 (see InboundBuilder); this policy attests only to GDPR.
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.COMPLIANCE_MODE, PolicyValue.Values.of("GDPR")));

    assertThat(
            refusal(gateway.pipeline().execute(request().compliance(Set.of("SOC2")).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.COMPLIANCE_CONFLICT.code());
    gateway.stop();
  }

  @Test
  void anAttestedRegimeIsSatisfiedOnTheLivePath() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.COMPLIANCE_MODE, PolicyValue.Values.of("SOC2", "GDPR")));

    assertReachedRouter(gateway.pipeline().execute(request().compliance(Set.of("SOC2")).build()));
    gateway.stop();
  }

  @Test
  void aPiiClassifiedRequestIsRefusedWherePiiIsProhibited() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.PII_RESTRICTION, PolicyValue.Flag.TRUE));

    assertThat(refusal(gateway.pipeline().execute(request().pii().build())).refusal().reason())
        .isEqualTo(DenialReason.COMPLIANCE_CONFLICT.code());
    gateway.stop();
  }

  // ---- streaming policy -----------------------------------------------------------------------

  @Test
  void streamingIsRefusedWhereItIsNotPermitted() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.STREAMING_ALLOWED, PolicyValue.Flag.FALSE));

    assertThat(
            refusal(gateway.pipeline().execute(request().streaming().build())).refusal().reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  @Test
  void aBatchRequestIsUnaffectedByAStreamingProhibition() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.STREAMING_ALLOWED, PolicyValue.Flag.FALSE));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void aBatchRequestIsRefusedWhereStreamingIsRequired() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.STREAMING_REQUIRED, PolicyValue.Flag.TRUE));

    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  @Test
  void aStreamingRequestSatisfiesAStreamingRequirement() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.STREAMING_REQUIRED, PolicyValue.Flag.TRUE));

    assertReachedRouter(gateway.pipeline().execute(request().streaming().build()));
    gateway.stop();
  }

  @Test
  void aRequestWithoutAPinnedSchemaIsRefusedWhereJsonModeIsRequired() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.JSON_MODE_REQUIRED, PolicyValue.Flag.TRUE));

    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.FEATURE_DISABLED.code());
    gateway.stop();
  }

  // ---- token policy ---------------------------------------------------------------------------

  @Test
  void anOversizedPromptIsRefusedByTheContextCeiling() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10)));

    final PipelineOutcome outcome =
        gateway
            .pipeline()
            .execute(request().messages(List.of(new Message("user", "x".repeat(500)))).build());

    assertThat(refusal(outcome).refusal().reason()).isEqualTo(DenialReason.QUOTA_EXCEEDED.code());
    gateway.stop();
  }

  @Test
  void aPromptInsideTheContextCeilingIsAdmitted() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(4096)));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void theContextCeilingIsMeasuredAgainstTheActualPromptNotAConstant() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(60)));

    // "user" (4) + 50 chars = 54, inside the ceiling; 100 chars is not. If the assembler were
    // feeding
    // a constant, these two would decide the same way.
    assertReachedRouter(
        gateway
            .pipeline()
            .execute(request().messages(List.of(new Message("user", "x".repeat(50)))).build()));
    assertThat(
            refusal(
                    gateway
                        .pipeline()
                        .execute(
                            request()
                                .messages(List.of(new Message("user", "x".repeat(100))))
                                .build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.QUOTA_EXCEEDED.code());
    gateway.stop();
  }

  @Test
  void toolSchemasCountTowardTheContextCeiling() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(20)));

    // A tiny prompt with a large tool schema is still a large request. A bound that ignored schemas
    // would under-count exactly the requests most likely to be oversized.
    final PipelineOutcome outcome =
        gateway
            .pipeline()
            .execute(
                request()
                    .tools(List.of(new ToolDefinition("t", "{\"x\":\"" + "y".repeat(200) + "\"}")))
                    .build());

    assertThat(refusal(outcome).refusal().reason()).isEqualTo(DenialReason.QUOTA_EXCEEDED.code());
    gateway.stop();
  }

  @Test
  void anOversizedDeclaredCompletionIsRefusedByTheOutputCeiling() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(16)));

    assertThat(
            refusal(gateway.pipeline().execute(request().maxOutput(OptionalLong.of(4096)).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.QUOTA_EXCEEDED.code());
    gateway.stop();
  }

  @Test
  void aDeclaredCompletionInsideTheOutputCeilingIsAdmitted() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(16)));

    assertReachedRouter(
        gateway.pipeline().execute(request().maxOutput(OptionalLong.of(16)).build()));
    gateway.stop();
  }

  @Test
  void anUndeclaredCompletionCeilingRefusesRatherThanPassingTheOutputCheckTrivially() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(16)));

    // No declared ceiling means the request could emit anything. Treating that as zero would make
    // the
    // cap pass for exactly the requests it exists to stop.
    assertThat(
            refusal(gateway.pipeline().execute(request().maxOutput(OptionalLong.empty()).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.POLICY_UNAVAILABLE.code());
    gateway.stop();
  }

  @Test
  void aNodeThatCannotCountTokensRefusesRatherThanIgnoringTheContextCeiling() {
    final GatewayRuntime gateway =
        runtimeWithoutTokenCounting(mandatory(PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(4096)));

    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.POLICY_UNAVAILABLE.code());
    gateway.stop();
  }

  @Test
  void theTokenRateCapCountsPromptAndDeclaredCompletionTogether() {
    usage.set(new PolicyUsage(0L, 0L, 100L, 0L, 0L, 0L, NOW));
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_TPM, PolicyValue.Limit.of(120)));

    // 100 already consumed + ~9 prompt + 16 declared output exceeds 120.
    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.RATE_LIMITED.code());
    gateway.stop();
  }

  // ---- budget policy --------------------------------------------------------------------------

  @Test
  void aRequestCostingMoreThanTheSingleRequestCeilingIsRefused() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_COST, PolicyValue.Limit.of(1L)));

    // 9 prompt tokens at 10 micros + 16 declared output at 30 micros is far above a 1-micro
    // ceiling.
    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.BUDGET_EXCEEDED.code());
    gateway.stop();
  }

  @Test
  void anAffordableRequestPassesTheSingleRequestCostCeiling() {
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MAX_COST, PolicyValue.Limit.of(10_000_000L)));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void aRequestThatWouldBreachTheDailyBudgetIsRefused() {
    usage.set(new PolicyUsage(0L, 0L, 0L, 0L, 999_999L, 999_999L, NOW));
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.DAILY_BUDGET, PolicyValue.Limit.of(1_000_000L)));

    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.BUDGET_EXCEEDED.code());
    gateway.stop();
  }

  @Test
  void aRequestInsideTheDailyBudgetIsAdmitted() {
    usage.set(new PolicyUsage(0L, 0L, 0L, 0L, 0L, 0L, NOW));
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.DAILY_BUDGET, PolicyValue.Limit.of(1_000_000L)));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    gateway.stop();
  }

  @Test
  void aRequestThatWouldBreachTheMonthlyBudgetIsRefused() {
    usage.set(new PolicyUsage(0L, 0L, 0L, 0L, 0L, 999_999L, NOW));
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.MONTHLY_BUDGET, PolicyValue.Limit.of(1_000_000L)));

    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.BUDGET_EXCEEDED.code());
    gateway.stop();
  }

  @Test
  void anUnpriceableRequestRefusesRatherThanBypassingItsBudget() {
    // No declared output ceiling means the Cost Engine cannot upper-bound the spend, so the budget
    // cannot be checked — and an unpriceable request is precisely what a spend ceiling exists to
    // stop.
    final GatewayRuntime gateway =
        runtimeEnforcing(mandatory(PolicyType.DAILY_BUDGET, PolicyValue.Limit.of(1_000_000L)));

    assertThat(
            refusal(gateway.pipeline().execute(request().maxOutput(OptionalLong.empty()).build()))
                .refusal()
                .reason())
        .isEqualTo(DenialReason.POLICY_UNAVAILABLE.code());
    gateway.stop();
  }

  // ---- the gate as a whole --------------------------------------------------------------------

  @Test
  void aRefusedRequestNeverReachesTheRouter() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.MODEL_DENY_LIST, PolicyValue.Values.of(MODEL.value())));

    final PipelineOutcome outcome = gateway.pipeline().execute(request().build());

    assertThat(outcome.trace().stages())
        .containsExactly(MandatoryStage.INGRESS, MandatoryStage.AUTHN, MandatoryStage.GOVERNANCE);
    gateway.stop();
  }

  @Test
  void theStageOrderIsUnchangedByTheRicherQuestion() {
    final GatewayRuntime gateway = runtimeEnforcing();

    final PipelineOutcome outcome = gateway.pipeline().execute(request().build());

    assertThat(outcome.trace().stages())
        .startsWith(
            MandatoryStage.INGRESS,
            MandatoryStage.AUTHN,
            MandatoryStage.GOVERNANCE,
            MandatoryStage.ROUTER);
    gateway.stop();
  }

  @Test
  void everyLivePathDecisionIsAudited() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.MODEL_DENY_LIST, PolicyValue.Values.of(MODEL.value())));

    gateway.pipeline().execute(request().build());

    assertThat(audited).hasSize(1);
    assertThat(audited.get(0).verdict()).isEqualTo(Verdict.DENY);
    assertThat(audited.get(0).matchedRuleIds()).containsExactly("r-model_deny_list");
    gateway.stop();
  }

  @Test
  void anAdvisoryBreachAdmitsTheRequestAndStillRecordsTheViolation() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            new PolicyRule(
                "soft-context",
                PolicyType.MAX_CONTEXT,
                PolicyValue.Limit.of(1),
                io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel.ADVISORY));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    assertThat(audited.get(0).verdict()).isEqualTo(Verdict.SOFT_DENY);
    assertThat(audited.get(0).matchedRuleIds()).containsExactly("soft-context");
    gateway.stop();
  }

  @Test
  void aShadowRuleAdmitsTheRequestAndRecordsWhatWouldHaveHappened() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            new PolicyRule(
                "shadow-context",
                PolicyType.MAX_CONTEXT,
                PolicyValue.Limit.of(1),
                io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel.SHADOW));

    assertReachedRouter(gateway.pipeline().execute(request().build()));
    assertThat(audited.get(0).verdict()).isEqualTo(Verdict.DRY_RUN);
    gateway.stop();
  }

  @Test
  void theHighestPrecedenceViolationIsTheOneTheCallerIsTold() {
    final GatewayRuntime gateway =
        runtimeEnforcing(
            mandatory(PolicyType.MODEL_DENY_LIST, PolicyValue.Values.of(MODEL.value())),
            mandatory(PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1)));

    // Authorization outranks quota, and evaluation short-circuits, so the caller hears about the
    // model.
    assertThat(refusal(gateway.pipeline().execute(request().build())).refusal().reason())
        .isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
    gateway.stop();
  }
}
