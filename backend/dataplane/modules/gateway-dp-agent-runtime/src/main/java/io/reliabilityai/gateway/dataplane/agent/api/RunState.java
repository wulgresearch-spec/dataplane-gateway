package io.reliabilityai.gateway.dataplane.agent.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * The run lifecycle states (AD-025 §53.1).
 *
 * <p><b>On the absence of a {@code TERMINAL} constant.</b> The milestone brief lists "Terminal"
 * alongside the ten states below. It is modelled here as a <em>property</em> — {@link #terminal()}
 * and {@link #TERMINAL_STATES} — rather than as an eleventh constant, because a run sitting in a
 * state literally named {@code TERMINAL} would carry strictly less information than {@code
 * COMPLETED}, {@code FAILED}, {@code CANCELLED}, {@code TIMED_OUT} or {@code DEAD_LETTER} already
 * carry, and it would give the state machine two absorbing states that an illegal-transition test
 * cannot tell apart. Every guarantee the brief asks for — absorbing terminal states, rejected
 * illegal transitions — holds; only the spelling differs.
 */
public enum RunState {

  /** Admitted and recorded, not yet queued for an executor. */
  CREATED,

  /** Ready to be claimed by any executor node (AD-025 SM-5). */
  QUEUED,

  /** An executor holds the claim and a step is in flight. The only state with an open session. */
  RUNNING,

  /** Parked on a durable timer or an external signal. Holds no execution resources. */
  WAITING,

  /** A step failed retryably; the supervisor scheduled another attempt. */
  RETRYING,

  /** The plan finished. Terminal. */
  COMPLETED,

  /** Cancelled by a caller, a parent, an operator, policy or the supervisor. Terminal. */
  CANCELLED,

  /** The wall-clock bound or a step deadline expired. Terminal. */
  TIMED_OUT,

  /** Terminated by a failure the supervision policy did not absorb. Terminal. */
  FAILED,

  /** Restart intensity exhausted, or a failure no retry can help. Parked for human attention. */
  DEAD_LETTER;

  /** The absorbing states. No transition leaves any of these (AD-025 SM-1). */
  public static final Set<RunState> TERMINAL_STATES =
      java.util.Collections.unmodifiableSet(
          EnumSet.of(COMPLETED, CANCELLED, TIMED_OUT, FAILED, DEAD_LETTER));

  /**
   * Reports whether this state is absorbing.
   *
   * @return true when no transition may leave this state
   */
  public boolean terminal() {
    return TERMINAL_STATES.contains(this);
  }

  /**
   * Reports whether a run in this state may hold an open tool session.
   *
   * <p>AD-025 SM-3: {@code RUNNING} is the only such state. Anything else holding a session is a
   * leak.
   *
   * @return true only for {@link #RUNNING}
   */
  public boolean mayHoldSession() {
    return this == RUNNING;
  }

  /**
   * Reports whether a run in this state occupies executor resources.
   *
   * <p>AD-025 SM-6: the waiting states must hold nothing, which is what lets a run park for hours
   * on a human approval or a timer at zero cost.
   *
   * @return true only for {@link #RUNNING}
   */
  public boolean holdsResources() {
    return this == RUNNING;
  }
}
