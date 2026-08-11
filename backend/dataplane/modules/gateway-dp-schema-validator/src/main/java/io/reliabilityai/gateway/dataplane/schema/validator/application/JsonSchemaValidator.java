package io.reliabilityai.gateway.dataplane.schema.validator.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schema.validator.api.SchemaValidatorConfig;
import io.reliabilityai.gateway.dataplane.schema.validator.domain.CompiledSchemaGraph;
import io.reliabilityai.gateway.dataplane.schema.validator.domain.SchemaCompiler;
import io.reliabilityai.gateway.dataplane.schema.validator.domain.ValidationEngine;
import io.reliabilityai.gateway.dataplane.schema.validator.internal.Json;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.OutputSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaCompilationException;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaValidatorPort;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation;
import io.reliabilityai.gateway.dataplane.schemalock.domain.ValidationVerdict;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The frozen {@link SchemaValidatorPort} implemented over a JSON Schema 2020-12 subset (Doc 17).
 *
 * <p><b>Compile once, validate many.</b> {@link CompiledSchema} is a value — id, version and
 * complexity only — so it cannot carry the compiled form. The compiled graph therefore lives in
 * this validator, keyed by {@link SchemaId}, which is the content hash of the schema text. That
 * hash is what makes the cache safe: two schemas with the same id are byte-identical by
 * construction, so a cache hit can never validate against the wrong schema.
 *
 * <p><b>Fail closed.</b> Every failure path — malformed schema, malformed output, a bound exceeded,
 * an unexpected fault — produces a non-conformant verdict with a named {@link FailureClass}.
 * Nothing here throws into the pipeline, and no path returns "conformant" for output that was not
 * fully checked.
 *
 * <p>Thread-safe: the cache is concurrent, compiled graphs are immutable, and the engine is
 * stateless.
 */
public final class JsonSchemaValidator implements SchemaValidatorPort {

  private final SchemaValidatorConfig config;
  private final ValidationEngine engine;
  private final Map<SchemaId, CompiledSchemaGraph> compiled = new ConcurrentHashMap<>();

  /**
   * Creates the validator.
   *
   * @param config the compilation and validation bounds
   */
  public JsonSchemaValidator(final SchemaValidatorConfig config) {
    this.config = Preconditions.requireNonNull(config, "config");
    this.engine = new ValidationEngine(config);
  }

  @Override
  public CompiledSchema compile(final OutputSchema schema) throws SchemaCompilationException {
    Preconditions.requireNonNull(schema, "schema");
    final CompiledSchemaGraph graph;
    try {
      // A fresh compiler per call: it carries per-compilation counters, so sharing one would let
      // two
      // concurrent compilations corrupt each other's node budget.
      graph = new SchemaCompiler(config).compile(schema.schemaText());
    } catch (final SchemaCompiler.CompilationFailure failure) {
      throw new SchemaCompilationException(failure.getMessage());
    } catch (final RuntimeException unexpected) {
      throw new SchemaCompilationException("schema-compilation-failed");
    }

    if (compiled.size() >= config.maxCachedSchemas()) {
      // Bounded cache: schemas are tenant-supplied, so an unbounded map is a memory leak with a
      // trivial trigger. Clearing costs a recompile; growing without limit costs the process.
      compiled.clear();
    }
    compiled.put(schema.schemaId(), graph);
    return new CompiledSchema(schema.schemaId(), schema.version(), graph.complexity());
  }

  @Override
  public ValidationVerdict validate(final CompiledSchema schema, final String outputJson) {
    if (schema == null) {
      return ValidationVerdict.nonConformant(FailureClass.SCHEMA_INVALID, List.of());
    }
    final CompiledSchemaGraph graph = compiled.get(schema.schemaId());
    if (graph == null) {
      // The schema was never compiled here, or was evicted. Guessing would mean validating against
      // nothing and calling the result conformant.
      return ValidationVerdict.nonConformant(
          FailureClass.SCHEMA_INVALID,
          List.of(new SchemaViolation("/", "$schema", "compiled schema", "absent")));
    }
    if (outputJson == null || outputJson.length() > config.maxInstanceBytes()) {
      return ValidationVerdict.nonConformant(
          FailureClass.RESOURCE_EXCEEDED,
          List.of(
              new SchemaViolation(
                  "/", "maxBytes", "<= " + config.maxInstanceBytes(), "oversized")));
    }

    final Json.JsonValue instance;
    try {
      instance = Json.parse(outputJson, config.maxDepth(), config.maxInstanceNodes());
    } catch (final Json.JsonException malformed) {
      return ValidationVerdict.nonConformant(
          FailureClass.MALFORMED,
          List.of(new SchemaViolation("/", "json", "well-formed json", malformed.getMessage())));
    }

    final List<SchemaViolation> violations;
    try {
      violations = engine.validate(graph, instance);
    } catch (final RuntimeException unexpected) {
      return ValidationVerdict.nonConformant(
          FailureClass.SCHEMA_INVALID,
          List.of(new SchemaViolation("/", "validation", "completed", "failed")));
    }

    return violations.isEmpty()
        ? ValidationVerdict.passed()
        : ValidationVerdict.nonConformant(FailureClass.NON_CONFORMANT, violations);
  }

  /**
   * Whether a partial document can already be proven not to satisfy the schema.
   *
   * <p><b>Deliberately conservative.</b> This drives early cancellation of a stream, so a false
   * positive kills a response that would have been valid — far worse than letting a doomed stream
   * run to completion. It therefore answers {@code true} only when the partial text is already
   * complete, parseable and non-conformant; a truncated document is never called impossible,
   * because the missing remainder could still satisfy every constraint.
   *
   * @param schema the compiled schema
   * @param partialJson the partial output
   * @return {@code true} only when the partial output definitively cannot conform
   */
  @Override
  public boolean isStructurallyImpossible(final CompiledSchema schema, final String partialJson) {
    if (schema == null || partialJson == null || partialJson.isBlank()) {
      return false;
    }
    final CompiledSchemaGraph graph = compiled.get(schema.schemaId());
    if (graph == null) {
      return false;
    }
    if (partialJson.length() > config.maxInstanceBytes()) {
      return true; // already past the ceiling; no continuation makes it smaller
    }
    final Json.JsonValue instance;
    try {
      instance = Json.parse(partialJson, config.maxDepth(), config.maxInstanceNodes());
    } catch (final Json.JsonException incomplete) {
      return false; // truncated, not impossible — the rest may still arrive
    }
    try {
      return !engine.validate(graph, instance).isEmpty();
    } catch (final RuntimeException unexpected) {
      return false;
    }
  }

  /**
   * The number of schemas currently cached, for operational visibility.
   *
   * @return the cache size
   */
  public int cachedSchemaCount() {
    return compiled.size();
  }
}
