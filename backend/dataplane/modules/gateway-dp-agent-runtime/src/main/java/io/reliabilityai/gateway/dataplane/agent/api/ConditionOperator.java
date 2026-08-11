package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The closed set of predicates a plan may express (AD-025 PLC-10).
 *
 * <p>Deliberately not an expression language. An arbitrary predicate language is a program, and a
 * program cannot be authorized as a value at admission, cannot be guaranteed total, and cannot be
 * guaranteed side-effect-free — all three of which the replayable interpreter depends on.
 *
 * <p>Every operator here is <b>total</b>: it returns a boolean for every input including a missing
 * subject, and throws for nothing. A predicate that could throw would turn a plan-authoring mistake
 * into a mid-run crash during failure handling, which is the worst place to discover it.
 */
public enum ConditionOperator {

  /** The subject exists and equals the operand exactly. */
  EQUALS,

  /** The subject is missing, or exists and differs from the operand. */
  NOT_EQUALS,

  /** The subject exists and contains the operand as a substring. */
  CONTAINS,

  /** The subject exists and does not contain the operand. */
  NOT_CONTAINS,

  /** The subject exists at all. The operand is ignored. */
  EXISTS,

  /** The subject does not exist. The operand is ignored. */
  ABSENT,

  /** Both parse as longs and the subject is greater. False when either does not parse. */
  GREATER_THAN,

  /** Both parse as longs and the subject is smaller. False when either does not parse. */
  LESS_THAN;

  /**
   * Evaluates the predicate.
   *
   * @param subject the recorded value the predicate is about, or null when no such value was
   *     recorded
   * @param operand the literal the plan declared
   * @return the predicate's value; never throws, for any input
   */
  public boolean test(final String subject, final String operand) {
    Preconditions.requireNonNull(operand, "operand");
    return switch (this) {
      case EXISTS -> subject != null;
      case ABSENT -> subject == null;
      case EQUALS -> operand.equals(subject);
      case NOT_EQUALS -> !operand.equals(subject);
      case CONTAINS -> subject != null && subject.contains(operand);
      case NOT_CONTAINS -> subject == null || !subject.contains(operand);
      case GREATER_THAN -> compareNumeric(subject, operand) > 0;
      case LESS_THAN -> compareNumeric(subject, operand) < 0;
    };
  }

  /**
   * Compares two values numerically, yielding "equal" when either is not a number.
   *
   * <p>Returning zero for unparseable input makes both {@link #GREATER_THAN} and {@link #LESS_THAN}
   * false, which is the safe reading: a numeric comparison against a non-number has no true answer,
   * and guessing one would silently take a branch the plan author never intended.
   */
  private static int compareNumeric(final String subject, final String operand) {
    if (subject == null) {
      return 0;
    }
    try {
      return Long.compare(Long.parseLong(subject.trim()), Long.parseLong(operand.trim()));
    } catch (final NumberFormatException notNumeric) {
      return 0;
    }
  }
}
