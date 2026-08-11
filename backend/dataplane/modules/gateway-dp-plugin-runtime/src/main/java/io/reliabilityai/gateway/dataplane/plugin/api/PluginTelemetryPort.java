package io.reliabilityai.gateway.dataplane.plugin.api;

import java.time.Duration;

/**
 * Content-free plugin telemetry (Doc 28 §47, Doc 27).
 *
 * <p>The method signatures are the enforcement. Every parameter is an id, an enum or a count —
 * there is no free-text parameter anywhere, so a caller cannot pass a plugin's exception message or
 * a tool's output into telemetry even carelessly (Doc 14 §5, Doc 27 §15.1).
 *
 * <p>Passive, exactly like {@code TelemetryEmitPort}: emitting must never alter, block or fail an
 * invocation (Doc 27 OT-A1).
 */
public interface PluginTelemetryPort {

  /**
   * A plugin lifecycle transition occurred.
   *
   * @param pluginId the plugin
   * @param from the prior state
   * @param to the new state
   */
  void lifecycle(PluginId pluginId, PluginState from, PluginState to);

  /**
   * A plugin invocation finished, whatever its outcome.
   *
   * @param pluginId the plugin
   * @param operation the tool name or extension point name
   * @param outcome the terminal outcome
   * @param duration the measured wall-clock duration
   */
  void invocation(PluginId pluginId, String operation, Outcome outcome, Duration duration);

  /**
   * A plugin was isolated.
   *
   * @param pluginId the plugin
   * @param kind why it was isolated
   */
  void isolated(PluginId pluginId, PluginFailureKind kind);

  /**
   * A resource quota was breached (Doc 28 REC-4, {@code prt_plugin_budget_breach}).
   *
   * @param pluginId the plugin
   * @param resource which quota
   */
  void budgetBreach(PluginId pluginId, Resource resource);

  /**
   * A snapshot was refused at verification (Doc 28 §STC, {@code prt_signature_reject}).
   *
   * @param pluginId the plugin
   * @param verdict why it was refused
   */
  void signatureReject(PluginId pluginId, PluginSignaturePort.Verdict verdict);

  /** The terminal outcome of an invocation, as a bounded metric label. */
  enum Outcome {
    /** Ran to completion. */
    COMPLETED,
    /** Failed, breached a quota, or attempted a violation. */
    ISOLATED,
    /** Cancelled before finishing. */
    CANCELLED
  }

  /** Which quota was breached, as a bounded metric label. */
  enum Resource {
    /** The wall-clock deadline. */
    WALL_CLOCK,
    /** The CPU allowance. */
    CPU,
    /** The memory ceiling. */
    MEMORY,
    /** The mediated IO allowance. */
    IO
  }

  /** A telemetry port that discards everything. */
  PluginTelemetryPort NO_OP =
      new PluginTelemetryPort() {
        @Override
        public void lifecycle(
            final PluginId pluginId, final PluginState from, final PluginState to) {
          // discarded
        }

        @Override
        public void invocation(
            final PluginId pluginId,
            final String operation,
            final Outcome outcome,
            final Duration duration) {
          // discarded
        }

        @Override
        public void isolated(final PluginId pluginId, final PluginFailureKind kind) {
          // discarded
        }

        @Override
        public void budgetBreach(final PluginId pluginId, final Resource resource) {
          // discarded
        }

        @Override
        public void signatureReject(
            final PluginId pluginId, final PluginSignaturePort.Verdict verdict) {
          // discarded
        }
      };
}
