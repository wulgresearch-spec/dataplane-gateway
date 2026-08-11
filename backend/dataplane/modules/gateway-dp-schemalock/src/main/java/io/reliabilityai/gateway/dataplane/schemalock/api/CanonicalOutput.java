package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation;
import io.reliabilityai.gateway.dataplane.schemalock.domain.Strategy;
import java.util.List;

/**
 * The provider-neutral canonical output model (Doc 17 §27, SL-D10) — identical in shape across
 * every provider (AD-007), with <b>zero provider leakage</b> (Doc 12 §16.10). The validated value
 * is present <b>iff</b> {@code conformant} (only completion-validated output is ever delivered,
 * SL-INV); a non-conforming result is a first-class explicit outcome carrying the neutral
 * classification and structural violations (never a {@code 200}-with-bad-body, Doc 17 §26).
 * Immutable.
 *
 * @param conformant whether the output was completion-validated as conformant
 * @param value the validated output JSON (present iff conformant; null otherwise)
 * @param schemaId the content-hash schema id
 * @param schemaVersion the schema version
 * @param strategyUsed the elicitation strategy used
 * @param attempts the number of generation attempts made
 * @param repairApplied whether a safe (NR-1) normalization was applied
 * @param classification the neutral failure class (present iff not conformant)
 * @param violations the structural violations (present iff not conformant; never raw values)
 */
public record CanonicalOutput(
    boolean conformant,
    String value,
    SchemaId schemaId,
    String schemaVersion,
    Strategy strategyUsed,
    int attempts,
    boolean repairApplied,
    FailureClass classification,
    List<SchemaViolation> violations) {

  /** Compact constructor enforcing the conformant/surfaced invariants and copying violations. */
  public CanonicalOutput {
    Preconditions.requireNonNull(schemaId, "schemaId");
    Preconditions.requireNonBlank(schemaVersion, "schemaVersion");
    Preconditions.requireNonNull(strategyUsed, "strategyUsed");
    if (attempts < 0) {
      throw new IllegalArgumentException("attempts must be non-negative");
    }
    violations = violations == null ? List.of() : List.copyOf(violations);
    if (conformant) {
      if (value == null || classification != null || !violations.isEmpty()) {
        throw new IllegalArgumentException(
            "conformant output requires a value and no classification/violations");
      }
    } else if (value != null || classification == null) {
      throw new IllegalArgumentException(
          "surfaced output carries no value and requires a classification");
    }
  }

  /**
   * Creates a conformant canonical output (Doc 17 §27) — only ever from a passed completion
   * verdict.
   *
   * @param value the validated output JSON
   * @param schema the compiled schema
   * @param strategyUsed the strategy used
   * @param attempts the attempts made
   * @param repairApplied whether NR-1 repair was applied
   * @return a conformant canonical output
   */
  public static CanonicalOutput conformant(
      final String value,
      final CompiledSchema schema,
      final Strategy strategyUsed,
      final int attempts,
      final boolean repairApplied) {
    Preconditions.requireNonNull(value, "value");
    Preconditions.requireNonNull(schema, "schema");
    return new CanonicalOutput(
        true,
        value,
        schema.schemaId(),
        schema.version(),
        strategyUsed,
        attempts,
        repairApplied,
        null,
        List.of());
  }

  /**
   * Creates a surfaced (non-conforming) canonical output (Doc 17 §26/§48) — never carries a value.
   *
   * @param classification the neutral failure class
   * @param violations the structural violations
   * @param schema the compiled schema
   * @param strategyUsed the strategy used
   * @param attempts the attempts made
   * @param repairApplied whether NR-1 repair was applied
   * @return a surfaced canonical output
   */
  public static CanonicalOutput surfaced(
      final FailureClass classification,
      final List<SchemaViolation> violations,
      final CompiledSchema schema,
      final Strategy strategyUsed,
      final int attempts,
      final boolean repairApplied) {
    Preconditions.requireNonNull(classification, "classification");
    Preconditions.requireNonNull(schema, "schema");
    return new CanonicalOutput(
        false,
        null,
        schema.schemaId(),
        schema.version(),
        strategyUsed,
        attempts,
        repairApplied,
        classification,
        violations);
  }
}
