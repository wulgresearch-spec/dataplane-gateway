package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;

/**
 * Works out which nodes of the governance hierarchy a request is governed by.
 *
 * <p>The organization, workspace and project nodes come straight from the authenticated tenant
 * scope and are never in doubt. The rest — which API key, which principal, which deployment
 * environment — are assertions carried in the authenticated identity, and the names those
 * assertions travel under differ per issuer. This seam is where an operator states that mapping,
 * rather than the gateway inferring it from claim names it happens to recognise.
 *
 * <p>That matters more than it looks. A resolver that guessed wrong would attach a request to the
 * wrong API-key node and enforce a different tenant's key-level policy against it — a cross-tenant
 * policy leak produced entirely by inference. Making the mapping explicit means a wrong mapping is
 * a configuration error someone wrote down, not an emergent surprise.
 */
@FunctionalInterface
public interface GovernanceScopeResolver {

  /**
   * Resolves the hierarchy address for one authenticated request.
   *
   * @param principal the authenticated principal
   * @param tenant the resolved tenant scope
   * @return the scope chain the request is governed by
   */
  ScopeChain resolve(PrincipalContext principal, TenantContext tenant);
}
