package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The per-tenant contract entitlement (Doc 22 §16/§17), from the C8/C5 contract snapshot (authored
 * upstream). It declares which higher-precedence pricing classes
 * (negotiated/enterprise/committed/burst) a tenant is entitled to; {@code LIST} is always
 * implicitly available. The engine <b>applies</b> entitlements at their precedence — it never
 * negotiates or infers a contract (Doc 22 §CE-D7). An entitled class with no authoritative
 * descriptor ⇒ fail closed, never a silent list fallback (Doc 22 §15). Immutable; classes
 * defensively copied.
 *
 * @param version the contract snapshot version (stamped on the result)
 * @param entitledClasses the higher-precedence classes the tenant is entitled to (LIST always
 *     implied)
 */
public record ContractEntitlement(String version, Set<PricingClass> entitledClasses) {

  /** Compact constructor validating and defensively copying the entitled classes. */
  public ContractEntitlement {
    Preconditions.requireNonBlank(version, "version");
    // The copy was already here; the wrapper was not. EnumSet.copyOf defends against the caller
    // mutating its own set afterwards, but the accessor then handed that same EnumSet back, and an
    // EnumSet is mutable — so any holder of a ContractEntitlement could call
    // entitledClasses().add(NEGOTIATED) and grant itself a higher-precedence pricing tier. The
    // engine applies entitlements and never negotiates or infers one (Doc 22 §CE-D7), which it
    // cannot honour while the set it was handed is writable.
    //
    // EnumSet is kept as the backing set rather than switching to Set.copyOf so that iteration
    // stays in ordinal (precedence) order; Set.copyOf is unordered.
    entitledClasses =
        Collections.unmodifiableSet(
            entitledClasses == null || entitledClasses.isEmpty()
                ? EnumSet.noneOf(PricingClass.class)
                : EnumSet.copyOf(entitledClasses));
  }

  /**
   * A list-only entitlement (no negotiated/enterprise/committed terms).
   *
   * @param version the contract snapshot version
   * @return a list-only entitlement
   */
  public static ContractEntitlement listOnly(final String version) {
    return new ContractEntitlement(version, EnumSet.noneOf(PricingClass.class));
  }
}
