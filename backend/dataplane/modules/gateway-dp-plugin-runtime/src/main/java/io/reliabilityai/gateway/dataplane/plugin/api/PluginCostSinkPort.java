package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * Where per-invocation plugin cost records go (Doc 28 §47, Doc 22).
 *
 * <p>Separate from the Cost Engine's {@code CostPort} on purpose. That port prices a {@code
 * UsageFact} — model tokens against a rate card — and a plugin invocation is not that: it has no
 * tokens, no model and no provider. Forcing plugin cost through it would mean fabricating a usage
 * fact, and a fabricated fact in the accounting stream is worse than an honest separate record (Doc
 * 22 §CE-D10, Doc 23).
 *
 * <p>Emitted for <em>every</em> invocation including failures, so a crash-looping plugin's cost is
 * visible rather than silently excluded.
 */
public interface PluginCostSinkPort {

  /**
   * Records what one invocation consumed.
   *
   * @param cost the content-free accounting record
   */
  void record(PluginInvocationCost cost);

  /** A sink that discards everything. */
  PluginCostSinkPort NO_OP = cost -> {};
}
