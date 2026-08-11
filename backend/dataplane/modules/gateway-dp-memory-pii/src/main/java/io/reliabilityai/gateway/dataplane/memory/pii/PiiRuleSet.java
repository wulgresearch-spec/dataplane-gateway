package io.reliabilityai.gateway.dataplane.memory.pii;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A compiled, immutable, ordered set of rules (AD-029 §5.2).
 *
 * <p>Compiled once and read many times, never mutated. Installing new rules produces a new set that
 * replaces the old one by reference, which is what lets detection run entirely without locks — the
 * same compile-once, swap-atomically shape AD-026 uses for policy (MEM-18, MEM-27).
 *
 * <p>Rules arrive already sorted by descending effective priority, so overlap resolution is a
 * linear walk rather than a sort per document.
 */
public final class PiiRuleSet {

  private final List<Compiled> rules;

  private final long version;

  /**
   * Creates a rule set. Use {@link PiiRuleCompiler} rather than calling this directly.
   *
   * @param rules the compiled rules, already ordered
   * @param version the version of this set
   */
  PiiRuleSet(final List<Compiled> rules, final long version) {
    this.rules = List.copyOf(Preconditions.requireNonNull(rules, "rules"));
    this.version = version;
  }

  /**
   * An empty set, which detects nothing.
   *
   * <p>Useful only for tests that need to prove the engine's aggregation separately from its rules.
   * A deployment running this would classify every body as clean, which is why it is not the
   * default anywhere.
   *
   * @return an empty rule set at version zero
   */
  public static PiiRuleSet empty() {
    return new PiiRuleSet(List.of(), 0L);
  }

  /**
   * The compiled rules, in descending priority order.
   *
   * @return the rules
   */
  List<Compiled> rules() {
    return rules;
  }

  /**
   * How many rules this set holds.
   *
   * @return the rule count
   */
  public int size() {
    return rules.size();
  }

  /**
   * This set's version.
   *
   * <p>Recorded on every {@link PiiDetection} so that a stored classification can be traced to the
   * exact rules that produced it — without which, re-classifying old records after a rule change is
   * guesswork.
   *
   * @return the version
   */
  public long version() {
    return version;
  }

  /**
   * The identifiers of every rule in this set, in priority order.
   *
   * @return the rule identifiers
   */
  public List<String> ruleIds() {
    return rules.stream().map(compiled -> compiled.rule().id()).toList();
  }

  /**
   * One rule together with its compiled pattern.
   *
   * @param rule the declarative rule
   * @param pattern the compiled pattern
   */
  record Compiled(PiiRule rule, Pattern pattern) {}
}
