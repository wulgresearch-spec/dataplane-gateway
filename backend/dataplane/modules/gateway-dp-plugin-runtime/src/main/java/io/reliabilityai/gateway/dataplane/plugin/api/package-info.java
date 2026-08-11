/**
 * The Plugin Runtime's public contracts (Doc 28, C12 data-plane presence, AD-002/AD-004).
 *
 * <p>Inbound: {@link io.reliabilityai.gateway.dataplane.plugin.api.PluginRegistryPort} binds
 * verified plugins, {@link io.reliabilityai.gateway.dataplane.plugin.api.ToolExecutionPort}
 * executes tools, and the frozen {@code PluginRuntimePort} in {@code gateway-lib-ports} is how the
 * request pipeline reaches the frozen five extension points.
 *
 * <p>Outbound: {@link io.reliabilityai.gateway.dataplane.plugin.api.VettedPluginSnapshotPort} (the
 * C12 feed), {@link io.reliabilityai.gateway.dataplane.plugin.api.PluginSignaturePort}
 * (verify-only), {@link io.reliabilityai.gateway.dataplane.plugin.api.SandboxHostPort} (the
 * isolation substrate), {@link
 * io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort} (deny-by-default), plus
 * audit, telemetry and cost sinks.
 *
 * <p>There is no port here for signing, publishing, vetting, registering-of-record or authoring
 * provenance. Doc 28 ROC-1..ROC-8 place all of those with the control plane, and the absence of the
 * type is the enforcement.
 */
package io.reliabilityai.gateway.dataplane.plugin.api;
