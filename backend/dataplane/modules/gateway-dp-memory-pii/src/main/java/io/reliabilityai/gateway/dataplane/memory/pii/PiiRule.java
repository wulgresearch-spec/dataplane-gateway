package io.reliabilityai.gateway.dataplane.memory.pii;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;

/**
 * One detection rule, as pure data (AD-029 §5).
 *
 * <p>Nothing here is executable except the pattern, and the pattern runs under a step budget. A
 * rule is therefore something a tenant can be allowed to write, an operator can review in a diff,
 * and a store can hold — none of which is true of a rule that carries code.
 *
 * @param id a stable identifier, unique within a rule set
 * @param version the rule's version, so a detection can name exactly what produced it
 * @param priority higher wins when two rules overlap
 * @param origin who supplied it
 * @param effect whether it detects sensitive content or exempts content from detection
 * @param type what a match represents
 * @param category the regime a match falls under
 * @param severity the harm a match represents
 * @param confidence the base confidence of a match, in [0, 1]
 * @param regex the pattern
 * @param caseInsensitive whether the pattern ignores case
 * @param validator a checksum or range check a match must also pass
 * @param contextKeywords words that, appearing near a match, raise confidence
 * @param requiredAnyOf tokens of which at least one must appear near a match, or empty when the
 *     pattern stands alone
 * @param requiresDigit whether a match must contain at least one digit
 */
public record PiiRule(
    String id,
    int version,
    int priority,
    Origin origin,
    Effect effect,
    PiiType type,
    PiiCategory category,
    PiiSeverity severity,
    double confidence,
    String regex,
    boolean caseInsensitive,
    PiiValidator validator,
    Set<String> contextKeywords,
    Set<String> requiredAnyOf,
    boolean requiresDigit) {

  /** Who supplied a rule, which decides how it layers against the others. */
  public enum Origin {

    /** Shipped with the engine. The floor a deployment gets for doing nothing. */
    BUILTIN(0),

    /** Supplied by an organization, applying to every tenant beneath it. */
    ORGANIZATION(1),

    /** Supplied by one tenant, applying only to that tenant. */
    TENANT(2);

    private final long priorityFloor;

    /**
     * Creates an origin.
     *
     * @param rank the layer this origin occupies, higher being stronger
     */
    Origin(final long rank) {
      this.priorityFloor = rank;
    }

    /**
     * The base added to a rule's declared priority.
     *
     * <p>This is what makes layering total rather than a matter of who picked the larger number: a
     * tenant rule always outranks an organization rule, which always outranks a built-in, whatever
     * priorities they declare among themselves. A tenant cannot be overruled inside its own data by
     * an organization rule that simply chose {@code Integer.MAX_VALUE}, and a built-in cannot
     * silently win over a deliberate local decision.
     *
     * <p>A <em>rank</em> rather than an additive floor, and that distinction is load-bearing. The
     * first version added 0, 1000 and 2000 to the declared priority, which fails the moment a rule
     * declares a priority larger than the gap -- an organization rule at {@code Integer.MAX_VALUE}
     * outranked every tenant rule, exactly inverting the guarantee. The sabotage run for AD-029
     * SS11 exposed it: the priority sabotage was caught by nothing, because the test that should
     * have caught it was passing on integer overflow instead.
     *
     * @return the layer rank, higher being stronger
     */
    public long priorityFloor() {
      return priorityFloor;
    }
  }

  /** What a match means. */
  public enum Effect {

    /**
     * A match is sensitive content and becomes a span.
     *
     * <p>The mission's "deny rule": content matching this is flagged, and governance decides what
     * that costs.
     */
    DENY,

    /**
     * A match is explicitly not sensitive, and suppresses any detection overlapping it.
     *
     * <p>Necessary for the real world: a documentation corpus full of {@code 4111 1111 1111 1111},
     * a test tenant whose fixtures are all {@code @example.com}, a support system whose ticket
     * references look exactly like an insurance number. Without exemptions the only way to stop a
     * false positive is to weaken the detector for everyone.
     */
    ALLOW
  }

  /**
   * Validates the rule.
   *
   * @param id the identifier
   * @param version the version
   * @param priority the priority
   * @param origin who supplied it
   * @param effect what a match means
   * @param type what a match represents
   * @param category the regime
   * @param severity the harm level
   * @param confidence the base confidence
   * @param regex the pattern
   * @param caseInsensitive whether case is ignored
   * @param validator the additional check
   * @param contextKeywords the confidence-raising words
   */
  public PiiRule {
    Preconditions.requireNonBlank(id, "id");
    Preconditions.requireNonNull(origin, "origin");
    Preconditions.requireNonNull(effect, "effect");
    Preconditions.requireNonNull(type, "type");
    Preconditions.requireNonNull(category, "category");
    Preconditions.requireNonNull(severity, "severity");
    Preconditions.requireNonBlank(regex, "regex");
    Preconditions.requireNonNull(validator, "validator");
    contextKeywords = Set.copyOf(Preconditions.requireNonNull(contextKeywords, "contextKeywords"));
    requiredAnyOf = Set.copyOf(Preconditions.requireNonNull(requiredAnyOf, "requiredAnyOf"));
    if (version < 1) {
      throw new IllegalArgumentException("rule version must be at least 1");
    }
    if (confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be within [0, 1]");
    }
    if (category == PiiCategory.MULTIPLE) {
      throw new IllegalArgumentException("a rule may not declare the MULTIPLE summary category");
    }
  }

  /**
   * Builds a rule with no prefilter, so it runs against every body.
   *
   * @param id the identifier
   * @param version the version
   * @param priority the priority
   * @param origin who supplied it
   * @param effect what a match means
   * @param type what a match represents
   * @param category the regime
   * @param severity the harm level
   * @param confidence the base confidence
   * @param regex the pattern
   * @param caseInsensitive whether case is ignored
   * @param validator the additional check
   * @param contextKeywords the confidence-raising words
   */
  public PiiRule(
      final String id,
      final int version,
      final int priority,
      final Origin origin,
      final Effect effect,
      final PiiType type,
      final PiiCategory category,
      final PiiSeverity severity,
      final double confidence,
      final String regex,
      final boolean caseInsensitive,
      final PiiValidator validator,
      final Set<String> contextKeywords) {
    this(
        id,
        version,
        priority,
        origin,
        effect,
        type,
        category,
        severity,
        confidence,
        regex,
        caseInsensitive,
        validator,
        contextKeywords,
        Set.of(),
        false);
  }

  /**
   * Returns a copy of this rule that only runs when one of these substrings is present.
   *
   * <p>A rule whose pattern requires an at-sign, or the word "passport", cannot match a body that
   * contains neither. Checking that first is two orders of magnitude cheaper than running the
   * pattern, and it is exact rather than heuristic: the substrings must be things the pattern
   * genuinely cannot match without. Getting one wrong causes a false negative, so they are chosen
   * from the pattern's own mandatory literals and nowhere else.
   *
   * @param substrings lowercase substrings, any one of which permits the rule to run
   * @return the adjusted rule
   */
  public PiiRule requiring(final String... substrings) {
    return new PiiRule(
        id,
        version,
        priority,
        origin,
        effect,
        type,
        category,
        severity,
        confidence,
        regex,
        caseInsensitive,
        validator,
        contextKeywords,
        Set.of(substrings),
        requiresDigit);
  }

  /**
   * Returns a copy of this rule that only runs when the body contains a digit.
   *
   * @return the adjusted rule
   */
  public PiiRule requiringDigit() {
    return new PiiRule(
        id,
        version,
        priority,
        origin,
        effect,
        type,
        category,
        severity,
        confidence,
        regex,
        caseInsensitive,
        validator,
        contextKeywords,
        requiredAnyOf,
        true);
  }

  /**
   * Reports whether this rule could possibly match a body.
   *
   * @param lowered the body, already lowercased once by the caller
   * @param hasDigit whether the body contains any digit
   * @return true when the rule must be run
   */
  boolean couldMatch(final String lowered, final boolean hasDigit) {
    if (requiresDigit && !hasDigit) {
      return false;
    }
    if (requiredAnyOf.isEmpty()) {
      return true;
    }
    for (final String required : requiredAnyOf) {
      if (lowered.contains(required)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The rule's effective priority, including its origin's floor.
   *
   * @return the comparable priority
   */
  public long effectivePriority() {
    // Long arithmetic, not int. A rule declaring Integer.MAX_VALUE is unusual but legal, and adding
    // an origin floor to it overflows to a large negative number -- which would silently rank the
    // most emphatic rule in the set last. The sabotage run for AD-029 SS11 found exactly that: the
    // priority sabotage went undetected because the test that should have caught it was passing for
    // this reason rather than for the right one.
    // The rank occupies the high bits so that origin strictly dominates; the declared priority is
    // biased into an unsigned range so it orders correctly across the whole int domain. Exact in a
    // long: the largest value is 2 * 2^33 + 2^32, well inside range.
    return (origin.priorityFloor() << 33) + ((long) priority - Integer.MIN_VALUE);
  }

  /**
   * Builds a detection rule with the type's default category and severity.
   *
   * @param id the identifier
   * @param type what a match represents
   * @param regex the pattern
   * @param validator the additional check
   * @return the rule
   */
  public static PiiRule detecting(
      final String id, final PiiType type, final String regex, final PiiValidator validator) {
    return new PiiRule(
        id,
        1,
        0,
        Origin.BUILTIN,
        Effect.DENY,
        type,
        type.defaultCategory(),
        type.defaultSeverity(),
        0.8,
        regex,
        false,
        validator,
        Set.of());
  }

  /**
   * Returns a copy of this rule with a different origin and priority.
   *
   * @param newOrigin the origin to apply
   * @param newPriority the priority to apply
   * @return the adjusted rule
   */
  public PiiRule withOrigin(final Origin newOrigin, final int newPriority) {
    return new PiiRule(
        id,
        version,
        newPriority,
        newOrigin,
        effect,
        type,
        category,
        severity,
        confidence,
        regex,
        caseInsensitive,
        validator,
        contextKeywords,
        requiredAnyOf,
        requiresDigit);
  }

  /**
   * Returns a copy of this rule with different confidence and context keywords.
   *
   * @param newConfidence the base confidence
   * @param keywords words that raise confidence when near a match
   * @return the adjusted rule
   */
  public PiiRule scoring(final double newConfidence, final String... keywords) {
    return new PiiRule(
        id,
        version,
        priority,
        origin,
        effect,
        type,
        category,
        severity,
        newConfidence,
        regex,
        caseInsensitive,
        validator,
        Set.of(keywords),
        requiredAnyOf,
        requiresDigit);
  }

  /**
   * Returns a copy of this rule that ignores case.
   *
   * @return the adjusted rule
   */
  public PiiRule ignoringCase() {
    return new PiiRule(
        id,
        version,
        priority,
        origin,
        effect,
        type,
        category,
        severity,
        confidence,
        regex,
        true,
        validator,
        contextKeywords,
        requiredAnyOf,
        requiresDigit);
  }

  /**
   * Returns a copy of this rule with a different severity and category.
   *
   * @param newCategory the regime
   * @param newSeverity the harm level
   * @return the adjusted rule
   */
  public PiiRule classifiedAs(final PiiCategory newCategory, final PiiSeverity newSeverity) {
    return new PiiRule(
        id,
        version,
        priority,
        origin,
        effect,
        type,
        newCategory,
        newSeverity,
        confidence,
        regex,
        caseInsensitive,
        validator,
        contextKeywords,
        requiredAnyOf,
        requiresDigit);
  }
}
