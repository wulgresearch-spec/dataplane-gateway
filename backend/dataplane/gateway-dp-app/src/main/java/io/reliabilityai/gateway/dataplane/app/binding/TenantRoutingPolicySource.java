package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicy;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicyPort;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the per-tenant {@link RoutingPolicy} from an immutable operator-supplied table, falling
 * back to a default policy when the tenant has no override (Doc 19). Purely a lookup — all
 * weighting and eligibility logic stays inside the router.
 */
public final class TenantRoutingPolicySource implements RoutingPolicyPort {

  private final RoutingPolicy defaultPolicy;
  private final Map<TenantScope, RoutingPolicy> overrides;

  /**
   * Creates the policy source.
   *
   * @param defaultPolicy the policy applied to tenants without an override
   * @param overrides the immutable per-tenant overrides (may be empty, never null)
   */
  public TenantRoutingPolicySource(
      final RoutingPolicy defaultPolicy, final Map<TenantScope, RoutingPolicy> overrides) {
    this.defaultPolicy = Preconditions.requireNonNull(defaultPolicy, "defaultPolicy");
    this.overrides = Map.copyOf(Preconditions.requireNonNull(overrides, "overrides"));
  }

  @Override
  public Optional<RoutingPolicy> resolve(final TenantScope tenantScope) {
    if (tenantScope == null) {
      return Optional.of(defaultPolicy);
    }
    return Optional.of(overrides.getOrDefault(tenantScope, defaultPolicy));
  }
}
