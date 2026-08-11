package io.reliabilityai.gateway.dataplane.memory.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.PiiAction;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The ten policies resolved to a single, immutable answer for one scope (AD-026 §8).
 *
 * <p>Produced once by merging along a scope chain and then held in a compiled snapshot, so the hot
 * path reads a resolved value rather than merging on every request (MEM-18).
 *
 * <p><b>Every merge is monotonically non-loosening</b> (MEM-19). {@link #mergeWith} takes the
 * stricter of each dimension, and the property is asserted exhaustively rather than assumed: for
 * any pair of policies, merging produces something no more permissive than either input.
 *
 * @param ttl how long a memory may live, or empty for no ceiling
 * @param retention how long it must live, or empty for no floor
 * @param piiAction what to do with personal data
 * @param permittedRegions the regions that may hold the bytes; empty set means none are permitted
 * @param encryptionRequired whether sealing at rest is mandatory
 * @param legalHold whether deletion is suspended
 * @param deleteAllowed whether explicit deletion is permitted at all
 * @param archiveAfter the age at which a memory moves to cold storage, or empty for never
 * @param versioning whether a write supersedes in place or appends a version
 * @param snapshotInterval how often a point-in-time capture is taken, or empty for never
 * @param maxClassification the strictest classification this scope may hold
 * @param enforceable whether this policy could actually be established
 */
public record EffectiveMemoryPolicy(
    Optional<Duration> ttl,
    Optional<Duration> retention,
    PiiAction piiAction,
    Set<String> permittedRegions,
    boolean encryptionRequired,
    boolean legalHold,
    boolean deleteAllowed,
    Optional<Duration> archiveAfter,
    VersioningMode versioning,
    Optional<Duration> snapshotInterval,
    DataClassification maxClassification,
    boolean enforceable) {

  /** Whether a write replaces the current record or adds a version beside it. */
  public enum VersioningMode {
    /** Overwrite in place. The previous content is gone. */
    SUPERSEDE,
    /** Keep the previous version and add a new one. */
    APPEND
  }

  /**
   * The policy used when nothing could be resolved.
   *
   * <p><b>Not permissive</b> (MEM-21). Every dimension is at its strictest and {@code enforceable}
   * is false, so a pipeline that receives this refuses. An "empty means allow" default would
   * silently turn a policy-source outage into an open door — which is exactly the failure the
   * governance engine's {@code UNENFORCEABLE} verdict was introduced to prevent, and the same
   * answer applies here.
   */
  public static final EffectiveMemoryPolicy UNENFORCEABLE =
      new EffectiveMemoryPolicy(
          Optional.of(Duration.ZERO),
          Optional.empty(),
          PiiAction.REFUSE,
          Set.of(),
          true,
          false,
          false,
          Optional.empty(),
          VersioningMode.APPEND,
          Optional.empty(),
          DataClassification.UNCLASSIFIED,
          false);

  /**
   * Validates and canonicalises the policy.
   *
   * @param ttl the life ceiling
   * @param retention the life floor
   * @param piiAction the personal-data action
   * @param permittedRegions the permitted regions
   * @param encryptionRequired whether sealing is mandatory
   * @param legalHold whether deletion is suspended
   * @param deleteAllowed whether deletion is permitted
   * @param archiveAfter the archive age
   * @param versioning the versioning mode
   * @param snapshotInterval the snapshot cadence
   * @param maxClassification the strictest classification permitted
   * @param enforceable whether the policy was established
   */
  public EffectiveMemoryPolicy {
    Preconditions.requireNonNull(ttl, "ttl");
    Preconditions.requireNonNull(retention, "retention");
    Preconditions.requireNonNull(piiAction, "piiAction");
    Preconditions.requireNonNull(permittedRegions, "permittedRegions");
    Preconditions.requireNonNull(archiveAfter, "archiveAfter");
    Preconditions.requireNonNull(versioning, "versioning");
    Preconditions.requireNonNull(snapshotInterval, "snapshotInterval");
    Preconditions.requireNonNull(maxClassification, "maxClassification");
    // Sorted and frozen: a policy digest must be stable across nodes, and Set.copyOf does not
    // order.
    permittedRegions = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(permittedRegions));
  }

  /**
   * Merges this policy with one from a narrower scope, taking the stricter of every dimension.
   *
   * <p>Idempotent, commutative and associative, so the order scopes are visited in cannot change
   * the answer. Each dimension follows the merge rule its {@code MemoryPolicyType} declares.
   *
   * @param narrower the policy from the narrower scope
   * @return the merged policy, never more permissive than either input
   */
  public EffectiveMemoryPolicy mergeWith(final EffectiveMemoryPolicy narrower) {
    Preconditions.requireNonNull(narrower, "narrower");

    final TreeSet<String> regions = new TreeSet<>(permittedRegions);
    regions.retainAll(narrower.permittedRegions);

    return new EffectiveMemoryPolicy(
        shorter(ttl, narrower.ttl),
        longer(retention, narrower.retention),
        piiAction.strictest(narrower.piiAction),
        regions,
        encryptionRequired || narrower.encryptionRequired,
        legalHold || narrower.legalHold,
        deleteAllowed && narrower.deleteAllowed,
        shorter(archiveAfter, narrower.archiveAfter),
        // APPEND keeps history; SUPERSEDE destroys it. Keeping history is the safer merge.
        versioning == VersioningMode.APPEND || narrower.versioning == VersioningMode.APPEND
            ? VersioningMode.APPEND
            : VersioningMode.SUPERSEDE,
        shorter(snapshotInterval, narrower.snapshotInterval),
        maxClassification.severity() <= narrower.maxClassification.severity()
            ? maxClassification
            : narrower.maxClassification,
        enforceable && narrower.enforceable);
  }

  /**
   * Reports whether a retention floor conflicts with the TTL ceiling.
   *
   * <p>Surfaced rather than resolved (AD-026 §8.2). Deleting data a retention rule requires is the
   * worse error, and a rule conflict nobody sees is worse than both — so a conflicted record is
   * retained, flagged, and left for an operator.
   *
   * @return true when retention outlives the TTL
   */
  public boolean retentionConflictsWithTtl() {
    return ttl.isPresent() && retention.isPresent() && retention.get().compareTo(ttl.get()) > 0;
  }

  /**
   * Returns the expiry to stamp on a record written now.
   *
   * <p>Empty when the record does not expire, either because no TTL applies or because a retention
   * floor outlives it. The caller's requested TTL may only shorten this, never lengthen it —
   * otherwise any caller could opt out of retention limits by asking for a century.
   *
   * @param now the creation instant
   * @param requested the caller's requested lifetime, if any
   * @return the expiry instant, or empty when the record does not expire
   */
  public Optional<java.time.Instant> expiryFor(
      final java.time.Instant now, final Optional<Duration> requested) {
    Preconditions.requireNonNull(now, "now");
    Preconditions.requireNonNull(requested, "requested");
    if (retentionConflictsWithTtl()) {
      return Optional.empty();
    }
    final Optional<Duration> effective = shorter(ttl, requested);
    return effective.map(now::plus);
  }

  /**
   * Reports whether a region may hold bytes under this policy.
   *
   * @param region the region to test
   * @return true when the region is permitted
   */
  public boolean permitsRegion(final String region) {
    Preconditions.requireNonNull(region, "region");
    return permittedRegions.contains(region);
  }

  /**
   * Reports whether any region at all is permitted.
   *
   * <p>An empty intersection means two scopes permit disjoint regions, so there is nowhere lawful
   * to put the bytes and the write must be refused (MEM-22).
   *
   * @return true when at least one region is permitted
   */
  public boolean hasPermittedRegion() {
    return !permittedRegions.isEmpty();
  }

  /**
   * Reports whether content of a classification may be held under this policy.
   *
   * @param classification the classification to test
   * @return true when the classification is within the ceiling and is storable at all
   */
  public boolean permitsClassification(final DataClassification classification) {
    Preconditions.requireNonNull(classification, "classification");
    return classification.storable()
        && classification.established()
        && classification.severity() <= maxClassification.severity();
  }

  /**
   * Reports whether sealing is required for content of a classification.
   *
   * <p>True when the encryption policy demands it <em>or</em> the PII action forces it — the two
   * are independent reasons and either suffices.
   *
   * @param classification the content classification
   * @return true when the content must be sealed at rest
   */
  public boolean requiresSealing(final DataClassification classification) {
    Preconditions.requireNonNull(classification, "classification");
    return encryptionRequired || (classification.personal() && piiAction.forcesEncryption());
  }

  private static Optional<Duration> shorter(
      final Optional<Duration> left, final Optional<Duration> right) {
    if (left.isEmpty()) {
      return right;
    }
    if (right.isEmpty()) {
      return left;
    }
    return Optional.of(left.get().compareTo(right.get()) <= 0 ? left.get() : right.get());
  }

  private static Optional<Duration> longer(
      final Optional<Duration> left, final Optional<Duration> right) {
    if (left.isEmpty()) {
      return right;
    }
    if (right.isEmpty()) {
      return left;
    }
    return Optional.of(left.get().compareTo(right.get()) >= 0 ? left.get() : right.get());
  }
}
