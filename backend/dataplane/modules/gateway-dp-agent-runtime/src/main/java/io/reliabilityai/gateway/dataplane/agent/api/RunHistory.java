package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A run's append-only event log (AD-025 §20, HSC-1).
 *
 * <p>Immutable. {@link #append} returns a new history rather than mutating this one — there is no
 * setter, no {@code remove}, no {@code clear}, and the returned event list is unmodifiable. The
 * append-only property is therefore a fact about the type, not a rule someone has to remember.
 *
 * <p>The offset of an event is its index. That is what makes {@link StepId} derivable (AD-025
 * §45.2) and what the store's conditional append is written against: an append at offset N succeeds
 * only if the history is exactly N long, which is the mutual-exclusion primitive that stops two
 * executors advancing one run (AD-025 HSC-3, SM-5).
 *
 * @param runId the run this history belongs to
 * @param events the events in order, oldest first
 */
public record RunHistory(RunId runId, List<RunEvent> events) {

  /**
   * Validates and freezes the history.
   *
   * @param runId the owning run
   * @param events the ordered events
   */
  public RunHistory {
    Preconditions.requireNonNull(runId, "runId");
    events = events == null ? List.of() : List.copyOf(events);
  }

  /**
   * Creates an empty history.
   *
   * @param runId the owning run
   * @return a history with no events
   */
  public static RunHistory empty(final RunId runId) {
    return new RunHistory(runId, List.of());
  }

  /**
   * Returns a history with one more event.
   *
   * @param event the event to append
   * @return a new history; this one is unchanged
   */
  public RunHistory append(final RunEvent event) {
    Preconditions.requireNonNull(event, "event");
    final List<RunEvent> next = new ArrayList<>(events.size() + 1);
    next.addAll(events);
    next.add(event);
    return new RunHistory(runId, next);
  }

  /**
   * Returns the number of events, which is also the offset the next append must use.
   *
   * @return the event count
   */
  public long offset() {
    return events.size();
  }

  /**
   * Reports whether anything has been recorded.
   *
   * @return true when no event exists
   */
  public boolean isEmpty() {
    return events.isEmpty();
  }

  /**
   * Returns the most recent event.
   *
   * @return the last event, or empty for a new run
   */
  public Optional<RunEvent> last() {
    return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
  }

  /**
   * Reports whether the history ends with a step that was scheduled but never resolved.
   *
   * <p>This is the interrupted-step predicate (AD-025 §35.2). A tail of {@link
   * RunEvent.StepScheduled} means an executor died between the two durable writes that bracket a
   * step, so the step's real outcome is unknown to this layer and must be resolved by asking the
   * layer below.
   *
   * @return true when recovery must resolve an interrupted step
   */
  public boolean endsInterrupted() {
    return last().filter(event -> event instanceof RunEvent.StepScheduled).isPresent();
  }

  /**
   * Returns the interrupted step's scheduling event, if the history ends interrupted.
   *
   * @return the unresolved scheduling event, or empty
   */
  public Optional<RunEvent.StepScheduled> interruptedStep() {
    return last()
        .filter(event -> event instanceof RunEvent.StepScheduled)
        .map(event -> (RunEvent.StepScheduled) event);
  }

  /**
   * Reports whether the run has ended.
   *
   * @return true when the history contains a termination event
   */
  public boolean terminated() {
    return last().filter(event -> event instanceof RunEvent.RunTerminated).isPresent();
  }

  /**
   * Returns the events from an offset onward.
   *
   * <p>Used by stream consumers reconnecting at a known position (AD-025 §38.2), which is what lets
   * the orchestration stream be best-effort without ever affecting run correctness.
   *
   * @param fromOffset the first offset to include
   * @return the tail, empty when the offset is at or past the end
   */
  public List<RunEvent> since(final long fromOffset) {
    Preconditions.requireNonNegative(fromOffset, "fromOffset");
    if (fromOffset >= events.size()) {
      return List.of();
    }
    return List.copyOf(events.subList((int) fromOffset, events.size()));
  }

  /**
   * Returns the run's creation event.
   *
   * @return the first event as a creation, or empty when the history is empty or malformed
   */
  public Optional<RunEvent.RunCreated> created() {
    if (events.isEmpty()) {
      return Optional.empty();
    }
    final RunEvent first = events.get(0);
    return first instanceof RunEvent.RunCreated createdEvent
        ? Optional.of(createdEvent)
        : Optional.empty();
  }
}
