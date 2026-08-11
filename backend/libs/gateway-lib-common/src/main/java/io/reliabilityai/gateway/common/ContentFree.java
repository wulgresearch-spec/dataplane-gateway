package io.reliabilityai.gateway.common;

/**
 * Marker for value objects that are guaranteed to be content-free (Doc 14 §7.1, Doc 27 §15.1).
 *
 * <p>A {@code ContentFree} type carries only ids, counts, categories, and versions — never prompt
 * or completion content, secrets, or PII. Audit records (Doc 13 §19) and telemetry (Doc 27)
 * implement this marker; a leak scanner (Doc 15 T-052) verifies conformance.
 */
public interface ContentFree {}
