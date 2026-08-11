package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * Where a memory lives, and who may reach it (AD-026 §9).
 *
 * <p><b>Mandatory and complete on every record</b> (MEM-14). There is no constructor that produces
 * a partial scope, so "forgot to set the tenant" is not a reachable state — it is a compile error
 * or a rejected construction, never a record that quietly belongs to nobody.
 *
 * <p>Four isolation levels are expressed here:
 *
 * <ul>
 *   <li><b>Tenant</b> — {@code tenant.org()} + {@code tenant.tenant()}. A hard partition. Two
 *       scopes differing here have no intersection at all.
 *   <li><b>Workspace</b> — {@code tenant.workspace()}. A sub-partition within a tenant.
 *   <li><b>User</b> — {@code owner}. Present means the record is private to that principal; absent
 *       means it is shared within its workspace.
 *   <li><b>Role</b> — deliberately <em>not</em> here. A role gates which types and operations a
 *       caller may request, checked at admission; it is not a row predicate (AD-026 §9.3). Encoding
 *       roles into every query would push an authorization model into nine adapters and guarantee
 *       the nine disagree.
 * </ul>
 *
 * @param tenant the org/tenant/workspace/project coordinates; never null
 * @param owner the principal a record is private to, or empty when shared within the workspace
 * @param partition an opaque session or task key, or empty when the record belongs to no partition
 */
public record MemoryScope(TenantScope tenant, Optional<PrincipalId> owner, String partition) {

  /** The value meaning "no partition". Empty rather than null so the field is never absent. */
  public static final String NO_PARTITION = "";

  /**
   * Validates the scope.
   *
   * @param tenant the tenant coordinates
   * @param owner the owning principal, if any
   * @param partition the session or task key, or {@link #NO_PARTITION}
   */
  public MemoryScope {
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonNull(owner, "owner");
    Preconditions.requireNonNull(partition, "partition");
  }

  /**
   * A scope covering a whole tenant: no owner, no partition.
   *
   * @param tenant the tenant coordinates
   * @return the scope
   */
  public static MemoryScope ofTenant(final TenantScope tenant) {
    return new MemoryScope(tenant, Optional.empty(), NO_PARTITION);
  }

  /**
   * A scope private to one principal.
   *
   * @param tenant the tenant coordinates
   * @param owner the owning principal
   * @return the scope
   */
  public static MemoryScope ofUser(final TenantScope tenant, final PrincipalId owner) {
    return new MemoryScope(tenant, Optional.of(owner), NO_PARTITION);
  }

  /**
   * A scope private to one principal within one session or task.
   *
   * @param tenant the tenant coordinates
   * @param owner the owning principal
   * @param partition the session or task key
   * @return the scope
   */
  public static MemoryScope ofPartition(
      final TenantScope tenant, final PrincipalId owner, final String partition) {
    Preconditions.requireNonBlank(partition, "partition");
    return new MemoryScope(tenant, Optional.of(owner), partition);
  }

  /**
   * Reports whether this scope is private to a principal.
   *
   * @return true when an owner is present
   */
  public boolean userPrivate() {
    return owner.isPresent();
  }

  /**
   * Reports whether this scope names a session or task.
   *
   * @return true when a partition is present
   */
  public boolean partitioned() {
    return !partition.isEmpty();
  }

  /**
   * Returns the workspace, or empty when the scope covers a whole tenant.
   *
   * @return the workspace name, if any
   */
  public Optional<String> workspace() {
    return Optional.ofNullable(tenant.workspace());
  }

  /**
   * Reports whether two scopes are in the same hard tenant partition.
   *
   * <p>Organisation <em>and</em> tenant must match. This is the predicate every cross-tenant
   * refusal is written against, and it deliberately ignores workspace, owner and partition — those
   * are narrower questions asked only once this one has been answered yes.
   *
   * @param other the scope to compare
   * @return true when both belong to the same tenant
   */
  public boolean sameTenantAs(final MemoryScope other) {
    Preconditions.requireNonNull(other, "other");
    return tenant.org().equals(other.tenant.org()) && tenant.tenant().equals(other.tenant.tenant());
  }

  /**
   * Narrows a requested scope to what a caller is actually authorized for (MEM-15).
   *
   * <p><b>This is the single most important method in the module.</b> It runs <em>before</em> a
   * query reaches an adapter, so the adapter is never handed a query that could match another
   * tenant's records. Filtering afterwards would mean the adapter had already read them: discarded,
   * perhaps, but read, loggable, and present in a heap dump.
   *
   * <p>The rules, in order:
   *
   * <ol>
   *   <li>Different tenant → <b>no intersection</b>. Not an empty result — a refusal.
   *   <li>Caller pinned to a workspace → the result is that workspace, whatever was requested.
   *   <li>Caller pinned to an owner → the result is that owner, whatever was requested. A caller
   *       restricted to their own memories cannot ask for someone else's by naming them.
   *   <li>Caller unrestricted on a dimension → the request's value is kept.
   * </ol>
   *
   * <p>Every rule can only <em>tighten</em>. There is no input for which this returns a scope wider
   * than the caller's own, which is the property the isolation tests assert exhaustively.
   *
   * @param caller the scope the caller is authorized for
   * @return the narrowed scope, or empty when the two do not intersect at all
   */
  public Optional<MemoryScope> narrowTo(final MemoryScope caller) {
    Preconditions.requireNonNull(caller, "caller");

    if (!sameTenantAs(caller)) {
      return Optional.empty();
    }

    final String callerWorkspace = caller.tenant.workspace();
    final String requestedWorkspace = tenant.workspace();
    if (callerWorkspace != null
        && requestedWorkspace != null
        && !callerWorkspace.equals(requestedWorkspace)) {
      return Optional.empty();
    }
    final String workspace = callerWorkspace != null ? callerWorkspace : requestedWorkspace;

    final String callerProject = caller.tenant.project();
    final String requestedProject = tenant.project();
    if (callerProject != null
        && requestedProject != null
        && !callerProject.equals(requestedProject)) {
      return Optional.empty();
    }
    final String project = callerProject != null ? callerProject : requestedProject;

    // The owner dimension is a privacy boundary, and it is symmetric in both directions.
    //
    // A caller pinned to an owner may only ever see that owner's records. Asking for another
    // principal's memories is not an error the caller is told about — it is simply narrowed away,
    // because telling them would confirm that the other principal exists (AD-026 §10.3).
    //
    // A caller with *no* owner is authorized for shared records only, and may not narrow *into*
    // somebody's private scope. That is the less obvious half, and omitting it made this method
    // disagree with visibleTo — which a generated cross-product of every scope pair caught. Without
    // it, any tenant-wide caller could read every user's private memories simply by naming them,
    // which would make user isolation decorative. Reaching a user's private records requires being
    // authorized as that user; an elevated administrative capability is a separate, explicit grant
    // rather than a consequence of leaving a field empty.
    final Optional<PrincipalId> resolvedOwner;
    if (caller.owner.isPresent()) {
      if (owner.isPresent() && !owner.get().equals(caller.owner.get())) {
        return Optional.empty();
      }
      resolvedOwner = caller.owner;
    } else {
      if (owner.isPresent()) {
        return Optional.empty();
      }
      resolvedOwner = Optional.empty();
    }

    if (caller.partitioned() && partitioned() && !caller.partition.equals(partition)) {
      return Optional.empty();
    }
    final String resolvedPartition = caller.partitioned() ? caller.partition : partition;

    return Optional.of(
        new MemoryScope(
            new TenantScope(tenant.org(), tenant.tenant(), workspace, project),
            resolvedOwner,
            resolvedPartition));
  }

  /**
   * Reports whether a record in this scope is visible to a caller in the given scope.
   *
   * <p>Used to re-verify what an adapter returned (AD-026 §6.1). An adapter is trusted to answer a
   * query, not to enforce policy; a record that comes back outside the narrowed scope is an adapter
   * defect, and the runtime drops it rather than serving it.
   *
   * @param caller the caller's authorized scope
   * @return true when the caller may see a record in this scope
   */
  public boolean visibleTo(final MemoryScope caller) {
    Preconditions.requireNonNull(caller, "caller");
    if (!sameTenantAs(caller)) {
      return false;
    }
    if (!dimensionVisible(caller.tenant.workspace(), tenant.workspace())) {
      return false;
    }
    if (!dimensionVisible(caller.tenant.project(), tenant.project())) {
      return false;
    }
    if (caller.owner.isPresent() && owner.isPresent() && !owner.equals(caller.owner)) {
      return false;
    }
    // A user-private record is invisible to a caller who is not that user, even inside the
    // workspace.
    if (owner.isPresent() && caller.owner.isEmpty()) {
      return false;
    }
    // A partition is an organisational dimension, not a privacy boundary — deliberately unlike the
    // owner above. A caller with no partition sees every partition it is otherwise entitled to,
    // because sessions and tasks group memories rather than protect them. Anything that needs
    // protecting is protected by tenant, workspace or owner.
    return !caller.partitioned() || !partitioned() || caller.partition.equals(partition);
  }

  /**
   * A caller unrestricted on a dimension sees every value of it; a pinned caller sees only its own.
   */
  private static boolean dimensionVisible(final String callerValue, final String recordValue) {
    return callerValue == null || callerValue.equals(recordValue);
  }

  /**
   * Returns the scope chain from broadest to narrowest, for policy resolution (AD-026 §8.2).
   *
   * <p>Order matters: policy merges along this chain and each step may only tighten, so a chain in
   * the wrong order would let a narrow scope loosen a broad one.
   *
   * @return the chain, always beginning with the whole tenant
   */
  public java.util.List<MemoryScope> chain() {
    final java.util.List<MemoryScope> chain = new java.util.ArrayList<>(4);
    final TenantScope org = new TenantScope(tenant.org(), tenant.tenant(), null, null);
    chain.add(new MemoryScope(org, Optional.empty(), NO_PARTITION));

    if (tenant.workspace() != null) {
      chain.add(
          new MemoryScope(
              new TenantScope(tenant.org(), tenant.tenant(), tenant.workspace(), null),
              Optional.empty(),
              NO_PARTITION));
    }
    if (owner.isPresent()) {
      chain.add(new MemoryScope(tenant, owner, NO_PARTITION));
    }
    if (partitioned()) {
      chain.add(this);
    }
    return java.util.Collections.unmodifiableList(chain);
  }

  /**
   * Returns a stable string used as an adapter partition key.
   *
   * <p>Deterministic and collision-free across the four dimensions: every component is
   * length-prefixed, so no combination of values can produce the same key as a different
   * combination.
   *
   * @return the partition key
   */
  public String key() {
    final StringBuilder key = new StringBuilder(64);
    append(key, tenant.org());
    append(key, tenant.tenant());
    append(key, tenant.workspace());
    append(key, tenant.project());
    append(key, owner.map(PrincipalId::value).orElse(null));
    append(key, partition.isEmpty() ? null : partition);
    return key.toString();
  }

  private static void append(final StringBuilder key, final String value) {
    if (value == null) {
      key.append("-:");
    } else {
      key.append(value.length()).append(':').append(value).append(';');
    }
  }
}
