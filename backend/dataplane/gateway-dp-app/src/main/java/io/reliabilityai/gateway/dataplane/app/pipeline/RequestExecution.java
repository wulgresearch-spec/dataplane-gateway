package io.reliabilityai.gateway.dataplane.app.pipeline;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSourcePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The per-request carrier threaded through the pipeline: what the caller supplied, plus the facts
 * each stage establishes as the chain proceeds.
 *
 * <p>Immutable — every stage returns a new instance via a {@code with…} method rather than mutating
 * shared state. That is what makes the chain safe to run concurrently on many threads without a
 * lock, and it means a stage physically cannot corrupt a fact an earlier stage established. In
 * particular the tenant resolved at authentication and the credential lease materialized before the
 * provider call are carried forward unchanged; nothing downstream can substitute them.
 *
 * @param inbound what the caller supplied (fixed for the request's lifetime)
 * @param principal the authenticated principal — empty until AUTHN completes
 * @param tenant the resolved tenant scope — empty until AUTHN completes
 * @param route the selected provider route — empty until ROUTER completes
 * @param failover the ordered failover candidates — empty until ROUTER completes
 * @param credential the materialized credential lease — empty until SECRETS completes
 */
public record RequestExecution(
    Inbound inbound,
    Optional<PrincipalContext> principal,
    Optional<TenantContext> tenant,
    Optional<RouteTarget> route,
    List<RouteTarget> failover,
    Optional<CredentialLease> credential) {

  /** Validates the carrier. */
  public RequestExecution {
    Preconditions.requireNonNull(inbound, "inbound");
    Preconditions.requireNonNull(principal, "principal");
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonNull(route, "route");
    failover = failover == null ? List.of() : List.copyOf(failover);
    Preconditions.requireNonNull(credential, "credential");
  }

  /**
   * Everything the caller supplies at ingress. Fixed for the request's lifetime — no stage may
   * alter what was asked for, only decide about it.
   *
   * @param requestId the stable per-request identifier
   * @param requestContext correlation, idempotency, causation and region
   * @param transportIdentity the forwarded credential material to authenticate
   * @param request the canonical request
   * @param requiredCapabilities capabilities the chosen provider must support
   * @param minContextLength the minimum context window required
   * @param requiredCompliance compliance attestations the provider must carry
   * @param costCeilingMicros the per-request cost ceiling
   * @param preferredCandidates candidate ids to prefer, all else equal
   * @param deadline the wall-clock deadline for the whole invocation
   * @param idempotent whether the request may be safely retried
   * @param mode batch or streaming
   * @param outputSchema the compiled output schema — empty for unstructured requests
   * @param streamSource the provider transport stream — empty for unary requests
   * @param operation which call the front door served — the endpoint's own answer, never inferred
   * @param declaredMaxOutputTokens the completion ceiling the caller declared, empty when it
   *     declared none; the authoritative bound both governance and cost projection work from (Doc
   *     22 §23.1)
   * @param piiDeclared whether the caller classified this request as carrying personal data
   */
  public record Inbound(
      RequestId requestId,
      RequestContext requestContext,
      ForwardedTransportIdentity transportIdentity,
      CanonicalRequest request,
      Set<String> requiredCapabilities,
      int minContextLength,
      Set<String> requiredCompliance,
      long costCeilingMicros,
      Set<String> preferredCandidates,
      Instant deadline,
      boolean idempotent,
      Mode mode,
      Optional<CompiledSchema> outputSchema,
      Optional<TransportSourcePort> streamSource,
      Operation operation,
      OptionalLong declaredMaxOutputTokens,
      boolean piiDeclared) {

    /** Validates the inbound request. */
    public Inbound {
      Preconditions.requireNonNull(requestId, "requestId");
      Preconditions.requireNonNull(requestContext, "requestContext");
      Preconditions.requireNonNull(transportIdentity, "transportIdentity");
      Preconditions.requireNonNull(request, "request");
      requiredCapabilities =
          Set.copyOf(Preconditions.requireNonNull(requiredCapabilities, "requiredCapabilities"));
      requiredCompliance =
          Set.copyOf(Preconditions.requireNonNull(requiredCompliance, "requiredCompliance"));
      preferredCandidates =
          Set.copyOf(Preconditions.requireNonNull(preferredCandidates, "preferredCandidates"));
      Preconditions.requireNonNull(deadline, "deadline");
      Preconditions.requireNonNull(mode, "mode");
      Preconditions.requireNonNull(outputSchema, "outputSchema");
      Preconditions.requireNonNull(streamSource, "streamSource");
      Preconditions.requireNonNull(operation, "operation");
      Preconditions.requireNonNull(declaredMaxOutputTokens, "declaredMaxOutputTokens");
    }

    /**
     * Builds an inbound request that predates the governance-admission fields, defaulting the
     * operation to {@link Operation#CHAT}, declaring no output ceiling and no PII classification.
     *
     * <p>Kept so that adding those fields did not become a breaking change for every existing
     * caller and test. Note what the defaults mean at the gate: no declared output ceiling makes
     * token and budget policies unenforceable, so a deployment on this constructor with such
     * policies authored will see refusals rather than silent passes — visible, and the safe
     * direction.
     *
     * @param requestId the stable per-request identifier
     * @param requestContext correlation, idempotency, causation and region
     * @param transportIdentity the forwarded credential material to authenticate
     * @param request the canonical request
     * @param requiredCapabilities capabilities the chosen provider must support
     * @param minContextLength the minimum context window required
     * @param requiredCompliance compliance attestations the provider must carry
     * @param costCeilingMicros the per-request cost ceiling
     * @param preferredCandidates candidate ids to prefer, all else equal
     * @param deadline the wall-clock deadline for the whole invocation
     * @param idempotent whether the request may be safely retried
     * @param mode batch or streaming
     * @param outputSchema the compiled output schema
     * @param streamSource the provider transport stream
     */
    public Inbound(
        final RequestId requestId,
        final RequestContext requestContext,
        final ForwardedTransportIdentity transportIdentity,
        final CanonicalRequest request,
        final Set<String> requiredCapabilities,
        final int minContextLength,
        final Set<String> requiredCompliance,
        final long costCeilingMicros,
        final Set<String> preferredCandidates,
        final Instant deadline,
        final boolean idempotent,
        final Mode mode,
        final Optional<CompiledSchema> outputSchema,
        final Optional<TransportSourcePort> streamSource) {
      this(
          requestId,
          requestContext,
          transportIdentity,
          request,
          requiredCapabilities,
          minContextLength,
          requiredCompliance,
          costCeilingMicros,
          preferredCandidates,
          deadline,
          idempotent,
          mode,
          outputSchema,
          streamSource,
          Operation.CHAT,
          OptionalLong.empty(),
          false);
    }
  }

  /**
   * Starts a fresh execution from the caller's inbound request, with nothing yet established.
   *
   * @param inbound the caller-supplied request
   * @return a new execution carrier
   */
  public static RequestExecution start(final Inbound inbound) {
    return new RequestExecution(
        inbound, Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty());
  }

  /**
   * Records the authenticated identity.
   *
   * @param authenticatedPrincipal the verified principal
   * @param resolvedTenant the resolved tenant scope
   * @return a new carrier carrying the identity
   */
  public RequestExecution withIdentity(
      final PrincipalContext authenticatedPrincipal, final TenantContext resolvedTenant) {
    return new RequestExecution(
        inbound,
        Optional.of(authenticatedPrincipal),
        Optional.of(resolvedTenant),
        route,
        failover,
        credential);
  }

  /**
   * Records the routing decision.
   *
   * @param primary the selected route
   * @param failoverCandidates the ordered failover candidates
   * @return a new carrier carrying the route
   */
  public RequestExecution withRoute(
      final RouteTarget primary, final List<RouteTarget> failoverCandidates) {
    return new RequestExecution(
        inbound, principal, tenant, Optional.of(primary), failoverCandidates, credential);
  }

  /**
   * Records the materialized credential lease.
   *
   * @param lease the credential lease
   * @return a new carrier carrying the lease
   */
  public RequestExecution withCredential(final CredentialLease lease) {
    return new RequestExecution(inbound, principal, tenant, route, failover, Optional.of(lease));
  }
}
