package io.reliabilityai.gateway.dataplane.app.pipeline;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ordered record of what the pipeline did, stage by stage (AD-018 evidence).
 *
 * <p>This exists so the non-bypass guarantee is <em>checkable</em> rather than asserted: a test
 * reads the trace and proves every mandatory stage was entered exactly once, in frozen order, and
 * that a refusal stopped the chain where it claimed to. It is deliberately inert — nothing in the
 * pipeline reads the trace to make a decision, so removing it would change no behaviour.
 *
 * <p>Not thread-safe by design: one trace belongs to one request on one thread. Elapsed times are
 * measured with {@code System.nanoTime()} and are observational only; no stage decision depends on
 * them, which is what keeps the pipeline deterministic.
 */
public final class StageTrace {

  /** What a stage did when it was consulted. */
  public enum Disposition {
    /** The stage ran and did work. */
    EXECUTED,
    /**
     * The stage was consulted and determined it had nothing to do for this request — for example
     * StreamGuard on a unary response, or SchemaLock on a request with no output schema. This is a
     * consulted-and-declined result, not a skip: the stage still executed its decision.
     */
    NOT_APPLICABLE,
    /** The stage refused. The chain stops here. */
    REFUSED
  }

  /**
   * One stage's record.
   *
   * @param stage the mandatory stage
   * @param disposition what the stage decided
   * @param elapsedNanos wall time spent in the stage (observational only)
   * @param detail a short, content-free description of the outcome
   */
  public record Entry(
      MandatoryStage stage, Disposition disposition, long elapsedNanos, String detail) {

    /** Validates the entry. */
    public Entry {
      Preconditions.requireNonNull(stage, "stage");
      Preconditions.requireNonNull(disposition, "disposition");
      Preconditions.requireNonNull(detail, "detail");
    }
  }

  private final List<Entry> entries = new ArrayList<>(MandatoryStage.values().length);
  private final List<ExtensionEntry> extensions = new ArrayList<>(0);

  /**
   * Records a completed stage.
   *
   * @param stage the stage that was consulted
   * @param disposition what it decided
   * @param startNanos the {@code System.nanoTime()} reading taken when the stage was entered
   * @param detail a short, content-free outcome description
   */
  void record(
      final MandatoryStage stage,
      final Disposition disposition,
      final long startNanos,
      final String detail) {
    entries.add(new Entry(stage, disposition, System.nanoTime() - startNanos, detail));
  }

  /**
   * Every recorded stage entry, in execution order.
   *
   * @return the ordered entries
   */
  /**
   * Records what the plugins at one frozen extension point contributed.
   *
   * <p>Kept in its own list, deliberately apart from {@link #entries()}. The mandatory-stage trace
   * is what the non-bypass tests assert on — that every mandatory stage ran exactly once, in order
   * — and mixing an optional, additive plugin point into it would make that assertion depend on
   * which plugins a node happens to have bound. A plugin must not be able to change what the
   * non-bypass evidence says.
   *
   * @param point the frozen extension point
   * @param detail a short, content-free summary
   */
  public void recordExtension(final ExtensionPoint point, final String detail) {
    extensions.add(new ExtensionEntry(point, detail));
  }

  /**
   * What ran at the frozen extension points, in order.
   *
   * @return the extension entries — empty on a node running no plugins
   */
  public List<ExtensionEntry> extensions() {
    return List.copyOf(extensions);
  }

  /**
   * One extension point's contribution summary.
   *
   * @param point the frozen extension point
   * @param detail a short, content-free summary
   */
  public record ExtensionEntry(ExtensionPoint point, String detail) {}

  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  /**
   * The stages entered, in execution order — the sequence the non-bypass test asserts on.
   *
   * @return the ordered stages
   */
  public List<MandatoryStage> stages() {
    return entries.stream().map(Entry::stage).toList();
  }

  /**
   * The stage that refused, if any.
   *
   * @return the refusing stage, or empty when the request completed
   */
  public Optional<MandatoryStage> refusalStage() {
    return entries.stream()
        .filter(entry -> entry.disposition() == Disposition.REFUSED)
        .map(Entry::stage)
        .findFirst();
  }

  /**
   * Whether the request completed without any stage refusing.
   *
   * @return {@code true} if no stage refused
   */
  public boolean successful() {
    return refusalStage().isEmpty();
  }

  /**
   * How many times a given stage was entered — used to prove exactly-once execution.
   *
   * @param stage the stage to count
   * @return the number of entries for that stage
   */
  public long timesEntered(final MandatoryStage stage) {
    return entries.stream().filter(entry -> entry.stage() == stage).count();
  }
}
