package io.reliabilityai.gateway.canonical.plugin;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * The per-invocation plugin context (C12, Doc 33 §10.8, Doc 28 §6). Capability-gated,
 * tenant-scoped, deadline-bounded; a plugin sees only this context and returns an additive signal
 * (Doc 28 §PEB). Immutable.
 *
 * @param pluginId the vetted plugin id
 * @param extensionPoint the frozen extension point this invocation binds to
 * @param correlationId the request correlation id
 * @param tenantScope the tenant scope (AD-021)
 * @param deadline the per-invocation deadline (Doc 28 §REC)
 */
public record PluginContext(
    String pluginId,
    ExtensionPoint extensionPoint,
    CorrelationId correlationId,
    TenantScope tenantScope,
    Instant deadline) {

  /** Compact constructor validating required fields. */
  public PluginContext {
    Preconditions.requireNonBlank(pluginId, "pluginId");
    Preconditions.requireNonNull(extensionPoint, "extensionPoint");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(deadline, "deadline");
  }
}
