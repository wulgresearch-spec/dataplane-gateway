package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.SecretsProviderPort;
import java.time.Instant;

/**
 * Wraps the credential materialization service so that materializing a credential also publishes
 * the request's tenant identity to {@link RequestCredentialScope}.
 *
 * <p>The pipeline's SECRETS stage already calls {@code materialize} with the full {@link
 * CredentialRequest} — tenant and correlation included — immediately before the provider
 * invocation, and closes the resulting lease immediately after. Binding the scope to that lease's
 * lifetime therefore costs nothing and needs no change to the frozen pipeline: the identity is
 * available exactly while the provider call is in flight, and gone the moment it finishes.
 *
 * <p>Adds no caching and changes no decision. Every call is delegated; the wrapper only observes.
 */
public final class ScopedSecretsProvider implements SecretsProviderPort {

  private final SecretsProviderPort delegate;
  private final RequestCredentialScope scope;

  /**
   * Creates the wrapper.
   *
   * @param delegate the real materialization service
   * @param scope the request-scope holder to publish into
   */
  public ScopedSecretsProvider(
      final SecretsProviderPort delegate, final RequestCredentialScope scope) {
    this.delegate = Preconditions.requireNonNull(delegate, "delegate");
    this.scope = Preconditions.requireNonNull(scope, "scope");
  }

  @Override
  public MaterializationResult materialize(final CredentialRequest request) {
    Preconditions.requireNonNull(request, "request");
    final MaterializationResult result = delegate.materialize(request);
    if (!(result instanceof MaterializationResult.Leased leased)) {
      return result; // a refusal publishes no identity
    }
    final RequestCredentialScope.Binding binding;
    try {
      binding = scope.bind(request.tenantScope(), request.correlationId());
    } catch (final IllegalStateException alreadyBound) {
      // Never leave a materialized lease unreleased just because the scope could not be bound.
      leased.lease().close();
      throw alreadyBound;
    }
    return new MaterializationResult.Leased(new ScopeReleasingLease(leased.lease(), binding));
  }

  /**
   * Releases the identity scope when the pipeline closes its lease, so the scope can never outlive
   * the provider invocation it was bound for.
   */
  private static final class ScopeReleasingLease implements CredentialLease {

    private final CredentialLease delegate;
    private final RequestCredentialScope.Binding binding;

    ScopeReleasingLease(
        final CredentialLease delegate, final RequestCredentialScope.Binding binding) {
      this.delegate = delegate;
      this.binding = binding;
    }

    @Override
    public String leaseId() {
      return delegate.leaseId();
    }

    @Override
    public io.reliabilityai.gateway.canonical.identity.TenantScope tenantScope() {
      return delegate.tenantScope();
    }

    @Override
    public Instant notAfter() {
      return delegate.notAfter();
    }

    @Override
    public boolean active() {
      return delegate.active();
    }

    @Override
    public void use(final SecretConsumer consumer) {
      delegate.use(consumer);
    }

    @Override
    public void close() {
      try {
        binding.close(); // release the identity first: it must not survive a failing zeroization
      } finally {
        delegate.close();
      }
    }
  }
}
