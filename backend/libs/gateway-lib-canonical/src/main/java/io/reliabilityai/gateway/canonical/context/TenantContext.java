package io.reliabilityai.gateway.canonical.context;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The tenant context (C7, Doc 33 §10.1), resolved only after successful C6 authentication (Doc 32
 * §SPT, Doc 37 §TRF). Basis of tenant isolation (AD-021). Immutable; transient.
 *
 * @param tenantScope the resolved tenant scope hierarchy
 */
public record TenantContext(TenantScope tenantScope) {

  /** Compact constructor validating the tenant scope presence. */
  public TenantContext {
    Preconditions.requireNonNull(tenantScope, "tenantScope");
  }
}
