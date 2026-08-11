package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A cost amount in integer micro-units of a currency (Doc 22 §21) — integer arithmetic avoids
 * floating-point drift (mirrors {@code CostFact.amountMicros}). Non-negative (cost is never
 * negative). Immutable.
 *
 * @param amountMicros the amount in micro-units ({@code 1_000_000} micros = 1 unit)
 * @param currency the ISO currency code (or the canonical comparative unit)
 */
public record Money(long amountMicros, String currency) {

  /** Compact constructor validating the amount and currency. */
  public Money {
    Preconditions.requireNonNegative(amountMicros, "amountMicros");
    Preconditions.requireNonBlank(currency, "currency");
  }
}
