package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.CredentialPort;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.SecretsProviderPort;
import java.util.Optional;

/**
 * The production bridge from the secrets module to the provider adapter's {@link CredentialPort}
 * (Doc 26 §17.1).
 *
 * <p>Every acquisition mints a <b>fresh</b> lease from the materialization service at the moment of
 * the provider call. Nothing is cached: not the plaintext key, not the lease, not a derived header.
 * The adapter closes the lease it receives, which zeroizes the material — so a credential's
 * lifetime is bounded by one provider invocation and no longer.
 *
 * <p><b>Fail closed on missing scope.</b> The tenant comes from {@link RequestCredentialScope}. If
 * no scope is bound the request is not in a credential window, and this returns empty rather than
 * guessing a tenant — the adapter then surfaces {@code credential_unavailable}. Guessing here would
 * be a cross-tenant credential leak, which is the worst failure this component could have.
 *
 * <p>Stateless and immutable; safe on platform and virtual threads alike.
 */
public final class SecretsCredentialPort implements CredentialPort {

  private final SecretsProviderPort secrets;
  private final RequestCredentialScope scope;

  /**
   * Creates the bridge.
   *
   * @param secrets the <b>undecorated</b> materialization service — decorating it here would rebind
   *     the request scope and release it early when the adapter closes its lease
   * @param scope the request-scope holder supplying the tenant identity
   */
  public SecretsCredentialPort(
      final SecretsProviderPort secrets, final RequestCredentialScope scope) {
    this.secrets = Preconditions.requireNonNull(secrets, "secrets");
    this.scope = Preconditions.requireNonNull(scope, "scope");
  }

  @Override
  public Optional<CredentialLease> acquire(final RouteTarget routeTarget) {
    Preconditions.requireNonNull(routeTarget, "routeTarget");
    final Optional<RequestCredentialScope.Scope> bound = scope.current();
    if (bound.isEmpty()) {
      return Optional.empty(); // no request scope ⇒ no tenant ⇒ no credential
    }
    final RequestCredentialScope.Scope identity = bound.orElseThrow();

    final SecretsProviderPort.MaterializationResult result =
        secrets.materialize(
            new CredentialRequest(identity.tenantScope(), routeTarget, identity.correlationId()));

    if (result instanceof SecretsProviderPort.MaterializationResult.Leased leased) {
      return Optional.of(leased.lease());
    }
    return Optional.empty();
  }
}
