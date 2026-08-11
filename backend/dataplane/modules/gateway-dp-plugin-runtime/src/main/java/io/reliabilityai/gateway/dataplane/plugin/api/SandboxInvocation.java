package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * What the substrate needs to know to contain and budget one invocation (Doc 28 §REC).
 *
 * <p>Deliberately thin. The substrate gets an id for telemetry, a budget to enforce and a deadline
 * to cut at — not the manifest, not the tenant, not the payload. A substrate that could read the
 * work it is containing is a substrate with a reason to interpret it.
 *
 * @param pluginId the plugin being invoked, for content-free telemetry
 * @param correlationId the request correlation id
 * @param budget the enforced quota
 * @param deadline the absolute instant at which the invocation is cancelled
 */
public record SandboxInvocation(
    PluginId pluginId, CorrelationId correlationId, ResourceBudget budget, Instant deadline) {

  /** Compact constructor validating the containment request. */
  public SandboxInvocation {
    Preconditions.requireNonNull(pluginId, "pluginId");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(budget, "budget");
    Preconditions.requireNonNull(deadline, "deadline");
  }
}
