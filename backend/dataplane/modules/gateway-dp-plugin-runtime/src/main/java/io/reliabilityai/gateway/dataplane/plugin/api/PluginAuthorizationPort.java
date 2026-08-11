package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;

/**
 * Decides whether a tenant may run a given plugin (Doc 28 §36.1, AD-019).
 *
 * <p>This is a <b>second</b> authorization, distinct from the request's. The pipeline's GOVERNANCE
 * stage decides whether the request may be served; this decides whether this tenant is entitled to
 * this plugin. They are different questions with different answers, and collapsing them would mean
 * either that any admitted request may run any plugin, or that a plugin entitlement can veto a
 * request the Policy Enforcement Point already permitted.
 *
 * <p>There is a genuine ordering constraint behind the separation: a plugin bound at {@code
 * CLASSIFICATION} runs <em>before</em> the GOVERNANCE stage decides, so the request-level decision
 * does not exist yet when that plugin is dispatched. This port is what makes plugin execution
 * governed anyway.
 *
 * <p>Deny-by-default, exactly like the Policy Enforcement Point: an implementation that cannot
 * reach its policy must deny (AD-019).
 */
public interface PluginAuthorizationPort {

  /**
   * Whether the tenant may execute the plugin.
   *
   * @param tenantScope the tenant the invocation is attributed to
   * @param descriptor the plugin being dispatched
   * @return the decision
   */
  Decision authorize(TenantScope tenantScope, PluginDescriptor descriptor);

  /** The plugin-level authorization decision. */
  enum Decision {
    /** The tenant is entitled to run this plugin. */
    PERMIT,
    /** The tenant is not entitled to it. */
    DENY,
    /** Policy could not be evaluated. Treated as DENY (AD-019 deny-by-default). */
    INDETERMINATE;

    /**
     * Whether execution may proceed.
     *
     * @return true only for {@link #PERMIT}
     */
    public boolean permitted() {
      return this == PERMIT;
    }
  }

  /**
   * The deny-all default.
   *
   * <p>Deliberately the safe direction. A node with no plugin policy wired runs no plugins, rather
   * than running all of them.
   */
  PluginAuthorizationPort DENY_ALL = (tenantScope, descriptor) -> Decision.DENY;

  /**
   * Permits every tenant to run every registered plugin.
   *
   * <p>For single-tenant nodes and tests. Named so that choosing it is a visible decision in the
   * composition root rather than an accident.
   */
  PluginAuthorizationPort PERMIT_ALL = (tenantScope, descriptor) -> Decision.PERMIT;
}
