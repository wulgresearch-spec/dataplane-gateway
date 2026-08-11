package io.reliabilityai.gateway.dataplane.memory.pii;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * One detection: what was found, where, and how sure the engine is (AD-029 §4).
 *
 * <p><b>The matched text is not stored.</b> A span carries offsets, not content. A detection result
 * describing where the credit cards are is a useful thing to log, audit and count; one that quotes
 * them is a second copy of the data with fewer protections than the first, and it would inevitably
 * end up somewhere the original never would.
 *
 * @param type what was found
 * @param category the regime it falls under
 * @param severity how much harm its disclosure would do
 * @param start the index of the first character, inclusive
 * @param end the index after the last character, exclusive
 * @param confidence how sure the engine is, in [0, 1]
 * @param ruleId which rule matched
 * @param ruleVersion the version of that rule
 */
public record PiiSpan(
    PiiType type,
    PiiCategory category,
    PiiSeverity severity,
    int start,
    int end,
    double confidence,
    String ruleId,
    int ruleVersion)
    implements Comparable<PiiSpan> {

  /**
   * Validates the span.
   *
   * @param type what was found
   * @param category the regime it falls under
   * @param severity the harm level
   * @param start the start offset
   * @param end the end offset
   * @param confidence the confidence in [0, 1]
   * @param ruleId the matching rule
   * @param ruleVersion the rule's version
   */
  public PiiSpan {
    Preconditions.requireNonNull(type, "type");
    Preconditions.requireNonNull(category, "category");
    Preconditions.requireNonNull(severity, "severity");
    Preconditions.requireNonBlank(ruleId, "ruleId");
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("span must be non-empty and non-negative");
    }
    if (confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be within [0, 1]");
    }
    if (category == PiiCategory.MULTIPLE) {
      // MULTIPLE summarises a document. A span carrying it would make "how many categories are
      // present" unanswerable, which is the one question the category dimension exists to answer.
      throw new IllegalArgumentException("a span may not carry the MULTIPLE summary category");
    }
  }

  /**
   * How many characters this span covers.
   *
   * @return the length
   */
  public int length() {
    return end - start;
  }

  /**
   * Reports whether this span shares any character with another.
   *
   * @param other the span to test
   * @return true when the two overlap
   */
  public boolean overlaps(final PiiSpan other) {
    return start < other.end && other.start < end;
  }

  /**
   * Orders spans by position, then by descending length.
   *
   * <p>Position first so that a result reads in document order. Longer-first on a tie because when
   * two rules match at the same offset, the longer match is the more specific one — a full IBAN
   * beats the account-number fragment inside it.
   *
   * @param other the span to compare against
   * @return the comparison
   */
  @Override
  public int compareTo(final PiiSpan other) {
    final int byStart = Integer.compare(start, other.start);
    return byStart != 0 ? byStart : Integer.compare(other.length(), length());
  }
}
