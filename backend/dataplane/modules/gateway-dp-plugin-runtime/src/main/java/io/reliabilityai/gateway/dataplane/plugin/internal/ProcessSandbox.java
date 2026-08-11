package io.reliabilityai.gateway.dataplane.plugin.internal;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.IsolationLevel;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxHostPort;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxOutcome;
import io.reliabilityai.gateway.dataplane.plugin.domain.FailureClassifier;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The process-isolated substrate (Doc 28 ISO-1, ISO-2).
 *
 * <p>This is the boundary Doc 28 requires for untrusted code. The plugin's code runs in a separate
 * OS process with no shared heap with the request path; what runs here on the host side is only the
 * I/O plumbing that talks to it.
 *
 * <p><b>Why the process registry exists.</b> Interrupting the host-side thread is not enough to
 * cancel a process invocation: a thread blocked reading a pipe does not observe an interrupt, so a
 * deadline breach would leave the caller released but the child still running and the pipe still
 * held. The plugin adapter therefore binds its live child process to the invocation through {@link
 * #bind(CorrelationId, Process)}, and a breach destroys it — which both unblocks the read
 * immediately and enforces containment for real.
 *
 * <p><b>What this genuinely guarantees</b> (Doc 28 ISO-2/ISO-6): no shared heap, containment of a
 * crash or an escape attempt to the child, a kill that always works, and real CPU accounting from
 * the OS. <b>What it does not</b> (ISO-9): absolute escape prevention, and a memory ceiling —
 * enforcing that needs an OS mechanism (cgroups, job objects, a container) that this substrate
 * delegates to the deployment rather than pretending to implement.
 */
public final class ProcessSandbox implements SandboxHostPort {

  private final int maxConcurrentInvocations;
  private final Map<String, Process> liveProcesses = new ConcurrentHashMap<>();
  private final AtomicInteger inFlight = new AtomicInteger();
  private volatile boolean shutdown;

  /**
   * Creates the substrate.
   *
   * @param maxConcurrentInvocations the ceiling on simultaneous child processes
   */
  public ProcessSandbox(final int maxConcurrentInvocations) {
    if (maxConcurrentInvocations < 1) {
      throw new IllegalArgumentException("maxConcurrentInvocations must be at least 1");
    }
    this.maxConcurrentInvocations = maxConcurrentInvocations;
  }

  @Override
  public IsolationLevel isolation() {
    return IsolationLevel.PROCESS;
  }

  @Override
  public boolean supports(final PluginType type) {
    // Every type that needs a real boundary. INTERNAL is excluded not because it would be unsafe
    // here
    // but because paying for a process to run first-party in-JVM code is pure overhead.
    return type == PluginType.PROCESS || type == PluginType.REMOTE || type == PluginType.MCP;
  }

  /**
   * Binds a live child process to an in-flight invocation so a deadline breach can destroy it.
   *
   * <p>Called by the plugin adapter immediately after spawning. Unbind in a {@code finally}: a
   * stale binding would let a later breach kill a process that already finished, and worse, one the
   * next invocation might have reused the id for.
   *
   * @param correlationId the invocation's correlation id
   * @param process the live child process
   */
  public void bind(final CorrelationId correlationId, final Process process) {
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(process, "process");
    liveProcesses.put(correlationId.value(), process);
  }

  /**
   * Releases a binding once the invocation is done with its child.
   *
   * @param correlationId the invocation's correlation id
   */
  public void unbind(final CorrelationId correlationId) {
    Preconditions.requireNonNull(correlationId, "correlationId");
    liveProcesses.remove(correlationId.value());
  }

  @Override
  public <T> SandboxOutcome<T> run(final SandboxInvocation invocation, final Callable<T> task) {
    Preconditions.requireNonNull(invocation, "invocation");
    Preconditions.requireNonNull(task, "task");

    if (shutdown) {
      return new SandboxOutcome.Cancelled<>(SandboxOutcome.ResourceUsage.none());
    }
    if (inFlight.incrementAndGet() > maxConcurrentInvocations) {
      inFlight.decrementAndGet();
      return new SandboxOutcome.Breached<>(
          PluginFailureKind.RESOURCE_EXCEEDED, SandboxOutcome.ResourceUsage.none());
    }

    final long startedNanos = System.nanoTime();
    try {
      final CompletableFuture<T> result = new CompletableFuture<>();
      final Thread worker =
          Thread.ofVirtual()
              .name("plugin-proc-" + invocation.pluginId().value())
              .start(
                  () -> {
                    try {
                      result.complete(task.call());
                    } catch (final Throwable failure) {
                      result.completeExceptionally(failure);
                    }
                  });

      try {
        final T value =
            result.get(invocation.budget().wallClock().toMillis(), TimeUnit.MILLISECONDS);
        return new SandboxOutcome.Completed<>(value, usage(invocation, startedNanos));
      } catch (final TimeoutException overran) {
        // Kill first, then interrupt. Destroying the child is what actually unblocks the pipe read;
        // the interrupt only matters for a plumbing thread that happens to be waiting elsewhere.
        final SandboxOutcome.ResourceUsage consumed = usage(invocation, startedNanos);
        terminate(invocation.correlationId());
        worker.interrupt();
        return new SandboxOutcome.Breached<>(PluginFailureKind.TIMEOUT, consumed);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        final SandboxOutcome.ResourceUsage consumed = usage(invocation, startedNanos);
        terminate(invocation.correlationId());
        worker.interrupt();
        return new SandboxOutcome.Cancelled<>(consumed);
      } catch (final CancellationException cancelled) {
        final SandboxOutcome.ResourceUsage consumed = usage(invocation, startedNanos);
        terminate(invocation.correlationId());
        worker.interrupt();
        return new SandboxOutcome.Cancelled<>(consumed);
      } catch (final ExecutionException failed) {
        return new SandboxOutcome.Threw<>(
            FailureClassifier.classifyUnwrapped(failed.getCause()),
            usage(invocation, startedNanos));
      }
    } finally {
      inFlight.decrementAndGet();
    }
  }

  /**
   * Destroys the child bound to an invocation, if any. Idempotent and never throws — cancellation
   * must succeed even when the process is already gone.
   *
   * @param correlationId the invocation's correlation id
   */
  public void terminate(final CorrelationId correlationId) {
    final Process process = liveProcesses.remove(correlationId.value());
    if (process == null) {
      return;
    }
    try {
      process.destroyForcibly();
    } catch (final RuntimeException ignored) {
      // A process that cannot be destroyed is already gone; nothing left to contain.
    }
  }

  @Override
  public void shutdown() {
    shutdown = true;
    // Every child dies with the node. Leaving one behind would outlive the runtime that was
    // accounting for it, which is how orphaned plugin processes accumulate across restarts.
    for (final Process process : liveProcesses.values()) {
      try {
        process.destroyForcibly();
      } catch (final RuntimeException ignored) {
        // best effort; shutdown must complete
      }
    }
    liveProcesses.clear();
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
   * Measures what the invocation consumed, reading real CPU time from the OS when the child is
   * still bound.
   *
   * <p>Falls back to the wall-clock upper bound once the child has exited, because the handle's CPU
   * total is no longer readable then. Memory stays zero: this substrate does not implement a memory
   * ceiling and does not pretend to measure one (Doc 28 ISO-9).
   */
  private SandboxOutcome.ResourceUsage usage(
      final SandboxInvocation invocation, final long startedNanos) {
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
    final Process process = liveProcesses.get(invocation.correlationId().value());
    long cpuMillis = elapsed.toMillis();
    if (process != null) {
      cpuMillis =
          process.info().totalCpuDuration().map(Duration::toMillis).orElse(elapsed.toMillis());
    }
    return new SandboxOutcome.ResourceUsage(elapsed, cpuMillis, 0L);
  }
}
