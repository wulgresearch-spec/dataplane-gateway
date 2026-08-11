/**
 * The Plugin Runtime's use cases (Doc 28, C12).
 *
 * <p>{@code PluginRegistryService} verifies and binds; {@code PluginLifecycleService} starts,
 * stops, enables, disables and reloads in dependency order; {@code PluginRuntimeService} dispatches
 * at the frozen five extension points; {@code ToolExecutionService} executes tools.
 *
 * <p>Every one of them is orchestration only. The decisions — legal transitions, dependency order,
 * manifest admissibility, permission evaluation, execution order, failure classification — live in
 * {@code domain}, and the containment lives in {@code internal}. Nothing here decides anything a
 * test would have to construct a runtime to check.
 */
package io.reliabilityai.gateway.dataplane.plugin.application;
