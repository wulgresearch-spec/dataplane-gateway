package io.reliabilityai.gateway.dataplane.schemalock.api;

/**
 * The SchemaLock inbound port (Doc 17 §6) — the non-bypassable structured-output correctness entry
 * point (AD-018). It compiles/caches caller schemas, and executes structured generation in batch or
 * streaming, always validating before delivery: any returned value is completion-validated or the
 * request is surfaced as non-conforming — never silently incorrect (SL-INV, BRULE-1).
 */
public interface SchemaLockPort {

  /**
   * Compiles and caches a caller schema (Doc 17 §9/§10) — idempotent, content-hash-keyed.
   *
   * @param schema the caller output schema
   * @return the immutable compiled-schema handle
   * @throws SchemaCompilationException if the schema is invalid (caller error, fail fast)
   */
  CompiledSchema compile(OutputSchema schema) throws SchemaCompilationException;

  /**
   * Executes a batch structured-output request end to end (Doc 17 §8 batch): strategy → generate →
   * completion-validate → resolve (repair/guided-retry) → conformant output or surfaced
   * non-conformance.
   *
   * @param request the structured-output request
   * @param schema the compiled schema
   * @return the canonical output (conformant or surfaced)
   */
  CanonicalOutput executeStructured(StructuredOutputRequest request, CompiledSchema schema);

  /**
   * Opens a streaming structured-output session (Doc 17 §8/§18), terminal-gated on completion
   * validation.
   *
   * @param request the structured-output request
   * @param schema the compiled schema
   * @param source the StreamGuard delta/verdict source
   * @return the streaming session
   */
  StructuredStreamSession openStream(
      StructuredOutputRequest request, CompiledSchema schema, StreamSourcePort source);
}
