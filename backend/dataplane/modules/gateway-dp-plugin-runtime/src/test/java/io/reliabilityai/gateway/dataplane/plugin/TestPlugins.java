package io.reliabilityai.gateway.dataplane.plugin;

import io.reliabilityai.gateway.dataplane.plugin.api.ExtensionPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.ExtensionRequest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginEvent;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginEventSink;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginHealth;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.StreamingToolPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolContext;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Plugin implementations the tests drive. */
final class TestPlugins {

  private TestPlugins() {}

  /** A tool plugin whose behaviour is a supplied function. */
  static final class FunctionTool implements ToolPlugin {
    private final PluginManifest manifest;
    private final Function<ToolInvocation, ToolResponse> body;
    final AtomicInteger invocations = new AtomicInteger();
    final AtomicReference<ToolContext> lastContext = new AtomicReference<>();

    FunctionTool(final PluginManifest manifest, final Function<ToolInvocation, ToolResponse> body) {
      this.manifest = manifest;
      this.body = body;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public PluginHealth health() {
      return PluginHealth.READY;
    }

    @Override
    public ToolResponse invoke(final ToolInvocation invocation) {
      invocations.incrementAndGet();
      lastContext.set(invocation.context());
      return body.apply(invocation);
    }
  }

  /** A tool plugin that throws whatever it was given. */
  static final class ThrowingTool implements ToolPlugin {
    private final PluginManifest manifest;
    private final Exception failure;

    ThrowingTool(final PluginManifest manifest, final Exception failure) {
      this.manifest = manifest;
      this.failure = failure;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public ToolResponse invoke(final ToolInvocation invocation) throws Exception {
      throw failure;
    }
  }

  /** A tool plugin that sleeps past its deadline, honouring interrupts. */
  static final class SlowTool implements ToolPlugin {
    private final PluginManifest manifest;
    private final Duration sleep;
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicInteger interruptions = new AtomicInteger();

    SlowTool(final PluginManifest manifest, final Duration sleep) {
      this.manifest = manifest;
      this.sleep = sleep;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public ToolResponse invoke(final ToolInvocation invocation) throws Exception {
      entered.countDown();
      try {
        Thread.sleep(sleep.toMillis());
      } catch (final InterruptedException interrupted) {
        interruptions.incrementAndGet();
        Thread.currentThread().interrupt();
        throw interrupted;
      }
      return ToolResponse.of("slow-finished");
    }
  }

  /** A plugin whose start hook fails. */
  static final class UnstartableTool implements ToolPlugin {
    private final PluginManifest manifest;

    UnstartableTool(final PluginManifest manifest) {
      this.manifest = manifest;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      throw new IllegalStateException("cannot initialize");
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public ToolResponse invoke(final ToolInvocation invocation) {
      return ToolResponse.of("never reached");
    }
  }

  /** An extension plugin returning a fixed contribution. Subclassed to vary reported health. */
  static class FixedExtension implements ExtensionPlugin {
    private final PluginManifest manifest;
    private final Map<String, String> contribution;
    final AtomicInteger invocations = new AtomicInteger();
    final AtomicReference<ExtensionRequest> lastRequest = new AtomicReference<>();

    FixedExtension(final PluginManifest manifest, final Map<String, String> contribution) {
      this.manifest = manifest;
      this.contribution = contribution;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public Map<String, String> contributeAt(final ExtensionRequest request) throws Exception {
      invocations.incrementAndGet();
      lastRequest.set(request);
      return contribution;
    }
  }

  /** An extension plugin that throws. */
  static final class ThrowingExtension implements ExtensionPlugin {
    private final PluginManifest manifest;

    ThrowingExtension(final PluginManifest manifest) {
      this.manifest = manifest;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public Map<String, String> contributeAt(final ExtensionRequest request) {
      throw new IllegalStateException("extension exploded");
    }
  }

  /** An extension plugin that records the order it ran in, shared across instances. */
  static final class OrderRecordingExtension implements ExtensionPlugin {
    private final PluginManifest manifest;
    private final java.util.List<String> log;

    OrderRecordingExtension(final PluginManifest manifest, final java.util.List<String> log) {
      this.manifest = manifest;
      this.log = log;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public Map<String, String> contributeAt(final ExtensionRequest request) {
      log.add(manifest.id().value());
      return Map.of("ran", manifest.id().value());
    }
  }

  /** A streaming tool plugin emitting a fixed number of tokens then completing. */
  static final class CountingStream implements StreamingToolPlugin {
    private final PluginManifest manifest;
    private final int tokens;
    final AtomicInteger emitted = new AtomicInteger();
    final AtomicInteger refused = new AtomicInteger();

    CountingStream(final PluginManifest manifest, final int tokens) {
      this.manifest = manifest;
      this.tokens = tokens;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public ToolResponse invoke(final ToolInvocation invocation) {
      return ToolResponse.of("unary-" + tokens);
    }

    @Override
    public void invokeStreaming(final ToolInvocation invocation, final PluginEventSink sink) {
      for (int index = 0; index < tokens; index++) {
        if (!sink.emit(new PluginEvent.Token("t" + index))) {
          refused.incrementAndGet();
          return;
        }
        emitted.incrementAndGet();
      }
      sink.emit(new PluginEvent.Completed(ToolResponse.of("done")));
    }
  }

  /** A streaming plugin that returns without ever emitting a terminal event. */
  static final class TerminallessStream implements StreamingToolPlugin {
    private final PluginManifest manifest;

    TerminallessStream(final PluginManifest manifest) {
      this.manifest = manifest;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      // nothing to release
    }

    @Override
    public ToolResponse invoke(final ToolInvocation invocation) {
      return ToolResponse.of("unary");
    }

    @Override
    public void invokeStreaming(final ToolInvocation invocation, final PluginEventSink sink) {
      sink.emit(new PluginEvent.Progress(0.5, "half"));
      // Returns without a terminal — a contract breach the runtime must not paper over.
    }
  }
}
