package io.reliabilityai.gateway.dataplane.plugin.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.IsolationLevel;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxHostPort;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxOutcome;
import io.reliabilityai.gateway.dataplane.plugin.domain.FailureClassifier;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The in-JVM substrate: one virtual thread per invocation, bounded by a wall-clock deadline (Doc 28
 * §ISO, §REC).
 *
 * <p><b>This is not the isolation boundary.</b> Doc 28 ISO-1 places that at the OS process, and
 * ISO-8 says in-JVM sandboxing is not claimed. This substrate reports {@link
 * IsolationLevel#IN_PROCESS} for exactly that reason, and the registry refuses to put third-party
 * code on it. It exists for first-party code shipped with the gateway, where the process boundary
 * would buy containment against a threat model that does not apply and cost a process per
 * invocation.
 *
 * <p><b>What it does enforce:</b> a wall-clock deadline, interrupt-based cancellation, and a hard
 * cap on how many invocations may be in flight at once. That last one is the real protection here —
 * an unbounded virtual-thread count is how a slow plugin turns into host memory exhaustion, and it
 * is enforceable in-process where a memory ceiling is not.
 *
 * <p><b>What it cannot enforce:</b> a memory ceiling, a CPU ceiling independent of wall-clock, or
 * containment of a thread that refuses to be interrupted. The JVM offers no per-thread heap limit,
 * and {@code Thread.stop} is gone for good reasons. A first-party plugin that ignores interrupts is
 * abandoned — the caller is released on the deadline and the orphan runs until it finishes. That is
 * a real, stated residual (Doc 28 ISO-9), and the reason untrusted code does not come near this
 * class.
 */
public final class InProcessSandbox implements SandboxHostPort {

  private final int maxConcurrentInvocations;
  private final AtomicInteger inFlight = new AtomicInteger();
  private volatile boolean shutdown;

  /**
   * Creates the substrate.
   *
   * @param maxConcurrentInvocations the ceiling on simultaneous in-flight invocations
   */
  public InProcessSandbox(final int maxConcurrentInvocations) {
    if (maxConcurrentInvocations < 1) {
      throw new IllegalArgumentException("maxConcurrentInvocations must be at least 1");
    }
    this.maxConcurrentInvocations = maxConcurrentInvocations;
  }

  @Override
  public IsolationLevel isolation() {
    return IsolationLevel.IN_PROCESS;
  }

  @Override
  public boolean supports(final PluginType type) {
    // Only the one type Doc 28 ISO-1 permits in the host JVM. Everything else — including the
    // reserved
    // WASM and PYTHON types — is refused here rather than silently downgraded onto a weaker
    // substrate.
    return type == PluginType.INTERNAL;
  }

  @Override
  public <T> SandboxOutcome<T> run(final SandboxInvocation invocation, final Callable<T> task) {
    Preconditions.requireNonNull(invocation, "invocation");
    Preconditions.requireNonNull(task, "task");

    if (shutdown) {
      return new SandboxOutcome.Cancelled<>(SandboxOutcome.ResourceUsage.none());
    }
    // Admission before the thread is created: refusing here costs nothing, whereas refusing after
    // starting the work means the resource was already spent.
    if (inFlight.incrementAndGet() > maxConcurrentInvocations) {
      inFlight.decrementAndGet();
      return new SandboxOutcome.Breached<>(
          PluginFailureKind.RESOURCE_EXCEEDED, SandboxOutcome.ResourceUsage.none());
    }

    final long startedNanos = System.nanoTime();
    final Thread[] worker = new Thread[1];
    try {
      final CompletableFuture<T> result = new CompletableFuture<>();
      final Thread thread =
          Thread.ofVirtual()
              .name("plugin-" + invocation.pluginId().value())
              .unstarted(
                  () -> {
                    try {
                      result.complete(task.call());
                    } catch (final Throwable failure) {
                      // Errors are captured too: letting an Error escape a virtual thread would
                      // kill
                      // it silently and leave the caller waiting out the full deadline for a result
                      // that is never coming.
                      result.completeExceptionally(failure);
                    }
                  });
      worker[0] = thread;
      thread.start();

      try {
        final T value =
            result.get(invocation.budget().wallClock().toMillis(), TimeUnit.MILLISECONDS);
        return new SandboxOutcome.Completed<>(value, usage(startedNanos));
      } catch (final TimeoutException overran) {
        thread.interrupt();
        return new SandboxOutcome.Breached<>(PluginFailureKind.TIMEOUT, usage(startedNanos));
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        thread.interrupt();
        return new SandboxOutcome.Cancelled<>(usage(startedNanos));
      } catch (final CancellationException cancelled) {
        thread.interrupt();
        return new SandboxOutcome.Cancelled<>(usage(startedNanos));
      } catch (final ExecutionException failed) {
        return new SandboxOutcome.Threw<>(
            FailureClassifier.classifyUnwrapped(failed.getCause()), usage(startedNanos));
      }
    } finally {
      inFlight.decrementAndGet();
      if (shutdown && worker[0] != null) {
        worker[0].interrupt();
      }
    }
  }

  @Override
  public void shutdown() {
    // Marks the substrate closed so no further invocation is admitted. In-flight virtual threads
    // are
    // interrupted as they are released; there is deliberately no join, because waiting on plugin
    // code
    // to finish would let a plugin hold up node shutdown.
    shutdown = true;
  }

  /**
   * How many invocations are in flight right now.
   *
   * @return the in-flight count
   */
  public int inFlight() {
    return inFlight.get();
  }

  /**
   * Measures what the invocation consumed.
   *
   * <p>CPU is <b>proxied by elapsed wall-clock</b>, which is an upper bound for the single-threaded
   * work this substrate runs. The platform offers no per-virtual-thread CPU accounting, and
   * reporting a fabricated number would be worse than reporting an honest upper bound. Memory is
   * reported as zero because the JVM provides no per-task heap attribution at all — zero here means
   * "not measured on this substrate", which the process substrate does measure (Doc 28 ISO-9).
   */
  private static SandboxOutcome.ResourceUsage usage(final long startedNanos) {
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
    return new SandboxOutcome.ResourceUsage(elapsed, elapsed.toMillis(), 0L);
  }
}
