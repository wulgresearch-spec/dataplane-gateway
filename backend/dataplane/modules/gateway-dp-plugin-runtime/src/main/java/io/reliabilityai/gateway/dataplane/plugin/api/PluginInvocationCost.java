package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * What one plugin invocation consumed (Doc 28 §47, Doc 22).
 *
 * <p>{@code providerCostMicros} is always zero and is present to say so explicitly. Doc 28 §31.1
 * denies plugins any provider capability, so a plugin invocation structurally cannot incur provider
 * spend — recording a field that is provably zero is more useful to a downstream consumer than
 * omitting it and leaving them to wonder whether it was simply not measured.
 *
 * <p>{@code cpuMillis} and {@code memoryBytes} are <b>estimates</b>, and the field names say so.
 * The in-process substrate samples them; the process substrate reads what the OS reports. Neither
 * is a billing-grade measurement, and Doc 28 ISO-9 forbids claiming otherwise.
 *
 * @param pluginId the plugin invoked
 * @param toolName the tool invoked, or the extension point name for a contribution
 * @param tenantScope the tenant the invocation was attributed to
 * @param duration the measured wall-clock duration
 * @param cpuMillisEstimate the estimated CPU time consumed
 * @param memoryBytesEstimate the estimated peak memory used
 * @param toolCostMicros the operator-declared cost of running this tool once
 * @param providerCostMicros always zero — plugins cannot call providers (Doc 28 §31.1)
 */
public record PluginInvocationCost(
    PluginId pluginId,
    String toolName,
    TenantScope tenantScope,
    Duration duration,
    long cpuMillisEstimate,
    long memoryBytesEstimate,
    long toolCostMicros,
    long providerCostMicros)
    implements ContentFree {

  /** Compact constructor validating the accounting record. */
  public PluginInvocationCost {
    Preconditions.requireNonNull(pluginId, "pluginId");
    Preconditions.requireNonBlank(toolName, "toolName");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(duration, "duration");
    Preconditions.requireNonNegative(cpuMillisEstimate, "cpuMillisEstimate");
    Preconditions.requireNonNegative(memoryBytesEstimate, "memoryBytesEstimate");
    Preconditions.requireNonNegative(toolCostMicros, "toolCostMicros");
    if (providerCostMicros != 0) {
      throw new IllegalArgumentException(
          "providerCostMicros must be zero: plugins cannot call providers (Doc 28 §31.1)");
    }
  }

  /**
   * The total cost attributable to the tenant for this invocation.
   *
   * @return the tenant cost in micros
   */
  public long tenantCostMicros() {
    return toolCostMicros + providerCostMicros;
  }
}
