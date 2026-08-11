package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.pipeline.Operation;
import io.reliabilityai.gateway.dataplane.app.pipeline.PipelineOutcome;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestPipeline;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import io.reliabilityai.gateway.ports.ClockPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one agent step as exactly one complete {@code RequestPipeline} execution.
 *
 * <p><b>This class is where AD-025's central rule stops being a diagram.</b> Every model invocation
 * an agent makes enters the pipeline at INGRESS and passes through every mandatory stage in frozen
 * order — AUTHN, GOVERNANCE, ROUTER, RELIABILITY, SECRETS, ADAPTER, STREAM_GUARD, SCHEMA_LOCK,
 * METERING, COST, EMITTER — with <em>nothing</em> reused from the step before it. No cached
 * authorization, no cached route, no cached credential, no cached policy decision. Step 40 of a run
 * is authorized exactly as rigorously as step 1, because as far as the pipeline is concerned it is
 * a new request that happens to have the same correlation id.
 *
 * <p><b>What this adapter is and is not.</b> AD-024 specifies C13, a Tool Session Orchestrator that
 * owns a multi-turn model-plus-tools loop. C13 does not exist in this codebase — AD-024 is Proposed
 * and was never built. This adapter therefore implements the <em>degenerate</em> case AD-025 §14
 * anticipates: a session of exactly one turn. It satisfies the port's contract for single-turn
 * steps and satisfies nothing beyond that. A plan that needs a model to call tools and come back
 * needs C13, and until C13 exists that plan must be expressed as alternating pipeline and plugin
 * steps — which works, is fully governed, and is more verbose.
 *
 * <p><b>Cost is reported as unknown.</b> {@code PipelineOutcome} carries no priced figure; the cost
 * engine is driven through metering's own seam inside the pipeline and its result is not returned
 * to the caller. Rather than report a zero — which would make every budget check pass and turn the
 * run's spend bound into decoration — this adapter sets {@code costKnown = false}, and the Agent
 * Runtime charges the step's full allotment instead. That over-charges, which ends runs early and
 * visibly. A seam returning the priced figure would fix it; that is a recorded blocker, not a
 * silent default.
 */
public final class PipelineToolSessionAdapter implements ToolSessionPort {

  /**
   * The gateway-specific facts a neutral session request cannot carry.
   *
   * <p>The Agent Runtime is provider-neutral and knows nothing of models, regions or transport
   * credentials (AD-025 AGT-27). Those belong to the caller who started the run, and are looked up
   * here rather than smuggled through the port — which is what keeps the runtime free of gateway
   * types.
   *
   * @param model the canonical model the run's steps target; routing still chooses the provider
   * @param transportIdentity the caller's forwarded credential material, re-authenticated every
   *     step
   * @param region the caller's region
   * @param causationId the causation to stamp on every step's request
   * @param minContextLength the context window steps require
   * @param requiredCompliance compliance attestations the provider must carry
   */
  public record SessionContext(
      CanonicalModelId model,
      ForwardedTransportIdentity transportIdentity,
      Region region,
      CausationId causationId,
      int minContextLength,
      Set<String> requiredCompliance) {

    /**
     * Validates the context.
     *
     * @param model the canonical model
     * @param transportIdentity the forwarded credential material
     * @param region the caller's region
     * @param causationId the causation
     * @param minContextLength the required context window
     * @param requiredCompliance the required attestations
     */
    public SessionContext {
      Preconditions.requireNonNull(model, "model");
      Preconditions.requireNonNull(transportIdentity, "transportIdentity");
      Preconditions.requireNonNull(region, "region");
      Preconditions.requireNonNull(causationId, "causationId");
      requiredCompliance =
          Set.copyOf(Preconditions.requireNonNull(requiredCompliance, "requiredCompliance"));
      if (minContextLength < 0) {
        throw new IllegalArgumentException("minContextLength must be non-negative");
      }
    }
  }

  /** Where a run's gateway context comes from. */
  @FunctionalInterface
  public interface SessionContextSource {

    /**
     * Returns the context a run's steps execute under.
     *
     * @param correlationId the run's correlation
     * @return the context, or empty when the run is unknown to this node
     */
    Optional<SessionContext> contextFor(CorrelationId correlationId);
  }

  private final RequestPipeline pipeline;
  private final SessionContextSource contexts;
  private final ClockPort clock;
  private final Map<String, SessionOutcome> completed = new ConcurrentHashMap<>();

  /**
   * Creates the adapter.
   *
   * @param pipeline the request pipeline, used unchanged
   * @param contexts where a run's gateway context comes from
   * @param clock the injected clock
   */
  public PipelineToolSessionAdapter(
      final RequestPipeline pipeline, final SessionContextSource contexts, final ClockPort clock) {
    this.pipeline = Preconditions.requireNonNull(pipeline, "pipeline");
    this.contexts = Preconditions.requireNonNull(contexts, "contexts");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  @Override
  public SessionOutcome openSession(final SessionRequest request) {
    Preconditions.requireNonNull(request, "request");

    final Optional<SessionContext> context = contexts.contextFor(request.correlationId());
    if (context.isEmpty()) {
      // Fail closed. Inventing a model, a region or a credential to keep the step moving would mean
      // the gateway executing a request nobody authorized the shape of.
      return record(
          request,
          new Failed(
              FailureClass.STEP_DENIED,
              "no session context registered for correlation " + request.correlationId(),
              0L,
              true,
              0));
    }

    final PipelineOutcome outcome;
    try {
      outcome = pipeline.execute(inboundFor(request, context.get()));
    } catch (final RuntimeException failure) {
      return record(
          request,
          new Failed(
              FailureClass.STEP_TRANSIENT, failure.getClass().getSimpleName(), 0L, false, 1));
    }

    return record(request, translate(outcome));
  }

  private SessionOutcome translate(final PipelineOutcome outcome) {
    return switch (outcome) {
      case PipelineOutcome.Completed done -> new Completed(textOf(done), 0L, false, 1, false);
      case PipelineOutcome.Refused refused ->
          new Failed(
              classify(refused.refusal().stage()),
              refused.refusal().stage() + ": " + refused.refusal().reason(),
              0L,
              false,
              1);
      // A streamed outcome hands back a live transport. A step must produce a settled, recordable
      // value before the run advances, and draining a stream here would put an unbounded read
      // inside a
      // step boundary. Refused rather than half-supported.
      case PipelineOutcome.Streamed ignored ->
          new Failed(
              FailureClass.STEP_PERMANENT,
              "streaming responses are not consumable by an agent step in V1",
              0L,
              false,
              1);
    };
  }

  /**
   * Maps the refusing stage to the closed failure taxonomy.
   *
   * <p>The distinction that matters is retryability. A governance or authentication refusal is
   * deterministic — retrying burns budget to be refused identically — while a routing or provider
   * refusal may well succeed on the next attempt.
   */
  private static FailureClass classify(final MandatoryStage stage) {
    return switch (stage) {
      case AUTHN, GOVERNANCE, SECRETS -> FailureClass.STEP_DENIED;
      case ROUTER, ADAPTER -> FailureClass.STEP_TRANSIENT;
      case INGRESS, RELIABILITY, STREAM_GUARD, SCHEMA_LOCK, METERING, COST, EMITTER ->
          FailureClass.STEP_PERMANENT;
    };
  }

  private static String textOf(final PipelineOutcome.Completed done) {
    return done.response().content();
  }

  /**
   * Builds the inbound request for one step.
   *
   * <p>The request id and idempotency key are <b>derived from the session reference</b>, which is
   * itself derived from the run id, the step index and the attempt number. Two things follow. A
   * retried step gets a genuinely different identity, so metering does not treat the retry as a
   * duplicate of the attempt that failed. And a step re-executed after a crash produces the same
   * identity it had before, so an idempotency-aware downstream can recognise it — which is the
   * difference between at-least-once and at-least-once-that-double-bills.
   */
  private RequestExecution.Inbound inboundFor(
      final SessionRequest request, final SessionContext context) {

    final List<Message> messages = new ArrayList<>();
    for (final String input : request.inputs()) {
      // Prior results enter as ordinary message content, so they pass through StreamGuard,
      // SchemaLock
      // and every policy that inspects a request — which is precisely what AGT-9 requires of a tool
      // artifact reaching a model.
      messages.add(new Message("user", input));
    }
    messages.add(new Message("user", request.instruction()));

    return new RequestExecution.Inbound(
        new RequestId(request.sessionRef()),
        new RequestContext(
            request.correlationId(),
            new IdempotencyKey(request.sessionRef()),
            context.causationId(),
            null,
            context.region()),
        context.transportIdentity(),
        new CanonicalRequest(context.model(), List.copyOf(messages), List.of(), Map.of()),
        request.grantedCapabilities(),
        context.minContextLength(),
        context.requiredCompliance(),
        request.budgetMicros(),
        Set.of(),
        clock.now().plus(request.deadline()),
        false,
        Mode.BATCH,
        Optional.empty(),
        Optional.empty(),
        Operation.CHAT,
        OptionalLong.empty(),
        false);
  }

  @Override
  public void cancelSession(final String sessionRef) {
    Preconditions.requireNonNull(sessionRef, "sessionRef");
    // A single-turn session is synchronous: by the time anyone can cancel it, it has either
    // returned
    // or its thread is inside the pipeline, which owns its own deadline. There is nothing to
    // interrupt
    // that would not be a worse bug than letting the deadline fire.
  }

  @Override
  public Optional<SessionOutcome> lookup(final String sessionRef) {
    Preconditions.requireNonNull(sessionRef, "sessionRef");
    return Optional.ofNullable(completed.get(sessionRef));
  }

  /**
   * Remembers an outcome so an interrupted step can be resolved without re-running it.
   *
   * <p>Process-local, and therefore only useful when the node that recovers is the node that ran
   * the step. A node that died takes its map with it, and recovery falls through to treating the
   * step as failed — which is correct, and is why AD-025 §35.2 has three resolutions rather than
   * one. A durable session record in C13 would make the common case recoverable across nodes.
   */
  private SessionOutcome record(final SessionRequest request, final SessionOutcome outcome) {
    completed.put(request.sessionRef(), outcome);
    return outcome;
  }

  /**
   * Returns how many session outcomes this node remembers.
   *
   * @return the remembered outcome count
   */
  public int rememberedOutcomes() {
    return completed.size();
  }
}
