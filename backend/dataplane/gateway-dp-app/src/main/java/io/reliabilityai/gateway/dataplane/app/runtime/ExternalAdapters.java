package io.reliabilityai.gateway.dataplane.app.runtime;

import io.reliabilityai.gateway.dataplane.authn.api.IdentityVerifierPort;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilitySnapshotPort;
import io.reliabilityai.gateway.dataplane.provider.api.CredentialPort;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTranslator;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTransportPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.ProviderGenerationPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaValidatorPort;
import io.reliabilityai.gateway.ports.GovernancePort;
import java.util.Optional;

/**
 * The seams whose production implementations live outside this repository, supplied explicitly by
 * the operator. Each is optional, and <b>what is present here decides which mandatory pipeline
 * stages the startup validator can consider bound</b> (AD-018).
 *
 * <p>This is the honesty boundary of the composition root. The runtime deliberately does not invent
 * a stand-in for any of these: a fake HTTP transport or a permissive schema validator would let the
 * gateway activate a pipeline that silently does not do its job, which is precisely the bypass the
 * non-bypass invariant exists to prevent. Absent an adapter, the stage stays unbound and startup
 * fails closed with a named diagnostic.
 *
 * <p>Wiring is derived, not declared: the operator cannot assert a stage is bound while leaving its
 * adapter null, so the validator's input cannot drift from the object graph actually constructed.
 *
 * @param ingress the transport ingress lifecycle the runtime starts last and stops first (INGRESS)
 * @param identityVerifier verifies a forwarded transport identity against the key snapshot (AUTHN)
 * @param governance the policy decision point authorizing each request (GOVERNANCE)
 * @param providerTransport performs the provider HTTP/gRPC exchange (ADAPTER)
 * @param providerTranslator translates provider wire responses to canonical form (ADAPTER)
 * @param credentialPort acquires a request-scoped credential lease for a route (ADAPTER)
 * @param capabilitySnapshot resolves the pinned provider API version for a route (ADAPTER)
 * @param schemaValidator validates output against a compiled JSON schema (SCHEMA_LOCK)
 * @param providerGeneration drives constrained re-generation attempts (SCHEMA_LOCK)
 */
public record ExternalAdapters(
    Optional<IngressLifecycle> ingress,
    Optional<IdentityVerifierPort> identityVerifier,
    Optional<GovernancePort> governance,
    Optional<ProviderTransportPort> providerTransport,
    Optional<ProviderTranslator> providerTranslator,
    Optional<CredentialPort> credentialPort,
    Optional<CapabilitySnapshotPort> capabilitySnapshot,
    Optional<SchemaValidatorPort> schemaValidator,
    Optional<ProviderGenerationPort> providerGeneration) {

  /** Validates that every seam is explicitly present-or-absent, never null. */
  public ExternalAdapters {
    if (ingress == null
        || identityVerifier == null
        || governance == null
        || providerTransport == null
        || providerTranslator == null
        || credentialPort == null
        || capabilitySnapshot == null
        || schemaValidator == null
        || providerGeneration == null) {
      throw new IllegalArgumentException("external adapter seams must be Optional, never null");
    }
  }

  /**
   * The substrate-only wiring: no external adapter supplied. Startup fails closed at the pipeline
   * gate, which is the correct behaviour for a node that cannot yet reach a provider.
   *
   * @return an all-absent seam set
   */
  public static ExternalAdapters none() {
    return new ExternalAdapters(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Whether every seam the provider-adapter stage needs is present.
   *
   * @return {@code true} if transport, translator, credentials and capability mapping are all
   *     supplied
   */
  public boolean providerAdapterComplete() {
    return providerTransport.isPresent()
        && providerTranslator.isPresent()
        && credentialPort.isPresent()
        && capabilitySnapshot.isPresent();
  }

  /**
   * Whether every seam the schema-lock stage needs is present.
   *
   * @return {@code true} if both the validator and the generation driver are supplied
   */
  public boolean schemaLockComplete() {
    return schemaValidator.isPresent() && providerGeneration.isPresent();
  }
}
