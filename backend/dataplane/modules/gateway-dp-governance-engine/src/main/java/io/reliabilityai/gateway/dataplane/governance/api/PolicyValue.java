package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a rule actually says. Sealed to exactly four shapes, one per family of {@link PolicyKind}.
 *
 * <p>Sealing matters here more than usual. The merge and the evaluator both switch on this type,
 * and a sealed hierarchy means the compiler — not a test, not a code review — proves those switches
 * are exhaustive. A fifth value shape cannot be introduced without every merge and every check
 * refusing to compile until they handle it, which is the opposite of the usual failure mode where a
 * new policy shape silently evaluates to "no opinion" and quietly widens access.
 *
 * <p>All four variants are deeply immutable and hold no reference to a request, a tenant or a
 * clock, so one instance is safely shared by every thread evaluating against a snapshot.
 */
public sealed interface PolicyValue
    permits PolicyValue.Values, PolicyValue.Flag, PolicyValue.Limit, PolicyValue.Windows {

  /**
   * Whether this value can legally express the given kind.
   *
   * @param kind the kind declared by the policy type
   * @return {@code true} when the shapes agree
   */
  boolean supports(PolicyKind kind);

  /**
   * A set of opaque identifiers — models, tools, providers, regions, compliance regimes.
   *
   * <p>Stored sorted so that two snapshots compiled from the same authored policy are
   * byte-identical, which is what lets a decision be replayed and compared months later (Doc 21
   * GV-D12).
   *
   * @param values the identifiers, sorted and immutable
   */
  record Values(List<String> values) implements PolicyValue {

    /** Validates and canonically sorts the identifiers. */
    public Values {
      Preconditions.requireNonNull(values, "values");
      values = List.copyOf(new TreeSet<>(values));
    }

    /**
     * Creates a value set from a set.
     *
     * @param values the identifiers
     * @return the policy value
     */
    public static Values of(final Set<String> values) {
      return new Values(List.copyOf(Preconditions.requireNonNull(values, "values")));
    }

    /**
     * Creates a value set from varargs.
     *
     * @param values the identifiers
     * @return the policy value
     */
    public static Values of(final String... values) {
      return new Values(List.of(values));
    }

    /**
     * Whether an identifier is present.
     *
     * @param candidate the identifier to look for
     * @return {@code true} when the set contains it
     */
    public boolean contains(final String candidate) {
      return values.contains(candidate);
    }

    @Override
    public boolean supports(final PolicyKind kind) {
      return kind == PolicyKind.ALLOW_LIST || kind == PolicyKind.DENY_LIST;
    }
  }

  /**
   * A boolean switch. Its meaning depends on the kind: permitted, required, or forbidden.
   *
   * @param value the switch position
   */
  record Flag(boolean value) implements PolicyValue {

    /** The set flag. */
    public static final Flag TRUE = new Flag(true);

    /** The cleared flag. */
    public static final Flag FALSE = new Flag(false);

    /**
     * Returns the flag for a boolean.
     *
     * @param value the switch position
     * @return the corresponding flag
     */
    public static Flag of(final boolean value) {
      return value ? TRUE : FALSE;
    }

    @Override
    public boolean supports(final PolicyKind kind) {
      return kind == PolicyKind.CAPABILITY
          || kind == PolicyKind.REQUIREMENT
          || kind == PolicyKind.PROHIBITION;
    }
  }

  /**
   * A numeric upper bound. Always non-negative; a bound of zero means "none permitted", which is a
   * legitimate and deliberately expressible policy.
   *
   * @param value the ceiling
   */
  record Limit(long value) implements PolicyValue {

    /** Validates the ceiling. */
    public Limit {
      Preconditions.requireNonNegative(value, "value");
    }

    /**
     * Creates a ceiling.
     *
     * @param value the ceiling
     * @return the policy value
     */
    public static Limit of(final long value) {
      return new Limit(value);
    }

    @Override
    public boolean supports(final PolicyKind kind) {
      return kind == PolicyKind.CEILING;
    }
  }

  /**
   * A set of half-open intervals during which the request is refused.
   *
   * @param windows the intervals, sorted by start, immutable
   */
  record Windows(List<TimeWindow> windows) implements PolicyValue {

    /** Validates and canonically sorts the intervals. */
    public Windows {
      Preconditions.requireNonNull(windows, "windows");
      final List<TimeWindow> sorted = new java.util.ArrayList<>(windows);
      sorted.sort(
          java.util.Comparator.comparing(TimeWindow::from).thenComparing(TimeWindow::toExclusive));
      windows = List.copyOf(sorted);
    }

    /**
     * Creates a window set.
     *
     * @param windows the intervals
     * @return the policy value
     */
    public static Windows of(final TimeWindow... windows) {
      return new Windows(List.of(windows));
    }

    /**
     * Whether an instant falls inside any interval.
     *
     * @param instant the instant to test, from the injected clock
     * @return {@code true} when the instant is within a window
     */
    public boolean covers(final Instant instant) {
      for (final TimeWindow window : windows) {
        if (window.covers(instant)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean supports(final PolicyKind kind) {
      return kind == PolicyKind.WINDOW;
    }
  }

  /**
   * A half-open interval {@code [from, toExclusive)}.
   *
   * <p>Half-open so that adjacent windows tile without overlapping and without leaving a
   * one-instant gap through which a request could slip.
   *
   * @param from the inclusive start
   * @param toExclusive the exclusive end
   */
  record TimeWindow(Instant from, Instant toExclusive) {

    /** Validates the interval. */
    public TimeWindow {
      Preconditions.requireNonNull(from, "from");
      Preconditions.requireNonNull(toExclusive, "toExclusive");
      if (!toExclusive.isAfter(from)) {
        throw new IllegalArgumentException("toExclusive must be after from");
      }
    }

    /**
     * Whether an instant falls inside this interval.
     *
     * @param instant the instant to test
     * @return {@code true} when {@code from <= instant < toExclusive}
     */
    public boolean covers(final Instant instant) {
      return !instant.isBefore(from) && instant.isBefore(toExclusive);
    }
  }
}
