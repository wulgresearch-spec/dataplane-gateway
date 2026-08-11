package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * A state transition the run state machine does not permit.
 *
 * <p>Thrown, never logged-and-continued. A runtime that tolerated an illegal transition would have
 * a state machine in name only: the guarantees that rest on it — that terminal states are
 * absorbing, that only one executor advances a run, that a cancelled run stops billing — all reduce
 * to "usually".
 *
 * @see io.reliabilityai.gateway.dataplane.agent.domain.RunStateMachine
 */
public final class IllegalRunTransitionException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /**
   * The transition endpoints.
   *
   * <p>Deliberately <b>not</b> {@code transient}, for the same reason as {@code
   * KmsException.reason}. This class is serializable whether or not anyone intends it to be ({@code
   * Throwable implements Serializable}) and declares a {@code serialVersionUID}. A transient field
   * with no {@code readObject} to restore it deserializes to {@code null}, so a round-tripped
   * instance would answer {@code null} from {@link #from()} and {@link #to()} — two accessors
   * documented to return the states involved, on an exception whose whole purpose is to say which
   * transition was refused. {@link RunState} is an enum carrying no tenant content, so keeping it
   * serializable costs nothing.
   */
  private final RunState from;

  private final RunState to;

  /**
   * Creates the exception.
   *
   * @param from the state the run was in
   * @param to the state something tried to move it to
   */
  public IllegalRunTransitionException(final RunState from, final RunState to) {
    super("illegal run transition: " + from + " -> " + to);
    this.from = from;
    this.to = to;
  }

  /**
   * Returns the state the run was in.
   *
   * @return the source state
   */
  public RunState from() {
    return from;
  }

  /**
   * Returns the state that was refused.
   *
   * @return the target state
   */
  public RunState to() {
    return to;
  }
}
