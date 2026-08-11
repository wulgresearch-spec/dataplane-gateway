package io.reliabilityai.gateway.dataplane.config.api;

import io.reliabilityai.gateway.canonical.identity.CodeVersion;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The inbound port the config-cache exposes to the pipeline: pin the configuration for one request
 * (Doc 36 §7). Pinning happens once, after C6 authentication (Doc 32 §SPT) — the tenant scope is a
 * required input because tenant-scoped flag/config resolution is only defined post-C6 (Doc 36
 * §TFI). The result is fail-closed by construction (Doc 36 CFG-INV).
 */
public interface ConfigPinPort {

  /**
   * Pins the config snapshot set for a request from the last-known-good cache (Doc 36 §6/§7); makes
   * no synchronous control-plane call (Doc 36 CFG-A2). Fail-closed on unavailability, schema
   * incompatibility, or region mismatch.
   *
   * @param tenantScope the pinned tenant scope (present only post-C6, Doc 32 §SPT)
   * @param region the residency region (Doc 36 §14)
   * @param executionIdentity the request execution identity, recorded for replay (Doc 29 §CVR)
   * @param codeVersion the running code version (schema-compat anchor, Doc 29 §SCC)
   * @return a pinned config, or a fail-closed outcome
   */
  PinResult pin(
      TenantScope tenantScope,
      Region region,
      ExecutionIdentity executionIdentity,
      CodeVersion codeVersion);

  /** The terminal pin outcome (Doc 36 CFG-INV — fail closed). */
  sealed interface PinResult permits PinResult.Pinned, PinResult.FailClosed {

    /**
     * A successful pin.
     *
     * @param config the immutable pinned config
     */
    record Pinned(PinnedConfig config) implements PinResult {
      /** Compact constructor validating the config. */
      public Pinned {
        Preconditions.requireNonNull(config, "config");
      }
    }

    /**
     * A fail-closed pin (Doc 36 §SDS/§DRC). The request must be denied; the runtime never proceeds
     * on unresolved required config.
     *
     * @param reason the content-free fail-closed reason
     */
    record FailClosed(String reason) implements PinResult {
      /** Compact constructor validating the reason. */
      public FailClosed {
        Preconditions.requireNonBlank(reason, "reason");
      }
    }
  }
}
