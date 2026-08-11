package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.dataplane.schemalock.domain.ValidationVerdict;

/**
 * The replaceable JSON Schema validator seam (Doc 17 §9/§11, SL-D6, AD-002). SchemaLock binds to
 * <b>no</b> concrete validator library (SL-A2); the actual JSON Schema Draft 2020-12 engine —
 * parsing, meta-schema validation, remote-{@code $ref} rejection (Doc 17 §33), and conformance
 * validation — lives behind this port as a swappable adapter (a hand-rolled validator is explicitly
 * rejected, SL-D6). The validation core it implements is a pure, deterministic function of {@code
 * (CompiledSchema, output)} (Doc 17 §28) with stable violation ordering.
 *
 * <p>Deliberately <b>not implemented in this module</b>: the concrete validator requires a real
 * JSON Schema library (and JSON parser), which is the infra dependency this seam abstracts.
 */
public interface SchemaValidatorPort {

  /**
   * Compiles a caller schema into an immutable validation plan (Doc 17 §9). Meta-schema-validates
   * and rejects remote {@code $ref} / unsupported dialects.
   *
   * @param schema the caller output schema
   * @return the compiled schema handle (carries the complexity score)
   * @throws SchemaCompilationException if the schema is invalid ({@code SCHEMA_INVALID}, caller
   *     error)
   */
  CompiledSchema compile(OutputSchema schema) throws SchemaCompilationException;

  /**
   * The authoritative completion validation (Doc 17 §21) — this verdict alone decides conformance
   * (SL-INV). Pure and deterministic.
   *
   * @param schema the compiled schema
   * @param outputJson the (envelope-normalized) output JSON text
   * @return the validation verdict
   */
  ValidationVerdict validate(CompiledSchema schema, String outputJson);

  /**
   * An advisory incremental check (Doc 17 §19/§19.1) — returns whether the partial object is
   * already <b>structurally impossible</b> to satisfy (fail-fast only). It never makes a
   * conformance decision; the completion validation is authoritative.
   *
   * @param schema the compiled schema
   * @param partialJson the reconstructed partial JSON so far
   * @return {@code true} if the partial state already provably cannot conform
   */
  boolean isStructurallyImpossible(CompiledSchema schema, String partialJson);
}
