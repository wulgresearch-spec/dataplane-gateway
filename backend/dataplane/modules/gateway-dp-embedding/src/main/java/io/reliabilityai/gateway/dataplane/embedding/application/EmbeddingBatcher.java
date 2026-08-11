package io.reliabilityai.gateway.dataplane.embedding.application;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingCapability;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingMetricsPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Turns many small calls into few large ones (AD-030 §6.2).
 *
 * <p>Two separate mechanisms live here, and conflating them is a common way to get batching wrong.
 *
 * <p><b>Splitting</b> is a pure function: a request of any size becomes a list of requests each
 * within the provider's declared batch and payload limits. It has no threads, no timing and no
 * state, which is why it is a static method and is exhaustively testable.
 *
 * <p><b>Coalescing</b> is the part that needs a clock. The Memory Runtime calls {@code
 * EmbeddingPort} one text at a time, so the only way to batch its traffic is to hold each caller
 * briefly and gather whatever else arrives in that window. Concurrent writers therefore batch; a
 * single-threaded writer gets batches of one and pays the timeout. That asymmetry is inherent to a
 * synchronous single-text port and is recorded as B56.
 *
 * <p>Inputs are grouped by tenant and model, because a provider call carries one model and mixing
 * tenants in one call would make per-tenant cost attribution guesswork.
 */
public final class EmbeddingBatcher implements AutoCloseable {

  /** How many timeout-flushed batches may be in flight at once. */
  private static final int DISPATCH_THREADS = 8;

  /** How many flushed batches may wait for a dispatch worker before the flusher runs one inline. */
  private static final int DISPATCH_QUEUE = 64;

  private final int maxBatchSize;

  private final int maxPayloadBytes;

  private final Duration batchTimeout;

  private final int maxQueueDepth;

  private final EmbeddingMetricsPort metrics;

  private final Function<EmbeddingRequest, EmbeddingResponse> executor;

  private final Map<GroupKey, Group> groups = new ConcurrentHashMap<>();

  private final AtomicInteger queued = new AtomicInteger();

  private final Thread flusher;

  /**
   * Where timeout-flushed batches actually run.
   *
   * <p><b>Not the flusher thread.</b> The flusher's job is to notice that a group has waited long
   * enough; running the provider call on it as well made one slow tenant everyone's problem.
   * Measured before this changed: with a 50 ms batch window and one tenant's provider taking 3 s, a
   * second tenant's single input waited <b>5,887 ms</b> — it was queued behind a network call for a
   * tenant it has no relationship with. That is a tenant-isolation failure on the availability axis
   * (AD-021), and no amount of per-tenant grouping fixes it while the grouping is drained serially.
   *
   * <p>The pool is bounded and its queue is bounded, so this is a bulkhead rather than an unbounded
   * hand-off: when every worker is busy the flusher runs the batch inline, which restores the old
   * behaviour only under genuine saturation and never silently drops a batch.
   */
  private final ThreadPoolExecutor dispatcher;

  private volatile boolean running = true;

  /**
   * Creates a batcher and starts its flusher thread.
   *
   * @param maxBatchSize how many inputs one provider call may carry
   * @param maxPayloadBytes how many encoded bytes one provider call may carry
   * @param batchTimeout how long an input waits for company before being sent alone
   * @param maxQueueDepth how many inputs may be waiting before submission blocks
   * @param metrics where queue depth is reported
   * @param executor what actually performs a batch
   */
  public EmbeddingBatcher(
      final int maxBatchSize,
      final int maxPayloadBytes,
      final Duration batchTimeout,
      final int maxQueueDepth,
      final EmbeddingMetricsPort metrics,
      final Function<EmbeddingRequest, EmbeddingResponse> executor) {
    if (maxBatchSize < 1 || maxPayloadBytes < 1 || maxQueueDepth < 1) {
      throw new IllegalArgumentException("batcher limits must be positive");
    }
    this.maxBatchSize = maxBatchSize;
    this.maxPayloadBytes = maxPayloadBytes;
    this.batchTimeout = Preconditions.requireNonNull(batchTimeout, "batchTimeout");
    this.maxQueueDepth = maxQueueDepth;
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.executor = Preconditions.requireNonNull(executor, "executor");
    this.dispatcher =
        new ThreadPoolExecutor(
            2,
            DISPATCH_THREADS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(DISPATCH_QUEUE),
            runnable -> {
              final Thread thread = new Thread(runnable, "embedding-batch-dispatch");
              thread.setDaemon(true);
              return thread;
            },
            // Caller-runs on saturation. The alternative policies are both wrong here: aborting
            // drops
            // a batch whose futures callers are already blocked on, and an unbounded queue turns a
            // slow provider into an out-of-memory kill, which is the failure this class exists to
            // avoid on the submit path.
            new ThreadPoolExecutor.CallerRunsPolicy());
    this.flusher = new Thread(this::flushLoop, "embedding-batcher");
    this.flusher.setDaemon(true);
    this.flusher.start();
  }

  /**
   * Splits a request into pieces each provider can accept.
   *
   * <p>An input that alone exceeds the payload limit still gets its own piece rather than being
   * dropped here. Refusing it is the provider's answer to report, and inventing the refusal locally
   * would mean two places deciding what is too large.
   *
   * @param request the whole request
   * @param capability the provider limits
   * @return the pieces, in order, together covering every input exactly once
   */
  public static List<EmbeddingRequest> split(
      final EmbeddingRequest request, final EmbeddingCapability capability) {
    final List<EmbeddingRequest> pieces = new ArrayList<>();
    final List<String> current = new ArrayList<>();
    int currentBytes = 0;
    for (final String text : request.texts()) {
      final int bytes = text.getBytes(StandardCharsets.UTF_8).length;
      final boolean wouldOverflow =
          !current.isEmpty()
              && (current.size() >= capability.maxBatchSize()
                  || currentBytes + bytes > capability.maxPayloadBytes());
      if (wouldOverflow) {
        pieces.add(
            new EmbeddingRequest(
                request.tenant(), request.modelId(), List.copyOf(current), request.purpose()));
        current.clear();
        currentBytes = 0;
      }
      current.add(text);
      currentBytes += bytes;
    }
    if (!current.isEmpty()) {
      pieces.add(
          new EmbeddingRequest(
              request.tenant(), request.modelId(), List.copyOf(current), request.purpose()));
    }
    return pieces;
  }

  /**
   * Submits one input and waits for its turn in a batch.
   *
   * @param tenant whose input it is
   * @param modelId the neutral model id
   * @param text the input
   * @param purpose why it is wanted
   * @return a future completing with that input outcome
   */
  public CompletableFuture<EmbeddingResponse.Outcome> submit(
      final TenantScope tenant,
      final String modelId,
      final String text,
      final EmbeddingRequest.EmbeddingPurpose purpose) {
    final CompletableFuture<EmbeddingResponse.Outcome> future = new CompletableFuture<>();
    if (!running) {
      future.complete(
          new EmbeddingResponse.Outcome.Failed(
              EmbeddingFailure.UNAVAILABLE, "the batcher is shut down"));
      return future;
    }
    if (queued.get() >= maxQueueDepth) {
      // Back-pressure made visible rather than absorbed. Growing the queue without bound converts a
      // slow provider into an out-of-memory failure, which is a worse outage than a refused write.
      future.complete(
          new EmbeddingResponse.Outcome.Failed(
              EmbeddingFailure.UNAVAILABLE, "the embedding queue is full"));
      metrics.failed(EmbeddingFailure.UNAVAILABLE, 1);
      return future;
    }
    final GroupKey key = new GroupKey(tenant, modelId, purpose);
    final Group group = groups.computeIfAbsent(key, ignored -> new Group());
    final int depth;
    List<Pending> ready = null;
    group.lock.lock();
    try {
      if (group.pending.isEmpty()) {
        group.oldestNanos = System.nanoTime();
      }
      group.pending.add(new Pending(text, future));
      depth = queued.incrementAndGet();
      if (group.pending.size() >= maxBatchSize) {
        ready = group.drain();
      }
    } finally {
      group.lock.unlock();
    }
    metrics.queueDepth(depth);
    // Executed outside the lock: a provider call can take hundreds of milliseconds, and holding a
    // group lock across it would serialise every other submitter for that tenant behind the
    // network.
    if (ready != null) {
      run(key, ready);
    }
    return future;
  }

  /**
   * How many inputs are waiting for a batch to close.
   *
   * @return the queue depth
   */
  public int queueDepth() {
    return queued.get();
  }

  @Override
  public void close() {
    running = false;
    flusher.interrupt();
    try {
      flusher.join(TimeUnit.SECONDS.toMillis(5));
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    // Batches already handed to the dispatcher are allowed to finish before their futures are
    // failed below. shutdown() rather than shutdownNow(): a batch that is mid-provider-call has
    // callers waiting on its outcome, and cancelling it would complete them with a failure for work
    // that actually succeeded.
    dispatcher.shutdown();
    try {
      if (!dispatcher.awaitTermination(10L, TimeUnit.SECONDS)) {
        dispatcher.shutdownNow();
      }
    } catch (final InterruptedException interrupted) {
      dispatcher.shutdownNow();
      Thread.currentThread().interrupt();
    }
    // Whatever is still queued is completed rather than abandoned: a caller blocked on a future
    // that
    // never completes is a hang, and a hang on shutdown is the kind that survives to production.
    for (final Map.Entry<GroupKey, Group> entry : groups.entrySet()) {
      final Group group = entry.getValue();
      group.lock.lock();
      try {
        for (final Pending pending : group.drain()) {
          queued.decrementAndGet();
          pending.future.complete(
              new EmbeddingResponse.Outcome.Failed(
                  EmbeddingFailure.UNAVAILABLE,
                  "the batcher shut down before this input was sent"));
        }
      } finally {
        group.lock.unlock();
      }
    }
  }

  /** Sends any group that has waited long enough. */
  private void flushLoop() {
    final long tickMillis = Math.max(1L, batchTimeout.toMillis() / 4);
    while (running) {
      try {
        Thread.sleep(tickMillis);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      final long now = System.nanoTime();
      for (final Map.Entry<GroupKey, Group> entry : groups.entrySet()) {
        final Group group = entry.getValue();
        final List<Pending> ready;
        group.lock.lock();
        try {
          if (group.pending.isEmpty() || now - group.oldestNanos < batchTimeout.toNanos()) {
            continue;
          }
          ready = group.drain();
        } finally {
          group.lock.unlock();
        }
        // Handed to the dispatcher rather than executed here. The flusher must stay free to notice
        // every other group's timeout; a provider call on this thread makes one tenant's latency
        // every tenant's latency.
        dispatch(entry.getKey(), ready);
      }
    }
  }

  /**
   * Runs a flushed batch off the flusher thread.
   *
   * @param key the tenant, model and purpose shared by the batch
   * @param batch the pending inputs
   */
  private void dispatch(final GroupKey key, final List<Pending> batch) {
    if (batch.isEmpty()) {
      return;
    }
    try {
      dispatcher.execute(() -> run(key, batch));
    } catch (final RejectedExecutionException shuttingDown) {
      // Only reachable once the dispatcher is closing. Completing inline is better than leaving
      // callers blocked on futures nobody owns any more.
      run(key, batch);
    }
  }

  /**
   * Performs one batch and completes its futures.
   *
   * @param key the tenant, model and purpose shared by the batch
   * @param batch the pending inputs
   */
  private void run(final GroupKey key, final List<Pending> batch) {
    if (batch.isEmpty()) {
      return;
    }
    queued.addAndGet(-batch.size());
    final List<String> texts = batch.stream().map(Pending::text).toList();
    try {
      final EmbeddingResponse response =
          executor.apply(new EmbeddingRequest(key.tenant, key.modelId, texts, key.purpose));
      for (int i = 0; i < batch.size(); i++) {
        batch.get(i).future.complete(response.outcomes().get(i));
      }
    } catch (final RuntimeException failure) {
      // One caller must not inherit an exception raised on a shared flusher thread, and the flusher
      // must not die of it either. Every future in the batch is completed with the same neutral
      // failure and the loop continues.
      for (final Pending pending : batch) {
        pending.future.complete(
            new EmbeddingResponse.Outcome.Failed(
                EmbeddingFailure.INTERNAL, failure.getClass().getSimpleName()));
      }
    }
  }

  /**
   * The batching key.
   *
   * @param tenant whose inputs
   * @param modelId which model
   * @param purpose why
   */
  private record GroupKey(
      TenantScope tenant, String modelId, EmbeddingRequest.EmbeddingPurpose purpose) {}

  /**
   * One input waiting for a batch.
   *
   * @param text the input
   * @param future what to complete
   */
  private record Pending(String text, CompletableFuture<EmbeddingResponse.Outcome> future) {}

  /** The pending inputs for one key. */
  private static final class Group {

    private final ReentrantLock lock = new ReentrantLock();

    private final List<Pending> pending = new ArrayList<>();

    private long oldestNanos = System.nanoTime();

    /**
     * Takes everything pending.
     *
     * @return the drained inputs
     */
    private List<Pending> drain() {
      final List<Pending> drained = List.copyOf(pending);
      pending.clear();
      oldestNanos = System.nanoTime();
      return drained;
    }
  }
}
