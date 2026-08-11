package io.reliabilityai.gateway.dataplane.schemalock.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A structural schema violation (Doc 17 §22) — <b>metadata only, never raw values</b> (Doc 17 §37,
 * Doc 14 §7.1). It records where and how the output failed, never what the value was, so it is safe
 * for telemetry, retry feedback, and surfaced errors.
 *
 * @param pointer the JSON Pointer to the offending location
 * @param keyword the schema keyword that failed (e.g. {@code type}, {@code enum}, {@code required})
 * @param expected the neutral description of what was expected (no raw value)
 * @param actualKind the neutral kind of what was found (e.g. {@code string}, {@code missing}) — no
 *     value
 */
public record SchemaViolation(String pointer, String keyword, String expected, String actualKind) {

  /** Compact constructor validating the structural fields. */
  public SchemaViolation {
    Preconditions.requireNonBlank(pointer, "pointer");
    Preconditions.requireNonBlank(keyword, "keyword");
    Preconditions.requireNonNull(expected, "expected");
    Preconditions.requireNonNull(actualKind, "actualKind");
  }
}
