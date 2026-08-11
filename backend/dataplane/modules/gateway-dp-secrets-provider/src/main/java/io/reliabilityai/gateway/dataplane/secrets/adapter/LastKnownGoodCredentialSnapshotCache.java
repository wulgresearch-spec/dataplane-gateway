package io.reliabilityai.gateway.dataplane.secrets.adapter;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.secrets.api.CredentialMaterialSource;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretSnapshotPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The data-plane last-known-good credential-snapshot cache — the frozen DP Secret-cache node (Doc
 * 26 §12(a)/§13/§13.1, AD-017/AD-022). Holds one pinned, C14-authored credential snapshot set
 * (keyed by {@code (tenant, route)}) and serves it read-only. A newly published snapshot (routine
 * refresh or rotation) <b>swaps in atomically for new requests only</b>, with <b>no in-place
 * mutation</b>; the superseded master reference is dropped (GC-eligible). Routine rotation
 * deliberately does <b>not</b> eagerly zeroize the superseded master, so an in-flight
 * materialization still reading it is never corrupted mid-use (Doc 26 MSC-5 residual-heap
 * disclaimer); explicit {@link #invalidate} and expiry eviction ({@code evictExpired}) <b>do</b>
 * zeroize (Doc 26 MSC-6). When C14 is unavailable the cache keeps serving the last-known-good; a
 * control-plane outage never causes a request outage (AD-017). It holds no independent credential
 * store beyond the C14-authored snapshot (Doc 26 §13).
 *
 * <p>Version-aware: {@link #pinnedVersion()} reports the pinned snapshot version. TTL/expiry
 * refusal is the materialization use-case's decision (it holds the {@code ClockPort}); this cache
 * authors no TTL and never extends one (Doc 26 SP-D3). Thread-safe / virtual-thread-safe (AD-023):
 * the pinned snapshot is an {@link AtomicReference} swapped as a whole.
 */
public final class LastKnownGoodCredentialSnapshotCache implements SecretSnapshotPort {

  /**
   * The cache key: tenant scope + opaque canonical route reference (Doc 26 §3.2, AD-007 — never a
   * provider name).
   *
   * @param tenantScope the tenant scope
   * @param providerRouteRef the opaque canonical route reference
   */
  public record Key(TenantScope tenantScope, String providerRouteRef) {

    /** Compact constructor validating the key. */
    public Key {
      Preconditions.requireNonNull(tenantScope, "tenantScope");
      Preconditions.requireNonBlank(providerRouteRef, "providerRouteRef");
    }

    /**
     * Builds a key from a tenant scope and route target.
     *
     * @param tenantScope the tenant scope
     * @param routeTarget the route target
     * @return the cache key
     */
    public static Key of(final TenantScope tenantScope, final RouteTarget routeTarget) {
      Preconditions.requireNonNull(routeTarget, "routeTarget");
      return new Key(tenantScope, routeTarget.providerRouteRef());
    }
  }

  private record Pinned(SnapshotVersion version, Map<Key, CredentialMaterialSource> entries) {}

  private final AtomicReference<Pinned> pinned = new AtomicReference<>();

  @Override
  public Optional<CredentialMaterialSource> resolve(
      final TenantScope tenantScope, final RouteTarget routeTarget) {
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(routeTarget, "routeTarget");
    final Pinned current = pinned.get();
    if (current == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(current.entries().get(Key.of(tenantScope, routeTarget)));
  }

  /**
   * Applies a newly published, C14-validated credential snapshot, swapping it in atomically for new
   * requests (routine refresh or rotation, Doc 26 §15). The superseded snapshot is <b>dropped</b>;
   * its master material is reclaimed by GC (best-effort, Doc 26 MSC-5/MSC-6). Routine rotation
   * deliberately does <b>not</b> synchronously wipe the old master (M-1): the new credential is
   * published ahead of the old's expiry, so in-flight requests that already resolved the old source
   * complete normally instead of failing closed. Immediate wiping is reserved for {@link
   * #invalidate()} (revocation) and {@link #evictExpired(Instant)} (TTL). No in-place mutation.
   *
   * <p><b>Ordering assumption (M-1):</b> this pins whatever version it is handed and does not
   * enforce version monotonicity — {@code SnapshotVersion} carries no ordering, and
   * version-sequence authority belongs to C14, not this consume-only node (Doc 26 SP-D2/RIC-8;
   * enforcing it here would author version semantics, violating SP-INV). The C14 refresh mechanism
   * MUST deliver snapshots in order (no replay of an older snapshot after a newer one). Any
   * downgrade is bounded by the materialization TTL check: a rotated-out/revoked credential has a
   * past {@code notAfter} and fails closed regardless of which version is pinned (Doc 26 §16/§21,
   * SP-D4).
   *
   * @param version the published snapshot version (pinned)
   * @param entries the tenant/route → material source map (defensively copied)
   */
  public void applyPublished(
      final SnapshotVersion version, final Map<Key, CredentialMaterialSource> entries) {
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(entries, "entries");
    final Pinned next = new Pinned(version, Map.copyOf(entries));
    pinned.set(next);
  }

  /**
   * Invalidates the pinned snapshot (revocation / eviction): drops all entries and wipes their
   * master material (Doc 26 §13.1, MSC-6). Subsequent resolves are empty until the next published
   * snapshot, so materialization fails closed (Doc 26 §16).
   */
  public void invalidate() {
    wipe(pinned.getAndSet(null));
  }

  /**
   * Evicts and wipes every entry whose C14-authored expiry is at or before {@code now} (Doc 26
   * §13.1 /§16, SP-INV "never cache beyond policy"). Driven by the out-of-band C14 refresh
   * scheduler so no expired secret material lingers in memory; the cache reads no ambient clock
   * (C-1 fix) — the scheduler supplies the instant (Doc 11 R-063 / Doc 26 §19 determinism).
   *
   * @param now the current instant supplied by the scheduler (never an ambient clock read here)
   */
  public void evictExpired(final Instant now) {
    Preconditions.requireNonNull(now, "now");
    final Pinned current = pinned.get();
    if (current == null) {
      return;
    }
    final Map<Key, CredentialMaterialSource> retained = new LinkedHashMap<>();
    final List<CredentialMaterialSource> expired = new ArrayList<>();
    for (final Map.Entry<Key, CredentialMaterialSource> entry : current.entries().entrySet()) {
      if (entry.getValue().ref().notAfter().isAfter(now)) {
        retained.put(entry.getKey(), entry.getValue());
      } else {
        expired.add(entry.getValue());
      }
    }
    if (expired.isEmpty()) {
      return;
    }
    final Pinned next = new Pinned(current.version(), Map.copyOf(retained));
    // CAS so a concurrent applyPublished/invalidate wins; if it did, it already wiped `current`.
    if (pinned.compareAndSet(current, next)) {
      for (final CredentialMaterialSource source : expired) {
        if (source instanceof SnapshotCredentialMaterialSource wipeable) {
          wipeable.zeroize();
        }
      }
    }
  }

  /**
   * The currently pinned snapshot version, if any (version awareness, Doc 26 §6/§15).
   *
   * @return the pinned version, or empty when the cache is cold/invalidated
   */
  public Optional<SnapshotVersion> pinnedVersion() {
    final Pinned current = pinned.get();
    return current == null ? Optional.empty() : Optional.of(current.version());
  }

  private static void wipe(final Pinned superseded) {
    if (superseded == null) {
      return;
    }
    for (final CredentialMaterialSource source : superseded.entries().values()) {
      if (source instanceof SnapshotCredentialMaterialSource wipeable) {
        wipeable.zeroize();
      }
    }
  }
}
