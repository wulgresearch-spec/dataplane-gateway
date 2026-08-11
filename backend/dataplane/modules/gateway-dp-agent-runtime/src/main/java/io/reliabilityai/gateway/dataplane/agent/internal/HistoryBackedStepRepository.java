package io.reliabilityai.gateway.dataplane.agent.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.StepRepository;
import io.reliabilityai.gateway.dataplane.agent.api.StepStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The step read model, computed from the run history.
 *
 * <p>Holds no state of its own. Every answer is a fold over the events, so it cannot disagree with
 * the history — which a separately-maintained step table eventually would, silently, in exactly the
 * cases where somebody is looking at it to understand a failure.
 *
 * <p>A retried step appears once per attempt. Flattening attempts into a single row would hide the
 * thing an operator opening this view is almost always looking for: that a step succeeded on its
 * fourth try, and what the first three said.
 */
public final class HistoryBackedStepRepository implements StepRepository {

  private final RunRepository runs;

  /**
   * Creates the read model.
   *
   * @param runs the store to fold histories from
   */
  public HistoryBackedStepRepository(final RunRepository runs) {
    this.runs = Preconditions.requireNonNull(runs, "runs");
  }

  @Override
  public List<StepView> stepsOf(final RunId runId) {
    Preconditions.requireNonNull(runId, "runId");
    final Optional<RunHistory> history = runs.load(runId);
    if (history.isEmpty()) {
      return List.of();
    }

    // Keyed by execution index, so a retry — which has its own index — gets its own row, while the
    // scheduling and completion of one attempt collapse into one.
    final Map<Integer, Mutable> byIndex = new LinkedHashMap<>();
    for (final RunEvent event : history.get().events()) {
      switch (event) {
        case RunEvent.StepScheduled scheduled ->
            byIndex
                .computeIfAbsent(scheduled.stepId().index(), index -> new Mutable())
                .schedule(scheduled);
        case RunEvent.StepCompleted completed ->
            touch(byIndex, completed.stepId())
                .finish(StepStatus.SUCCEEDED, completed.costMicros(), "");
        case RunEvent.StepFailed failed ->
            touch(byIndex, failed.stepId())
                .finish(
                    failed.failure()
                            == io.reliabilityai.gateway.dataplane.agent.api.FailureClass
                                .STEP_TIMEOUT
                        ? StepStatus.TIMED_OUT
                        : StepStatus.FAILED,
                    failed.costMicros(),
                    failed.failure() + ": " + failed.reason());
        case RunEvent.StepSkipped skipped ->
            touch(byIndex, skipped.stepId())
                .finish(StepStatus.SKIPPED, 0L, "skipped after " + skipped.failure());
        case RunEvent.StepCancelled cancelled ->
            touch(byIndex, cancelled.stepId())
                .finish(
                    StepStatus.CANCELLED,
                    cancelled.costMicros(),
                    "cancelled: " + cancelled.cause());
        default -> {
          // Run-level events say nothing about an individual step.
        }
      }
    }

    final List<StepView> views = new ArrayList<>(byIndex.size());
    for (final Map.Entry<Integer, Mutable> entry : byIndex.entrySet()) {
      views.add(entry.getValue().toView(StepId.of(runId, entry.getKey())));
    }
    return List.copyOf(views);
  }

  @Override
  public Optional<StepView> stepView(final StepId stepId) {
    Preconditions.requireNonNull(stepId, "stepId");
    for (final StepView view : stepsOf(stepId.runId())) {
      if (view.stepId().equals(stepId)) {
        return Optional.of(view);
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<StepStatus> latestStatus(final RunId runId, final String stepName) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(stepName, "stepName");
    StepStatus latest = null;
    for (final StepView view : stepsOf(runId)) {
      if (view.stepName().equals(stepName)) {
        latest = view.status();
      }
    }
    return Optional.ofNullable(latest);
  }

  @Override
  public int countByStatus(final RunId runId, final StepStatus status) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(status, "status");
    int count = 0;
    for (final StepView view : stepsOf(runId)) {
      if (view.status() == status) {
        count++;
      }
    }
    return count;
  }

  private static Mutable touch(final Map<Integer, Mutable> byIndex, final StepId stepId) {
    return byIndex.computeIfAbsent(stepId.index(), index -> new Mutable());
  }

  /** Accumulates one step attempt while folding. Never escapes this class. */
  private static final class Mutable {
    private String name = "";
    private StepKind kind = StepKind.PIPELINE;
    private int attempt = 1;
    private StepStatus status = StepStatus.SCHEDULED;
    private long cost;
    private String detail = "";

    void schedule(final RunEvent.StepScheduled scheduled) {
      name = scheduled.stepName();
      kind = scheduled.kind();
      attempt = scheduled.attempt();
      status = StepStatus.SCHEDULED;
    }

    void finish(final StepStatus terminal, final long costMicros, final String why) {
      status = terminal;
      cost = costMicros;
      detail = why;
    }

    StepView toView(final StepId stepId) {
      return new StepView(stepId, name, kind, attempt, status, cost, detail);
    }
  }
}
