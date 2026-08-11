package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The authoritative, versioned, immutable FX table inside a pricing snapshot (Doc 22 §16.1). The
 * engine performs <b>no live FX lookup and downloads no rate</b> (FX-1); it converts only from this
 * table. Beyond {@link #validUntil} the table is untrusted ⇒ fail closed (FX-7); a missing pair ⇒
 * fail closed (FX-8). Immutable; rates defensively copied.
 *
 * @param version the FX table version (stamped on every conversion, FX-2)
 * @param validUntil the validity boundary (beyond ⇒ fail closed)
 * @param canonicalCurrency the canonical comparative unit currency
 * @param ratesByCurrency source currency → rate to the canonical unit
 */
public record FxTable(
    String version,
    Instant validUntil,
    String canonicalCurrency,
    Map<String, FxRate> ratesByCurrency) {

  /** Compact constructor validating fields and defensively copying rates. */
  public FxTable {
    Preconditions.requireNonBlank(version, "version");
    Preconditions.requireNonNull(validUntil, "validUntil");
    Preconditions.requireNonBlank(canonicalCurrency, "canonicalCurrency");
    ratesByCurrency = ratesByCurrency == null ? Map.of() : Map.copyOf(ratesByCurrency);
  }

  /**
   * Resolves the authoritative rate to convert the given currency to the canonical unit (Doc 22
   * §16.1). The canonical currency converts by identity.
   *
   * @param currency the source currency
   * @return the rate, or empty ⇒ fail closed (FX-8)
   */
  public Optional<FxRate> rateFor(final String currency) {
    if (canonicalCurrency.equals(currency)) {
      return Optional.of(FxRate.IDENTITY);
    }
    return Optional.ofNullable(ratesByCurrency.get(currency));
  }
}
