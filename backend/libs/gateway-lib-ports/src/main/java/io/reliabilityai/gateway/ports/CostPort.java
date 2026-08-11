package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.usage.CostFact;
import io.reliabilityai.gateway.canonical.usage.UsageFact;

/**
 * The Cost Engine port (C10, Doc 22, AD-002). Prices a recorded usage fact into a cost fact using
 * the pinned pricing snapshot. The Cost Engine consumes usage as authoritative and never re-derives
 * it (Doc 22 §CE-D10); pricing is a pure function of the usage fact and the pinned rate card.
 */
public interface CostPort {

  /**
   * Prices a recorded usage fact (Doc 22 §20).
   *
   * @param usage the authoritative usage fact (never re-derived here)
   * @return the priced cost fact
   */
  CostFact price(UsageFact usage);
}
