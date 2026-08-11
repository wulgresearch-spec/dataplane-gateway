package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the plugins bound at one frozen extension point contributed, as the pipeline sees it.
 *
 * <p>Deliberately <b>inert</b>. It carries advisory signals and a count of isolations, and no
 * mandatory stage reads it to decide anything. That is not an oversight — it is Doc 28 PRT-D1
 * expressed in the type system: "removing all plugins changes no mandatory-stage outcome". A field
 * that a stage consumed would make that sentence false, and consuming a signal is a change to that
 * stage's own semantics, which belongs to that stage's milestone rather than to the runtime's.
 *
 * <p>{@link ContentFree}: plugin ids, counts and content-free advisory keys. A contribution reaches
 * the trace and the audit stream, so it carries no prompt, no completion and no plugin payload.
 *
 * @param point the frozen extension point these contributions came from
 * @param contributions each contributing plugin's advisory signal, in execution order
 * @param isolated the plugins whose invocation crashed, timed out or violated, in execution order
 */
public record ExtensionOutcome(
    ExtensionPoint point, Map<String, Map<String, String>> contributions, List<String> isolated)
    implements ContentFree {

  /** Validates the outcome. */
  public ExtensionOutcome {
    Preconditions.requireNonNull(point, "point");
    // Each value is itself a map, so the copy is deep. A shallow Map.copyOf freezes the outer map
    // and leaves every plugin's contribution writable through whatever reference the caller kept —
    // an advisory signal that reaches the trace and the audit stream must not still be editable
    // after the point is closed. Today's only producer already hands over immutable contributions
    // (ExtensionPointDispatcher fills them from PluginResult.Completed.contribution()), so this
    // closes a contract-level hole rather than a live one. The copies are also inlined rather than
    // routed through Preconditions; see that class's javadoc (EI_EXPOSE_REP).
    final Map<String, Map<String, String>> copied = new HashMap<>();
    if (contributions != null) {
      for (final Map.Entry<String, Map<String, String>> entry : contributions.entrySet()) {
        copied.put(entry.getKey(), Map.copyOf(entry.getValue()));
      }
    }
    contributions = Map.copyOf(copied);
    isolated = isolated == null ? List.of() : List.copyOf(isolated);
  }

  /**
   * The outcome for a point with nothing bound, or for a node running no plugin runtime at all.
   *
   * <p>The same value in both cases on purpose: a node without a plugin runtime and a node whose
   * plugins all declined to bind here must be indistinguishable downstream, or the presence of the
   * runtime itself would be observable in the request path.
   *
   * @param point the frozen extension point
   * @return an empty outcome
   */
  public static ExtensionOutcome none(final ExtensionPoint point) {
    return new ExtensionOutcome(point, Map.of(), List.of());
  }

  /**
   * Whether any plugin ran at this point.
   *
   * @return {@code true} when nothing contributed and nothing was isolated
   */
  public boolean isEmpty() {
    return contributions.isEmpty() && isolated.isEmpty();
  }

  /**
   * How many plugins contributed.
   *
   * @return the contribution count
   */
  public int contributed() {
    return contributions.size();
  }

  /**
   * A short, content-free summary for the stage trace.
   *
   * @return the summary
   */
  public String summary() {
    return "plugins=" + contributions.size() + " isolated=" + isolated.size();
  }
}
