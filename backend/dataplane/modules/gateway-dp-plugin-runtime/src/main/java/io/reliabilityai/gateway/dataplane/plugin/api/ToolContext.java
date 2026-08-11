package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Map;

/**
 * Everything a tool invocation is allowed to know about its caller (Doc 28 §ISO-4, §36.1).
 *
 * <p>This is the whole surface. A plugin sees a correlation id, a tenant scope, its deadline, its
 * granted capabilities and the configuration its own manifest declared — and nothing else. There is
 * no handle back into the pipeline, no credential, no provider, no request body. Doc 28 ISO-4 calls
 * this the closed, mediated API; the closure is enforced here by simply not having fields for
 * anything else.
 *
 * <p>The tenant scope is present so a plugin can partition its own state, and its presence is
 * capability-gated: without {@link PluginCapabilities#READ_TENANT_SCOPE} the runtime supplies a
 * redacted scope. Cross-tenant access is impossible regardless, because a context is built per
 * invocation from that invocation's tenant and never cached (Doc 28 §36.1, AD-021).
 *
 * @param correlationId the request correlation id, for content-free tracing
 * @param tenantScope the invocation's tenant scope
 * @param deadline the absolute instant at which this invocation is cancelled
 * @param grantedCapabilities the capabilities actually granted, after the deny-by-default check
 * @param configuration the plugin's own vetted configuration
 */
public record ToolContext(
    CorrelationId correlationId,
    TenantScope tenantScope,
    Instant deadline,
    PluginCapabilities grantedCapabilities,
    Map<String, String> configuration) {

  /** Compact constructor validating the mediated surface. */
  public ToolContext {
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(deadline, "deadline");
    Preconditions.requireNonNull(grantedCapabilities, "grantedCapabilities");
    configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
  }

  /**
   * Whether the invocation still has time left, per the injected clock.
   *
   * @param now the current instant, read from the runtime's ClockPort
   * @return true if the deadline has not yet passed
   */
  public boolean live(final Instant now) {
    Preconditions.requireNonNull(now, "now");
    return now.isBefore(deadline);
  }
}
