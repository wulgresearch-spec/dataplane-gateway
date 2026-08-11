package io.reliabilityai.gateway.dataplane.app.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.io.ToolDefinition;
import io.reliabilityai.gateway.dataplane.app.pipeline.Operation;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.cost.api.ComputationOutcome;
import io.reliabilityai.gateway.dataplane.cost.api.CostEnginePort;
import io.reliabilityai.gateway.dataplane.cost.api.CostProjection;
import io.reliabilityai.gateway.dataplane.cost.api.CostRequest;
import io.reliabilityai.gateway.dataplane.cost.api.CostUnavailable;
import io.reliabilityai.gateway.dataplane.cost.api.ProjectionOutcome;
import io.reliabilityai.gateway.dataplane.cost.domain.CostUnavailableReason;
import io.reliabilityai.gateway.dataplane.cost.domain.Money;
import io.reliabilityai.gateway.dataplane.cost.domain.NormalizedUsage;
import io.reliabilityai.gateway.dataplane.cost.domain.Phase;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingClass;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistryPort;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistrySnapshot;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests the assembler: where every governed fact comes from, and what happens when an upstream
 * cannot answer. These are the tests that pin "nothing is guessed" — each asserts that a field
 * traces back to a named source, and that an absent source yields an absent field rather than a
 * convenient default.
 */
class GovernanceAdmissionAssemblerTest {

  private static final CanonicalModelId MODEL = new CanonicalModelId("model.test.v1");
  private static final Region REGION = new Region("us-east-1");
  private static final TenantScope TENANT = new TenantScope("org-a", "tenant-a", "ws-a", "proj-a");
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private final AtomicReference<Optional<CapabilityRegistrySnapshot>> registry =
      new AtomicReference<>(Optional.of(snapshot("candidate-a", "candidate-b")));
  private final AtomicReference<ProjectionOutcome> projection =
      new AtomicReference<>(
          new CostProjection(
              new Money(4_200L, "USD"), PricingClass.LIST, "declared_max_output", Map.of()));

  private GovernanceAdmissionAssembler assembler() {
    return assembler(new CharacterBoundTokenEstimator(0L));
  }

  private GovernanceAdmissionAssembler assembler(final PromptTokenEstimator estimator) {
    return new GovernanceAdmissionAssembler(
        (CapabilityRegistryPort) registry::get,
        new AdmissionCostProjector(costEngine(), estimator, "USD"),
        ClaimBasedScopeResolver.tenantOnly());
  }

  private CostEnginePort costEngine() {
    return new CostEnginePort() {
      @Override
      public ProjectionOutcome project(final CostRequest request) {
        return projection.get();
      }

      @Override
      public ComputationOutcome compute(final CostRequest request, final NormalizedUsage usage) {
        return new CostUnavailable(CostUnavailableReason.INTERNAL_ERROR);
      }
    };
  }

  private static CapabilityRegistrySnapshot snapshot(final String... candidateIds) {
    final List<CapabilityDescriptor> descriptors = new java.util.ArrayList<>();
    for (final String id : candidateIds) {
      descriptors.add(
          new CapabilityDescriptor(
              id,
              MODEL,
              "route-" + id,
              Set.of("chat"),
              8192,
              Set.of(),
              Set.of("us-east-1"),
              1_000L,
              0.99d,
              100L,
              false));
    }
    return new CapabilityRegistrySnapshot(new SnapshotVersion("capabilities", "v1"), descriptors);
  }

  private static PrincipalContext principal() {
    return new PrincipalContext(new PrincipalId("principal-a"), Map.of(), "jws", "auth-1");
  }

  private static TenantContext tenant() {
    return new TenantContext(TENANT);
  }

  private static RequestExecution.Inbound inbound() {
    return inbound(
        List.of(new Message("user", "hello")),
        List.of(),
        Mode.BATCH,
        Operation.CHAT,
        Set.of("chat"),
        OptionalLong.of(256L),
        false,
        MODEL);
  }

  private static RequestExecution.Inbound inbound(
      final List<Message> messages,
      final List<ToolDefinition> tools,
      final Mode mode,
      final Operation operation,
      final Set<String> capabilities,
      final OptionalLong maxOutput,
      final boolean pii,
      final CanonicalModelId model) {
    return new RequestExecution.Inbound(
        new RequestId("req-1"),
        new RequestContext(
            new CorrelationId("corr-1"),
            new IdempotencyKey("idem-1"),
            new CausationId("cause-1"),
            "traceparent",
            REGION),
        new ForwardedTransportIdentity("Bearer", "token", Map.of()),
        new CanonicalRequest(model, messages, tools, Map.of()),
        capabilities,
        1024,
        Set.of("SOC2"),
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

  // ---- identity and scope ---------------------------------------------------------------------

  @Test
  void theTenantAndPrincipalComeFromAuthentication() {
    final PolicyRequest request = assembler().assemble(inbound(), principal(), tenant());

    assertThat(request.tenant().tenantScope()).isEqualTo(TENANT);
    assertThat(request.principal().principalId().value()).isEqualTo("principal-a");
  }

  @Test
  void theScopeChainFollowsTheAuthenticatedTenantHierarchy() {
    final PolicyRequest request = assembler().assemble(inbound(), principal(), tenant());

    assertThat(request.scopeChain().refs())
        .extracting(PolicyScopeRef::scope)
        .containsExactly(
            PolicyScope.GLOBAL,
            PolicyScope.ORGANIZATION,
            PolicyScope.WORKSPACE,
            PolicyScope.PROJECT);
  }

  @Test
  void theRegionComesFromTheRequestContext() {
    assertThat(assembler().assemble(inbound(), principal(), tenant()).region()).isEqualTo(REGION);
  }

  @Test
  void theCorrelationIdIsCarriedThroughUnchanged() {
    assertThat(
            assembler()
                .assemble(inbound(), principal(), tenant())
                .requestContext()
                .correlationId()
                .value())
        .isEqualTo("corr-1");
  }

  // ---- model and tools ------------------------------------------------------------------------

  @Test
  void theModelComesFromTheCanonicalRequest() {
    assertThat(assembler().assemble(inbound(), principal(), tenant()).model()).contains(MODEL);
  }

  @Test
  void toolNamesComeFromTheRequestsToolDefinitions() {
    final RequestExecution.Inbound in =
        inbound(
            List.of(new Message("user", "hi")),
            List.of(new ToolDefinition("calculator", "{}"), new ToolDefinition("search", "{}")),
            Mode.BATCH,
            Operation.CHAT,
            Set.of("chat"),
            OptionalLong.of(16L),
            false,
            MODEL);

    assertThat(assembler().assemble(in, principal(), tenant()).tools())
        .containsExactly("calculator", "search");
  }

  @Test
  void toolSchemasAreNotCarriedIntoTheGovernanceQuestion() {
    final RequestExecution.Inbound in =
        inbound(
            List.of(new Message("user", "hi")),
            List.of(new ToolDefinition("calculator", "{\"secret\":\"do-not-leak\"}")),
            Mode.BATCH,
            Operation.CHAT,
            Set.of("chat"),
            OptionalLong.of(16L),
            false,
            MODEL);

    // Names only. Governance decides from shape, never content, so caller-authored JSON must not
    // reach
    // a decision that will be written to an audit stream.
    assertThat(assembler().assemble(in, principal(), tenant()).tools())
        .containsExactly("calculator");
  }

  @Test
  void complianceRegimesComeFromTheInboundRequirements() {
    assertThat(assembler().assemble(inbound(), principal(), tenant()).requiredComplianceRegimes())
        .containsExactly("SOC2");
  }

  // ---- candidate routes -----------------------------------------------------------------------

  @Test
  void candidateRoutesComeFromTheCapabilityRegistry() {
    assertThat(assembler().assemble(inbound(), principal(), tenant()).candidateProviders())
        .contains(List.of("candidate-a", "candidate-b"));
  }

  @Test
  void onlyRoutesServingTheRequestedModelAreCandidates() {
    registry.set(
        Optional.of(
            new CapabilityRegistrySnapshot(
                new SnapshotVersion("capabilities", "v1"),
                List.of(
                    new CapabilityDescriptor(
                        "serves-ours",
                        MODEL,
                        "r1",
                        Set.of("chat"),
                        8192,
                        Set.of(),
                        Set.of("us-east-1"),
                        1L,
                        0.9d,
                        1L,
                        false),
                    new CapabilityDescriptor(
                        "serves-other",
                        new CanonicalModelId("model.other"),
                        "r2",
                        Set.of("chat"),
                        8192,
                        Set.of(),
                        Set.of("us-east-1"),
                        1L,
                        0.9d,
                        1L,
                        false)))));

    assertThat(assembler().assemble(inbound(), principal(), tenant()).candidateProviders())
        .contains(List.of("serves-ours"));
  }

  @Test
  void anUnpublishedRegistryLeavesTheCandidateSetUnknownRatherThanEmpty() {
    registry.set(Optional.empty());

    // Empty would mean "no route serves this model", which provider policy treats as nothing to
    // exclude. Unknown makes provider policy unenforceable, which refuses.
    assertThat(assembler().assemble(inbound(), principal(), tenant()).candidateProviders())
        .isEmpty();
  }

  @Test
  void aRegistryThatThrowsLeavesTheCandidateSetUnknown() {
    final GovernanceAdmissionAssembler assembler =
        new GovernanceAdmissionAssembler(
            () -> {
              throw new IllegalStateException("registry unavailable");
            },
            new AdmissionCostProjector(costEngine(), new CharacterBoundTokenEstimator(0L), "USD"),
            ClaimBasedScopeResolver.tenantOnly());

    assertThat(assembler.assemble(inbound(), principal(), tenant()).candidateProviders()).isEmpty();
  }

  @Test
  void aRegistryWithNoRouteForTheModelYieldsAKnownEmptyCandidateSet() {
    registry.set(
        Optional.of(
            new CapabilityRegistrySnapshot(new SnapshotVersion("capabilities", "v1"), List.of())));

    assertThat(assembler().assemble(inbound(), principal(), tenant()).candidateProviders())
        .contains(List.of());
  }

  // ---- capability flags -----------------------------------------------------------------------

  @Test
  void streamingComesFromTheRequestMode() {
    final RequestExecution.Inbound streaming =
        inbound(
            List.of(new Message("user", "hi")),
            List.of(),
            Mode.STREAM,
            Operation.CHAT,
            Set.of("chat"),
            OptionalLong.of(16L),
            false,
            MODEL);

    assertThat(assembler().assemble(streaming, principal(), tenant()).streaming()).isTrue();
    assertThat(assembler().assemble(inbound(), principal(), tenant()).streaming()).isFalse();
  }

  @Test
  void jsonModeComesFromThePresenceOfAPinnedOutputSchema() {
    assertThat(assembler().assemble(inbound(), principal(), tenant()).jsonMode()).isFalse();
  }

  @Test
  void visionAndReasoningComeFromTheCallersAssertedCapabilities() {
    final RequestExecution.Inbound in =
        inbound(
            List.of(new Message("user", "hi")),
            List.of(),
            Mode.BATCH,
            Operation.CHAT,
            Set.of("chat", "vision", "reasoning"),
            OptionalLong.of(16L),
            false,
            MODEL);

    final PolicyRequest request = assembler().assemble(in, principal(), tenant());
    assertThat(request.vision()).isTrue();
    assertThat(request.reasoning()).isTrue();
  }

  @Test
  void operationDrivesTheOperationScopedCapabilityFlags() {
    for (final Operation operation : Operation.values()) {
      final RequestExecution.Inbound in =
          inbound(
              List.of(new Message("user", "hi")),
              List.of(),
              Mode.BATCH,
              operation,
              Set.of("chat"),
              OptionalLong.of(16L),
              false,
              MODEL);
      final PolicyRequest request = assembler().assemble(in, principal(), tenant());

      assertThat(request.embedding()).isEqualTo(operation == Operation.EMBEDDING);
      assertThat(request.imageGeneration()).isEqualTo(operation == Operation.IMAGE_GENERATION);
      assertThat(request.audio()).isEqualTo(operation == Operation.AUDIO);
      assertThat(request.fineTuning()).isEqualTo(operation == Operation.FINE_TUNING);
      assertThat(request.batch()).isEqualTo(operation == Operation.BATCH);
    }
  }

  @Test
  void piiClassificationComesFromTheCallersDeclaration() {
    final RequestExecution.Inbound classified =
        inbound(
            List.of(new Message("user", "hi")),
            List.of(),
            Mode.BATCH,
            Operation.CHAT,
            Set.of("chat"),
            OptionalLong.of(16L),
            true,
            MODEL);

    assertThat(assembler().assemble(classified, principal(), tenant()).containsPii()).isTrue();
    assertThat(assembler().assemble(inbound(), principal(), tenant()).containsPii()).isFalse();
  }

  // ---- quantities -----------------------------------------------------------------------------

  @Test
  void thePromptBoundComesFromTheEstimator() {
    // "user" + "hello" = 9 characters with zero per-message overhead.
    assertThat(assembler().assemble(inbound(), principal(), tenant()).contextTokens()).hasValue(9L);
  }

  @Test
  void anEstimatorThatCannotAnswerLeavesThePromptSizeUnknown() {
    assertThat(
            assembler(PromptTokenEstimator.UNKNOWN)
                .assemble(inbound(), principal(), tenant())
                .contextTokens())
        .isEmpty();
  }

  @Test
  void anEstimatorThatThrowsLeavesThePromptSizeUnknown() {
    final PromptTokenEstimator broken =
        request -> {
          throw new IllegalStateException("tokenizer unavailable");
        };

    assertThat(assembler(broken).assemble(inbound(), principal(), tenant()).contextTokens())
        .isEmpty();
  }

  @Test
  void theOutputCeilingComesFromTheCallersDeclaration() {
    assertThat(assembler().assemble(inbound(), principal(), tenant()).outputTokens())
        .hasValue(256L);
  }

  @Test
  void anUndeclaredOutputCeilingStaysUnknown() {
    final RequestExecution.Inbound undeclared =
        inbound(
            List.of(new Message("user", "hi")),
            List.of(),
            Mode.BATCH,
            Operation.CHAT,
            Set.of("chat"),
            OptionalLong.empty(),
            false,
            MODEL);

    assertThat(assembler().assemble(undeclared, principal(), tenant()).outputTokens()).isEmpty();
  }

  @Test
  void theProjectedCostComesFromTheCostEngine() {
    assertThat(assembler().assemble(inbound(), principal(), tenant()).projectedCostMicros())
        .hasValue(4_200L);
  }

  @Test
  void aCostEngineThatFailsClosedLeavesTheProjectedCostUnknown() {
    projection.set(new CostUnavailable(CostUnavailableReason.NO_REGIONAL_PRICE));

    assertThat(assembler().assemble(inbound(), principal(), tenant()).projectedCostMicros())
        .isEmpty();
  }

  @Test
  void aCostEngineThatThrowsLeavesTheProjectedCostUnknown() {
    final CostEnginePort broken =
        new CostEnginePort() {
          @Override
          public ProjectionOutcome project(final CostRequest request) {
            throw new IllegalStateException("pricing subsystem down");
          }

          @Override
          public ComputationOutcome compute(
              final CostRequest request, final NormalizedUsage usage) {
            throw new IllegalStateException("pricing subsystem down");
          }
        };
    final GovernanceAdmissionAssembler assembler =
        new GovernanceAdmissionAssembler(
            (CapabilityRegistryPort) registry::get,
            new AdmissionCostProjector(broken, new CharacterBoundTokenEstimator(0L), "USD"),
            ClaimBasedScopeResolver.tenantOnly());

    assertThat(assembler.assemble(inbound(), principal(), tenant()).projectedCostMicros())
        .isEmpty();
  }

  @Test
  void aProjectionInAnotherCurrencyIsTreatedAsNoAnswer() {
    projection.set(
        new CostProjection(
            new Money(4_200L, "EUR"), PricingClass.LIST, "declared_max_output", Map.of()));

    // Comparing euros against a budget authored in canonical micros would be wrong by an exchange
    // rate, and would fail open whenever the projection currency was the weaker one.
    assertThat(assembler().assemble(inbound(), principal(), tenant()).projectedCostMicros())
        .isEmpty();
  }

  @Test
  void anUnboundablePromptAlsoMakesTheCostUnknown() {
    // The projection needs an input-token count. No count, no projection — and no silent zero.
    assertThat(
            assembler(PromptTokenEstimator.UNKNOWN)
                .assemble(inbound(), principal(), tenant())
                .projectedCostMicros())
        .isEmpty();
  }

  @Test
  void theProjectionIsAskedForWithTheCallersDeclaredOutputCeiling() {
    final AtomicReference<CostRequest> seen = new AtomicReference<>();
    final CostEnginePort recording =
        new CostEnginePort() {
          @Override
          public ProjectionOutcome project(final CostRequest request) {
            seen.set(request);
            return projection.get();
          }

          @Override
          public ComputationOutcome compute(
              final CostRequest request, final NormalizedUsage usage) {
            return new CostUnavailable(CostUnavailableReason.INTERNAL_ERROR);
          }
        };
    new GovernanceAdmissionAssembler(
            (CapabilityRegistryPort) registry::get,
            new AdmissionCostProjector(recording, new CharacterBoundTokenEstimator(0L), "USD"),
            ClaimBasedScopeResolver.tenantOnly())
        .assemble(inbound(), principal(), tenant());

    assertThat(seen.get().phase()).isEqualTo(Phase.PROJECTION);
    assertThat(seen.get().inputTokens()).isEqualTo(9L);
    assertThat(seen.get().declaredMaxOutputTokens()).hasValue(256L);
    assertThat(seen.get().tenantScope()).isEqualTo(TENANT);
    assertThat(seen.get().canonicalModelId()).isEqualTo(MODEL);
    assertThat(seen.get().region()).isEqualTo(REGION);
  }

  @Test
  void theAdmissionAttemptIdIsDeterministicSoTheProjectionIsReplayable() {
    final AtomicReference<CostRequest> seen = new AtomicReference<>();
    final CostEnginePort recording =
        new CostEnginePort() {
          @Override
          public ProjectionOutcome project(final CostRequest request) {
            seen.set(request);
            return projection.get();
          }

          @Override
          public ComputationOutcome compute(
              final CostRequest request, final NormalizedUsage usage) {
            return new CostUnavailable(CostUnavailableReason.INTERNAL_ERROR);
          }
        };
    final GovernanceAdmissionAssembler assembler =
        new GovernanceAdmissionAssembler(
            (CapabilityRegistryPort) registry::get,
            new AdmissionCostProjector(recording, new CharacterBoundTokenEstimator(0L), "USD"),
            ClaimBasedScopeResolver.tenantOnly());

    assembler.assemble(inbound(), principal(), tenant());
    final String first = seen.get().attemptId().value();
    assembler.assemble(inbound(), principal(), tenant());

    assertThat(seen.get().attemptId().value()).isEqualTo(first).isEqualTo("corr-1:admission");
  }

  // ---- totality -------------------------------------------------------------------------------

  @Test
  void theAssemblerNeverThrowsEvenWhenEveryUpstreamFails() {
    final CostEnginePort broken =
        new CostEnginePort() {
          @Override
          public ProjectionOutcome project(final CostRequest request) {
            throw new IllegalStateException("down");
          }

          @Override
          public ComputationOutcome compute(
              final CostRequest request, final NormalizedUsage usage) {
            throw new IllegalStateException("down");
          }
        };
    final GovernanceAdmissionAssembler assembler =
        new GovernanceAdmissionAssembler(
            () -> {
              throw new IllegalStateException("down");
            },
            new AdmissionCostProjector(
                broken,
                request -> {
                  throw new IllegalStateException("down");
                },
                "USD"),
            ClaimBasedScopeResolver.tenantOnly());

    final PolicyRequest request = assembler.assemble(inbound(), principal(), tenant());

    // Every quantity unknown, but a question was still produced — so the decision is the engine's
    // to
    // make, and it will refuse everything that depended on the missing facts.
    assertThat(request.contextTokens()).isEmpty();
    assertThat(request.projectedCostMicros()).isEmpty();
    assertThat(request.candidateProviders()).isEmpty();
    assertThat(request.model()).contains(MODEL);
  }

  @Test
  void assemblingTheSameRequestTwiceProducesTheSameQuestion() {
    final GovernanceAdmissionAssembler assembler = assembler();

    final PolicyRequest first = assembler.assemble(inbound(), principal(), tenant());
    final PolicyRequest second = assembler.assemble(inbound(), principal(), tenant());

    assertThat(first.contextTokens()).isEqualTo(second.contextTokens());
    assertThat(first.projectedCostMicros()).isEqualTo(second.projectedCostMicros());
    assertThat(first.candidateProviders()).isEqualTo(second.candidateProviders());
    assertThat(first.scopeChain()).isEqualTo(second.scopeChain());
    assertThat(first.tools()).isEqualTo(second.tools());
  }
}
