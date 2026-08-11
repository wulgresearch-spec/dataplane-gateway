package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * What a PERMIT resolves to: the provider-neutral constraints downstream stages must honour (Doc 21
 * §6, GV-D8/GV-D9/GV-D11).
 *
 * <p>This is the contract between governance and routing. The Router consumes the residency scope
 * and compliance regimes as <b>hard filters</b>, not hints — which is why they are resolved once
 * here rather than re-derived downstream where they could drift. Lists rather than sets, in sorted
 * order, so two evaluations of the same inputs produce byte-identical context for replay (GV-D12).
 *
 * @param residencyScope the regions the request may execute in, sorted
 * @param complianceRegimes the regimes the execution path must satisfy, sorted
 * @param authorizedCapabilities the capabilities the caller is authorized to use, sorted
 * @param authorizedTools the tools the caller is authorized to invoke, sorted
 * @param quotaHeadroom requests still admissible in the current window
 * @param budgetHeadroomMicros spend still admissible in the current period
 */
public record ResolvedGovernanceContext(
    List<String> residencyScope,
    List<String> complianceRegimes,
    List<String> authorizedCapabilities,
    List<String> authorizedTools,
    long quotaHeadroom,
    long budgetHeadroomMicros) {

  /** Validates the resolved context. */
  public ResolvedGovernanceContext {
    residencyScope = residencyScope == null ? List.of() : List.copyOf(residencyScope);
    complianceRegimes = complianceRegimes == null ? List.of() : List.copyOf(complianceRegimes);
    authorizedCapabilities =
        authorizedCapabilities == null ? List.of() : List.copyOf(authorizedCapabilities);
    authorizedTools = authorizedTools == null ? List.of() : List.copyOf(authorizedTools);
    Preconditions.requireNonNegative(quotaHeadroom, "quotaHeadroom");
    Preconditions.requireNonNegative(budgetHeadroomMicros, "budgetHeadroomMicros");
  }
}
