package io.reliabilityai.gateway.canonical.capability;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * An immutable set of {@link ProviderCapability}, and the single type both sides of a capability
 * question speak.
 *
 * <p>Backed by an {@link EnumSet}, so membership is a bit test rather than a hash lookup and "does
 * this route support everything this request needs" is a machine-word mask comparison. That matters
 * because the question is asked once per candidate route per request on the routing hot path; with
 * a closed vocabulary there is no reason for it to cost more than an {@code AND}.
 *
 * <p>Deeply immutable and safe to share across threads: the backing set is copied on construction
 * and never handed out.
 *
 * <p>{@link #tokens()} projects to the sorted {@code Set<String>} form that capability snapshots
 * and the router's existing filter already speak, which is what let this type be introduced without
 * changing a single routing decision.
 */
public final class CapabilitySet {

  private static final CapabilitySet EMPTY =
      new CapabilitySet(EnumSet.noneOf(ProviderCapability.class));

  private final EnumSet<ProviderCapability> capabilities;
  private final List<String> tokens;

  private CapabilitySet(final EnumSet<ProviderCapability> capabilities) {
    this.capabilities = capabilities;
    final Set<String> sorted = new TreeSet<>();
    for (final ProviderCapability capability : capabilities) {
      sorted.add(capability.token());
    }
    this.tokens = List.copyOf(sorted);
  }

  /**
   * The empty set — a route that declares nothing.
   *
   * @return the empty capability set
   */
  public static CapabilitySet none() {
    return EMPTY;
  }

  /**
   * Builds a set from capabilities.
   *
   * @param capabilities the capabilities
   * @return the immutable set
   */
  public static CapabilitySet of(final ProviderCapability... capabilities) {
    Preconditions.requireNonNull(capabilities, "capabilities");
    final EnumSet<ProviderCapability> set = EnumSet.noneOf(ProviderCapability.class);
    for (final ProviderCapability capability : capabilities) {
      set.add(Preconditions.requireNonNull(capability, "capability"));
    }
    return new CapabilitySet(set);
  }

  /**
   * Builds a set from a collection.
   *
   * @param capabilities the capabilities
   * @return the immutable set
   */
  public static CapabilitySet copyOf(final Collection<ProviderCapability> capabilities) {
    Preconditions.requireNonNull(capabilities, "capabilities");
    final EnumSet<ProviderCapability> set = EnumSet.noneOf(ProviderCapability.class);
    for (final ProviderCapability capability : capabilities) {
      set.add(Preconditions.requireNonNull(capability, "capability"));
    }
    return new CapabilitySet(set);
  }

  /**
   * Builds a set from published wire tokens, dropping any this node does not recognise.
   *
   * <p>Dropping rather than failing is the right reading of an unknown token: a snapshot from a
   * newer control plane may name capabilities this build has never heard of, and the honest
   * interpretation is that this node cannot honour them. Silently <em>keeping</em> one would be
   * worse — the route would appear to support something no code here can deliver.
   *
   * @param tokens the wire tokens
   * @return the immutable set of recognised capabilities
   */
  public static CapabilitySet fromTokens(final Collection<String> tokens) {
    Preconditions.requireNonNull(tokens, "tokens");
    final EnumSet<ProviderCapability> set = EnumSet.noneOf(ProviderCapability.class);
    for (final String token : tokens) {
      ProviderCapability.fromToken(token).ifPresent(set::add);
    }
    return new CapabilitySet(set);
  }

  /**
   * Whether this set contains a capability.
   *
   * @param capability the capability
   * @return {@code true} when present
   */
  public boolean supports(final ProviderCapability capability) {
    return capability != null && capabilities.contains(capability);
  }

  /**
   * Whether this set contains every capability in another — the routing question, as one mask test.
   *
   * @param required the required capabilities
   * @return {@code true} when nothing required is missing
   */
  public boolean supportsAll(final CapabilitySet required) {
    Preconditions.requireNonNull(required, "required");
    return capabilities.containsAll(required.capabilities);
  }

  /**
   * The capabilities in {@code required} that this set lacks — the explanation half of a refusal.
   *
   * @param required the required capabilities
   * @return the missing capabilities, in declaration order
   */
  public CapabilitySet missingFrom(final CapabilitySet required) {
    Preconditions.requireNonNull(required, "required");
    final EnumSet<ProviderCapability> missing = EnumSet.copyOf(required.capabilities);
    missing.removeAll(capabilities);
    return new CapabilitySet(missing);
  }

  /**
   * The intersection of this set with another.
   *
   * @param other the other set
   * @return the capabilities present in both
   */
  public CapabilitySet intersect(final CapabilitySet other) {
    Preconditions.requireNonNull(other, "other");
    final EnumSet<ProviderCapability> both = EnumSet.copyOf(capabilities);
    both.retainAll(other.capabilities);
    return new CapabilitySet(both);
  }

  /**
   * The union of this set with another.
   *
   * @param other the other set
   * @return the capabilities present in either
   */
  public CapabilitySet union(final CapabilitySet other) {
    Preconditions.requireNonNull(other, "other");
    final EnumSet<ProviderCapability> either = EnumSet.copyOf(capabilities);
    either.addAll(other.capabilities);
    return new CapabilitySet(either);
  }

  /**
   * The capabilities, in declaration order.
   *
   * @return an immutable view
   */
  public Set<ProviderCapability> capabilities() {
    return Set.copyOf(capabilities);
  }

  /**
   * The stable wire tokens, sorted — the form capability snapshots and the router's filter speak.
   *
   * @return the immutable sorted token list
   */
  public List<String> tokens() {
    return tokens;
  }

  /**
   * The tokens as a set, for the seams that take {@code Set<String>}.
   *
   * @return the immutable token set
   */
  public Set<String> tokenSet() {
    return Set.copyOf(tokens);
  }

  /**
   * Whether nothing is declared.
   *
   * @return {@code true} when empty
   */
  public boolean isEmpty() {
    return capabilities.isEmpty();
  }

  /**
   * How many capabilities are declared.
   *
   * @return the count
   */
  public int size() {
    return capabilities.size();
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof CapabilitySet set && capabilities.equals(set.capabilities);
  }

  @Override
  public int hashCode() {
    return capabilities.hashCode();
  }

  @Override
  public String toString() {
    return tokens.toString();
  }
}
