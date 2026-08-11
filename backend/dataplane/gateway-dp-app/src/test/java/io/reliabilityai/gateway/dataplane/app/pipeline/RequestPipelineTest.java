package io.reliabilityai.gateway.dataplane.app.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.decision.AttemptClass;
import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.AttemptId;
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
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.io.ProviderMeta;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.canonical.usage.AccountingFact;
import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.binding.AdmissionCostProjector;
import io.reliabilityai.gateway.dataplane.app.binding.ClaimBasedScopeResolver;
import io.reliabilityai.gateway.dataplane.app.binding.GovernanceAdmissionAssembler;
import io.reliabilityai.gateway.dataplane.app.binding.LegacyGovernanceAdmission;
import io.reliabilityai.gateway.dataplane.app.binding.PromptTokenEstimator;
import io.reliabilityai.gateway.dataplane.cost.api.CostEnginePort;
import io.reliabilityai.gateway.dataplane.metering.api.UsageMeteringEnginePort;
import io.reliabilityai.gateway.dataplane.metering.domain.ExecutionFact;
import io.reliabilityai.gateway.dataplane.metering.domain.MeteringResult;
import io.reliabilityai.gateway.dataplane.metering.domain.RequestUsageManifest;
import io.reliabilityai.gateway.dataplane.metering.domain.UnrecordedReason;
import io.reliabilityai.gateway.dataplane.reliability.api.InvocationPlan;
import io.reliabilityai.gateway.dataplane.reliability.api.InvocationResult;
import io.reliabilityai.gateway.dataplane.router.api.RoutingFailure;
import io.reliabilityai.gateway.dataplane.router.api.RoutingRequest;
import io.reliabilityai.gateway.dataplane.router.api.RoutingResult;
import io.reliabilityai.gateway.dataplane.schemalock.api.CanonicalOutput;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StructuredOutputRequest;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import io.reliabilityai.gateway.dataplane.schemalock.domain.Strategy;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSourcePort;
import io.reliabilityai.gateway.dataplane.streamguard.application.StreamGuardService;
import io.reliabilityai.gateway.ports.AuthenticationPort;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import io.reliabilityai.gateway.ports.SecretsProviderPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the co-located pipeline assembly: exact stage order, fail-closed refusal, and
 * exactly-once accounting. Every collaborator is a counting double, so a stage that silently runs
 * twice — or runs at all after a refusal — fails the test rather than passing unnoticed.
 */
class RequestPipelineTest {

  private final Harness harness = new Harness();

  @Test
  void successfulRequestExecutesEveryMandatoryStageExactlyOnceInFrozenOrder() {
    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Completed.class);
    assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());
    for (final MandatoryStage stage : MandatoryStage.values()) {
      assertThat(outcome.trace().timesEntered(stage)).as("%s entered once", stage).isEqualTo(1L);
    }
    assertThat(outcome.trace().successful()).isTrue();
  }

  @Test
  void meteringCostAndPublisherEachExecuteExactlyOnce() {
    harness.pipeline().execute(harness.inbound());

    assertThat(harness.meterAttempts.get()).isEqualTo(1);
    assertThat(harness.finalizations.get()).isEqualTo(1);
    assertThat(harness.publishes.get()).isEqualTo(1);
  }

  @Test
  void costIsPricedThroughTheMeteringSeamNotReinvoked() {
    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    // Metering owns the cost trigger (Doc 22 §pricing-input). The COST stage must record that
    // pricing
    // happened without calling the engine a second time, or every request would be charged twice.
    final StageTrace.Entry cost =
        outcome.trace().entries().stream()
            .filter(entry -> entry.stage() == MandatoryStage.COST)
            .findFirst()
            .orElseThrow();
    assertThat(cost.disposition()).isEqualTo(StageTrace.Disposition.EXECUTED);
    assertThat(cost.detail()).isEqualTo("priced-via-metering-seam");
  }

  @Test
  void authenticationRefusalStopsExecutionImmediately() {
    harness.authenticated = false;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.AUTHN);
    assertThat(outcome.trace().stages())
        .containsExactly(MandatoryStage.INGRESS, MandatoryStage.AUTHN);
    assertThat(harness.governanceCalls.get()).isZero();
    assertThat(harness.providerInvocations.get()).isZero();
    assertThat(harness.meterAttempts.get()).isZero();
    assertThat(harness.publishes.get()).isZero();
  }

  @Test
  void governanceRefusalStopsExecutionAndNeverReachesTheProvider() {
    harness.permitted = false;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    assertThat(((PipelineOutcome.Refused) outcome).refusal().stage())
        .isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(outcome.trace().stages())
        .containsExactly(MandatoryStage.INGRESS, MandatoryStage.AUTHN, MandatoryStage.GOVERNANCE);
    assertThat(outcome.trace().refusalStage()).contains(MandatoryStage.GOVERNANCE);

    // The whole point of a policy gate: no credential is minted and no provider is touched.
    assertThat(harness.credentialRequests).isEmpty();
    assertThat(harness.providerInvocations.get()).isZero();
    assertThat(harness.meterAttempts.get()).isZero();
    assertThat(harness.publishes.get()).isZero();
  }

  @Test
  void routingRefusalStopsBeforeSecretsAreMaterialized() {
    harness.routable = false;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(((PipelineOutcome.Refused) outcome).refusal().stage())
        .isEqualTo(MandatoryStage.ROUTER);
    assertThat(harness.credentialRequests).isEmpty();
    assertThat(harness.providerInvocations.get()).isZero();
  }

  @Test
  void secretsRefusalStopsBeforeTheProviderIsInvoked() {
    harness.credentialAvailable = false;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(((PipelineOutcome.Refused) outcome).refusal().stage())
        .isEqualTo(MandatoryStage.SECRETS);
    assertThat(outcome.trace().stages()).endsWith(MandatoryStage.SECRETS);
    assertThat(harness.providerInvocations.get()).isZero();
    assertThat(harness.meterAttempts.get()).isZero();
  }

  @Test
  void tenantResolvedAtAuthenticationReachesSecretsAndMeteringUnchanged() {
    harness.pipeline().execute(harness.inbound());

    assertThat(harness.credentialRequests).hasSize(1);
    assertThat(harness.credentialRequests.get(0).tenantScope()).isEqualTo(Harness.TENANT);
    assertThat(harness.meteredFacts).hasSize(1);
    assertThat(harness.meteredFacts.get(0).tenantScope()).isEqualTo(Harness.TENANT);
  }

  @Test
  void materializedCredentialReachesTheProviderAndIsClosedAfterwards() {
    harness.pipeline().execute(harness.inbound());

    // The lease handed to the provider stage is the one secrets minted — not a substitute...
    assertThat(harness.credentialRequests.get(0).routeTarget()).isEqualTo(Harness.ROUTE);
    // ...and it must not outlive the invocation.
    assertThat(harness.lease.closed()).isTrue();
  }

  @Test
  void credentialIsClosedEvenWhenTheProviderStageRefuses() {
    harness.providerSucceeds = false;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(((PipelineOutcome.Refused) outcome).refusal().stage())
        .isEqualTo(MandatoryStage.ADAPTER);
    assertThat(harness.lease.closed()).isTrue();
    assertThat(harness.meterAttempts.get()).isZero();
    assertThat(harness.publishes.get()).isZero();
  }

  @Test
  void conditionalStagesAreConsultedNotSkipped() {
    // This request is unary and unstructured, so StreamGuard and SchemaLock have no work — but the
    // non-bypass invariant requires they still be consulted and recorded.
    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(entry(outcome, MandatoryStage.STREAM_GUARD).disposition())
        .isEqualTo(StageTrace.Disposition.NOT_APPLICABLE);
    assertThat(entry(outcome, MandatoryStage.SCHEMA_LOCK).disposition())
        .isEqualTo(StageTrace.Disposition.NOT_APPLICABLE);
  }

  @Test
  void schemaLockRunsAndRefusesNonConformantOutput() {
    harness.schema = Optional.of(new CompiledSchema(new SchemaId("abc"), "v1", 1));
    harness.conformant = false;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(((PipelineOutcome.Refused) outcome).refusal().stage())
        .isEqualTo(MandatoryStage.SCHEMA_LOCK);
    assertThat(harness.meterAttempts.get()).isZero(); // a rejected answer is never billed
    assertThat(harness.publishes.get()).isZero();
  }

  // ---- EMITTER: absent provider usage must not crash the request -----------------------------

  @Test
  void emitsTheAuthoritativeAccountingFactUnchangedWhenUsageWasEstablished() {
    harness.pipeline().execute(harness.inbound());

    final AccountingFact fact = (AccountingFact) harness.published.get(0);
    assertThat(fact.units())
        .isEqualTo(new CanonicalUsage(10L, 5L, 0L, 0L, 0L, UsageClass.AUTHORITATIVE));
    assertThat(fact.sealed()).isTrue();
    assertThat(fact.tenantScope()).isEqualTo(Harness.TENANT);
  }

  @Test
  void absentProviderUsageDoesNotCrashTheRequest() {
    harness.usageEstablished = false;

    // Providers may legally omit usage. Before the fix this threw at the final stage, after the
    // caller's answer had already been produced and the provider already paid for.
    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Completed.class);
    assertThat(((PipelineOutcome.Completed) outcome).response().content()).isEqualTo("hello");
  }

  @Test
  void absentProviderUsageStillEmitsExactlyOneAccountingFact() {
    harness.usageEstablished = false;

    harness.pipeline().execute(harness.inbound());

    assertThat(harness.publishes.get()).isEqualTo(1);
    assertThat(harness.published).hasSize(1).allMatch(AccountingFact.class::isInstance);
  }

  @Test
  void absentProviderUsageEmitsZeroedEstimatedUsage() {
    harness.usageEstablished = false;

    harness.pipeline().execute(harness.inbound());

    final AccountingFact fact = (AccountingFact) harness.published.get(0);
    assertThat(fact.units().usageClass()).isEqualTo(UsageClass.ESTIMATED);
    assertThat(fact.units().prompt()).isZero();
    assertThat(fact.units().completion()).isZero();
    assertThat(fact.units().reasoning()).isZero();
    assertThat(fact.units().cached()).isZero();
    assertThat(fact.units().toolTokens()).isZero();
  }

  @Test
  void anEstimatedFactIsNotSealedSoAccountingCanTellItApartFromAMeasuredZero() {
    harness.usageEstablished = false;

    harness.pipeline().execute(harness.inbound());

    // A measured zero and an estimated zero must never be indistinguishable downstream.
    final AccountingFact fact = (AccountingFact) harness.published.get(0);
    assertThat(fact.sealed()).isFalse();
    assertThat(fact.units().usageClass()).isEqualTo(UsageClass.ESTIMATED);
  }

  @Test
  void absentProviderUsageStillRunsEveryMandatoryStageInOrder() {
    harness.usageEstablished = false;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());
    assertThat(entry(outcome, MandatoryStage.EMITTER).disposition())
        .isEqualTo(StageTrace.Disposition.EXECUTED);
    assertThat(entry(outcome, MandatoryStage.EMITTER).detail())
        .isEqualTo("request-finalized-usage-estimated");
  }

  @Test
  void absentUsageOnAStreamingShapedRequestStillSucceeds() {
    // The unary/structured switches are independent of accounting; a request with a schema and no
    // established usage must finalize just the same.
    harness.usageEstablished = false;
    harness.schema = Optional.of(new CompiledSchema(new SchemaId("abc"), "v1", 1));

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Completed.class);
    assertThat(harness.publishes.get()).isEqualTo(1);
  }

  @Test
  void aRefusedRequestStillEmitsNothingRegardlessOfUsage() {
    harness.usageEstablished = false;
    harness.permitted = false;

    harness.pipeline().execute(harness.inbound());

    // The fallback must not turn a fail-closed refusal into a billable artifact.
    assertThat(harness.publishes.get()).isZero();
    assertThat(harness.published).isEmpty();
  }

  @Test
  void aProviderFailureStillEmitsNothingRegardlessOfUsage() {
    harness.usageEstablished = false;
    harness.providerSucceeds = false;

    harness.pipeline().execute(harness.inbound());

    assertThat(harness.publishes.get()).isZero();
    assertThat(harness.meterAttempts.get()).isZero();
  }

  @Test
  void theEmittedFactIsIdenticalOnEveryRun() {
    harness.usageEstablished = false;

    harness.pipeline().execute(harness.inbound());
    final AccountingFact first = (AccountingFact) harness.published.get(0);

    for (int run = 0; run < 25; run++) {
      final Harness repeat = new Harness();
      repeat.usageEstablished = false;
      repeat.pipeline().execute(repeat.inbound());
      assertThat(repeat.published.get(0)).isEqualTo(first); // deterministic replay
    }
  }

  private static StageTrace.Entry entry(final PipelineOutcome outcome, final MandatoryStage stage) {
    return outcome.trace().entries().stream()
        .filter(candidate -> candidate.stage() == stage)
        .findFirst()
        .orElseThrow();
  }

  /**
   * Counting doubles for every collaborator, plus switches to make each stage refuse.
   * Package-private so the non-bypass integration test can reuse the same wiring.
   */
  static final class Harness {

    static final TenantScope TENANT = TenantScope.of("org-a", "tenant-a");
    static final CanonicalModelId MODEL = new CanonicalModelId("model.test.v1");
    static final RouteTarget ROUTE = new RouteTarget(MODEL, "route-a");
    static final RequestId REQUEST_ID = new RequestId("req-1");
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    boolean authenticated = true;
    boolean permitted = true;
    boolean routable = true;
    boolean credentialAvailable = true;
    boolean providerSucceeds = true;
    boolean conformant = true;

    /** When false, metering reports an incomplete manifest with no customer usage. */
    boolean usageEstablished = true;

    /** When set, the provider returns this guarded streaming result instead of a unary response. */
    ProviderInvocationResult.StreamingTransport streamingTransport;

    /** When true, the provider returns the legacy (unguardable) Streaming variant. */
    boolean legacyStreaming;

    Optional<CompiledSchema> schema = Optional.empty();
    Optional<TransportSourcePort> streamSource = Optional.empty();

    final AtomicInteger governanceCalls = new AtomicInteger();
    final AtomicInteger providerInvocations = new AtomicInteger();
    final AtomicInteger meterAttempts = new AtomicInteger();
    final AtomicInteger finalizations = new AtomicInteger();
    final AtomicInteger publishes = new AtomicInteger();
    final List<CredentialRequest> credentialRequests = new java.util.ArrayList<>();
    final List<ExecutionFact> meteredFacts = new java.util.ArrayList<>();
    final List<ContentFree> published = new java.util.ArrayList<>();
    final List<String> streamedFragments = new java.util.concurrent.CopyOnWriteArrayList<>();
    final RecordingLease lease = new RecordingLease();

    RequestExecution.Inbound inbound() {
      return new RequestExecution.Inbound(
          REQUEST_ID,
          new RequestContext(
              new CorrelationId("corr-1"),
              new IdempotencyKey("idem-1"),
              new CausationId("cause-1"),
              "traceparent",
              new Region("us-east-1")),
          new ForwardedTransportIdentity("Bearer", "token", Map.of()),
          new CanonicalRequest(MODEL, List.of(), List.of(), Map.of()),
          Set.of("chat"),
          1024,
          Set.of(),
          1_000_000L,
          Set.of(),
          NOW.plusSeconds(30),
          true,
          Mode.BATCH,
          schema,
          streamSource);
    }

    RequestPipeline pipeline() {
      return new RequestPipeline(
          this::authenticate,
          // The legacy port behind the V2 adapter: these tests are about pipeline sequencing, and
          // routing them through the adapter proves the migration path keeps that sequencing
          // intact.
          new LegacyGovernanceAdmission(this::authorize, (ClockPort) () -> NOW),
          admissionAssembler(),
          // No plugin runtime on this harness: the pipeline must behave identically with and
          // without
          // one, which is Doc 28 PRT-D1 and what the extension tests assert directly.
          io.reliabilityai.gateway.dataplane.app.binding.ExtensionPointDispatcher.DISABLED,
          this::route,
          this::materialize,
          this::invoke,
          new StreamGuardService(() -> NOW, verdict -> {}),
          new StubSchemaLock(),
          new CountingMetering(),
          new CountingPublisher(),
          new StreamGuardPolicy(
              1_048_576L, 65_536, 128, Duration.ofSeconds(30), Duration.ofMinutes(5)),
          "gateway.usage.ledger",
          (ClockPort) () -> NOW);
    }

    private AuthenticationPort.AuthenticationResult authenticate(
        final ForwardedTransportIdentity transportIdentity, final RequestContext requestContext) {
      if (!authenticated) {
        return new AuthenticationPort.AuthenticationResult.Unauthenticated("invalid-signature");
      }
      return new AuthenticationPort.AuthenticationResult.Authenticated(
          new PrincipalContext(new PrincipalId("principal-a"), Map.of(), "jws", "decision-1"),
          new TenantContext(TENANT));
    }

    /** An assembler whose upstreams all decline, so every governed quantity is honestly unknown. */
    private static GovernanceAdmissionAssembler admissionAssembler() {
      return new GovernanceAdmissionAssembler(
          java.util.Optional::empty,
          new AdmissionCostProjector(
              new CostEnginePort() {
                @Override
                public io.reliabilityai.gateway.dataplane.cost.api.ProjectionOutcome project(
                    final io.reliabilityai.gateway.dataplane.cost.api.CostRequest request) {
                  return new io.reliabilityai.gateway.dataplane.cost.api.CostUnavailable(
                      io.reliabilityai.gateway.dataplane.cost.domain.CostUnavailableReason
                          .PRICING_MISSING);
                }

                @Override
                public io.reliabilityai.gateway.dataplane.cost.api.ComputationOutcome compute(
                    final io.reliabilityai.gateway.dataplane.cost.api.CostRequest request,
                    final io.reliabilityai.gateway.dataplane.cost.domain.NormalizedUsage usage) {
                  return new io.reliabilityai.gateway.dataplane.cost.api.CostUnavailable(
                      io.reliabilityai.gateway.dataplane.cost.domain.CostUnavailableReason
                          .PRICING_MISSING);
                }
              },
              PromptTokenEstimator.UNKNOWN,
              "USD"),
          ClaimBasedScopeResolver.tenantOnly());
    }

    private GovernanceDecision authorize(
        final RequestContext requestContext,
        final PrincipalContext principal,
        final TenantContext tenant) {
      governanceCalls.incrementAndGet();
      return permitted
          ? new GovernanceDecision(true, List.of(), "permitted")
          : new GovernanceDecision(false, List.of(), "policy-denied");
    }

    private RoutingResult route(final RoutingRequest request) {
      if (!routable) {
        return new RoutingResult.Failed(
            new RoutingFailure(RoutingFailure.FailureReason.NO_ELIGIBLE_PROVIDER));
      }
      return new RoutingResult.Routed(ROUTE, List.of(), new SnapshotVersion("capabilities", "v1"));
    }

    private SecretsProviderPort.MaterializationResult materialize(final CredentialRequest request) {
      credentialRequests.add(request);
      if (!credentialAvailable) {
        return new SecretsProviderPort.MaterializationResult.CredentialUnavailable("no-credential");
      }
      return new SecretsProviderPort.MaterializationResult.Leased(lease);
    }

    private InvocationResult invoke(final InvocationPlan plan) {
      providerInvocations.incrementAndGet();
      if (!providerSucceeds) {
        return new InvocationResult.Surfaced(
            io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityFailureReason
                .CANDIDATES_EXHAUSTED,
            null,
            List.of());
      }
      if (legacyStreaming) {
        return new InvocationResult.Succeeded(
            new ProviderInvocationResult.Streaming(subscriber -> {}), ROUTE, List.of());
      }
      if (streamingTransport != null) {
        return new InvocationResult.Succeeded(streamingTransport, ROUTE, List.of());
      }
      return new InvocationResult.Succeeded(
          new ProviderInvocationResult.Unary(unaryResponse()), ROUTE, List.of());
    }

    /**
     * Only {@code executeStructured} is exercised; the compile/stream paths are not on this path.
     */
    private final class StubSchemaLock implements SchemaLockPort {
      @Override
      public CompiledSchema compile(
          final io.reliabilityai.gateway.dataplane.schemalock.api.OutputSchema schema) {
        return new CompiledSchema(SchemaId.of(schema.schemaText()), schema.version(), 1);
      }

      @Override
      public CanonicalOutput executeStructured(
          final StructuredOutputRequest request, final CompiledSchema compiled) {
        // A surfaced (non-conformant) output must carry no value and must name its failure class —
        // SchemaLock never hands back a partially-valid answer.
        return new CanonicalOutput(
            conformant,
            conformant ? "{}" : null,
            compiled.schemaId(),
            compiled.version(),
            Strategy.NATIVE_SCHEMA,
            1,
            false,
            conformant
                ? null
                : io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass.NON_CONFORMANT,
            List.of());
      }

      @Override
      public io.reliabilityai.gateway.dataplane.schemalock.api.StructuredStreamSession openStream(
          final StructuredOutputRequest request,
          final CompiledSchema compiled,
          final io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort source) {
        // Drains the bridge exactly as the real session does, so the incremental feed is exercised
        // rather than bypassed, then reports the configured verdict.
        return () -> {
          final StringBuilder seen = new StringBuilder();
          while (true) {
            final var item = source.next();
            if (item
                instanceof
                io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort.StreamItem.Delta
                        delta) {
              seen.append(delta.fragment());
              streamedFragments.add(delta.fragment());
              continue;
            }
            final boolean transportOk =
                item
                    instanceof
                    io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort.StreamItem
                        .TransportComplete;
            final boolean ok = conformant && transportOk;
            return new CanonicalOutput(
                ok,
                ok ? seen.toString() : null,
                compiled.schemaId(),
                compiled.version(),
                Strategy.NATIVE_SCHEMA,
                1,
                false,
                ok
                    ? null
                    : io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass
                        .NON_CONFORMANT,
                List.of());
          }
        };
      }
    }

    /** The canonical response the unary path returns. */
    static CanonicalResponse unaryResponse() {
      return new CanonicalResponse(
          "hello",
          List.of(),
          FinishReason.STOP,
          new CanonicalUsage(10L, 5L, 0L, 0L, 0L, UsageClass.AUTHORITATIVE),
          ProviderMeta.empty());
    }

    /** A canonical error, for exhaustiveness checks over the sealed result hierarchy. */
    static io.reliabilityai.gateway.canonical.io.CanonicalError anError() {
      return new io.reliabilityai.gateway.canonical.io.CanonicalError(
          io.reliabilityai.gateway.canonical.io.ErrorCategory.PROVIDER_UNAVAILABLE,
          Boolean.FALSE,
          "stub",
          false);
    }

    /** Counts metering calls so double-metering cannot pass silently. */
    private final class CountingMetering implements UsageMeteringEnginePort {
      @Override
      public MeteringResult meterAttempt(final ExecutionFact fact) {
        meterAttempts.incrementAndGet();
        meteredFacts.add(fact);
        return new MeteringResult.Recorded(
            new UsageFact(
                fact.idempotencyKey(),
                fact.attemptId(),
                fact.tenantScope(),
                fact.canonicalModelId(),
                fact.region(),
                UsageClass.AUTHORITATIVE,
                fact.reportedUsage(),
                AttemptClass.SUCCESSFUL,
                true,
                NOW,
                Map.of()));
      }

      @Override
      public RequestUsageManifest finalizeRequest(final RequestId requestId) {
        finalizations.incrementAndGet();
        if (!usageEstablished) {
          // Metering's documented shape when it could not establish a billable figure: no customer
          // usage, and an incomplete manifest naming the reason.
          return new RequestUsageManifest(
              requestId,
              false,
              new CanonicalUsage(0L, 0L, 0L, 0L, 0L, UsageClass.ESTIMATED),
              null,
              1,
              UnrecordedReason.MISSING_USAGE);
        }
        return new RequestUsageManifest(
            requestId,
            true,
            new CanonicalUsage(10L, 5L, 0L, 0L, 0L, UsageClass.AUTHORITATIVE),
            new CanonicalUsage(10L, 5L, 0L, 0L, 0L, UsageClass.AUTHORITATIVE),
            1,
            null);
      }
    }

    /** Captures published payloads so the emitted accounting fact can be inspected. */
    private final class CountingPublisher implements EventPublisherPort {
      @Override
      public <T extends ContentFree> void publish(
          final String topic, final DeliveryClass deliveryClass, final T payload) {
        publishes.incrementAndGet();
        published.add(payload);
      }
    }

    /** A lease that remembers whether the pipeline closed it. */
    static final class RecordingLease implements CredentialLease {
      private boolean closed;

      @Override
      public String leaseId() {
        return "lease-1";
      }

      @Override
      public TenantScope tenantScope() {
        return TENANT;
      }

      @Override
      public Instant notAfter() {
        return NOW.plusSeconds(300);
      }

      @Override
      public boolean active() {
        return !closed;
      }

      @Override
      public void use(final SecretConsumer consumer) {
        consumer.accept(new char[] {'s', 'k'});
      }

      @Override
      public void close() {
        closed = true;
      }

      boolean closed() {
        return closed;
      }
    }
  }

  /** Exposes the attempt id shape used by metering, for the integration test's assertions. */
  static AttemptId expectedAttemptId() {
    return new AttemptId(Harness.REQUEST_ID.value() + "-a1");
  }
}
