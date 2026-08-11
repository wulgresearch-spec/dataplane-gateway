package io.reliabilityai.gateway.dataplane.schema.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.schema.validator.api.SchemaValidatorConfig;
import io.reliabilityai.gateway.dataplane.schema.validator.application.JsonSchemaValidator;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.OutputSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaCompilationException;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation;
import io.reliabilityai.gateway.dataplane.schemalock.domain.ValidationVerdict;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Keyword coverage, resource bounds, determinism and concurrency for the schema validator. */
class JsonSchemaValidatorTest {

  private final JsonSchemaValidator validator =
      new JsonSchemaValidator(SchemaValidatorConfig.defaults());

  private CompiledSchema compile(final String schemaText) {
    try {
      return validator.compile(OutputSchema.of(schemaText, "v1"));
    } catch (final SchemaCompilationException e) {
      throw new IllegalStateException("unexpected compilation failure: " + e.getMessage(), e);
    }
  }

  private ValidationVerdict validate(final String schemaText, final String instance) {
    return validator.validate(compile(schemaText), instance);
  }

  private static void assertConformant(final ValidationVerdict verdict) {
    assertThat(verdict.conformant())
        .withFailMessage("expected conformant but got %s", verdict.violations())
        .isTrue();
  }

  private static List<String> keywords(final ValidationVerdict verdict) {
    return verdict.violations().stream().map(SchemaViolation::keyword).toList();
  }

  // ---- objects -------------------------------------------------------------------------------

  @Test
  void acceptsAValidObject() {
    assertConformant(
        validate(
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"age\":{\"type\":\"integer\"}},\"required\":[\"name\"]}",
            "{\"name\":\"ada\",\"age\":36}"));
  }

  @Test
  void reportsMissingRequiredFieldsWithPointerAndKeyword() {
    final ValidationVerdict verdict =
        validate(
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"required\":[\"a\",\"b\"]}",
            "{}");

    assertThat(verdict.conformant()).isFalse();
    assertThat(verdict.classification()).isEqualTo(FailureClass.NON_CONFORMANT);
    assertThat(verdict.violations()).hasSize(2);
    assertThat(verdict.violations().get(0).keyword()).isEqualTo("required");
    assertThat(verdict.violations().get(0).pointer()).isEqualTo("/");
    // Sorted required names make the report order stable.
    assertThat(verdict.violations().get(0).expected()).isEqualTo("a");
    assertThat(verdict.violations().get(1).expected()).isEqualTo("b");
  }

  @Test
  void reportsAWrongTypeWithTheFoundKindNotTheValue() {
    final ValidationVerdict verdict =
        validate(
            "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}",
            "{\"n\":\"not-a-number\"}");

    final SchemaViolation violation = verdict.violations().get(0);
    assertThat(violation.pointer()).isEqualTo("/n");
    assertThat(violation.keyword()).isEqualTo("type");
    assertThat(violation.expected()).isEqualTo("integer");
    // The kind, never the value: violations reach telemetry and must not leak model output.
    assertThat(violation.actualKind()).isEqualTo("string");
  }

  @Test
  void validatesNestedObjects() {
    final String schema =
        "{\"type\":\"object\",\"properties\":{\"user\":{\"type\":\"object\","
            + "\"properties\":{\"email\":{\"type\":\"string\"}},\"required\":[\"email\"]}}}";

    assertConformant(validate(schema, "{\"user\":{\"email\":\"a@b.c\"}}"));
    final ValidationVerdict verdict = validate(schema, "{\"user\":{}}");
    assertThat(verdict.violations().get(0).pointer()).isEqualTo("/user");
  }

  @Test
  void enforcesAdditionalPropertiesFalse() {
    final String schema =
        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
            + "\"additionalProperties\":false}";

    assertConformant(validate(schema, "{\"a\":\"x\"}"));
    final ValidationVerdict verdict = validate(schema, "{\"a\":\"x\",\"b\":1}");
    assertThat(verdict.violations().get(0).keyword()).isEqualTo("additionalProperties");
    assertThat(verdict.violations().get(0).pointer()).isEqualTo("/b");
  }

  @Test
  void appliesAnAdditionalPropertiesSchema() {
    final String schema =
        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":{\"type\":\"number\"}}";

    assertConformant(validate(schema, "{\"x\":1,\"y\":2.5}"));
    assertThat(validate(schema, "{\"x\":\"nope\"}").conformant()).isFalse();
  }

  @Test
  void escapesJsonPointerSegments() {
    final ValidationVerdict verdict =
        validate(
            "{\"type\":\"object\",\"properties\":{\"a/b~c\":{\"type\":\"integer\"}}}",
            "{\"a/b~c\":\"x\"}");

    assertThat(verdict.violations().get(0).pointer()).isEqualTo("/a~1b~0c");
  }

  // ---- arrays --------------------------------------------------------------------------------

  @Test
  void validatesArrayItems() {
    final String schema = "{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}";

    assertConformant(validate(schema, "[1,2,3]"));
    final ValidationVerdict verdict = validate(schema, "[1,\"two\",3]");
    assertThat(verdict.violations().get(0).pointer()).isEqualTo("/1");
  }

  @Test
  void validatesPrefixItemsPositionally() {
    final String schema =
        "{\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"},{\"type\":\"integer\"}],"
            + "\"items\":{\"type\":\"boolean\"}}";

    assertConformant(validate(schema, "[\"a\",1,true,false]"));
    assertThat(validate(schema, "[\"a\",\"b\"]").violations().get(0).pointer()).isEqualTo("/1");
    assertThat(validate(schema, "[\"a\",1,\"c\"]").violations().get(0).pointer()).isEqualTo("/2");
  }

  @Test
  void enforcesArrayLengthBounds() {
    final String schema = "{\"type\":\"array\",\"minItems\":2,\"maxItems\":3}";

    assertConformant(validate(schema, "[1,2]"));
    assertThat(keywords(validate(schema, "[1]"))).contains("minItems");
    assertThat(keywords(validate(schema, "[1,2,3,4]"))).contains("maxItems");
  }

  @Test
  void enforcesUniqueItems() {
    final String schema = "{\"type\":\"array\",\"uniqueItems\":true}";

    assertConformant(validate(schema, "[1,2,3]"));
    assertThat(keywords(validate(schema, "[1,2,1]"))).contains("uniqueItems");
    // Numeric equality is mathematical, so 1 and 1.0 are the same element.
    assertThat(keywords(validate(schema, "[1,1.0]"))).contains("uniqueItems");
    assertThat(keywords(validate(schema, "[{\"a\":1},{\"a\":1}]"))).contains("uniqueItems");
  }

  // ---- scalars -------------------------------------------------------------------------------

  @Test
  void enforcesStringLengthAndPattern() {
    final String schema =
        "{\"type\":\"string\",\"minLength\":2,\"maxLength\":5,\"pattern\":\"^[a-z]+$\"}";

    assertConformant(validate(schema, "\"abc\""));
    assertThat(keywords(validate(schema, "\"a\""))).contains("minLength");
    assertThat(keywords(validate(schema, "\"abcdefg\""))).contains("maxLength");
    assertThat(keywords(validate(schema, "\"ABC\""))).contains("pattern");
  }

  @Test
  void measuresStringLengthInCodePoints() {
    // One emoji is one character to a schema author, though Java stores it as a surrogate pair.
    assertConformant(validate("{\"type\":\"string\",\"maxLength\":1}", "\"\\uD83D\\uDE00\""));
  }

  @Test
  void enforcesNumericBounds() {
    final String inclusive = "{\"type\":\"number\",\"minimum\":0,\"maximum\":10}";
    assertConformant(validate(inclusive, "0"));
    assertConformant(validate(inclusive, "10"));
    assertThat(keywords(validate(inclusive, "-1"))).contains("minimum");
    assertThat(keywords(validate(inclusive, "11"))).contains("maximum");

    final String exclusive = "{\"type\":\"number\",\"exclusiveMinimum\":0,\"exclusiveMaximum\":10}";
    assertConformant(validate(exclusive, "5"));
    assertThat(keywords(validate(exclusive, "0"))).contains("exclusiveMinimum");
    assertThat(keywords(validate(exclusive, "10"))).contains("exclusiveMaximum");
  }

  @Test
  void distinguishesIntegerFromNumber() {
    assertConformant(validate("{\"type\":\"integer\"}", "42"));
    // 1.0 is mathematically an integer; 1.5 is not.
    assertConformant(validate("{\"type\":\"integer\"}", "1.0"));
    assertThat(keywords(validate("{\"type\":\"integer\"}", "1.5"))).contains("type");
  }

  @Test
  void preservesPrecisionBeyondDoubleRange() {
    // A double would round these two to the same value and wrongly accept the second.
    final String schema = "{\"type\":\"integer\",\"maximum\":10000000000000000000}";
    assertConformant(validate(schema, "10000000000000000000"));
    assertThat(keywords(validate(schema, "10000000000000000001"))).contains("maximum");
  }

  @Test
  void enforcesEnumAndConst() {
    assertConformant(validate("{\"enum\":[\"a\",\"b\"]}", "\"a\""));
    assertThat(keywords(validate("{\"enum\":[\"a\",\"b\"]}", "\"c\""))).contains("enum");

    assertConformant(validate("{\"const\":42}", "42"));
    assertThat(keywords(validate("{\"const\":42}", "43"))).contains("const");
  }

  @Test
  void acceptsMultipleDeclaredTypes() {
    final String schema = "{\"type\":[\"string\",\"null\"]}";

    assertConformant(validate(schema, "\"x\""));
    assertConformant(validate(schema, "null"));
    assertThat(keywords(validate(schema, "1"))).contains("type");
  }

  // ---- combinators ---------------------------------------------------------------------------

  @Test
  void enforcesAllOf() {
    final String schema = "{\"allOf\":[{\"type\":\"string\"},{\"minLength\":3}]}";

    assertConformant(validate(schema, "\"abc\""));
    assertThat(validate(schema, "\"ab\"").conformant()).isFalse();
  }

  @Test
  void enforcesAnyOf() {
    final String schema = "{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}";

    assertConformant(validate(schema, "\"x\""));
    assertConformant(validate(schema, "1"));
    assertThat(keywords(validate(schema, "true"))).contains("anyOf");
  }

  @Test
  void enforcesOneOfExactlyOnce() {
    final String disjoint = "{\"oneOf\":[{\"type\":\"integer\"},{\"type\":\"string\"}]}";
    assertConformant(validate(disjoint, "5"));
    assertConformant(validate(disjoint, "\"x\""));
    assertThat(keywords(validate(disjoint, "true"))).contains("oneOf"); // matches neither

    final String overlapping = "{\"oneOf\":[{\"type\":\"integer\"},{\"minimum\":100}]}";
    assertConformant(validate(overlapping, "5")); // integer only
    assertThat(keywords(validate(overlapping, "200"))).contains("oneOf"); // both branches match
  }

  @Test
  void numericKeywordsDoNotConstrainNonNumbers() {
    // Per the specification, `minimum` applies only to numbers — a string satisfies it vacuously.
    // This is easy to get wrong and would silently change what `oneOf` and `anyOf` mean.
    assertConformant(validate("{\"minimum\":100}", "\"any string\""));
    assertConformant(validate("{\"maxLength\":2}", "12345"));
    assertConformant(validate("{\"minItems\":5}", "{}"));
  }

  @Test
  void enforcesNot() {
    final String schema = "{\"not\":{\"type\":\"string\"}}";

    assertConformant(validate(schema, "1"));
    assertThat(keywords(validate(schema, "\"x\""))).contains("not");
  }

  @Test
  void theFalseSchemaRejectsEverything() {
    final String schema = "{\"type\":\"object\",\"properties\":{\"a\":false}}";

    assertThat(validate(schema, "{\"a\":1}").conformant()).isFalse();
    assertConformant(validate(schema, "{}"));
  }

  // ---- $defs and $ref ------------------------------------------------------------------------

  @Test
  void resolvesInternalReferences() {
    final String schema =
        "{\"$defs\":{\"Name\":{\"type\":\"string\",\"minLength\":2}},"
            + "\"type\":\"object\",\"properties\":{\"first\":{\"$ref\":\"#/$defs/Name\"},"
            + "\"last\":{\"$ref\":\"#/$defs/Name\"}},\"required\":[\"first\"]}";

    assertConformant(validate(schema, "{\"first\":\"ada\",\"last\":\"lovelace\"}"));
    final ValidationVerdict verdict = validate(schema, "{\"first\":\"a\"}");
    assertThat(verdict.violations().get(0).pointer()).isEqualTo("/first");
    assertThat(verdict.violations().get(0).keyword()).isEqualTo("minLength");
  }

  @Test
  void rejectsCyclicReferences() {
    // Left unchecked this would recurse forever on a deep enough instance.
    assertThatThrownBy(
            () ->
                validator.compile(
                    OutputSchema.of(
                        "{\"$defs\":{\"A\":{\"$ref\":\"#/$defs/B\"},\"B\":{\"$ref\":\"#/$defs/A\"}},"
                            + "\"$ref\":\"#/$defs/A\"}",
                        "v1")))
        .isInstanceOf(SchemaCompilationException.class)
        .hasMessageContaining("cyclic-ref");
  }

  @Test
  void rejectsSelfReference() {
    assertThatThrownBy(
            () ->
                validator.compile(
                    OutputSchema.of(
                        "{\"$defs\":{\"A\":{\"$ref\":\"#/$defs/A\"}},\"$ref\":\"#/$defs/A\"}",
                        "v1")))
        .isInstanceOf(SchemaCompilationException.class);
  }

  @Test
  void rejectsUnresolvedAndRemoteReferences() {
    assertThatThrownBy(
            () -> validator.compile(OutputSchema.of("{\"$ref\":\"#/$defs/Missing\"}", "v1")))
        .isInstanceOf(SchemaCompilationException.class);
    assertThatThrownBy(
            () ->
                validator.compile(
                    OutputSchema.of("{\"$ref\":\"https://example.com/schema.json\"}", "v1")))
        .isInstanceOf(SchemaCompilationException.class)
        .hasMessageContaining("unsupported-ref");
  }

  // ---- malformed input and schema ---------------------------------------------------------------

  @Test
  void malformedSchemaFailsCompilationWithoutThrowingRuntimeErrors() {
    assertThatThrownBy(() -> validator.compile(OutputSchema.of("{not json", "v1")))
        .isInstanceOf(SchemaCompilationException.class)
        .hasMessageContaining("schema-not-json");
    assertThatThrownBy(() -> validator.compile(OutputSchema.of("[1,2,3]", "v1")))
        .isInstanceOf(SchemaCompilationException.class);
    assertThatThrownBy(() -> validator.compile(OutputSchema.of("{\"type\":\"nonsense\"}", "v1")))
        .isInstanceOf(SchemaCompilationException.class);
    assertThatThrownBy(
            () -> validator.compile(OutputSchema.of("{\"pattern\":\"[unclosed\"}", "v1")))
        .isInstanceOf(SchemaCompilationException.class);
  }

  @Test
  void malformedOutputIsClassifiedAsMalformedNotNonConformant() {
    final ValidationVerdict verdict = validate("{\"type\":\"object\"}", "{\"a\":");

    assertThat(verdict.conformant()).isFalse();
    // The distinction matters: MALFORMED is retryable, SCHEMA_INVALID is not.
    assertThat(verdict.classification()).isEqualTo(FailureClass.MALFORMED);
  }

  @Test
  void anUnknownCompiledSchemaFailsClosedRatherThanPassing() {
    final CompiledSchema neverCompiled =
        new CompiledSchema(
            io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId.of(
                "{\"type\":\"object\"}"),
            "v1",
            1);

    final ValidationVerdict verdict = validator.validate(neverCompiled, "{}");

    assertThat(verdict.conformant()).isFalse();
    assertThat(verdict.classification()).isEqualTo(FailureClass.SCHEMA_INVALID);
  }

  @Test
  void unsupportedKeywordsAreRefusedRatherThanSilentlyIgnored() {
    // Ignoring these would make validation quietly weaker than the author asked for.
    for (final String keyword :
        List.of("unevaluatedProperties", "if", "patternProperties", "multipleOf")) {
      assertThatThrownBy(
              () ->
                  validator.compile(
                      OutputSchema.of("{\"type\":\"object\",\"" + keyword + "\":{}}", "v1")))
          .as(keyword)
          .isInstanceOf(SchemaCompilationException.class)
          .hasMessageContaining("unsupported-keyword");
    }
  }

  @Test
  void unknownAnnotationKeywordsAreIgnored() {
    // title and description constrain nothing, so they must not break a valid schema.
    assertConformant(
        validate(
            "{\"title\":\"T\",\"description\":\"D\",\"$comment\":\"c\",\"type\":\"string\"}",
            "\"x\""));
  }

  // ---- resource bounds -------------------------------------------------------------------------

  @Test
  void rejectsSchemasDeeperThanTheConfiguredLimit() {
    final JsonSchemaValidator shallow =
        new JsonSchemaValidator(
            new SchemaValidatorConfig(4, 10_000, 10_000, 65_536, 65_536, 100, 16));
    final StringBuilder deep = new StringBuilder("{\"type\":\"object\",\"properties\":{\"a\":");
    for (int i = 0; i < 12; i++) {
      deep.append("{\"type\":\"object\",\"properties\":{\"a\":");
    }
    deep.append("{\"type\":\"string\"}");
    deep.append("}}".repeat(13));

    assertThatThrownBy(() -> shallow.compile(OutputSchema.of(deep.toString(), "v1")))
        .isInstanceOf(SchemaCompilationException.class);
  }

  @Test
  void rejectsSchemasExceedingTheNodeBudget() {
    final JsonSchemaValidator small =
        new JsonSchemaValidator(new SchemaValidatorConfig(32, 20, 10_000, 65_536, 65_536, 100, 16));
    final StringBuilder wide = new StringBuilder("{\"type\":\"object\",\"properties\":{");
    for (int i = 0; i < 50; i++) {
      wide.append(i > 0 ? "," : "").append("\"p").append(i).append("\":{\"type\":\"string\"}");
    }
    wide.append("}}");

    assertThatThrownBy(() -> small.compile(OutputSchema.of(wide.toString(), "v1")))
        .isInstanceOf(SchemaCompilationException.class)
        .hasMessageContaining("schema-too-complex");
  }

  @Test
  void capsViolationsAtTheConfiguredMaximum() {
    final StringBuilder instance = new StringBuilder("[");
    for (int i = 0; i < 500; i++) {
      instance.append(i > 0 ? "," : "").append("\"wrong\"");
    }
    instance.append(']');

    final ValidationVerdict verdict =
        validate("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}", instance.toString());

    assertThat(verdict.violations()).hasSize(SchemaValidatorConfig.DEFAULT_MAX_VIOLATIONS);
  }

  @Test
  void handlesLargeArraysAndObjects() {
    final StringBuilder array = new StringBuilder("[");
    for (int i = 0; i < 5_000; i++) {
      array.append(i > 0 ? "," : "").append(i);
    }
    array.append(']');
    assertConformant(
        validate("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}", array.toString()));

    final StringBuilder object = new StringBuilder("{");
    for (int i = 0; i < 2_000; i++) {
      object.append(i > 0 ? "," : "").append("\"k").append(i).append("\":1");
    }
    object.append('}');
    assertConformant(
        validate(
            "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"}}",
            object.toString()));
  }

  @Test
  void rejectsInstancesDeeperThanTheConfiguredLimit() {
    final ValidationVerdict verdict =
        validate("{\"type\":\"array\"}", "[".repeat(200) + "]".repeat(200));

    assertThat(verdict.conformant()).isFalse();
    assertThat(verdict.classification()).isEqualTo(FailureClass.MALFORMED);
  }

  @Test
  void rejectsOversizedInstances() {
    final JsonSchemaValidator tiny =
        new JsonSchemaValidator(new SchemaValidatorConfig(32, 1_000, 1_000, 65_536, 64, 100, 16));
    final CompiledSchema schema;
    try {
      schema = tiny.compile(OutputSchema.of("{\"type\":\"string\"}", "v1"));
    } catch (final SchemaCompilationException e) {
      throw new IllegalStateException(e);
    }

    final ValidationVerdict verdict = tiny.validate(schema, "\"" + "x".repeat(200) + "\"");

    assertThat(verdict.classification()).isEqualTo(FailureClass.RESOURCE_EXCEEDED);
  }

  // ---- caching, determinism, concurrency --------------------------------------------------------

  @Test
  void compilesOnceAndReusesTheCompiledSchema() {
    final String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";
    final CompiledSchema first = compile(schema);
    final CompiledSchema second = compile(schema);

    // The schema id is the content hash, so recompiling identical text yields the same identity.
    assertThat(second.schemaId()).isEqualTo(first.schemaId());
    assertThat(second.complexity()).isEqualTo(first.complexity());
    assertThat(validator.cachedSchemaCount()).isEqualTo(1);
  }

  @Test
  void reportsComplexityAsTheCompiledNodeCount() {
    assertThat(compile("{\"type\":\"string\"}").complexity()).isEqualTo(1);
    assertThat(
            compile(
                    "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},"
                        + "\"b\":{\"type\":\"string\"}}}")
                .complexity())
        .isGreaterThan(1);
  }

  @Test
  void producesIdenticalOutputAcrossFiftyRuns() {
    final String schema =
        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},"
            + "\"b\":{\"type\":\"string\",\"minLength\":5}},\"required\":[\"a\",\"z\"],"
            + "\"additionalProperties\":false}";
    final String instance = "{\"a\":\"wrong\",\"b\":\"hi\",\"extra\":1}";
    final CompiledSchema compiled = compile(schema);

    final List<SchemaViolation> first = validator.validate(compiled, instance).violations();
    for (int run = 0; run < 50; run++) {
      assertThat(validator.validate(compiled, instance).violations()).isEqualTo(first);
    }
    assertThat(first).isNotEmpty();
  }

  @Test
  void validationIsSafeAndCorrectAcrossThirtyTwoThreads() throws Exception {
    final CompiledSchema compiled =
        compile(
            "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},"
                + "\"required\":[\"n\"]}");
    final int threads = 32;
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    final List<String> wrong = new CopyOnWriteArrayList<>();

    for (int i = 0; i < threads; i++) {
      final boolean valid = i % 2 == 0;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  go.await();
                  for (int run = 0; run < 50; run++) {
                    final ValidationVerdict verdict =
                        validator.validate(compiled, valid ? "{\"n\":1}" : "{\"n\":\"x\"}");
                    if (verdict.conformant() != valid) {
                      wrong.add("mismatch");
                    }
                  }
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }

    go.countDown();
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    assertThat(wrong).isEmpty();
  }

  @Test
  void validationNeverMutatesTheSchemaOrTheInput() {
    final String schemaText = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";
    final String instance = "{\"a\":1}";
    final CompiledSchema compiled = compile(schemaText);

    validator.validate(compiled, instance);
    validator.validate(compiled, instance);

    assertThat(schemaText)
        .isEqualTo("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
    assertThat(instance).isEqualTo("{\"a\":1}");
    assertConformant(validator.validate(compiled, "{\"a\":\"ok\"}"));
  }

  // ---- streaming early-abort --------------------------------------------------------------------

  @Test
  void structuralImpossibilityIsConservativeOnTruncatedOutput() {
    final CompiledSchema compiled = compile("{\"type\":\"object\",\"required\":[\"a\"]}");

    // Truncated: the rest could still satisfy the schema, so it must not be called impossible.
    assertThat(validator.isStructurallyImpossible(compiled, "{\"a\"")).isFalse();
    assertThat(validator.isStructurallyImpossible(compiled, "")).isFalse();
  }

  @Test
  void structuralImpossibilityDetectsACompletedNonConformantDocument() {
    final CompiledSchema compiled = compile("{\"type\":\"object\"}");

    assertThat(validator.isStructurallyImpossible(compiled, "[1,2,3]")).isTrue();
    assertThat(validator.isStructurallyImpossible(compiled, "{}")).isFalse();
  }
}
