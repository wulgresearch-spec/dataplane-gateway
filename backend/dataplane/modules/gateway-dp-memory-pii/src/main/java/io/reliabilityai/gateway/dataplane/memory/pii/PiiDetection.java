package io.reliabilityai.gateway.dataplane.memory.pii;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the engine found in one body (AD-029 §4).
 *
 * <p>Immutable, and complete enough to audit: every span, the aggregate severity, the categories
 * present, which rule set version produced it, and — critically — whether the scan actually
 * finished.
 *
 * @param spans every detection, in document order, with overlaps already resolved
 * @param severity the highest severity among the spans, or {@link PiiSeverity#NONE}
 * @param categories every category present
 * @param ruleSetVersion the version of the rule set that produced this
 * @param truncated whether the body was longer than the engine would scan
 * @param budgetExhausted whether a pattern hit its step budget and was abandoned
 */
public record PiiDetection(
    List<PiiSpan> spans,
    PiiSeverity severity,
    Set<PiiCategory> categories,
    long ruleSetVersion,
    boolean truncated,
    boolean budgetExhausted) {

  /**
   * Validates and freezes the result.
   *
   * @param spans the detections
   * @param severity the aggregate severity
   * @param categories the categories present
   * @param ruleSetVersion the producing rule set version
   * @param truncated whether the body was cut short
   * @param budgetExhausted whether a pattern was abandoned
   */
  public PiiDetection {
    spans = List.copyOf(Preconditions.requireNonNull(spans, "spans"));
    categories = Set.copyOf(Preconditions.requireNonNull(categories, "categories"));
    Preconditions.requireNonNull(severity, "severity");
  }

  /**
   * A result for a body in which nothing was found.
   *
   * @param ruleSetVersion the rule set that found nothing
   * @return an empty result
   */
  public static PiiDetection clean(final long ruleSetVersion) {
    return new PiiDetection(List.of(), PiiSeverity.NONE, Set.of(), ruleSetVersion, false, false);
  }

  /**
   * Reports whether anything was found.
   *
   * @return true when at least one span was detected
   */
  public boolean any() {
    return !spans.isEmpty();
  }

  /**
   * Reports whether this result can be relied on.
   *
   * <p>A truncated or abandoned scan looked at less than the whole body, so "nothing found" from
   * one means "nothing found in the part I read". Callers that fail closed must consult this;
   * AD-029 §7.2 explains why the classifier treats an incomplete scan as unclassified rather than
   * as clean.
   *
   * @return true when the whole body was scanned by every rule
   */
  public boolean complete() {
    return !truncated && !budgetExhausted;
  }

  /**
   * The single category that best describes this body.
   *
   * <p>{@link PiiCategory#MULTIPLE} when more than one is present — the only place that value is
   * ever produced.
   *
   * @return the summary category, or empty when nothing was found
   */
  public Optional<PiiCategory> summaryCategory() {
    if (categories.isEmpty()) {
      return Optional.empty();
    }
    if (categories.size() > 1) {
      return Optional.of(PiiCategory.MULTIPLE);
    }
    return Optional.of(categories.iterator().next());
  }

  /**
   * How many spans of each type were found.
   *
   * <p>Sorted by type name so that two runs over the same input produce byte-identical output —
   * this is what makes a detection result usable as an audit record rather than merely as a signal.
   *
   * @return the counts, keyed by type
   */
  public Map<PiiType, Integer> countsByType() {
    final Map<PiiType, Integer> counts = new TreeMap<>();
    for (final PiiSpan span : spans) {
      counts.merge(span.type(), 1, Integer::sum);
    }
    return counts;
  }

  /**
   * Every type found.
   *
   * @return the set of types
   */
  public Set<PiiType> types() {
    final EnumSet<PiiType> found = EnumSet.noneOf(PiiType.class);
    for (final PiiSpan span : spans) {
      found.add(span.type());
    }
    return found;
  }
}
