package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The level of the governance hierarchy a policy document is attached to (Doc 21 GV-D2/GV-D3).
 *
 * <p>Declaration order <b>is</b> specificity order, least specific first. The merge walks the chain
 * in this order, so a policy attached higher up is always applied before — and can therefore never
 * be loosened by — one attached lower down. {@link #ordinal()} is the merge sequence and is relied
 * upon by {@code PolicyMerge}; reordering these constants changes the hierarchy, which is why the
 * order is asserted by test rather than left to convention.
 *
 * <p>A request resolves to at most one document per scope. Scopes a request does not populate (a
 * call made with no workspace, say) are simply absent from its chain — absence contributes nothing,
 * and in a most-restrictive-wins merge contributing nothing is the safe direction.
 */
public enum PolicyScope {

  /** Platform-wide policy, applied to every request on the node. */
  GLOBAL,

  /** Policy for one organization — the outermost tenant boundary (Doc 33 §10.1). */
  ORGANIZATION,

  /** Policy for one workspace inside an organization. */
  WORKSPACE,

  /** Policy for one project inside a workspace. */
  PROJECT,

  /** Policy for one deployment environment (production, staging) of a project. */
  ENVIRONMENT,

  /** Policy attached to the API key the call authenticated with — key-level least privilege. */
  API_KEY,

  /** Policy attached to a human principal. */
  USER,

  /** Policy attached to a non-human principal. */
  SERVICE_ACCOUNT,

  /** Constraints the caller asked for on this single request — self-restriction only. */
  REQUEST;

  /**
   * Whether this scope is more specific than another, i.e. merged later.
   *
   * @param other the scope to compare against
   * @return {@code true} when this scope sits lower in the hierarchy
   */
  public boolean isMoreSpecificThan(final PolicyScope other) {
    return ordinal() > other.ordinal();
  }
}
