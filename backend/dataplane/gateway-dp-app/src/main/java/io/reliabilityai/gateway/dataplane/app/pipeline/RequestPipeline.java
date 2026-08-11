package io.reliabilityai.gateway.dataplane.app.pipeline;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.decision.AttemptClass;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import io.reliabilityai.gateway.canonical.stream.StreamState;
import io.reliabilityai.gateway.canonical.usage.AccountingFact;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.binding.ExtensionOutcome;
import io.reliabilityai.gateway.dataplane.app.binding.ExtensionPointDispatcher;
import io.reliabilityai.gateway.dataplane.app.binding.GovernanceAdmissionAssembler;
import io.reliabilityai.gateway.dataplane.app.binding.TransportStreamSource;
import io.reliabilityai.gateway.dataplane.app.pipeline.StageTrace.Disposition;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceAdmissionPort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.metering.api.UsageMeteringEnginePort;
import io.reliabilityai.gateway.dataplane.metering.domain.ExecutionFact;
import io.reliabilityai.gateway.dataplane.metering.domain.MeteringResult;
import io.reliabilityai.gateway.dataplane.metering.domain.RequestUsageManifest;
import io.reliabilityai.gateway.dataplane.reliability.api.InvocationPlan;
import io.reliabilityai.gateway.dataplane.reliability.api.InvocationResult;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityEnginePort;
import io.reliabilityai.gateway.dataplane.router.api.ProviderRouterPort;
import io.reliabilityai.gateway.dataplane.router.api.RoutingRequest;
import io.reliabilityai.gateway.dataplane.router.api.RoutingResult;
import io.reliabilityai.gateway.dataplane.schemalock.api.CanonicalOutput;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StructuredOutputRequest;
import io.reliabilityai.gateway.dataplane.schemalock.api.StructuredStreamSession;
import io.reliabilityai.gateway.dataplane.schemalock.domain.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPort;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportEvent;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSession;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportVerdict;
import io.reliabilityai.gateway.ports.AuthenticationPort;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import io.reliabilityai.gateway.ports.SecretsProviderPort;
import io.reliabilityai.gateway.ports.SecretsProviderPort.MaterializationResult;
import java.util.ArrayList;
import java.util.List;

/**
 * The co-located data-plane pipeline: one request, every mandatory stage, in frozen order (Doc 06
 * §8, AD-018).
 *
 * <p>The order is the order of the statements in {@link #execute}. There is no stage registry, no
 * dispatch table and no dynamic composition — a reviewer can read the method top to bottom and see
 * the whole contract, and a stage cannot be inserted, removed or reordered without editing code
 * that the non-bypass test guards. Every decision belongs to the module that owns it; this class
 * only sequences calls and carries results between them.
 *
 * <p><b>Fail closed.</b> The first stage that refuses returns a {@link PipelineOutcome.Refused}
 * immediately. No downstream stage runs — in particular nothing is metered, nothing is priced and
 * nothing is published, so a refused request can never produce a billable or audit artifact that
 * implies it was served.
 *
 * <p><b>Credential hygiene.</b> The lease materialized before the provider call is closed in a
 * {@code finally} on every path, including refusals and exceptions, so key material never outlives
 * the invocation it was minted for (Doc 26 §17.1).
 *
 * <p><b>Known limitation (blocker M4 / Doc 32 §SFC).</b> This assembly completes the <em>unary</em>
 * response path. A streaming provider result cannot yet be finalized into a canonical response —
 * the streaming finalization contract needs a concrete provider adapter that does not exist. Rather
 * than fabricate a response, the pipeline fails closed at ADAPTER with an explicit reason. No
 * production caller can hit this today because no concrete provider adapter is wired.
 */
public final class RequestPipeline {

  private final AuthenticationPort authentication;
  private final GovernanceAdmissionPort governance;
  private final GovernanceAdmissionAssembler admission;
  private final ExtensionPointDispatcher extensions;
  private final ProviderRouterPort router;
  private final SecretsProviderPort secrets;
  private final ReliabilityEnginePort reliability;
  private final StreamGuardPort streamGuard;
  private final SchemaLockPort schemaLock;
  private final UsageMeteringEnginePort metering;
  private final EventPublisherPort publisher;
  private final StreamGuardPolicy streamPolicy;
  private final String finalizedTopic;
  private final ClockPort clock;

  /**
   * Creates the pipeline. Every collaborator is required: the runtime only constructs a pipeline
   * after the activation gate has confirmed each mandatory stage is bound, so a partially-wired
   * pipeline is unrepresentable.
   *
   * @param authentication the authentication service (AUTHN)
   * @param governance the policy enforcement point (GOVERNANCE)
   * @param admission assembles the full admission question governance evaluates (GOVERNANCE)
   * @param extensions runs plugins at the frozen additive extension points (Doc 28 §EPC)
   * @param router the provider router (ROUTER)
   * @param secrets the credential materialization service (SECRETS)
   * @param reliability the reliability engine driving the provider adapter (RELIABILITY, ADAPTER)
   * @param streamGuard the streaming transport integrity guard (STREAM_GUARD)
   * @param schemaLock the structured-output validator (SCHEMA_LOCK)
   * @param metering the usage metering engine (METERING; drives COST via its own seam)
   * @param publisher the durable event publisher (EMITTER)
   * @param streamPolicy the node-default stream integrity policy
   * @param finalizedTopic the topic the request-finalized accounting fact is published to
   * @param clock the injected clock
   */
  public RequestPipeline(
      final AuthenticationPort authentication,
      final GovernanceAdmissionPort governance,
      final GovernanceAdmissionAssembler admission,
      final ExtensionPointDispatcher extensions,
      final ProviderRouterPort router,
      final SecretsProviderPort secrets,
      final ReliabilityEnginePort reliability,
      final StreamGuardPort streamGuard,
      final SchemaLockPort schemaLock,
      final UsageMeteringEnginePort metering,
      final EventPublisherPort publisher,
      final StreamGuardPolicy streamPolicy,
      final String finalizedTopic,
      final ClockPort clock) {
    this.authentication = Preconditions.requireNonNull(authentication, "authentication");
    this.governance = Preconditions.requireNonNull(governance, "governance");
    this.admission = Preconditions.requireNonNull(admission, "admission");
    this.extensions = Preconditions.requireNonNull(extensions, "extensions");
    this.router = Preconditions.requireNonNull(router, "router");
    this.secrets = Preconditions.requireNonNull(secrets, "secrets");
    this.reliability = Preconditions.requireNonNull(reliability, "reliability");
    this.streamGuard = Preconditions.requireNonNull(streamGuard, "streamGuard");
    this.schemaLock = Preconditions.requireNonNull(schemaLock, "schemaLock");
    this.metering = Preconditions.requireNonNull(metering, "metering");
    this.publisher = Preconditions.requireNonNull(publisher, "publisher");
    this.streamPolicy = Preconditions.requireNonNull(streamPolicy, "streamPolicy");
    this.finalizedTopic = Preconditions.requireNonBlank(finalizedTopic, "finalizedTopic");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  /**
   * Runs one request through every mandatory stage, in frozen order, stopping at the first refusal.
   *
   * @param inbound the caller-supplied request
   * @return the canonical response, or a refusal naming the stage that stopped the request
   */
  public PipelineOutcome execute(final RequestExecution.Inbound inbound) {
    Preconditions.requireNonNull(inbound, "inbound");
    final StageTrace trace = new StageTrace();
    RequestExecution execution = RequestExecution.start(inbound);
    CredentialLease lease = null;
    try {
      // ---- INGRESS -------------------------------------------------------------------------
      long started = System.nanoTime();
      trace.record(MandatoryStage.INGRESS, Disposition.EXECUTED, started, "admitted");

      // ---- AUTHN ---------------------------------------------------------------------------
      started = System.nanoTime();
      final AuthenticationPort.AuthenticationResult authResult =
          authentication.authenticate(inbound.transportIdentity(), inbound.requestContext());
      if (authResult instanceof AuthenticationPort.AuthenticationResult.Unauthenticated denied) {
        return refuse(
            trace, MandatoryStage.AUTHN, started, denied.reason(), ErrorCategory.AUTH_FAILED);
      }
      final AuthenticationPort.AuthenticationResult.Authenticated identity =
          (AuthenticationPort.AuthenticationResult.Authenticated) authResult;
      execution = execution.withIdentity(identity.principal(), identity.tenant());
      trace.record(MandatoryStage.AUTHN, Disposition.EXECUTED, started, "authenticated");

      final PrincipalContext principal = identity.principal();
      final TenantContext tenant = identity.tenant();

      // ---- CLASSIFICATION (plugin extension point, Doc 28 §EPC) ----------------------------
      // Advisory only. Governance below reads none of this; Doc 28 PRT-D1 requires that removing
      // every plugin change no mandatory-stage outcome, and the contribution has nowhere to go.
      recordExtension(
          trace,
          extensions.dispatch(
              ExtensionPoint.CLASSIFICATION,
              inbound.requestContext().correlationId(),
              tenant,
              inbound.deadline(),
              java.util.Map.of()));

      // ---- GOVERNANCE ----------------------------------------------------------------------
      // Unchanged position: still immediately after AUTHN and strictly before ROUTER, so no request
      // reaches a provider un-governed. What changed is the question — the engine now receives the
      // model, tools, capabilities, candidate routes, token bound and projected spend, so the
      // policies
      // that compare against them are reachable from live traffic rather than only from tests.
      started = System.nanoTime();
      final PolicyDecision decision =
          governance.admit(admission.assemble(inbound, principal, tenant));
      if (!decision.admits()) {
        return refuse(
            trace,
            MandatoryStage.GOVERNANCE,
            started,
            decision.reasonCode(),
            ErrorCategory.AUTH_FAILED);
      }
      trace.record(
          MandatoryStage.GOVERNANCE,
          Disposition.EXECUTED,
          started,
          "permitted verdict=" + decision.verdict() + " observed=" + decision.violations().size());

      // ---- PRE_ROUTING (plugin extension point, Doc 28 §EPC) -------------------------------
      recordExtension(
          trace,
          extensions.dispatch(
              ExtensionPoint.PRE_ROUTING,
              inbound.requestContext().correlationId(),
              tenant,
              inbound.deadline(),
              java.util.Map.of()));

      // ---- ROUTER --------------------------------------------------------------------------
      started = System.nanoTime();
      final RoutingResult routingResult =
          router.route(
              new RoutingRequest(
                  inbound.request().canonicalModelId(),
                  inbound.requestContext().correlationId(),
                  tenant.tenantScope(),
                  inbound.requestContext().region(),
                  inbound.requiredCapabilities(),
                  inbound.minContextLength(),
                  inbound.requiredCompliance(),
                  inbound.costCeilingMicros(),
                  inbound.preferredCandidates()));
      if (routingResult instanceof RoutingResult.Failed failed) {
        return refuse(
            trace,
            MandatoryStage.ROUTER,
            started,
            failed.failure().reason().name(),
            ErrorCategory.PROVIDER_UNAVAILABLE);
      }
      final RoutingResult.Routed routed = (RoutingResult.Routed) routingResult;
      execution = execution.withRoute(routed.primary(), routed.failover());
      trace.record(
          MandatoryStage.ROUTER,
          Disposition.EXECUTED,
          started,
          "routed candidates=" + (1 + routed.failover().size()));

      // ---- RELIABILITY ---------------------------------------------------------------------
      // The engine is engaged and its plan pinned before credentials exist, so a routing or policy
      // problem is discovered without ever minting key material.
      started = System.nanoTime();
      final List<RouteTarget> candidates = new ArrayList<>();
      candidates.add(routed.primary());
      candidates.addAll(routed.failover());
      final InvocationPlan plan =
          new InvocationPlan(
              inbound.request(),
              List.copyOf(candidates),
              inbound.requestContext().correlationId(),
              inbound.deadline(),
              inbound.deadline(),
              inbound.idempotent());
      trace.record(
          MandatoryStage.RELIABILITY,
          Disposition.EXECUTED,
          started,
          "plan-pinned candidates=" + candidates.size());

      // ---- SECRETS -------------------------------------------------------------------------
      // Materialized immediately before the provider invocation, and closed in the finally below.
      started = System.nanoTime();
      final MaterializationResult materialization =
          secrets.materialize(
              new CredentialRequest(
                  tenant.tenantScope(),
                  routed.primary(),
                  inbound.requestContext().correlationId()));
      if (materialization instanceof MaterializationResult.CredentialUnavailable unavailable) {
        return refuse(
            trace,
            MandatoryStage.SECRETS,
            started,
            unavailable.reason(),
            ErrorCategory.AUTH_FAILED);
      }
      lease = ((MaterializationResult.Leased) materialization).lease();
      execution = execution.withCredential(lease);
      trace.record(MandatoryStage.SECRETS, Disposition.EXECUTED, started, "leased");

      // ---- ADAPTER (driven by the reliability engine) --------------------------------------
      started = System.nanoTime();
      final InvocationResult invocation = reliability.execute(plan);
      if (invocation instanceof InvocationResult.Surfaced surfaced) {
        return refuse(
            trace,
            MandatoryStage.ADAPTER,
            started,
            surfaced.reason().name(),
            surfaced.lastError() == null
                ? ErrorCategory.PROVIDER_UNAVAILABLE
                : surfaced.lastError().category());
      }
      final InvocationResult.Succeeded succeeded = (InvocationResult.Succeeded) invocation;
      if (succeeded.outcome() instanceof ProviderInvocationResult.Failed providerFailed) {
        return refuse(
            trace,
            MandatoryStage.ADAPTER,
            started,
            providerFailed.error().providerCodeOpaque(),
            providerFailed.error().category());
      }
      final int attempts = succeeded.history().size();

      // A guarded streaming response: the transport is still framed, so STREAM_GUARD can verify
      // what
      // the provider actually sent. The remaining stages run as the stream terminates.
      if (succeeded.outcome() instanceof ProviderInvocationResult.StreamingTransport streamed) {
        trace.record(
            MandatoryStage.ADAPTER,
            Disposition.EXECUTED,
            started,
            "streaming attempts=" + attempts);
        return openGuardedStream(streamed, inbound, tenant, attempts, trace);
      }

      if (!(succeeded.outcome() instanceof ProviderInvocationResult.Unary unary)) {
        // The legacy Streaming variant hands over canonical chunks, so the framing StreamGuard must
        // verify is already gone. Serving it would mean claiming an integrity proof we cannot make.
        return refuse(
            trace,
            MandatoryStage.ADAPTER,
            started,
            "unguardable-stream",
            ErrorCategory.PROVIDER_UNAVAILABLE);
      }
      final CanonicalResponse response = unary.response();
      trace.record(
          MandatoryStage.ADAPTER, Disposition.EXECUTED, started, "invoked attempts=" + attempts);

      // ---- STREAM_GUARD --------------------------------------------------------------------
      started = System.nanoTime();
      if (inbound.streamSource().isPresent()) {
        final TransportVerdict verdict = guardStream(inbound);
        if (verdict.integrity() == TransportVerdict.Integrity.FAILED) {
          return refuse(
              trace,
              MandatoryStage.STREAM_GUARD,
              started,
              verdict.failureClass().name(),
              ErrorCategory.MALFORMED_RESPONSE);
        }
        trace.record(
            MandatoryStage.STREAM_GUARD,
            Disposition.EXECUTED,
            started,
            "integrity-proven deltas=" + verdict.deltasEmitted());
      } else {
        trace.record(
            MandatoryStage.STREAM_GUARD, Disposition.NOT_APPLICABLE, started, "unary-response");
      }

      // ---- VALIDATION (plugin extension point, Doc 28 §EPC) ----------------------------
      recordExtension(
          trace,
          extensions.dispatch(
              ExtensionPoint.VALIDATION,
              inbound.requestContext().correlationId(),
              tenant,
              inbound.deadline(),
              java.util.Map.of()));

      // ---- SCHEMA_LOCK ---------------------------------------------------------------------
      started = System.nanoTime();
      if (inbound.outputSchema().isPresent()) {
        final CompiledSchema schema = inbound.outputSchema().orElseThrow();
        final CanonicalOutput output =
            schemaLock.executeStructured(
                new StructuredOutputRequest(
                    schema.schemaId(),
                    inbound.requestContext().correlationId(),
                    CapabilityDescriptor.LEAST_CAPABLE,
                    inbound.mode()),
                schema);
        if (!output.conformant()) {
          return refuse(
              trace,
              MandatoryStage.SCHEMA_LOCK,
              started,
              output.classification() == null ? "non-conformant" : output.classification().name(),
              ErrorCategory.MALFORMED_RESPONSE);
        }
        trace.record(
            MandatoryStage.SCHEMA_LOCK,
            Disposition.EXECUTED,
            started,
            "conformant attempts=" + output.attempts());
      } else {
        trace.record(
            MandatoryStage.SCHEMA_LOCK, Disposition.NOT_APPLICABLE, started, "unstructured-output");
      }

      // ---- ATTRIBUTION (plugin extension point, Doc 28 §EPC) ----------------------------
      recordExtension(
          trace,
          extensions.dispatch(
              ExtensionPoint.ATTRIBUTION,
              inbound.requestContext().correlationId(),
              tenant,
              inbound.deadline(),
              java.util.Map.of()));

      // ---- METERING ------------------------------------------------------------------------
      // Exactly once per delivered attempt, then the request is finalized (Doc 23 §17.1).
      started = System.nanoTime();
      final AttemptId attemptId =
          new AttemptId(inbound.requestId().value() + "-a" + Math.max(attempts, 1));
      final MeteringResult metered =
          metering.meterAttempt(
              new ExecutionFact(
                  inbound.requestId(),
                  inbound.requestContext().idempotencyKey(),
                  attemptId,
                  tenant.tenantScope(),
                  inbound.request().canonicalModelId(),
                  inbound.requestContext().region(),
                  AttemptClass.SUCCESSFUL,
                  true,
                  response.usage(),
                  response.usage().usageClass()));
      final RequestUsageManifest manifest = metering.finalizeRequest(inbound.requestId());
      trace.record(
          MandatoryStage.METERING,
          Disposition.EXECUTED,
          started,
          (metered instanceof MeteringResult.Recorded ? "recorded" : "unrecorded")
              + " complete="
              + manifest.complete());

      // ---- COST ----------------------------------------------------------------------------
      // Cost is driven by metering's own CostUsagePort seam (Doc 22 §pricing-input): recording an
      // attempt prices it exactly once. Calling the cost engine again here would double-charge, so
      // this stage observes the seam's outcome rather than re-invoking it.
      started = System.nanoTime();
      if (metered instanceof MeteringResult.Recorded) {
        trace.record(
            MandatoryStage.COST, Disposition.EXECUTED, started, "priced-via-metering-seam");
      } else {
        trace.record(MandatoryStage.COST, Disposition.NOT_APPLICABLE, started, "usage-unrecorded");
      }

      // ---- TELEMETRY (plugin extension point, Doc 28 §EPC) ----------------------------
      recordExtension(
          trace,
          extensions.dispatch(
              ExtensionPoint.TELEMETRY,
              inbound.requestContext().correlationId(),
              tenant,
              inbound.deadline(),
              java.util.Map.of()));

      // ---- EMITTER -------------------------------------------------------------------------
      started = System.nanoTime();
      publisher.publish(
          finalizedTopic,
          DeliveryClass.ZL,
          new AccountingFact(
              new ExecutionIdentity(
                  inbound.requestContext().idempotencyKey(),
                  attemptId,
                  inbound.requestContext().correlationId()),
              tenant.tenantScope(),
              billableUsage(manifest),
              manifest.complete(),
              clock.now()));
      trace.record(
          MandatoryStage.EMITTER,
          Disposition.EXECUTED,
          started,
          manifest.complete() ? "request-finalized" : "request-finalized-usage-estimated");

      return new PipelineOutcome.Completed(response, trace);
    } finally {
      if (lease != null) {
        lease.close(); // key material never outlives the invocation, on every path
      }
    }
  }

  /**
   * The usage to seal the request with.
   *
   * <p>{@code customerUsage} is <b>null by contract</b> on an incomplete manifest (Doc 23):
   * metering refuses to state a billable figure it could not establish authoritatively — for
   * example when the provider omitted its usage block, or the durable usage journal was
   * unavailable. Providers are entitled to omit usage, so this is a normal path, not a provider
   * fault.
   *
   * <p>Dereferencing it crashed the request at the final stage, after the caller's answer had
   * already been produced and paid for. Falling back to a zeroed {@link UsageClass#ESTIMATED} fact
   * keeps the request-finalized signal flowing while stating plainly that the figure is not
   * authoritative: downstream accounting can tell an estimated zero from a measured zero by the
   * usage class and the fact's unsealed flag, so nothing is silently billed as if it had been
   * measured.
   *
   * @param manifest the finalized usage manifest
   * @return the manifest's customer usage, or zeroed estimated usage when it established none
   */
  /**
   * Records what a point's plugins contributed, as trace detail and nothing more.
   *
   * <p>The only consumer of an {@link ExtensionOutcome} in this class. Doc 28 PRT-D1 requires that
   * removing every plugin change no mandatory-stage outcome; a contribution that reached a decision
   * would break that, so the trace is where it stops.
   *
   * @param trace the request's stage trace
   * @param outcome what the point's plugins contributed
   */
  private static void recordExtension(final StageTrace trace, final ExtensionOutcome outcome) {
    if (!outcome.isEmpty()) {
      trace.recordExtension(outcome.point(), outcome.summary());
    }
  }

  private static CanonicalUsage billableUsage(final RequestUsageManifest manifest) {
    final CanonicalUsage customerUsage = manifest.customerUsage();
    if (customerUsage != null) {
      return customerUsage;
    }
    return new CanonicalUsage(0L, 0L, 0L, 0L, 0L, UsageClass.ESTIMATED);
  }

  /**
   * Opens the guarded stream and wires the stages that run when it terminates.
   *
   * <p>STREAM_GUARD is recorded at the <em>terminal</em>, not at open, because that is when its
   * verdict actually exists — a transport can only be proven intact once it has finished. Recording
   * it earlier would put an unearned "executed" in the trace. The remaining stages then run in
   * frozen order behind it, so a drained stream produces exactly the same stage sequence as a unary
   * request.
   */
  private PipelineOutcome openGuardedStream(
      final ProviderInvocationResult.StreamingTransport streamed,
      final RequestExecution.Inbound inbound,
      final TenantContext tenant,
      final int attempts,
      final StageTrace trace) {

    final TransportSession session =
        streamGuard.openTransport(new TransportStreamSource(streamed.source()), streamPolicy);

    // Structured output is validated as fragments arrive, not after the fact.
    final SchemaLockStreamBridge bridge =
        inbound.outputSchema().isPresent() ? new SchemaLockStreamBridge() : null;
    final StructuredStreamSession structuredSession =
        bridge == null
            ? null
            : schemaLock.openStream(
                new StructuredOutputRequest(
                    inbound.outputSchema().orElseThrow().schemaId(),
                    inbound.requestContext().correlationId(),
                    CapabilityDescriptor.LEAST_CAPABLE,
                    inbound.mode()),
                inbound.outputSchema().orElseThrow(),
                bridge);
    final java.util.concurrent.CompletableFuture<CanonicalOutput> structuredOutcome =
        structuredSession == null
            ? null
            : java.util.concurrent.CompletableFuture.supplyAsync(
                structuredSession::awaitCompletion, command -> Thread.ofVirtual().start(command));

    final GuardedCanonicalStream.StreamFinalizer finalizer =
        (text, usage, integrityProven, finishReason) ->
            finalizeStream(
                inbound,
                tenant,
                attempts,
                trace,
                bridge,
                structuredOutcome,
                text,
                usage,
                integrityProven,
                finishReason);

    final GuardedCanonicalStream stream =
        new GuardedCanonicalStream(
            session,
            streamed.decoder(),
            finalizer,
            fragment -> {
              if (bridge != null) {
                bridge.offer(fragment);
              }
            });
    return new PipelineOutcome.Streamed(stream, trace);
  }

  /** Runs STREAM_GUARD → SCHEMA_LOCK → METERING → COST → EMITTER as the stream terminates. */
  private StreamChunk.Terminal finalizeStream(
      final RequestExecution.Inbound inbound,
      final TenantContext tenant,
      final int attempts,
      final StageTrace trace,
      final SchemaLockStreamBridge bridge,
      final java.util.concurrent.CompletableFuture<CanonicalOutput> structuredOutcome,
      final String text,
      final CanonicalUsage usage,
      final boolean integrityProven,
      final FinishReason finishReason) {

    long started = System.nanoTime();
    if (!integrityProven) {
      trace.record(MandatoryStage.STREAM_GUARD, Disposition.REFUSED, started, "integrity-failed");
      if (bridge != null) {
        bridge.fail(FailureClass.TRUNCATED);
      }
      // Nothing downstream runs: a stream whose transport was not proven intact is never metered,
      // priced or sealed as delivered.
      return new StreamChunk.Terminal(StreamState.TERMINAL_FAILED, FinishReason.ERROR);
    }
    trace.record(MandatoryStage.STREAM_GUARD, Disposition.EXECUTED, started, "integrity-proven");

    // ---- SCHEMA_LOCK ------------------------------------------------------------------------
    started = System.nanoTime();
    if (bridge == null) {
      trace.record(
          MandatoryStage.SCHEMA_LOCK, Disposition.NOT_APPLICABLE, started, "unstructured-output");
    } else {
      bridge.complete();
      CanonicalOutput output = null;
      try {
        output =
            structuredOutcome.get(SCHEMA_LOCK_AWAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (final java.util.concurrent.ExecutionException
          | java.util.concurrent.TimeoutException failure) {
        output = null;
      }
      if (output == null || !output.conformant()) {
        trace.record(
            MandatoryStage.SCHEMA_LOCK,
            Disposition.REFUSED,
            started,
            output == null || output.classification() == null
                ? "non-conformant"
                : output.classification().name());
        return new StreamChunk.Terminal(StreamState.TERMINAL_FAILED, FinishReason.ERROR);
      }
      trace.record(
          MandatoryStage.SCHEMA_LOCK, Disposition.EXECUTED, started, "conformant streamed");
    }

    // ---- METERING ---------------------------------------------------------------------------
    started = System.nanoTime();
    final AttemptId attemptId =
        new AttemptId(inbound.requestId().value() + "-a" + Math.max(attempts, 1));
    final MeteringResult metered =
        metering.meterAttempt(
            new ExecutionFact(
                inbound.requestId(),
                inbound.requestContext().idempotencyKey(),
                attemptId,
                tenant.tenantScope(),
                inbound.request().canonicalModelId(),
                inbound.requestContext().region(),
                AttemptClass.SUCCESSFUL,
                true,
                usage,
                usage.usageClass()));
    final RequestUsageManifest manifest = metering.finalizeRequest(inbound.requestId());
    trace.record(
        MandatoryStage.METERING,
        Disposition.EXECUTED,
        started,
        (metered instanceof MeteringResult.Recorded ? "recorded" : "unrecorded")
            + " streamed complete="
            + manifest.complete());

    // ---- COST -------------------------------------------------------------------------------
    started = System.nanoTime();
    if (metered instanceof MeteringResult.Recorded) {
      trace.record(MandatoryStage.COST, Disposition.EXECUTED, started, "priced-via-metering-seam");
    } else {
      trace.record(MandatoryStage.COST, Disposition.NOT_APPLICABLE, started, "usage-unrecorded");
    }

    // ---- EMITTER ----------------------------------------------------------------------------
    started = System.nanoTime();
    publisher.publish(
        finalizedTopic,
        DeliveryClass.ZL,
        new AccountingFact(
            new ExecutionIdentity(
                inbound.requestContext().idempotencyKey(),
                attemptId,
                inbound.requestContext().correlationId()),
            tenant.tenantScope(),
            billableUsage(manifest),
            manifest.complete(),
            clock.now()));
    trace.record(
        MandatoryStage.EMITTER,
        Disposition.EXECUTED,
        started,
        manifest.complete() ? "request-finalized" : "request-finalized-usage-estimated");

    return new StreamChunk.Terminal(StreamState.TERMINAL_COMPLETE, finishReason);
  }

  /** How long the terminal waits for the incremental structured-output session to settle. */
  private static final long SCHEMA_LOCK_AWAIT_SECONDS = 10;

  /** Drains the guarded transport to its terminal verdict. */
  private TransportVerdict guardStream(final RequestExecution.Inbound inbound) {
    final TransportSession session =
        streamGuard.openTransport(inbound.streamSource().orElseThrow(), streamPolicy);
    while (true) {
      final TransportEvent event = session.next();
      if (event instanceof TransportEvent.Completed completed) {
        return completed.verdict();
      }
    }
  }

  private static PipelineOutcome refuse(
      final StageTrace trace,
      final MandatoryStage stage,
      final long startNanos,
      final String reason,
      final ErrorCategory category) {
    trace.record(stage, Disposition.REFUSED, startNanos, reason);
    return new PipelineOutcome.Refused(
        new StageRefusal(stage, reason, new CanonicalError(category, Boolean.FALSE, reason, false)),
        trace);
  }
}
