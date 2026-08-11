package io.reliabilityai.gateway.dataplane.memory.pii;

/**
 * A hard step limit on one regular-expression scan (AD-029 §8.1).
 *
 * <p><b>Why this exists.</b> This engine accepts tenant-supplied patterns. Java's regex engine
 * backtracks, and a pattern such as {@code (a+)+$} against a few dozen characters takes longer than
 * the age of the universe. Without a limit, any tenant able to install a rule can hang the write
 * path for every tenant on the node — a denial of service that needs no privileges beyond the
 * feature working as designed. Validating patterns for "dangerous constructs" at compile time is a
 * well-known losing game; bounding the work is not.
 *
 * <p>The mechanism is that {@link java.util.regex.Matcher} reads its input exclusively through
 * {@link CharSequence#charAt(int)}. Counting those reads counts the engine's actual steps,
 * including every step spent backtracking, and throwing once the budget is spent turns an unbounded
 * hang into a bounded, reportable failure.
 *
 * <p>An exhausted budget is <b>not</b> treated as "nothing found". See {@link
 * PiiDetection#complete()} and AD-029 §7.2: a scan that did not finish tells you nothing about what
 * it did not reach, and the classifier fails closed on it.
 *
 * <p>Instances are mutable and single-use. One is created per scan; none is shared between threads.
 */
final class ScanBudget implements CharSequence {

  /**
   * How many character reads one pattern may spend per character of input.
   *
   * <p>A well-behaved pattern is close to linear. Two hundred reads per character leaves generous
   * room for ordinary alternation and bounded repetition while cutting exponential blow-up off
   * almost immediately — the pathological cases exceed any linear multiple within microseconds.
   */
  static final int READS_PER_CHARACTER = 200;

  /** A floor, so that short inputs still get a workable allowance. */
  private static final long MINIMUM_BUDGET = 100_000L;

  private final CharSequence delegate;

  private final long budget;

  private long reads;

  /**
   * Wraps a body with a budget proportional to its length.
   *
   * @param delegate the text to scan
   */
  ScanBudget(final CharSequence delegate) {
    this.delegate = delegate;
    this.budget = Math.max(MINIMUM_BUDGET, (long) delegate.length() * READS_PER_CHARACTER);
  }

  /**
   * Resets the counter so the next pattern gets a fresh allowance.
   *
   * <p>Per pattern rather than per document: a document with forty rules should not fail the
   * fortieth because the first thirty-nine were merely thorough.
   */
  void renew() {
    reads = 0;
  }

  /**
   * How many reads the last pattern spent.
   *
   * @return the read count since the last renewal
   */
  long spent() {
    return reads;
  }

  @Override
  public int length() {
    return delegate.length();
  }

  @Override
  public char charAt(final int index) {
    if (++reads > budget) {
      throw new ScanBudgetExceededException();
    }
    return delegate.charAt(index);
  }

  @Override
  public CharSequence subSequence(final int start, final int end) {
    // Deliberately unbudgeted: this is how a matched group is extracted once a match has already
    // been
    // found, so it is bounded by the match rather than by the search.
    return delegate.subSequence(start, end);
  }

  @Override
  public String toString() {
    return delegate.toString();
  }

  /**
   * Thrown when one pattern exceeds its step allowance.
   *
   * <p>Carries no message and no stack trace. It is control flow across a known boundary, thrown at
   * a rate an attacker chooses, and filling in a stack trace for each one would hand that attacker
   * a second, cheaper denial of service.
   */
  static final class ScanBudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception without a stack trace. */
    ScanBudgetExceededException() {
      super(null, null, false, false);
    }
  }
}
