package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Tenant scope — the {@code org → tenant → workspace → project} hierarchy (C7, Doc 33 §10.1).
 *
 * <p>Resolved only after C6 authentication (Doc 30 §IAB-5, Doc 32 §SPT, Doc 37 §TRF); the basis of
 * tenant isolation (AD-021). {@code workspace} and {@code project} are optional (may be null) to
 * represent org/tenant-scoped requests; {@code org} and {@code tenant} are required.
 *
 * @param org the organization id (required)
 * @param tenant the tenant id (required)
 * @param workspace the workspace id (nullable)
 * @param project the project id (nullable)
 */
public record TenantScope(String org, String tenant, String workspace, String project) {

  /** Compact constructor validating the required hierarchy roots. */
  public TenantScope {
    Preconditions.requireNonBlank(org, "org");
    Preconditions.requireNonBlank(tenant, "tenant");
  }

  /**
   * Creates an org+tenant scope with no workspace/project.
   *
   * @param org the organization id
   * @param tenant the tenant id
   * @return the tenant scope
   */
  public static TenantScope of(final String org, final String tenant) {
    return new TenantScope(org, tenant, null, null);
  }
}
