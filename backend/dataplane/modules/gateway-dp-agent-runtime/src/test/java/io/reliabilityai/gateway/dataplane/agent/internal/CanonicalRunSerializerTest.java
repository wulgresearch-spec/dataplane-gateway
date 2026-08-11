package io.reliabilityai.gateway.dataplane.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.agent.api.CancellationCause;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.PlanId;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunBudget;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunSecurityContext;
import io.reliabilityai.gateway.dataplane.agent.api.RunSerializationException;
import io.reliabilityai.gateway.dataplane.agent.api.RunVersion;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisionStrategy;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The durable journal codec.
 *
 * <p>Round-trip fidelity is a correctness requirement rather than a nicety: replay folds these
 * events, so a lost or reordered field makes a recovered run behave differently from the run that
 * crashed. The parameterised round-trip below covers every event type, and adding a sixteenth
 * without adding it to the source list fails the coverage assertion at the bottom.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CanonicalRunSerializerTest {

  private static final RunId RUN = RunId.of("run-1");
  private static final Instant AT = Instant.parse("2026-03-04T05:06:07.008Z");
  private final CanonicalRunSerializer serializer = new CanonicalRunSerializer();

  private static RunEvent.RunCreated created() {
    return new RunEvent.RunCreated(
        RunVersion.pin(PlanId.of("plan-a"), 3),
        new RunBounds(20, 2, 4, Duration.ofMinutes(45), 900L),
        new RunBudget(900L, 120L, 18L),
        new RunSecurityContext(
            new PrincipalId("p-9"),
            new TenantScope("acme", "core", "ws", "proj"),
            new CorrelationId("corr-9"),
            Set.of("chat", "search")),
        Optional.of(RunId.of("parent-1")),
        1,
        AT);
  }

  static Stream<RunEvent> everyEventType() {
    final StepId step = StepId.of(RUN, 4);
    return Stream.of(
        created(),
        new RunEvent.RunQueued(AT),
        new RunEvent.StepScheduled(step, 2, "draft", StepKind.PIPELINE, 3, "ref-1", AT),
        new RunEvent.StepCompleted(step, "draft", "the answer", "d1", 42L, true, AT),
        new RunEvent.StepFailed(step, "draft", FailureClass.STEP_TRANSIENT, "upstream 503", 7L, AT),
        new RunEvent.StepSkipped(step, "draft", FailureClass.TOOL_FAILURE, AT),
        new RunEvent.StepCancelled(step, "draft", CancellationCause.USER, 3L, AT),
        new RunEvent.ToolArtifactProduced(step, "art-1", "search", "d2", true, AT),
        new RunEvent.SupervisionApplied(step, SupervisionStrategy.RETRY_STEP, "retry", 2, AT),
        new RunEvent.BranchTaken(step, "choose", true, "draft", AT),
        new RunEvent.RunParked(AT.plusSeconds(600), AT),
        new RunEvent.RunResumed(1_234_567L, AT),
        new RunEvent.RunCheckpointed(11L, AT),
        new RunEvent.RunCancelled(CancellationCause.OPERATOR, "operator stopped it", AT),
        RunEvent.RunTerminated.of(TerminalReason.COMPLETED_SUCCESS, 6, 512L, AT));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyEventType")
  void everyEventRoundTripsToAnEqualValue(final RunEvent event) {
    assertThat(serializer.deserialize(serializer.serialize(event), RUN)).isEqualTo(event);
  }

  @ParameterizedTest
  @MethodSource("everyEventType")
  void everyEncodingIsASingleLine(final RunEvent event) {
    // The journal is line-delimited, so an embedded newline would split one record into two and
    // corrupt every offset after it.
    assertThat(serializer.serialize(event)).doesNotContain("\n").doesNotContain("\r");
  }

  @ParameterizedTest
  @MethodSource("everyEventType")
  void encodingIsStableSoTwoNodesProduceIdenticalBytes(final RunEvent event) {
    assertThat(serializer.serialize(event)).isEqualTo(serializer.serialize(event));
  }

  @ParameterizedTest
  @MethodSource("everyEventType")
  void everyEncodingStartsWithItsOwnTag(final RunEvent event) {
    assertThat(serializer.serialize(event)).startsWith(event.tag());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "plain",
        "with|pipe",
        "with=equals",
        "with\\backslash",
        "with\nnewline",
        "with\r\nwindows",
        "all|of=them\\at\nonce",
        "unicode → ✓ ✗",
        ""
      })
  void hostileContentSurvivesTheRoundTripIntact(final String content) {
    final RunEvent event =
        new RunEvent.StepCompleted(StepId.of(RUN, 1), "s", content, "dig", 0L, false, AT);
    final String encoded = serializer.serialize(event);
    assertThat(encoded).doesNotContain("\n");
    final RunEvent decoded = serializer.deserialize(encoded, RUN);
    assertThat(((RunEvent.StepCompleted) decoded).value()).isEqualTo(content);
  }

  @Test
  void aTenantWithNoWorkspaceOrProjectRoundTripsAsNullRatherThanEmptyString() {
    final RunEvent.RunCreated event =
        new RunEvent.RunCreated(
            RunVersion.pin(PlanId.of("p"), 1),
            RunBounds.DEFAULT,
            RunBudget.of(10L),
            new RunSecurityContext(
                new PrincipalId("p"), TenantScope.of("o", "t"), new CorrelationId("c"), Set.of()),
            Optional.empty(),
            0,
            AT);
    final RunEvent.RunCreated decoded =
        (RunEvent.RunCreated) serializer.deserialize(serializer.serialize(event), RUN);
    assertThat(decoded.security().tenant().workspace()).isNull();
    assertThat(decoded.security().tenant().project()).isNull();
  }

  @Test
  void anEmptyCapabilitySetRoundTripsAsEmptyRatherThanAsASetContainingBlank() {
    final RunEvent.RunCreated event =
        new RunEvent.RunCreated(
            RunVersion.pin(PlanId.of("p"), 1),
            RunBounds.DEFAULT,
            RunBudget.of(10L),
            new RunSecurityContext(
                new PrincipalId("p"), TenantScope.of("o", "t"), new CorrelationId("c"), Set.of()),
            Optional.empty(),
            0,
            AT);
    final RunEvent.RunCreated decoded =
        (RunEvent.RunCreated) serializer.deserialize(serializer.serialize(event), RUN);
    assertThat(decoded.security().capabilities()).isEmpty();
  }

  @Test
  void aRootRunRoundTripsWithNoParent() {
    final RunEvent.RunCreated event =
        new RunEvent.RunCreated(
            RunVersion.pin(PlanId.of("p"), 1),
            RunBounds.DEFAULT,
            RunBudget.of(10L),
            new RunSecurityContext(
                new PrincipalId("p"), TenantScope.of("o", "t"), new CorrelationId("c"), Set.of()),
            Optional.empty(),
            0,
            AT);
    final RunEvent.RunCreated decoded =
        (RunEvent.RunCreated) serializer.deserialize(serializer.serialize(event), RUN);
    assertThat(decoded.parent()).isEmpty();
  }

  @Test
  void aStepIdIsRebuiltRelativeToItsRunRatherThanRepeatedInFull() {
    final RunEvent event =
        new RunEvent.StepScheduled(StepId.of(RUN, 12), 0, "s", StepKind.PLUGIN, 1, "ref", AT);
    final RunId otherRun = RunId.of("run-2");
    final RunEvent decoded = serializer.deserialize(serializer.serialize(event), otherRun);
    assertThat(((RunEvent.StepScheduled) decoded).stepId().runId()).isEqualTo(otherRun);
    assertThat(((RunEvent.StepScheduled) decoded).stepId().index()).isEqualTo(12);
  }

  @Test
  void anUnknownTagIsRefusedRatherThanSkipped() {
    // Skipping would silently drop a fact that replay then folds into the wrong answer, and the run
    // would diverge for a reason nobody could trace back to a forward-compatibility shortcut.
    assertThatThrownBy(() -> serializer.deserialize("run.exploded|at=" + AT, RUN))
        .isInstanceOf(RunSerializationException.class)
        .hasMessageContaining("unknown event tag");
  }

  @Test
  void aMissingFieldIsRefusedRatherThanDefaulted() {
    assertThatThrownBy(() -> serializer.deserialize("run.queued", RUN))
        .isInstanceOf(RunSerializationException.class)
        .hasMessageContaining("missing field 'at'");
  }

  @Test
  void aNonNumericNumberIsRefused() {
    assertThatThrownBy(() -> serializer.deserialize("run.checkpointed|offset=abc|at=" + AT, RUN))
        .isInstanceOf(RunSerializationException.class)
        .hasMessageContaining("not a number");
  }

  @Test
  void aMalformedInstantIsRefused() {
    assertThatThrownBy(() -> serializer.deserialize("run.queued|at=yesterday", RUN))
        .isInstanceOf(RunSerializationException.class)
        .hasMessageContaining("not an instant");
  }

  @Test
  void aDanglingEscapeIsRefused() {
    assertThatThrownBy(() -> serializer.deserialize("run.queued|at=x\\", RUN))
        .isInstanceOf(RunSerializationException.class)
        .hasMessageContaining("dangling escape");
  }

  @Test
  void anUnknownEscapeIsRefused() {
    assertThatThrownBy(() -> serializer.deserialize("run.queued|at=\\q", RUN))
        .isInstanceOf(RunSerializationException.class)
        .hasMessageContaining("unknown escape");
  }

  @Test
  void anUnescapedSecondEqualsInAValueIsRefused() {
    assertThatThrownBy(() -> serializer.deserialize("run.queued|at=a=b", RUN))
        .isInstanceOf(RunSerializationException.class)
        .hasMessageContaining("unescaped '='");
  }

  @Test
  void theFormatVersionMatchesWhatARunPins() {
    assertThat(serializer.formatVersion()).isEqualTo(RunVersion.CURRENT_JOURNAL_FORMAT);
  }

  @Test
  void theRoundTripCoversEveryEventTypeThePortDeclares() {
    // Fails when a sixteenth event type is added without extending the source above, which is the
    // only thing standing between "we serialize everything" and "we serialize what we remembered".
    final long covered = everyEventType().count();
    final long declared = RunEvent.class.getPermittedSubclasses().length;
    assertThat(covered).isEqualTo(declared);
  }

  @Test
  void aTerminationEventWhoseStateContradictsItsReasonIsUnrepresentable() {
    assertThatThrownBy(
            () ->
                new RunEvent.RunTerminated(
                    TerminalReason.COMPLETED_SUCCESS,
                    io.reliabilityai.gateway.dataplane.agent.api.RunState.FAILED,
                    1,
                    0L,
                    AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires state");
  }

  @Test
  void aVeryLargeValueStillEncodesToOneLine() {
    final String big = "x".repeat(200_000);
    final RunEvent event =
        new RunEvent.StepCompleted(StepId.of(RUN, 1), "s", big, "d", 0L, false, AT);
    final String encoded = serializer.serialize(event);
    assertThat(encoded).doesNotContain("\n");
    assertThat(serializer.deserialize(encoded, RUN)).isEqualTo(event);
  }

  @Test
  void listOfEventsRoundTripsInOrder() {
    final List<RunEvent> events = everyEventType().toList();
    for (int i = 0; i < events.size(); i++) {
      assertThat(serializer.deserialize(serializer.serialize(events.get(i)), RUN))
          .as("event %d", i)
          .isEqualTo(events.get(i));
    }
  }
}
