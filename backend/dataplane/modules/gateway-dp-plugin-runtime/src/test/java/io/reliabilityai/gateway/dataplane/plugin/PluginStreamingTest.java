package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginEvent;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginInvocationCost;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginStream;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolDefinition;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolError;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolRequest;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolResponse;
import io.reliabilityai.gateway.dataplane.plugin.api.TrustTier;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginLifecycleService;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRegistryService;
import io.reliabilityai.gateway.dataplane.plugin.application.ToolExecutionService;
import io.reliabilityai.gateway.dataplane.plugin.internal.BoundedPluginStream;
import io.reliabilityai.gateway.dataplane.plugin.internal.InProcessSandbox;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Streaming plugin invocations: backpressure, cancellation and terminals (Doc 28 §30, §39–40). */
@DisplayName("plugin streaming")
class PluginStreamingTest {

  private final PluginFixture.TestClock clock = new PluginFixture.TestClock();
  private final InProcessSandbox sandbox = new InProcessSandbox(128);
  private final List<PluginInvocationCost> recordedCosts = new CopyOnWriteArrayList<>();
  private final PluginRegistryService registry =
      new PluginRegistryService(
          PluginFixture.trustAll(),
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          32);
  private final PluginLifecycleService lifecycle =
      new PluginLifecycleService(
          registry,
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          PluginFixture.TENANT);
  private final ToolExecutionService execution =
      new ToolExecutionService(
          registry,
          sandbox,
          PluginAuthorizationPort.PERMIT_ALL,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          recordedCosts::add,
          clock,
          100L);

  private static PluginManifest streamManifest(final String id, final boolean streamingCapability) {
    final PluginCapabilities capabilities =
        streamingCapability
            ? PluginCapabilities.of(
                PluginCapabilities.TOOL_EXECUTION, PluginCapabilities.TOOL_STREAMING)
            : PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION);
    return new PluginManifest(
        PluginId.of(id),
        id,
        new PluginVersion(1, 0, 0),
        "acme",
        "streaming plugin",
        PluginType.INTERNAL,
        TrustTier.FIRST_PARTY,
        Set.of(),
        0,
        PluginPermissions.none(),
        capabilities,
        List.of(new ToolDefinition("generate", "generates", "{}")),
        PluginFixture.budget(Duration.ofSeconds(5)),
        Map.of(),
        PluginFixture.health(),
        List.of());
  }

  private TestPlugins.CountingStream bindStream(final String id, final int tokens) {
    final PluginManifest manifest = streamManifest(id, true);
    final TestPlugins.CountingStream plugin = new TestPlugins.CountingStream(manifest, tokens);
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of(id));
    return plugin;
  }

  private static List<PluginEvent> drain(final PluginStream stream) throws InterruptedException {
    final List<PluginEvent> events = new ArrayList<>();
    while (true) {
      final PluginEvent event = stream.next();
      events.add(event);
      if (event.terminal()) {
        return events;
      }
    }
  }

  @Test
  @DisplayName("a streaming plugin's events reach the consumer in order")
  void eventsArriveInOrder() throws Exception {
    bindStream("stream-1", 4);
    try (PluginStream stream =
        execution.stream(
            PluginId.of("stream-1"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-1"))) {
      final List<PluginEvent> events = drain(stream);
      assertThat(events).hasSize(5);
      assertThat(events.subList(0, 4))
          .extracting(event -> ((PluginEvent.Token) event).text())
          .containsExactly("t0", "t1", "t2", "t3");
      assertThat(events.get(4)).isInstanceOf(PluginEvent.Completed.class);
    }
  }

  @Test
  @DisplayName("exactly one terminal event is delivered, and nothing after it")
  void exactlyOneTerminal() throws Exception {
    bindStream("stream-2", 2);
    try (PluginStream stream =
        execution.stream(
            PluginId.of("stream-2"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-2"))) {
      final List<PluginEvent> events = drain(stream);
      assertThat(events.stream().filter(PluginEvent::terminal).count()).isEqualTo(1L);
      // Reading past the terminal returns the same terminal rather than blocking or inventing more.
      assertThat(stream.next()).isSameAs(events.get(events.size() - 1));
    }
  }

  @Test
  @DisplayName("a plugin returning without a terminal gets Failed, never a fabricated Completed")
  void missingTerminalBecomesFailed() throws Exception {
    final PluginManifest manifest = streamManifest("terminalless", true);
    registry.register(
        PluginFixture.snapshot(manifest), new TestPlugins.TerminallessStream(manifest));
    lifecycle.start(PluginId.of("terminalless"));

    try (PluginStream stream =
        execution.stream(
            PluginId.of("terminalless"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-3"))) {
      final List<PluginEvent> events = drain(stream);
      // Claiming success for a plugin that did not claim it would be a fabrication.
      assertThat(events.get(events.size() - 1)).isInstanceOf(PluginEvent.Failed.class);
      assertThat(((PluginEvent.Failed) events.get(events.size() - 1)).error().kind())
          .isEqualTo(PluginFailureKind.PLUGIN_BUG);
    }
  }

  @Test
  @DisplayName("cancelling a stream stops the producer")
  void cancellationStopsTheProducer() throws Exception {
    final TestPlugins.CountingStream plugin = bindStream("stream-4", 100_000);
    final PluginStream stream =
        execution.stream(
            PluginId.of("stream-4"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-4"));

    assertThat(stream.next()).isInstanceOf(PluginEvent.Token.class);
    stream.cancel();

    // The producer notices via a refused emit or an interrupt; either way it stops well short of
    // 100,000 events.
    for (int attempt = 0; attempt < 200 && plugin.emitted.get() < 100_000; attempt++) {
      Thread.sleep(5);
      if (plugin.refused.get() > 0) {
        break;
      }
    }
    assertThat(plugin.emitted.get()).isLessThan(100_000);
  }

  @Test
  @DisplayName("a consumer blocked in next() wakes when another thread cancels")
  void cancellationWakesABlockedConsumer() throws Exception {
    bindStream("stream-5", 1);
    final PluginStream stream =
        execution.stream(
            PluginId.of("stream-5"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-5"));
    drain(stream);

    final BoundedPluginStream channel = new BoundedPluginStream(4, () -> {});
    final CountDownLatch blocked = new CountDownLatch(1);
    final AtomicBoolean woke = new AtomicBoolean();
    Thread.ofVirtual()
        .start(
            () -> {
              blocked.countDown();
              try {
                channel.next();
                woke.set(true);
              } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });

    assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(50);
    channel.cancel();

    for (int attempt = 0; attempt < 200 && !woke.get(); attempt++) {
      Thread.sleep(5);
    }
    assertThat(woke.get()).isTrue();
  }

  @Test
  @DisplayName("cancel is idempotent and safe from any thread")
  void cancelIsIdempotent() {
    final BoundedPluginStream channel = new BoundedPluginStream(2, () -> {});
    channel.cancel();
    channel.cancel();
    channel.close();
    assertThat(channel.cancelled()).isTrue();
  }

  @Test
  @DisplayName("the cancellation hook runs exactly once")
  void cancellationHookRunsOnce() {
    final java.util.concurrent.atomic.AtomicInteger hookRuns =
        new java.util.concurrent.atomic.AtomicInteger();
    final BoundedPluginStream channel = new BoundedPluginStream(2, hookRuns::incrementAndGet);
    channel.cancel();
    channel.cancel();
    assertThat(hookRuns.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("a misbehaving cancellation hook does not prevent cancellation")
  void hookFailureDoesNotBlockCancellation() {
    final BoundedPluginStream channel =
        new BoundedPluginStream(
            2,
            () -> {
              throw new IllegalStateException("hook down");
            });
    channel.cancel();
    assertThat(channel.cancelled()).isTrue();
  }

  @Test
  @DisplayName("a cancelled stream reports a cancelled terminal")
  void cancelledStreamReportsCancellation() throws Exception {
    final BoundedPluginStream channel = new BoundedPluginStream(4, () -> {});
    channel.cancel();
    final PluginEvent terminal = channel.next();
    assertThat(terminal).isInstanceOf(PluginEvent.Failed.class);
    assertThat(((PluginEvent.Failed) terminal).error().kind())
        .isEqualTo(PluginFailureKind.CANCELLED);
  }

  @Test
  @DisplayName("the producer blocks once the buffer is full, so memory stays bounded")
  void producerBlocksOnAFullBuffer() throws Exception {
    final BoundedPluginStream channel = new BoundedPluginStream(2, () -> {});
    final CountDownLatch thirdAttempted = new CountDownLatch(1);
    final AtomicBoolean thirdAccepted = new AtomicBoolean();

    final Thread producer =
        Thread.ofVirtual()
            .start(
                () -> {
                  channel.bindProducer(Thread.currentThread());
                  channel.emit(new PluginEvent.Token("a"));
                  channel.emit(new PluginEvent.Token("b"));
                  thirdAttempted.countDown();
                  thirdAccepted.set(channel.emit(new PluginEvent.Token("c")));
                });

    assertThat(thirdAttempted.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(100);

    // Capacity two, three offered: the third must still be waiting rather than having grown the
    // queue.
    assertThat(channel.buffered()).isEqualTo(2);
    assertThat(thirdAccepted.get()).isFalse();

    assertThat(((PluginEvent.Token) channel.next()).text()).isEqualTo("a");
    producer.join(5_000);
    assertThat(thirdAccepted.get()).isTrue();
  }

  @Test
  @DisplayName("emitting after cancellation is refused rather than buffered")
  void emitAfterCancellationIsRefused() {
    final BoundedPluginStream channel = new BoundedPluginStream(4, () -> {});
    channel.cancel();
    assertThat(channel.emit(new PluginEvent.Token("late"))).isFalse();
  }

  @Test
  @DisplayName("open() reports false once the stream is cancelled")
  void openReportsCancellation() {
    final BoundedPluginStream channel = new BoundedPluginStream(4, () -> {});
    assertThat(channel.open()).isTrue();
    channel.cancel();
    assertThat(channel.open()).isFalse();
  }

  @Test
  @DisplayName("a producer bound after a cancel is interrupted immediately")
  void lateBoundProducerIsInterrupted() throws Exception {
    final BoundedPluginStream channel = new BoundedPluginStream(4, () -> {});
    channel.cancel();

    final AtomicBoolean interrupted = new AtomicBoolean();
    final Thread producer =
        Thread.ofVirtual()
            .start(
                () -> {
                  channel.bindProducer(Thread.currentThread());
                  interrupted.set(Thread.currentThread().isInterrupted());
                });
    producer.join(5_000);
    // Losing the race with cancel must not leave a producer working on a stream nobody will read.
    assertThat(interrupted.get()).isTrue();
  }

  @Test
  @DisplayName("a plugin without the streaming capability is refused with a Failed stream")
  void streamingRequiresTheCapability() throws Exception {
    final PluginManifest manifest = streamManifest("no-stream-cap", false);
    registry.register(
        PluginFixture.snapshot(manifest), new TestPlugins.CountingStream(manifest, 3));
    lifecycle.start(PluginId.of("no-stream-cap"));

    try (PluginStream stream =
        execution.stream(
            PluginId.of("no-stream-cap"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-6"))) {
      final PluginEvent terminal = stream.next();
      assertThat(((PluginEvent.Failed) terminal).error().kind())
          .isEqualTo(PluginFailureKind.PERMISSION);
    }
  }

  @Test
  @DisplayName(
      "a non-streaming plugin is refused rather than having its unary result faked into one")
  void nonStreamingPluginIsRefused() throws Exception {
    final PluginManifest manifest = streamManifest("unary-only", true);
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FunctionTool(manifest, invocation -> ToolResponse.of("unary")));
    lifecycle.start(PluginId.of("unary-only"));

    try (PluginStream stream =
        execution.stream(
            PluginId.of("unary-only"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-7"))) {
      assertThat(stream.next()).isInstanceOf(PluginEvent.Failed.class);
    }
  }

  @Test
  @DisplayName("a refused stream still returns a stream, so the caller has one shape to handle")
  void refusedInvocationStillReturnsAStream() throws Exception {
    try (PluginStream stream =
        execution.stream(
            PluginId.of("nonexistent"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-8"))) {
      assertThat(stream.next()).isInstanceOf(PluginEvent.Failed.class);
    }
  }

  @Test
  @DisplayName("a streaming invocation records its cost")
  void streamingRecordsCost() throws Exception {
    bindStream("stream-cost", 3);
    recordedCosts.clear();
    try (PluginStream stream =
        execution.stream(
            PluginId.of("stream-cost"),
            ToolRequest.of("generate", "x"),
            PluginFixture.TENANT,
            new CorrelationId("s-9"))) {
      drain(stream);
    }
    for (int attempt = 0; attempt < 200 && recordedCosts.isEmpty(); attempt++) {
      Thread.sleep(5);
    }
    assertThat(recordedCosts).isNotEmpty();
    assertThat(recordedCosts.get(0).toolName()).isEqualTo("generate");
  }

  @Test
  @DisplayName("event payloads are bounded")
  void eventPayloadsAreBounded() {
    assertThatThrownBy(() -> new PluginEvent.Token("x".repeat(PluginEvent.MAX_PAYLOAD_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PluginEvent.PartialOutput("y".repeat(PluginEvent.MAX_PAYLOAD_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class);

    // ToolUpdate carries two free-form strings and bounded neither, which this test did not notice
    // because it only ever asked about Token and PartialOutput. Its name claims a general invariant
    // -- "event payloads are bounded" -- so it has to actually hold for every variant that carries
    // one, or the next unbounded field will be added just as quietly.
    final String tooLong = "z".repeat(PluginEvent.MAX_PAYLOAD_LENGTH + 1);
    assertThatThrownBy(() -> new PluginEvent.ToolUpdate(tooLong, "running"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PluginEvent.ToolUpdate("search", tooLong))
        .isInstanceOf(IllegalArgumentException.class);

    // Exactly at the limit is still accepted: the bound is a ceiling, not an off-by-one refusal.
    final String atLimit = "z".repeat(PluginEvent.MAX_PAYLOAD_LENGTH);
    assertThat(new PluginEvent.ToolUpdate(atLimit, atLimit).toolName())
        .hasSize(PluginEvent.MAX_PAYLOAD_LENGTH);

    // And the pre-existing blank/null contract is unchanged by the added bound.
    assertThatThrownBy(() -> new PluginEvent.ToolUpdate(" ", "running"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PluginEvent.ToolUpdate("search", " "))
        .isInstanceOf(IllegalArgumentException.class);

    // Progress.stage was the second unbounded free-form string, found only because this test was
    // widened to ask about every variant rather than the two it happened to start with.
    assertThatThrownBy(() -> new PluginEvent.Progress(0.5, tooLong))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new PluginEvent.Progress(0.5, atLimit).stage())
        .hasSize(PluginEvent.MAX_PAYLOAD_LENGTH);
    assertThatThrownBy(() -> new PluginEvent.Progress(0.5, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a progress fraction outside [0,1] — including NaN — is refused")
  void progressFractionIsValidated() {
    assertThatThrownBy(() -> new PluginEvent.Progress(1.5, "over"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PluginEvent.Progress(-0.1, "under"))
        .isInstanceOf(IllegalArgumentException.class);
    // NaN passes a naively-written range check written as (f < 0 || f > 1).
    assertThatThrownBy(() -> new PluginEvent.Progress(Double.NaN, "nan"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new PluginEvent.Progress(0.5, "half").terminal()).isFalse();
  }

  @Test
  @DisplayName("only Completed and Failed are terminal")
  void terminalityIsCorrect() {
    assertThat(new PluginEvent.Completed(ToolResponse.of("x")).terminal()).isTrue();
    assertThat(new PluginEvent.Failed(ToolError.of(PluginFailureKind.UNKNOWN)).terminal()).isTrue();
    assertThat(new PluginEvent.Token("t").terminal()).isFalse();
    assertThat(new PluginEvent.ToolUpdate("t", "running").terminal()).isFalse();
    assertThat(new PluginEvent.PartialOutput("p").terminal()).isFalse();
  }
}
