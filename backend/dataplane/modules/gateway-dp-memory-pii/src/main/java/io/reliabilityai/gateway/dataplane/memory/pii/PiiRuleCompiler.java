package io.reliabilityai.gateway.dataplane.memory.pii;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns declarative rules into an ordered, compiled rule set (AD-029 §5.2).
 *
 * <p>Compilation is where every rule that would misbehave at scan time is refused, because the cost
 * of a bad rule is paid once here and on every write forever after. Refusals are loud and name the
 * rule.
 *
 * <p><b>What compilation guarantees.</b> Identifiers are unique, patterns parse, no pattern can
 * match the empty string, and ordering is total and deterministic. What it deliberately does
 * <em>not</em> attempt is proving a pattern safe: deciding whether a regular expression backtracks
 * catastrophically is not something a syntactic check does reliably, and every published "dangerous
 * construct" blacklist has been bypassed. The engine bounds the work instead — see {@link
 * ScanBudget}.
 */
public final class PiiRuleCompiler {

  /** Versions are monotonic per JVM so that a set is never confused with an earlier one. */
  private static final AtomicLong VERSIONS = new AtomicLong();

  private PiiRuleCompiler() {}

  /**
   * Compiles the built-in rules alone.
   *
   * @return the compiled built-in set
   */
  public static PiiRuleSet builtIn() {
    return compile(BuiltInRules.all());
  }

  /**
   * Compiles the built-in rules together with organization and tenant additions.
   *
   * <p>Layering is by {@link PiiRule.Origin}, and the ordering it produces is total: every tenant
   * rule outranks every organization rule, which outranks every built-in. Within a layer, declared
   * priority decides; on a tie, the identifier decides, so the result never depends on the order
   * rules happened to arrive in.
   *
   * @param organization rules applying to every tenant beneath an organization
   * @param tenant rules applying to a single tenant
   * @return the compiled set
   * @throws PiiRuleCompilationException when any rule is unusable
   */
  public static PiiRuleSet compileLayered(
      final List<PiiRule> organization, final List<PiiRule> tenant) {
    final List<PiiRule> merged = new ArrayList<>(BuiltInRules.all());
    for (final PiiRule rule : organization) {
      merged.add(rule.withOrigin(PiiRule.Origin.ORGANIZATION, rule.priority()));
    }
    for (final PiiRule rule : tenant) {
      merged.add(rule.withOrigin(PiiRule.Origin.TENANT, rule.priority()));
    }
    return compile(merged);
  }

  /**
   * Compiles an explicit list of rules.
   *
   * @param rules the rules to compile
   * @return the compiled set
   * @throws PiiRuleCompilationException when any rule is unusable
   */
  public static PiiRuleSet compile(final List<PiiRule> rules) {
    final Set<String> seen = new HashSet<>();
    final List<PiiRuleSet.Compiled> compiled = new ArrayList<>(rules.size());
    for (final PiiRule rule : rules) {
      if (!seen.add(rule.id())) {
        // Silently keeping one of two rules with the same id would make behaviour depend on list
        // order, and would hide a tenant's rule behind a built-in of the same name.
        throw new PiiRuleCompilationException(rule.id(), "duplicate rule identifier");
      }
      compiled.add(new PiiRuleSet.Compiled(rule, patternFor(rule)));
    }
    compiled.sort(
        Comparator.comparingLong((PiiRuleSet.Compiled c) -> c.rule().effectivePriority())
            .reversed()
            .thenComparing(c -> c.rule().id()));
    return new PiiRuleSet(compiled, VERSIONS.incrementAndGet());
  }

  /**
   * Compiles and checks one rule's pattern.
   *
   * @param rule the rule
   * @return the compiled pattern
   * @throws PiiRuleCompilationException when the pattern will not work
   */
  private static Pattern patternFor(final PiiRule rule) {
    final Pattern pattern;
    try {
      pattern =
          Pattern.compile(
              rule.regex(),
              rule.caseInsensitive() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    } catch (final PatternSyntaxException malformed) {
      // The message is the engine's own description of the syntax error and quotes only the
      // pattern,
      // which is the rule author's text rather than anyone's data.
      throw new PiiRuleCompilationException(rule.id(), malformed.getDescription());
    }
    if (pattern.matcher("").find()) {
      // A pattern that matches nothing at all matches everywhere, producing a zero-width span at
      // every offset. The engine would spend the whole document emitting them.
      throw new PiiRuleCompilationException(rule.id(), "pattern matches the empty string");
    }
    return pattern;
  }

  /** Raised when a rule cannot be compiled into something safe to run. */
  public static final class PiiRuleCompilationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String ruleId;

    /**
     * Creates the exception.
     *
     * @param ruleId which rule was rejected
     * @param reason why, quoting no scanned data
     */
    PiiRuleCompilationException(final String ruleId, final String reason) {
      super("rule " + ruleId + " rejected: " + reason);
      this.ruleId = ruleId;
    }

    /**
     * Which rule was rejected.
     *
     * @return the rule identifier
     */
    public String ruleId() {
      return ruleId;
    }
  }
}
