package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;
import java.util.TreeSet;

/**
 * A run's immutable security context (AD-025 §61, AGT-26).
 *
 * <p>Fixed at admission and never widened. The capability set is a <em>ceiling</em>: each step's
 * session is opened with only the capabilities that step declares, so the run holds at step 40 no
 * more authority than the ceiling and each session holds far less (AD-025 §61.2).
 *
 * <p><b>Nothing widens this, including a human approval.</b> AD-025 P-3: approval authorizes one
 * use of one capability in one step. If approval could widen the set, a single approval early in a
 * long run would leave the run permanently more powerful — and the approver almost certainly did
 * not intend to authorize the remaining thirty-nine steps.
 *
 * <p>Authorization is never cached here. The context says who the run is, not what it was
 * previously allowed; every pipeline execution re-authorizes from scratch through the governance
 * stage.
 *
 * @param principal the authenticated principal the run acts as
 * @param tenant the tenant the run is partitioned into
 * @param correlationId the caller-supplied correlation, passed through unchanged
 * @param capabilities the capability ceiling, held in a deterministic order for stable digests
 */
public record RunSecurityContext(
    PrincipalId principal,
    TenantScope tenant,
    CorrelationId correlationId,
    Set<String> capabilities) {

  /**
   * Validates and canonicalises the context.
   *
   * <p>The capability set is copied into a sorted, unmodifiable set. Sorting is not cosmetic: the
   * run's digest and its serialized journal record must be byte-identical across nodes, and {@code
   * Set.copyOf} gives no ordering guarantee.
   *
   * @param principal the authenticated principal
   * @param tenant the tenant scope
   * @param correlationId the caller correlation
   * @param capabilities the capability ceiling
   */
  public RunSecurityContext {
    Preconditions.requireNonNull(principal, "principal");
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(capabilities, "capabilities");
    capabilities = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(capabilities));
  }

  /**
   * Reports whether the ceiling permits a capability.
   *
   * @param capability the capability a step wants
   * @return true when the run may grant it to a step
   */
  public boolean permits(final String capability) {
    Preconditions.requireNonBlank(capability, "capability");
    return capabilities.contains(capability);
  }

  /**
   * Reports whether the ceiling permits every capability in a set.
   *
   * @param required the capabilities a step declares
   * @return true when all are within the ceiling
   */
  public boolean permitsAll(final Set<String> required) {
    Preconditions.requireNonNull(required, "required");
    return capabilities.containsAll(required);
  }

  /**
   * Returns the capabilities a step actually receives: the intersection of the ceiling and its
   * request.
   *
   * <p>Intersection, not the request. A step asking for something outside the ceiling does not get
   * it and does not fail here — {@link #permitsAll} is the check, and this is the grant.
   *
   * @param requested the capabilities the step declared
   * @return the granted subset, in deterministic order
   */
  public Set<String> grantFor(final Set<String> requested) {
    Preconditions.requireNonNull(requested, "requested");
    final TreeSet<String> granted = new TreeSet<>(capabilities);
    granted.retainAll(requested);
    return java.util.Collections.unmodifiableSortedSet(granted);
  }

  /**
   * Returns a context for a child run: same identity, capabilities narrowed (AD-025 P-2).
   *
   * <p>Monotone narrowing is what stops a parent escaping its own restrictions by delegating to a
   * child with broader grants. It is the composition-level form of the governance engine's
   * most-restrictive-wins merge.
   *
   * @param requested the capabilities the child asked for
   * @return a context whose ceiling is a subset of this one's
   */
  public RunSecurityContext narrowTo(final Set<String> requested) {
    return new RunSecurityContext(principal, tenant, correlationId, grantFor(requested));
  }

  /**
   * Reports whether another context belongs to the same tenant.
   *
   * <p>Cross-tenant delegation is refused at admission (AD-025 §63); this is the predicate that
   * refusal is written against.
   *
   * @param other the other context
   * @return true when both are in the same tenant scope
   */
  public boolean sameTenantAs(final RunSecurityContext other) {
    Preconditions.requireNonNull(other, "other");
    return tenant.equals(other.tenant);
  }
}
