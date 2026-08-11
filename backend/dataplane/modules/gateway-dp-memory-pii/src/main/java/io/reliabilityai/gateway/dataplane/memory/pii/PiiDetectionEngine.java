package io.reliabilityai.gateway.dataplane.memory.pii;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * The detection engine (AD-029 §7).
 *
 * <p>Deterministic, thread-safe and allocation-conscious. No model, no network, no service call, no
 * mutable state after construction. The same body and the same rule set always produce
 * byte-identical output — which is what makes a classification worth storing beside a record and
 * re-checking later.
 *
 * <p>The pipeline is four stages, in this order and for these reasons:
 *
 * <ol>
 *   <li><b>Match</b> every rule against the body, each under its own step budget.
 *   <li><b>Validate</b> each match with its checksum or range check, discarding what cannot be a
 *       genuine instance of its type.
 *   <li><b>Score</b> each surviving match against nearby context, discarding what stays below the
 *       confidence floor. This is the stage that lets a bare date be ignored while a date labelled
 *       "DOB" is not.
 *   <li><b>Resolve</b> overlaps, applying exemptions first and then letting higher priority win.
 * </ol>
 *
 * <p>Exemptions are applied before priority, not after, because an {@code ALLOW} rule is a
 * statement that a region of text is not sensitive at all. A tenant that has exempted its
 * documentation's example card numbers should not then see them reported by a higher-priority
 * built-in.
 */
public final class PiiDetectionEngine {

  /**
   * How much of a body is scanned.
   *
   * <p>{@code MemoryContent.MAX_BYTES} is 256 KiB, so this covers every body the runtime will
   * accept. The bound exists so that the guarantee is a property of this class rather than of a
   * constant in another module.
   */
  public static final int DEFAULT_MAX_SCAN_CHARACTERS = 256 * 1024;

  /** How far either side of a match context keywords are looked for. */
  private static final int CONTEXT_WINDOW = 48;

  /** What a nearby keyword adds to a match's confidence. */
  private static final double CONTEXT_BOOST = 0.35;

  /** Below this, a match is not reported. */
  private static final double DEFAULT_MINIMUM_CONFIDENCE = 0.5;

  private final PiiRuleSet rules;

  private final PiiMetricsPort metrics;

  private final int maxScanCharacters;

  private final double minimumConfidence;

  /**
   * Creates an engine over a rule set.
   *
   * @param rules the compiled rules
   * @param metrics where detection events are reported
   */
  public PiiDetectionEngine(final PiiRuleSet rules, final PiiMetricsPort metrics) {
    this(rules, metrics, DEFAULT_MAX_SCAN_CHARACTERS, DEFAULT_MINIMUM_CONFIDENCE);
  }

  /**
   * Creates an engine with explicit bounds.
   *
   * @param rules the compiled rules
   * @param metrics where detection events are reported
   * @param maxScanCharacters how much of a body to scan
   * @param minimumConfidence the confidence floor below which matches are not reported
   */
  public PiiDetectionEngine(
      final PiiRuleSet rules,
      final PiiMetricsPort metrics,
      final int maxScanCharacters,
      final double minimumConfidence) {
    this.rules = Preconditions.requireNonNull(rules, "rules");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    if (maxScanCharacters < 1) {
      throw new IllegalArgumentException("maxScanCharacters must be positive");
    }
    if (minimumConfidence < 0.0 || minimumConfidence > 1.0) {
      throw new IllegalArgumentException("minimumConfidence must be within [0, 1]");
    }
    this.maxScanCharacters = maxScanCharacters;
    this.minimumConfidence = minimumConfidence;
  }

  /**
   * The rule set this engine runs.
   *
   * @return the rules
   */
  public PiiRuleSet rules() {
    return rules;
  }

  /**
   * Scans a body.
   *
   * @param body the plaintext to scan; may be null or empty
   * @return what was found
   */
  public PiiDetection detect(final String body) {
    if (body == null || body.isEmpty()) {
      return PiiDetection.clean(rules.version());
    }
    final long started = System.nanoTime();
    final boolean truncated = body.length() > maxScanCharacters;
    final String scanned = truncated ? body.substring(0, maxScanCharacters) : body;
    if (truncated) {
      metrics.truncated();
    }
    final String lowered = scanned.toLowerCase(Locale.ROOT);
    final ScanBudget budget = new ScanBudget(scanned);

    final List<PiiSpan> denials = new ArrayList<>();
    final List<PiiSpan> exemptions = new ArrayList<>();
    boolean budgetExhausted = false;

    boolean hasDigit = false;
    for (int i = 0; i < scanned.length() && !hasDigit; i++) {
      final char c = scanned.charAt(i);
      hasDigit = c >= '0' && c <= '9';
    }

    for (final PiiRuleSet.Compiled compiled : rules.rules()) {
      // Skipping a rule that cannot match is the difference between scanning prose in
      // microseconds and in milliseconds. Measured: every rule costs 20-85 us per KiB
      // because each begins with a lookbehind the engine evaluates at every position.
      if (!compiled.rule().couldMatch(lowered, hasDigit)) {
        continue;
      }
      budget.renew();
      try {
        collect(compiled, budget, scanned, lowered, denials, exemptions);
      } catch (final ScanBudget.ScanBudgetExceededException spent) {
        // One runaway pattern abandons itself, not the scan. The result is marked incomplete, and
        // the classifier fails closed on it — see PiiDetection.complete().
        budgetExhausted = true;
        metrics.budgetExhausted(compiled.rule().id());
      }
    }

    final List<PiiSpan> accepted = resolve(denials, exemptions);
    accepted.sort(null);

    PiiSeverity severity = PiiSeverity.NONE;
    final Set<PiiCategory> categories = EnumSet.noneOf(PiiCategory.class);
    for (final PiiSpan span : accepted) {
      severity = severity.max(span.severity());
      categories.add(span.category());
      metrics.detected(span.type());
    }
    metrics.scanned(scanned.length(), System.nanoTime() - started);
    return new PiiDetection(
        accepted, severity, categories, rules.version(), truncated, budgetExhausted);
  }

  /**
   * Runs one rule over the body, collecting spans that survive validation and scoring.
   *
   * @param compiled the rule and its pattern
   * @param budget the budgeted view of the body
   * @param scanned the body
   * @param lowered the body, lowercased once, for context matching
   * @param denials where detections accumulate
   * @param exemptions where exemptions accumulate
   */
  private void collect(
      final PiiRuleSet.Compiled compiled,
      final ScanBudget budget,
      final String scanned,
      final String lowered,
      final List<PiiSpan> denials,
      final List<PiiSpan> exemptions) {
    final PiiRule rule = compiled.rule();
    final Matcher matcher = compiled.pattern().matcher(budget);
    while (matcher.find()) {
      final int start = matcher.start();
      final int end = matcher.end();
      if (end <= start) {
        continue;
      }
      final String matched = scanned.substring(start, end);
      if (!rule.validator().accepts(matched)) {
        metrics.validatorRejected(rule.type());
        continue;
      }
      final double confidence = score(rule, lowered, start, end);
      if (rule.effect() == PiiRule.Effect.ALLOW) {
        // An exemption's confidence is not scored against the floor: a tenant asserting that a
        // region
        // is not sensitive is not making a probabilistic claim, it is making a decision.
        exemptions.add(span(rule, start, end, 1.0));
        continue;
      }
      if (confidence < minimumConfidence) {
        metrics.droppedBelowConfidence(rule.type());
        continue;
      }
      denials.add(span(rule, start, end, confidence));
    }
  }

  /**
   * Scores a match against the words around it.
   *
   * <p>A rule with no context keywords keeps its base confidence. A rule with keywords gains when
   * one appears nearby and keeps its base otherwise — the base is where the author expressed how
   * much the shape alone is worth, and a rule whose base already clears the floor does not need a
   * label.
   *
   * @param rule the matching rule
   * @param lowered the lowercased body
   * @param start the match start
   * @param end the match end
   * @return the confidence, capped at one
   */
  private static double score(
      final PiiRule rule, final String lowered, final int start, final int end) {
    if (rule.contextKeywords().isEmpty()) {
      return rule.confidence();
    }
    final int from = Math.max(0, start - CONTEXT_WINDOW);
    final int to = Math.min(lowered.length(), end + CONTEXT_WINDOW);
    final String window = lowered.substring(from, to);
    for (final String keyword : rule.contextKeywords()) {
      if (window.contains(keyword)) {
        return Math.min(1.0, rule.confidence() + CONTEXT_BOOST);
      }
    }
    return rule.confidence();
  }

  /**
   * Applies exemptions, then resolves overlaps by priority.
   *
   * <p>Candidates arrive grouped by rule, and rules are already in descending priority order, so
   * the first accepted span covering a region is by construction the highest-priority one. That is
   * why this is a linear walk and not a sort.
   *
   * @param denials the detections
   * @param exemptions the exemptions
   * @return the surviving spans
   */
  private List<PiiSpan> resolve(final List<PiiSpan> denials, final List<PiiSpan> exemptions) {
    final List<PiiSpan> accepted = new ArrayList<>(denials.size());
    for (final PiiSpan candidate : denials) {
      boolean exempt = false;
      for (final PiiSpan exemption : exemptions) {
        if (candidate.overlaps(exemption)) {
          exempt = true;
          metrics.suppressedByAllowRule(exemption.ruleId());
          break;
        }
      }
      if (exempt) {
        continue;
      }
      boolean covered = false;
      for (final PiiSpan already : accepted) {
        if (candidate.overlaps(already)) {
          covered = true;
          break;
        }
      }
      if (!covered) {
        accepted.add(candidate);
      }
    }
    return accepted;
  }

  /**
   * Builds a span from a rule and a match.
   *
   * @param rule the matching rule
   * @param start the match start
   * @param end the match end
   * @param confidence the scored confidence
   * @return the span
   */
  private static PiiSpan span(
      final PiiRule rule, final int start, final int end, final double confidence) {
    return new PiiSpan(
        rule.type(),
        rule.category(),
        rule.severity(),
        start,
        end,
        confidence,
        rule.id(),
        rule.version());
  }
}
