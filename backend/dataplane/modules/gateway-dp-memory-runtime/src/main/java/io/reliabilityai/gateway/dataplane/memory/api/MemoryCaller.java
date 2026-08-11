package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who is asking, and what they are allowed to ask for (AD-026 §9).
 *
 * <p>Constructed by the composition root from the authenticated request, <b>never</b> from anything
 * in a payload. This is the whole of T2 (cross-tenant write) mitigation: a caller cannot state
 * which tenant it belongs to, because nothing in the write request is consulted for it.
 *
 * <p>{@code authorizedScope} is a ceiling. Every requested scope is narrowed against it before an
 * adapter is touched (MEM-15), and narrowing can only tighten.
 *
 * @param principal the authenticated principal
 * @param authorizedScope the widest scope this caller may reach
 * @param roles the caller's roles, which gate operations and types rather than rows (AD-026 §9.3)
 * @param permittedTypes which memory kinds this caller may touch at all
 * @param correlationId the caller's correlation, passed through to audit unchanged
 */
public record MemoryCaller(
    PrincipalId principal,
    MemoryScope authorizedScope,
    Set<String> roles,
    Set<MemoryType> permittedTypes,
    CorrelationId correlationId) {

  /**
   * Validates and canonicalises the caller.
   *
   * @param principal the authenticated principal
   * @param authorizedScope the scope ceiling
   * @param roles the caller's roles
   * @param permittedTypes the permitted memory kinds
   * @param correlationId the caller correlation
   */
  public MemoryCaller {
    Preconditions.requireNonNull(principal, "principal");
    Preconditions.requireNonNull(authorizedScope, "authorizedScope");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(roles, "roles");
    Preconditions.requireNonNull(permittedTypes, "permittedTypes");
    // Sorted and frozen, so an audit digest over a caller is stable across nodes.
    roles = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(roles));
    permittedTypes =
        permittedTypes.isEmpty()
            ? java.util.Collections.unmodifiableSet(EnumSet.noneOf(MemoryType.class))
            : java.util.Collections.unmodifiableSet(EnumSet.copyOf(permittedTypes));
  }

  /**
   * A caller permitted every memory type within one scope.
   *
   * @param principal the authenticated principal
   * @param scope the scope ceiling
   * @param correlationId the caller correlation
   * @return the caller
   */
  public static MemoryCaller of(
      final PrincipalId principal, final MemoryScope scope, final CorrelationId correlationId) {
    return new MemoryCaller(
        principal, scope, Set.of(), EnumSet.allOf(MemoryType.class), correlationId);
  }

  /**
   * Reports whether this caller may touch a memory kind at all.
   *
   * <p>The role check, expressed as a capability rather than a row predicate. A denial here is a
   * refusal at admission, before any scope is narrowed and before any adapter is asked.
   *
   * @param type the memory kind
   * @return true when the caller may use it
   */
  public boolean mayUse(final MemoryType type) {
    Preconditions.requireNonNull(type, "type");
    return permittedTypes.contains(type);
  }

  /**
   * Reports whether this caller may touch every one of a set of memory kinds.
   *
   * @param types the memory kinds
   * @return true when all are permitted
   */
  public boolean mayUseAll(final Set<MemoryType> types) {
    Preconditions.requireNonNull(types, "types");
    return permittedTypes.containsAll(types);
  }

  /**
   * Reports whether the caller holds a role.
   *
   * @param role the role name
   * @return true when held
   */
  public boolean hasRole(final String role) {
    Preconditions.requireNonNull(role, "role");
    return roles.contains(role);
  }

  /**
   * Narrows a requested scope against this caller's ceiling.
   *
   * @param requested the scope the caller asked about
   * @return the narrowed scope, or empty when the two do not intersect
   */
  public java.util.Optional<MemoryScope> narrow(final MemoryScope requested) {
    Preconditions.requireNonNull(requested, "requested");
    return requested.narrowTo(authorizedScope);
  }
}
