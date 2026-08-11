/**
 * Authentication domain: the pure, deterministic {@code TenantResolver} that maps an authenticated
 * principal to its tenant scope from the pinned tenant-scope snapshot (Doc 37 §10/§TRF), resolved
 * only after successful authentication (Doc 32 §SPT). Framework-free.
 */
package io.reliabilityai.gateway.dataplane.authn.domain;
