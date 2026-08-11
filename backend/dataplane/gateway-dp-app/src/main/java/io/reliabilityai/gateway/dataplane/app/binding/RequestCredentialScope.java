package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * The request-scoped tenant identity the provider adapter needs but cannot be handed.
 *
 * <p><b>Why this exists.</b> {@code CredentialPort.acquire(RouteTarget)} is a frozen contract that
 * carries no tenant and no correlation id, while {@code SecretsProviderPort.materialize} requires
 * both. The pipeline knows the tenant, but the pipeline is frozen too. This holder closes that gap
 * without touching either contract: the identity is bound when the pipeline materializes its own
 * credential (the SECRETS stage) and released when that lease closes, which is exactly the window
 * in which the provider invocation happens.
 *
 * <p><b>What is stored — and what is not.</b> Only the tenant scope and correlation id. No key
 * material, no lease, no token, ever. This is an identity scope, not a credential cache.
 *
 * <p><b>Thread and virtual-thread safety.</b> Backed by a {@link ThreadLocal}, so each carrier
 * thread — platform or virtual — sees only its own binding and two concurrent requests can never
 * observe each other's tenant. The binding is removed on close rather than overwritten, so a pooled
 * carrier thread cannot leak one request's tenant into the next.
 */
public final class RequestCredentialScope {

  /**
   * The identity in scope for the current request.
   *
   * @param tenantScope the resolved tenant
   * @param correlationId the request correlation id
   */
  public record Scope(TenantScope tenantScope, CorrelationId correlationId) {

    /** Validates the scope. */
    public Scope {
      Preconditions.requireNonNull(tenantScope, "tenantScope");
      Preconditions.requireNonNull(correlationId, "correlationId");
    }
  }

  /** A binding that must be closed to release the scope. */
  public interface Binding extends AutoCloseable {
    @Override
    void close();
  }

  private final ThreadLocal<Scope> current = new ThreadLocal<>();

  /**
   * Binds an identity for the calling thread until the returned handle is closed.
   *
   * <p>Nesting is rejected rather than stacked: a second bind on the same thread means two requests
   * are interleaving on one carrier, which would make the credential lookup ambiguous. Failing
   * loudly beats silently serving one tenant's credential to another.
   *
   * @param tenantScope the resolved tenant
   * @param correlationId the request correlation id
   * @return the handle releasing the binding
   * @throws IllegalStateException if a scope is already bound on this thread
   */
  public Binding bind(final TenantScope tenantScope, final CorrelationId correlationId) {
    if (current.get() != null) {
      throw new IllegalStateException("a credential scope is already bound on this thread");
    }
    current.set(new Scope(tenantScope, correlationId));
    return current::remove;
  }

  /**
   * The identity in scope for the calling thread.
   *
   * @return the scope, or empty when none is bound
   */
  public Optional<Scope> current() {
    return Optional.ofNullable(current.get());
  }
}
