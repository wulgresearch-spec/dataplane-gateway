/**
 * The Plugin Runtime's substrates and adapters (Doc 28 §ISO, §F).
 *
 * <p>{@code InProcessSandbox} and {@code ProcessSandbox} are the two isolation substrates; {@code
 * BoundedPluginStream} is the consumer-paced event channel; {@code ProcessPluginAdapter} talks to a
 * child process; {@code RegisteredPlugin} holds the one piece of mutable state in the module.
 *
 * <p>Everything here is an implementation detail behind a port in {@code api}. Swapping the process
 * substrate for a container-backed one, or the child-process protocol for something else, is a
 * change confined to this package.
 */
package io.reliabilityai.gateway.dataplane.plugin.internal;
