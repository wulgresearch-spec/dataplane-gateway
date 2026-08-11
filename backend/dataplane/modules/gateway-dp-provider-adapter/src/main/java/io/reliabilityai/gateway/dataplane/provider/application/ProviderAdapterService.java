package io.reliabilityai.gateway.dataplane.provider.application;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.AdapterTelemetryPort;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilitySnapshotPort;
import io.reliabilityai.gateway.dataplane.provider.api.CredentialPort;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTranslator;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTransportPort;
import io.reliabilityai.gateway.dataplane.provider.api.TransportException;
import io.reliabilityai.gateway.dataplane.provider.api.TransportRequest;
import io.reliabilityai.gateway.dataplane.provider.api.TransportResponse;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import java.util.Optional;

/**
 * The provider-neutral <b>Anti-Corruption coordinator</b> (Doc 25 §8) — the reusable adapter body
 * for a single provider, wired with that provider's pure {@link ProviderTranslator} (mapping core)
 * and its {@link ProviderTransportPort} (impure shell). It orchestrates the frozen pipeline
 * <em>consume-capability → acquire-credential → translate-out → transport → translate-in</em> with
 * <b>fail-closed</b> semantics throughout (PA-INV): every branch yields a canonical {@code
 * ProviderInvocationResult}, never a leaked provider shape and never a thrown operational fault.
 *
 * <p><b>Ownership boundaries (Doc 25):</b> it consumes capability snapshots only (§14.1, never
 * authors); consumes short-lived credentials only, applied at the transport call and closed
 * immediately (§33.1); enforces only the handed {@link AttemptBudget} and authors <b>no</b>
 * retry/timeout/failover policy (§17.1 — one {@code invoke} == one attempt, TO-5); does not
 * validate tool calls (§17), guard streams or extract runtime usage (§18/§20.1), meter/price
 * (§22/§23), or branch on provider name (§7.1).
 *
 * <p>Stateless and virtual-thread-safe (AD-021/AD-023): it holds no cross-request business state;
 * the credential lease is bounded to the invocation and zeroized on close (§33.1 CR-4). Telemetry
 * is best-effort and never alters the canonical outcome.
 */
public final class ProviderAdapterService implements ProviderAdapterPort {

  private final ProviderTranslator translator;
  private final ProviderTransportPort transport;
  private final CredentialPort credentialPort;
  private final CapabilitySnapshotPort capabilityPort;
  private final AdapterTelemetryPort telemetry;

  /**
   * Creates the coordinator against its injected ports (AD-002).
   *
   * @param translator the provider-specific pure mapping core (Doc 25 §23.1 DET-1)
   * @param transport the impure transport shell (the only provider-coupled surface, PA-D1)
   * @param credentialPort the short-lived credential seam (Doc 25 §33.1)
   * @param capabilityPort the read-only capability seam (Doc 25 §14.1)
   * @param telemetry the content-free telemetry seam (Doc 25 §35); use {@link
   *     AdapterTelemetryPort#NO_OP} to omit
   */
  public ProviderAdapterService(
      final ProviderTranslator translator,
      final ProviderTransportPort transport,
      final CredentialPort credentialPort,
      final CapabilitySnapshotPort capabilityPort,
      final AdapterTelemetryPort telemetry) {
    this.translator = Preconditions.requireNonNull(translator, "translator");
    this.transport = Preconditions.requireNonNull(transport, "transport");
    this.credentialPort = Preconditions.requireNonNull(credentialPort, "credentialPort");
    this.capabilityPort = Preconditions.requireNonNull(capabilityPort, "capabilityPort");
    this.telemetry = Preconditions.requireNonNull(telemetry, "telemetry");
  }

  @Override
  public ProviderInvocationResult invoke(
      final CanonicalRequest request, final RouteTarget routeTarget, final AttemptBudget budget) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(routeTarget, "routeTarget");
    Preconditions.requireNonNull(budget, "budget");
    final CanonicalModelId model = request.canonicalModelId();
    try {
      return execute(request, routeTarget, budget, model);
    } catch (final RuntimeException unexpected) {
      // Fail-closed backstop: never throw an operational fault out of the ACL (PA-INV).
      return failClosed(model, ErrorCategory.UNKNOWN, "internal_error", false);
    }
  }

  private ProviderInvocationResult execute(
      final CanonicalRequest request,
      final RouteTarget routeTarget,
      final AttemptBudget budget,
      final CanonicalModelId model) {
    // 1. Consume the read-only capability snapshot (Doc 25 §14.1); missing/stale ⇒ fail closed
    // (CAP-5).
    final Optional<CapabilityMapping> maybeCaps = capabilityPort.mappingFor(routeTarget);
    if (maybeCaps.isEmpty()) {
      return failClosed(model, ErrorCategory.UNKNOWN, "capability_snapshot_unavailable", false);
    }
    final CapabilityMapping caps = maybeCaps.get();
    safeInvoked(model, caps);

    // 2. Consume the short-lived credential (Doc 25 §33.1); miss ⇒ fail closed auth_failed (CR-6).
    final Optional<CredentialLease> maybeLease = credentialPort.acquire(routeTarget);
    if (maybeLease.isEmpty()) {
      return failClosed(model, ErrorCategory.AUTH_FAILED, "credential_unavailable", false);
    }

    // Bounded lifetime; zeroized on close (CR-4). Closed in a finally so a close() fault
    // (best-effort
    // zeroization) can never discard an already-computed valid provider response (would fabricate a
    // failure over a real success, PA-INV).
    final CredentialLease lease = maybeLease.get();
    try {
      // 3. translate-out (pure); an untranslatable field ⇒ fail closed malformed (Doc 25 §26).
      final TransportRequest transportRequest;
      try {
        transportRequest = translator.translateOut(request, caps, caps.providerApiVersion());
      } catch (final RuntimeException mappingError) {
        return failClosed(model, ErrorCategory.MALFORMED_RESPONSE, "untranslatable_request", false);
      }

      // 4. transport (impure I/O); exactly one attempt within the handed budget (Doc 25 §17.1
      // TO-5).
      final TransportResponse transportResponse;
      try {
        transportResponse = transport.exchange(transportRequest, lease, budget);
      } catch (final TransportException transportFailure) {
        final CanonicalError error = translator.classifyTransportFailure(transportFailure);
        Preconditions.requireNonNull(error, "classifyTransportFailure result");
        safeFailed(model, error.category());
        return new ProviderInvocationResult.Failed(error);
      }

      // 5. translate-in (pure); an untranslatable response ⇒ fail closed malformed (Doc 25 §26).
      final ProviderInvocationResult result;
      try {
        result = translator.translateIn(transportResponse);
      } catch (final RuntimeException mappingError) {
        return failClosed(
            model, ErrorCategory.MALFORMED_RESPONSE, "untranslatable_response", false);
      }
      Preconditions.requireNonNull(result, "translateIn result");

      if (result instanceof ProviderInvocationResult.Failed failed) {
        safeFailed(model, failed.error().category());
      } else {
        safeSucceeded(model);
      }
      return result;
    } finally {
      safeCloseLease(lease);
    }
  }

  private void safeCloseLease(final CredentialLease lease) {
    try {
      lease.close(); // zeroize (CR-4); best-effort — never overrides a computed invocation outcome
    } catch (final RuntimeException ignored) {
      // a close/zeroization fault must not turn a successful response into a fabricated failure
    }
  }

  private ProviderInvocationResult failClosed(
      final CanonicalModelId model,
      final ErrorCategory category,
      final String codeOpaque,
      final boolean transientError) {
    safeFailed(model, category);
    return new ProviderInvocationResult.Failed(
        new CanonicalError(
            category, transientError ? Boolean.TRUE : Boolean.FALSE, codeOpaque, transientError));
  }

  // --- Best-effort telemetry: never alters the canonical outcome or breaks fail-closed (Doc 25
  // §35).

  private void safeInvoked(final CanonicalModelId model, final CapabilityMapping caps) {
    try {
      telemetry.invoked(model, caps.providerApiVersion(), caps.snapshotVersion());
    } catch (final RuntimeException ignored) {
      // telemetry must never affect the invocation outcome
    }
  }

  private void safeSucceeded(final CanonicalModelId model) {
    try {
      telemetry.succeeded(model);
    } catch (final RuntimeException ignored) {
      // no-op
    }
  }

  private void safeFailed(final CanonicalModelId model, final ErrorCategory category) {
    try {
      telemetry.failed(model, category);
    } catch (final RuntimeException ignored) {
      // no-op
    }
  }
}
