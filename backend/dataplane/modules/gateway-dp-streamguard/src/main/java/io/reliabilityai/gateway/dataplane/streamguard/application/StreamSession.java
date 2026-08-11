package io.reliabilityai.gateway.dataplane.streamguard.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportDelta;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportEvent;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportOutcomeSink;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSession;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSourcePort;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSourcePort.SourceChunk;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportVerdict;
import io.reliabilityai.gateway.dataplane.streamguard.domain.DedupWindow;
import io.reliabilityai.gateway.dataplane.streamguard.domain.FramingDecoder;
import io.reliabilityai.gateway.dataplane.streamguard.domain.IntegrityCheckpoint;
import io.reliabilityai.gateway.dataplane.streamguard.domain.RawFrame;
import io.reliabilityai.gateway.dataplane.streamguard.domain.Sequencer;
import io.reliabilityai.gateway.dataplane.streamguard.domain.TransportFailureClass;
import io.reliabilityai.gateway.dataplane.streamguard.domain.TransportIntegrityException;
import io.reliabilityai.gateway.dataplane.streamguard.domain.Utf8Validator;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The fail-closed transport-integrity session orchestrator (Doc 18 §8/§9) — the deterministic core
 * that realizes SG-INV. It pulls raw chunks, decodes framing (bounded), strict-validates UTF-8
 * (reject, never substitute), sequences + suppresses duplicates, enforces byte and dual-timeout
 * budgets, and emits arrival-ordered, effectively-once {@link TransportDelta}s — marking the stream
 * <b>complete only</b> on an explicit transport terminal after all checks pass (Doc 18 §21). Every
 * transport uncertainty — truncation, decode, overflow, timeout, duplicate, cancellation — resolves
 * to a {@code FAILED} verdict; there is no path from "uncertain" to "marked complete" (Doc 18 §53,
 * SG-D11).
 *
 * <p>Deterministic (Doc 18 §34): the delta sequence and verdict are a pure function of the input
 * byte sequence, policy, and injected {@link ClockPort} — no wall-clock/random in the core (R-063).
 * Per session; never shares mutable state across sessions (AD-021, Doc 18 §39). Not designed for
 * concurrent {@code next()} calls; {@link #cancel} is race-safe via a volatile flag (Doc 18 §27).
 *
 * <p><b>Scope note (honest):</b> pre-emission transport <em>retry</em> (Doc 18 §26) re-opens the
 * provider stream via C1 and is a composition-level concern using the point-of-no-return signal
 * ({@link #hasEmitted()}); this session operates over one opened source and never merges partial
 * streams (SG-D8).
 */
public final class StreamSession implements TransportSession {

  private final TransportSourcePort source;
  private final FramingDecoder decoder; // null ⇒ unsupported framing (fail closed)
  private final StreamGuardPolicy policy;
  private final ClockPort clock;
  private final TransportOutcomeSink sink;

  private final Sequencer sequencer = new Sequencer();
  private final DedupWindow dedup;
  private final Deque<RawFrame> pending = new ArrayDeque<>();

  private final Instant start;
  private Instant lastActivity;
  private long totalBytes;
  private long deltasEmitted;
  private boolean emitted;

  private volatile boolean cancelRequested;
  private boolean done;
  private TransportEvent terminal;

  StreamSession(
      final TransportSourcePort source,
      final FramingDecoder decoder,
      final StreamGuardPolicy policy,
      final ClockPort clock,
      final TransportOutcomeSink sink) {
    this.source = Preconditions.requireNonNull(source, "source");
    this.decoder = decoder; // may be null: unsupported framing
    this.policy = Preconditions.requireNonNull(policy, "policy");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.sink = Preconditions.requireNonNull(sink, "sink");
    this.dedup = new DedupWindow(policy.dedupWindow());
    this.start = clock.now();
    this.lastActivity = this.start;
  }

  @Override
  public TransportEvent next() {
    if (done) {
      return terminal; // idempotent terminal
    }
    if (cancelRequested) {
      return fail(TransportFailureClass.CANCELLED);
    }
    if (decoder == null) {
      return fail(TransportFailureClass.UNSUPPORTED_FRAMING);
    }
    try {
      return pump();
    } catch (final TransportIntegrityException e) {
      return fail(e.failureClass());
    }
  }

  private TransportEvent pump() throws TransportIntegrityException {
    while (true) {
      // 1. Drain already-decoded frames before pulling more (preserves arrival order).
      while (!pending.isEmpty()) {
        final TransportEvent event = handleFrame(pending.poll());
        if (event != null) {
          return event; // a data delta or the terminal verdict
        }
        // heartbeat or suppressed duplicate → keep draining
      }
      if (cancelRequested) {
        return fail(TransportFailureClass.CANCELLED);
      }

      // 2. Pull the next raw chunk.
      final SourceChunk chunk;
      try {
        chunk = source.read();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return fail(TransportFailureClass.CANCELLED);
      }
      final Instant now = clock.now();
      if (Duration.between(start, now).compareTo(policy.totalTimeout()) > 0) {
        return fail(TransportFailureClass.TIMEOUT); // total-duration budget (Doc 18 §24)
      }

      if (chunk instanceof SourceChunk.Data data) {
        if (Duration.between(lastActivity, now).compareTo(policy.inactivityTimeout()) > 0) {
          return fail(TransportFailureClass.TIMEOUT); // inactivity budget / slow-loris (Doc 18 §24)
        }
        lastActivity = now;
        final byte[] bytes = data.bytes();
        totalBytes += bytes.length;
        if (totalBytes > policy.maxSessionBytes()) {
          return fail(TransportFailureClass.OVERFLOW); // bounded session bytes (Doc 18 §31/§32)
        }
        pending.addAll(decoder.push(bytes)); // may throw DECODE/OVERFLOW/CORRUPTION
      } else {
        // Closed: complete only on an explicit terminal with nothing buffered (Doc 18 §21).
        if (decoder.terminalObserved() && pending.isEmpty() && decoder.bufferedBytes() == 0) {
          return complete();
        }
        return fail(
            TransportFailureClass.TRUNCATED); // close without terminal (Doc 18 §21) — never silent
      }
    }
  }

  private TransportEvent handleFrame(final RawFrame frame) throws TransportIntegrityException {
    switch (frame.kind()) {
      case HEARTBEAT -> {
        lastActivity = clock.now(); // liveness only; never emitted (Doc 18 §23)
        return null;
      }
      case TERMINAL -> {
        return complete(); // explicit transport terminal after passed checks (Doc 18 §21)
      }
      case DATA -> {
        final byte[] payload = frame.payload();
        Utf8Validator.validateStrict(payload); // strict; reject, never substitute (Doc 18 §13)
        final long hash = IntegrityCheckpoint.hash(payload);
        if (dedup.isDuplicate(hash)) {
          return null; // within-window duplicate suppressed once (Doc 18 §17/§16.1)
        }
        dedup.record(hash);
        final long seq = sequencer.nextSeq(); // monotonic; overflow guarded (Doc 18 §16)
        deltasEmitted++;
        emitted = true; // point of no return (Doc 18 §26)
        return new TransportEvent.Delta(TransportDelta.data(seq, payload));
      }
      default -> {
        return fail(TransportFailureClass.CORRUPTION);
      }
    }
  }

  private TransportEvent complete() {
    done = true;
    final TransportVerdict verdict = TransportVerdict.proven(deltasEmitted);
    terminal = new TransportEvent.Completed(verdict);
    safeSink(verdict);
    return terminal;
  }

  private TransportEvent fail(final TransportFailureClass failureClass) {
    done = true;
    safeCancelSource(); // stop provider on failure (Doc 18 §26/§27) — never silent recovery
    final TransportVerdict verdict = TransportVerdict.failed(failureClass, deltasEmitted);
    terminal = new TransportEvent.Completed(verdict);
    safeSink(verdict);
    return terminal;
  }

  @Override
  public void cancel(final String reason) {
    cancelRequested = true; // observed by next()/pump(); race-safe with completion (Doc 18 §27)
    safeCancelSource();
  }

  /**
   * Whether a delta has been emitted downstream — the point of no return (Doc 18 §26). Used by
   * composition to decide pre-emission transport retry eligibility.
   *
   * @return {@code true} once the first delta has been emitted
   */
  public boolean hasEmitted() {
    return emitted;
  }

  private void safeCancelSource() {
    try {
      source.cancel();
    } catch (final RuntimeException ignored) {
      // cancellation is best-effort; never masks the verdict
    }
  }

  private void safeSink(final TransportVerdict verdict) {
    try {
      sink.onVerdict(verdict);
    } catch (final RuntimeException ignored) {
      // content-free outcome sink is best-effort; never alters the verdict (Doc 18 §45)
    }
  }
}
