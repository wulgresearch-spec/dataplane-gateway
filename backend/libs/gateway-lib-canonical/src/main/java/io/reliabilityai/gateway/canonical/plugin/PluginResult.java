package io.reliabilityai.gateway.canonical.plugin;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * The outcome of a sandboxed plugin invocation (C12, Doc 33 §10.8, Doc 28 §6). A sealed set: {@link
 * Completed} carries an additive advisory contribution the owning stage may consume; {@link
 * Isolated} means the plugin crashed/timed out/violated and its contribution is discarded while the
 * mandatory pipeline proceeds unweakened (Doc 28 §EPFC). Immutable.
 */
public sealed interface PluginResult permits PluginResult.Completed, PluginResult.Isolated {

  /**
   * A completed plugin invocation carrying an additive, content-free advisory contribution. The
   * owning stage decides; the plugin never decides or overrides a verdict (Doc 28 PEB-1..3).
   *
   * @param contribution the advisory signal (read-only)
   */
  record Completed(Map<String, String> contribution) implements PluginResult {
    /** Compact constructor defensively copying the contribution. */
    public Completed {
      // Inlined copy, not Preconditions.immutableMap — see that method's javadoc (EI_EXPOSE_REP).
      contribution = contribution == null ? Map.of() : Map.copyOf(contribution);
    }
  }

  /**
   * An isolated invocation (crash/timeout/violation). The contribution is discarded; the pipeline
   * is unweakened (Doc 28 §EPFC).
   *
   * @param reason the content-free isolation reason
   */
  record Isolated(String reason) implements PluginResult {
    /** Compact constructor validating the reason. */
    public Isolated {
      Preconditions.requireNonBlank(reason, "reason");
    }
  }
}
