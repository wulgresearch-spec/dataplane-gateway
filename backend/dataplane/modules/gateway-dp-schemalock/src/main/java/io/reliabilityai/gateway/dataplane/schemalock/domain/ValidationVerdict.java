package io.reliabilityai.gateway.dataplane.schemalock.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * The authoritative validation verdict from the compiled-schema validator (Doc 17 §5/§21).
 * Conformant iff the completion validation passed; otherwise it carries the neutral {@link
 * FailureClass} and the structural {@link SchemaViolation}s (Doc 17 §22). Immutable; violations
 * defensively copied.
 *
 * @param conformant whether the output conforms to the compiled schema
 * @param violations the structural violations (empty iff conformant)
 * @param classification the neutral failure class (null iff conformant)
 */
public record ValidationVerdict(
    boolean conformant, List<SchemaViolation> violations, FailureClass classification) {

  /**
   * Compact constructor enforcing the conformant/non-conformant invariants and copying violations.
   */
  public ValidationVerdict {
    violations = violations == null ? List.of() : List.copyOf(violations);
    if (conformant) {
      if (classification != null || !violations.isEmpty()) {
        throw new IllegalArgumentException(
            "conformant verdict carries no classification/violations");
      }
    } else if (classification == null) {
      throw new IllegalArgumentException("non-conformant verdict requires a classification");
    }
  }

  /**
   * Creates a conformant (passed) verdict.
   *
   * @return a conformant verdict
   */
  public static ValidationVerdict passed() {
    return new ValidationVerdict(true, List.of(), null);
  }

  /**
   * Creates a non-conformant verdict (Doc 17 §25).
   *
   * @param classification the neutral failure class (non-conformant)
   * @param violations the structural violations
   * @return a non-conformant verdict
   */
  public static ValidationVerdict nonConformant(
      final FailureClass classification, final List<SchemaViolation> violations) {
    Preconditions.requireNonNull(classification, "classification");
    return new ValidationVerdict(false, violations, classification);
  }
}
