package io.reliabilityai.gateway.dataplane.metering.api;

import io.reliabilityai.gateway.canonical.usage.UsageFact;

/**
 * The Cost Engine seam (Doc 23 §30, UME-D10). The engine <b>provides</b> the immutable usage fact
 * (provider + delivered, labelled by attempt class + {@code delivered}) to the Cost Engine (Doc 22
 * §20) for pricing — the engine <b>never prices</b> (UME-D1) and the Cost Engine <b>never
 * re-derives usage</b> (Doc 22 §CE-D10). {@code UsageUnrecorded} ⇒ downstream {@code
 * CostUnavailable} (Doc 22 §39).
 */
public interface CostUsagePort {

  /**
   * Submits a recorded usage fact to the Cost Engine for pricing (Doc 23 §30).
   *
   * @param fact the durably-recorded usage fact (never priced here)
   */
  void submit(UsageFact fact);
}
