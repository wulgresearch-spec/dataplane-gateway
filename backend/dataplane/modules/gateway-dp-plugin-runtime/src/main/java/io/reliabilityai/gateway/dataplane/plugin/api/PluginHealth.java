package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * A plugin's self-reported health (Doc 28 §J).
 *
 * <p>Health is <b>observability, not admission control</b>. The runtime routes on {@link
 * PluginState}, which it owns; health is a hint the plugin supplies about itself and a compromised
 * or buggy plugin can report whatever it likes. Letting health gate execution would hand a plugin a
 * lever over the host's dispatch decisions, which Doc 28 PRT-D1 reserves to the runtime.
 *
 * <p>{@link #UNKNOWN} is the honest default: a plugin that has not answered a health probe is not
 * assumed healthy.
 */
public enum PluginHealth {

  /** The plugin reports it can serve. */
  READY,

  /** The plugin reports reduced capacity but can still serve. */
  DEGRADED,

  /** The plugin reports it cannot serve. */
  FAILED,

  /** No health verdict has been obtained. Never treated as healthy. */
  UNKNOWN
}
