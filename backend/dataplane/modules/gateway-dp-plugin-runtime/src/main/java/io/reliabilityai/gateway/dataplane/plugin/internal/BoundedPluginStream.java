package io.reliabilityai.gateway.dataplane.plugin.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginEvent;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginEventSink;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginStream;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolError;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The bounded, consumer-paced channel between a streaming plugin and its consumer (Doc 28 §30,
 * §40).
 *
 * <p>One object plays both roles — {@link PluginEventSink} for the producer, {@link PluginStream}
 * for the consumer — because they are two ends of the same fixed-capacity queue and splitting them
 * would only add an indirection between them.
 *
 * <p><b>Backpressure is the queue.</b> A producer that outruns its consumer blocks in {@code put}
 * until a slot frees. There is no growth, no spill and no drop-on-full: memory is capacity × event
 * size regardless of how much the plugin decides to emit, which is what Doc 28 §40's "no unbounded
 * buffering" actually requires. Nothing polls and nothing spins — both sides block on the queue and
 * are woken by it.
 *
 * <p><b>Cancellation always wins.</b> {@link #cancel()} interrupts the producer, drains the queue
 * and plants a terminal event in the space that draining just guaranteed. A consumer blocked in
 * {@link #next()} therefore always wakes, even if it cancelled from another thread while the queue
 * was full — which is precisely the deadlock a naive "offer a terminal on cancel" would hit.
 */
public final class BoundedPluginStream implements PluginStream, PluginEventSink {

  private final BlockingQueue<PluginEvent> events;
  private final Runnable cancellationHook;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private volatile Thread producer;
  private volatile PluginEvent terminal;

  /**
   * Creates the channel.
   *
   * @param capacity how many events may be buffered before the producer blocks
   * @param cancellationHook propagates cancellation to the substrate; run at most once
   */
  public BoundedPluginStream(final int capacity, final Runnable cancellationHook) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1");
    }
    this.events = new ArrayBlockingQueue<>(capacity);
    this.cancellationHook = Preconditions.requireNonNull(cancellationHook, "cancellationHook");
  }

  /**
   * Binds the producing thread so cancellation can interrupt it.
   *
   * <p>Called by the producer itself as it starts. Without this the producer would only notice
   * cancellation the next time it tried to emit, which for a plugin doing a long stretch of work
   * between events could be the entire deadline.
   *
   * @param thread the producing thread
   */
  public void bindProducer(final Thread thread) {
    this.producer = Preconditions.requireNonNull(thread, "thread");
    if (cancelled.get()) {
      // Lost the race with a cancel that landed before the producer registered. Interrupt now, or
      // the
      // producer would run to completion writing into a stream nobody will read.
      thread.interrupt();
    }
  }

  @Override
  public boolean emit(final PluginEvent event) {
    Preconditions.requireNonNull(event, "event");
    if (cancelled.get()) {
      return false;
    }
    try {
      events.put(event);
      return true;
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public boolean open() {
    return !cancelled.get() && terminal == null;
  }

  @Override
  public PluginEvent next() throws InterruptedException {
    final PluginEvent alreadyTerminal = terminal;
    if (alreadyTerminal != null) {
      // The stream is over. Returning the terminal again is friendlier than throwing at a caller
      // that
      // simply looped one more time, and it stays truthful — nothing new is invented.
      return alreadyTerminal;
    }
    final PluginEvent event = events.take();
    if (event.terminal()) {
      terminal = event;
    }
    return event;
  }

  @Override
  public void cancel() {
    if (!cancelled.compareAndSet(false, true)) {
      return; // idempotent, per the PluginStream contract
    }
    try {
      cancellationHook.run();
    } catch (final RuntimeException ignored) {
      // Cancellation must complete even if the substrate's hook misbehaves.
    }
    final Thread waiting = producer;
    if (waiting != null) {
      waiting.interrupt();
    }
    // Drain, then plant the terminal. Draining is what makes the offer below certain to fit;
    // without
    // it a full queue would swallow the terminal and leave a consumer blocked in take() forever.
    events.clear();
    events.offer(new PluginEvent.Failed(ToolError.of(PluginFailureKind.CANCELLED)));
  }

  /**
   * Whether this stream has been cancelled.
   *
   * @return true once cancel has run
   */
  public boolean cancelled() {
    return cancelled.get();
  }

  /**
   * How many events are buffered right now.
   *
   * <p>Exposed so a test can assert the producer is actually being held back rather than racing
   * ahead — backpressure that is claimed but not observable is backpressure that quietly regresses.
   *
   * @return the buffered event count
   */
  public int buffered() {
    return events.size();
  }
}
