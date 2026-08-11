package io.reliabilityai.gateway.dataplane.schemalock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.dataplane.schemalock.api.CanonicalOutput;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.CorrectnessOutcomeSink;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import io.reliabilityai.gateway.dataplane.schemalock.api.OutputSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.ProviderGenerationPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.RetryDecisionPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaCompilationException;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPolicy;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaValidatorPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StructuredOutputRequest;
import io.reliabilityai.gateway.dataplane.schemalock.domain.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation;
import io.reliabilityai.gateway.dataplane.schemalock.domain.Strategy;
import io.reliabilityai.gateway.dataplane.schemalock.domain.ValidationVerdict;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fail-closed resolution tests for the SchemaLock batch engine (Doc 17). */
class SchemaLockServiceTest {

  private static final SchemaLockPolicy POLICY =
      new SchemaLockPolicy(3, 1_000_000, 32, Duration.ofSeconds(5));
  private static final CapabilityDescriptor NATIVE =
      new CapabilityDescriptor(true, true, true, 100);
  // Fixed clock: never advances, so the total-latency budget never trips in the correctness tests.
  private static final ClockPort CLOCK = () -> Instant.parse("2026-07-25T00:00:00Z");

  private final FakeValidator validator = new FakeValidator();
  private final FakeGeneration generation = new FakeGeneration();
  private final FakeRetry retry = new FakeRetry();
  private final CountingSink sink = new CountingSink();

  private SchemaLockService service() {
    return new SchemaLockService(validator, generation, retry, sink, POLICY, CLOCK, 16);
  }

  private static CompiledSchema compiled() {
    return new CompiledSchema(SchemaId.of("{\"type\":\"object\"}"), "v1", 3);
  }

  private static StructuredOutputRequest request(final CapabilityDescriptor cap) {
    return new StructuredOutputRequest(
        compiled().schemaId(), new CorrelationId("corr-1"), cap, Mode.BATCH);
  }

  private static SchemaViolation violation() {
    return new SchemaViolation("/name", "required", "present", "missing");
  }

  @Test
  void conformantFirstAttemptReturnsValidatedValue() {
    generation.enqueue(produced("{\"ok\":true}"));
    validator.enqueue(ValidationVerdict.passed());
    final CanonicalOutput out = service().executeStructured(request(NATIVE), compiled());
    assertThat(out.conformant()).isTrue();
    assertThat(out.value()).isEqualTo("{\"ok\":true}");
    assertThat(out.strategyUsed()).isEqualTo(Strategy.NATIVE_SCHEMA);
    assertThat(out.attempts()).isEqualTo(1);
    assertThat(sink.conformant).isEqualTo(1);
  }

  @Test
  void nonConformantThenRetryThenConformant() {
    generation.enqueue(produced("bad"));
    validator.enqueue(
        ValidationVerdict.nonConformant(FailureClass.NON_CONFORMANT, List.of(violation())));
    generation.enqueue(produced("{\"ok\":true}"));
    validator.enqueue(ValidationVerdict.passed());
    final CanonicalOutput out = service().executeStructured(request(NATIVE), compiled());
    assertThat(out.conformant()).isTrue();
    assertThat(out.attempts()).isEqualTo(2);
    assertThat(generation.feedbackSeen).containsExactly(0, 1); // 2nd attempt carried 1 violation
  }

  @Test
  void nonConformantExhaustedSurfacesNeverDelivers() {
    for (int i = 0; i < 3; i++) {
      generation.enqueue(produced("bad"));
      validator.enqueue(
          ValidationVerdict.nonConformant(FailureClass.NON_CONFORMANT, List.of(violation())));
    }
    final CanonicalOutput out = service().executeStructured(request(NATIVE), compiled());
    assertThat(out.conformant()).isFalse();
    assertThat(out.value()).isNull(); // never delivers a non-conforming value (SL-INV)
    assertThat(out.classification()).isEqualTo(FailureClass.NON_CONFORMANT);
    assertThat(out.violations()).hasSize(1);
    assertThat(out.attempts()).isEqualTo(3); // hard attempt cap honored
  }

  @Test
  void retryBudgetVetoSurfacesImmediately() {
    retry.allow = false; // Retry Engine vetoes (shared budget exhausted)
    generation.enqueue(produced("bad"));
    validator.enqueue(
        ValidationVerdict.nonConformant(FailureClass.NON_CONFORMANT, List.of(violation())));
    final CanonicalOutput out = service().executeStructured(request(NATIVE), compiled());
    assertThat(out.conformant()).isFalse();
    assertThat(out.attempts()).isEqualTo(1); // no further attempts once vetoed
  }

  @Test
  void malformedIsRetriedThenSurfaced() {
    generation.enqueue(failed(FailureClass.MALFORMED));
    generation.enqueue(failed(FailureClass.MALFORMED));
    generation.enqueue(failed(FailureClass.MALFORMED));
    final CanonicalOutput out = service().executeStructured(request(NATIVE), compiled());
    assertThat(out.classification()).isEqualTo(FailureClass.MALFORMED);
    assertThat(out.attempts()).isEqualTo(3);
  }

  @Test
  void codeFenceEnvelopeIsRepairedThenValidated() {
    generation.enqueue(produced("```json\n{\"ok\":true}\n```"));
    validator.enqueue(ValidationVerdict.passed());
    final CanonicalOutput out = service().executeStructured(request(NATIVE), compiled());
    assertThat(out.conformant()).isTrue();
    assertThat(out.value()).isEqualTo("{\"ok\":true}"); // NR-1 stripped the fence, value preserved
    assertThat(out.repairApplied()).isTrue();
    assertThat(validator.lastValidated).isEqualTo("{\"ok\":true}");
  }

  @Test
  void oversizeOutputFailsClosedResourceExceeded() {
    final SchemaLockPolicy tiny = new SchemaLockPolicy(3, 4, 32, Duration.ofSeconds(5));
    final SchemaLockService svc =
        new SchemaLockService(validator, generation, retry, sink, tiny, CLOCK, 16);
    generation.enqueue(produced("way too long output"));
    final CanonicalOutput out = svc.executeStructured(request(NATIVE), compiled());
    assertThat(out.classification()).isEqualTo(FailureClass.RESOURCE_EXCEEDED);
  }

  @Test
  void latencyBudgetExhaustionSurfacesTimeoutAndCancels() {
    // An advancing clock trips the total-latency budget (perAttemptTimeout*maxAttempts) before the
    // attempt cap; the engine surfaces TIMEOUT and cancels the provider — never delivers
    // unvalidated.
    final Instant[] tick = {Instant.parse("2026-07-25T00:00:00Z")};
    final ClockPort advancing =
        () -> {
          final Instant t = tick[0];
          tick[0] = tick[0].plusSeconds(20); // > 5s*3 budget after the first read
          return t;
        };
    final SchemaLockService svc =
        new SchemaLockService(validator, generation, retry, sink, POLICY, advancing, 16);
    generation.enqueue(produced("bad"));
    validator.enqueue(
        ValidationVerdict.nonConformant(FailureClass.NON_CONFORMANT, List.of(violation())));
    final CanonicalOutput out = svc.executeStructured(request(NATIVE), compiled());
    assertThat(out.conformant()).isFalse();
    assertThat(out.classification()).isEqualTo(FailureClass.TIMEOUT);
    assertThat(generation.cancelled).isTrue();
  }

  @Test
  void validatorAdapterErrorFailsClosed() {
    generation.enqueue(produced("{\"ok\":true}"));
    validator.throwNext = true;
    final CanonicalOutput out = service().executeStructured(request(NATIVE), compiled());
    assertThat(out.conformant()).isFalse();
    assertThat(out.classification()).isEqualTo(FailureClass.PROVIDER_ERROR); // never delivers
  }

  @Test
  void leastCapableProviderSelectsPromptConstrained() {
    generation.enqueue(produced("{\"ok\":true}"));
    validator.enqueue(ValidationVerdict.passed());
    final CanonicalOutput out =
        service().executeStructured(request(CapabilityDescriptor.LEAST_CAPABLE), compiled());
    assertThat(out.strategyUsed()).isEqualTo(Strategy.PROMPT_CONSTRAINED);
  }

  @Test
  void compileCachesByContentHash() throws Exception {
    final SchemaLockService svc = service();
    final OutputSchema schema = OutputSchema.of("{\"type\":\"object\"}", "v1");
    final CompiledSchema a = svc.compile(schema);
    final CompiledSchema b = svc.compile(schema);
    assertThat(a).isSameAs(b); // second compile served from cache
    assertThat(validator.compileCalls).isEqualTo(1);
  }

  @Test
  void throwingOutcomeSinkNeverBreaksTheResult() {
    // Observability must never affect the correctness decision (Doc 17 §38): a throwing outcome
    // sink
    // must not turn a conformant result into an exception.
    final CorrectnessOutcomeSink boom =
        (id, conformant, classification, strategy, attempts) -> {
          throw new RuntimeException("boom");
        };
    final SchemaLockService svc =
        new SchemaLockService(validator, generation, retry, boom, POLICY, CLOCK, 16);
    generation.enqueue(produced("{\"ok\":true}"));
    validator.enqueue(ValidationVerdict.passed());
    assertThat(svc.executeStructured(request(NATIVE), compiled()).conformant()).isTrue();
  }

  @Test
  void compileSurfacesSchemaInvalid() {
    validator.compileThrows = true;
    assertThatThrownBy(() -> service().compile(OutputSchema.of("{bad}", "v1")))
        .isInstanceOf(SchemaCompilationException.class);
  }

  private static ProviderGenerationPort.GenerationOutcome produced(final String text) {
    return new ProviderGenerationPort.GenerationOutcome.Produced(text);
  }

  private static ProviderGenerationPort.GenerationOutcome failed(final FailureClass c) {
    return new ProviderGenerationPort.GenerationOutcome.Failed(c);
  }

  private static final class FakeValidator implements SchemaValidatorPort {
    private final Deque<ValidationVerdict> verdicts = new ArrayDeque<>();
    private int compileCalls;
    private boolean compileThrows;
    private boolean throwNext;
    private String lastValidated;

    void enqueue(final ValidationVerdict v) {
      verdicts.add(v);
    }

    @Override
    public CompiledSchema compile(final OutputSchema schema) throws SchemaCompilationException {
      compileCalls++;
      if (compileThrows) {
        throw new SchemaCompilationException("invalid schema");
      }
      return new CompiledSchema(schema.schemaId(), schema.version(), 3);
    }

    @Override
    public ValidationVerdict validate(final CompiledSchema schema, final String outputJson) {
      if (throwNext) {
        throw new IllegalStateException("validator down");
      }
      lastValidated = outputJson;
      return verdicts.isEmpty() ? ValidationVerdict.passed() : verdicts.poll();
    }

    @Override
    public boolean isStructurallyImpossible(final CompiledSchema schema, final String partialJson) {
      return false;
    }
  }

  private static final class FakeGeneration implements ProviderGenerationPort {
    private final Deque<GenerationOutcome> outcomes = new ArrayDeque<>();
    private final java.util.List<Integer> feedbackSeen = new java.util.ArrayList<>();
    private boolean cancelled;

    void enqueue(final GenerationOutcome o) {
      outcomes.add(o);
    }

    @Override
    public GenerationOutcome generate(final GenerationCommand command) {
      feedbackSeen.add(command.feedback().size());
      return outcomes.isEmpty()
          ? new GenerationOutcome.Failed(FailureClass.PROVIDER_ERROR)
          : outcomes.poll();
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }

  private static final class FakeRetry implements RetryDecisionPort {
    private boolean allow = true;

    @Override
    public boolean shouldRetry(final int nextAttempt, final FailureClass failureClass) {
      return allow;
    }
  }

  private static final class CountingSink implements CorrectnessOutcomeSink {
    private int conformant;
    private int surfaced;

    @Override
    public void onOutcome(
        final SchemaId schemaId,
        final boolean isConformant,
        final FailureClass classification,
        final Strategy strategy,
        final int attempts) {
      if (isConformant) {
        conformant++;
      } else {
        surfaced++;
      }
    }
  }
}
