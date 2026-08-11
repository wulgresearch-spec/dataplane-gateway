package io.reliabilityai.gateway.dataplane.schemalock.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.dataplane.schemalock.api.CanonicalOutput;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.CorrectnessOutcomeSink;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import io.reliabilityai.gateway.dataplane.schemalock.api.OutputSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.ProviderGenerationPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaCompilationException;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPolicy;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaValidatorPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort.StreamItem;
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

/** Terminal-gated streaming tests for the SchemaLock streaming session (Doc 17 §8/§18/§43.1). */
class StructuredStreamSessionTest {

  private static final SchemaLockPolicy POLICY =
      new SchemaLockPolicy(3, 1_000_000, 32, Duration.ofSeconds(5));
  private static final CapabilityDescriptor STREAMING =
      new CapabilityDescriptor(true, true, true, 100);
  private static final ClockPort CLOCK = () -> Instant.parse("2026-07-25T00:00:00Z");

  private final FakeValidator validator = new FakeValidator();
  private final CountingSink sink = new CountingSink();

  private SchemaLockService service() {
    return new SchemaLockService(
        validator, new NoGeneration(), (a, c) -> false, sink, POLICY, CLOCK, 16);
  }

  private static CompiledSchema compiled() {
    return new CompiledSchema(SchemaId.of("{\"type\":\"object\"}"), "v1", 3);
  }

  private static StructuredOutputRequest request(final CapabilityDescriptor cap) {
    return new StructuredOutputRequest(
        compiled().schemaId(), new CorrelationId("c"), cap, Mode.STREAM);
  }

  @Test
  void cleanStreamCompletionValidatesAndReturnsConformant() {
    validator.completion = ValidationVerdict.passed();
    final FakeSource src =
        FakeSource.of(
            new StreamItem.Delta("{\"a\":"),
            new StreamItem.Delta("1}"),
            new StreamItem.TransportComplete());
    final CanonicalOutput out =
        service().openStream(request(STREAMING), compiled(), src).awaitCompletion();
    assertThat(out.conformant()).isTrue();
    assertThat(out.value()).isEqualTo("{\"a\":1}"); // logical assembly = concatenated deltas
  }

  @Test
  void transportTruncationSurfacesNeverCompletesSuccess() {
    final FakeSource src =
        FakeSource.of(
            new StreamItem.Delta("{\"a\":1"),
            new StreamItem.TransportFailed(FailureClass.TRUNCATED));
    final CanonicalOutput out =
        service().openStream(request(STREAMING), compiled(), src).awaitCompletion();
    assertThat(out.conformant()).isFalse();
    assertThat(out.classification()).isEqualTo(FailureClass.TRUNCATED);
  }

  @Test
  void incrementalImpossibilityAbortsBeforeTerminal() {
    validator.impossible = true; // structurally impossible partial (§19)
    final FakeSource src =
        FakeSource.of(
            new StreamItem.Delta("{\"a\":\"wrongtype\"}"), new StreamItem.TransportComplete());
    final CanonicalOutput out =
        service().openStream(request(STREAMING), compiled(), src).awaitCompletion();
    assertThat(out.conformant()).isFalse();
    assertThat(out.classification()).isEqualTo(FailureClass.NON_CONFORMANT);
  }

  @Test
  void nonConformantCompletionSurfaces() {
    validator.completion =
        ValidationVerdict.nonConformant(
            FailureClass.NON_CONFORMANT,
            List.of(new SchemaViolation("/a", "type", "integer", "string")));
    final FakeSource src =
        FakeSource.of(new StreamItem.Delta("{\"a\":\"x\"}"), new StreamItem.TransportComplete());
    final CanonicalOutput out =
        service().openStream(request(STREAMING), compiled(), src).awaitCompletion();
    assertThat(out.conformant()).isFalse();
    assertThat(out.classification()).isEqualTo(FailureClass.NON_CONFORMANT);
    assertThat(out.violations()).hasSize(1);
  }

  @Test
  void nonStreamingProviderSurfacesCapabilityUnsupported() {
    final CapabilityDescriptor noStream = new CapabilityDescriptor(true, true, false, 100);
    final FakeSource src = FakeSource.of(new StreamItem.TransportComplete());
    final CanonicalOutput out =
        service().openStream(request(noStream), compiled(), src).awaitCompletion();
    assertThat(out.classification()).isEqualTo(FailureClass.CAPABILITY_UNSUPPORTED);
  }

  private static final class FakeSource implements StreamSourcePort {
    private final Deque<StreamItem> items = new ArrayDeque<>();

    static FakeSource of(final StreamItem... items) {
      final FakeSource s = new FakeSource();
      for (final StreamItem i : items) {
        s.items.add(i);
      }
      return s;
    }

    @Override
    public StreamItem next() {
      return items.isEmpty() ? new StreamItem.TransportComplete() : items.poll();
    }
  }

  private static final class FakeValidator implements SchemaValidatorPort {
    private ValidationVerdict completion = ValidationVerdict.passed();
    private boolean impossible;

    @Override
    public CompiledSchema compile(final OutputSchema schema) throws SchemaCompilationException {
      return new CompiledSchema(schema.schemaId(), schema.version(), 3);
    }

    @Override
    public ValidationVerdict validate(final CompiledSchema schema, final String outputJson) {
      return completion;
    }

    @Override
    public boolean isStructurallyImpossible(final CompiledSchema schema, final String partialJson) {
      return impossible;
    }
  }

  private static final class NoGeneration implements ProviderGenerationPort {
    @Override
    public GenerationOutcome generate(final GenerationCommand command) {
      return new GenerationOutcome.Failed(FailureClass.PROVIDER_ERROR);
    }

    @Override
    public void cancel() {
      // no-op
    }
  }

  private static final class CountingSink implements CorrectnessOutcomeSink {
    @Override
    public void onOutcome(
        final SchemaId schemaId,
        final boolean conformant,
        final FailureClass classification,
        final Strategy strategy,
        final int attempts) {
      // outcome asserted directly
    }
  }
}
