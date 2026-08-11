package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Identifies one node of the governance hierarchy: a {@link PolicyScope} plus the id of the thing
 * at that scope (Doc 21 GV-D3).
 *
 * <p>This is the key policy documents are stored under and the key the request path looks them up
 * by, so it is deliberately a small immutable value with structural equality — an {@code
 * EnumMap}-free {@code HashMap} lookup on this record is the O(1) step that keeps snapshot
 * resolution off the critical path's cost budget.
 *
 * <p>The id is opaque. The engine never parses it, never derives another scope's id from it, and
 * never compares it to a literal, which is what keeps one tenant's policy structurally unreachable
 * from another's evaluation (AD-021).
 *
 * @param scope the hierarchy level
 * @param id the identifier of the node at that level ({@code "-"} for the single GLOBAL node)
 */
public record PolicyScopeRef(PolicyScope scope, String id) {

  /** The id used for the one and only global node — there is nothing above it to distinguish. */
  public static final String GLOBAL_ID = "-";

  /** Validates the reference. */
  public PolicyScopeRef {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonBlank(id, "id");
  }

  /**
   * The single global node.
   *
   * @return the global scope reference
   */
  public static PolicyScopeRef global() {
    return new PolicyScopeRef(PolicyScope.GLOBAL, GLOBAL_ID);
  }

  /**
   * Creates a reference at the given scope.
   *
   * @param scope the hierarchy level
   * @param id the node identifier
   * @return the scope reference
   */
  public static PolicyScopeRef of(final PolicyScope scope, final String id) {
    return new PolicyScopeRef(scope, id);
  }
}
