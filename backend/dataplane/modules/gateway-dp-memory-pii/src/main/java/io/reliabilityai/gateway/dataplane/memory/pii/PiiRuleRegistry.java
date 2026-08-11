package io.reliabilityai.gateway.dataplane.memory.pii;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the compiled engine for each tenant, and the default for everyone else (AD-029 §5.3).
 *
 * <p>Rules are compiled when they are installed, never on the scan path. A write does one map
 * lookup and one volatile read; it never compiles a pattern, never takes a lock, and never blocks
 * behind an administrator installing rules for a different tenant.
 *
 * <p>Installation replaces a tenant's engine by reference. A scan in flight keeps the engine it
 * started with and completes against a consistent rule set — which is why {@link PiiDetection}
 * carries the rule set version: two records written seconds apart may legitimately have been
 * classified by different rules, and the record says which.
 */
public final class PiiRuleRegistry {

  private final PiiMetricsPort metrics;

  private final Map<String, PiiDetectionEngine> byTenant = new ConcurrentHashMap<>();

  /** Built-ins plus organization rules; what a tenant with no rules of its own gets. */
  private volatile PiiDetectionEngine fallback;

  private volatile List<PiiRule> organizationRules = List.of();

  /**
   * Creates a registry holding only the built-in rules.
   *
   * @param metrics where detection events are reported
   */
  public PiiRuleRegistry(final PiiMetricsPort metrics) {
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.fallback = new PiiDetectionEngine(PiiRuleCompiler.builtIn(), metrics);
  }

  /**
   * Installs organization rules, which apply to every tenant.
   *
   * <p>Every tenant engine is recompiled, because an organization rule that only reached tenants
   * onboarded after it was installed would be an organization rule in name only.
   *
   * @param rules the organization rules
   */
  public void installOrganizationRules(final List<PiiRule> rules) {
    Preconditions.requireNonNull(rules, "rules");
    organizationRules = List.copyOf(rules);
    fallback =
        new PiiDetectionEngine(
            PiiRuleCompiler.compileLayered(organizationRules, List.of()), metrics);
    for (final Map.Entry<String, PiiDetectionEngine> entry : byTenant.entrySet()) {
      final List<PiiRule> tenantRules = tenantRulesOf(entry.getValue());
      entry.setValue(
          new PiiDetectionEngine(
              PiiRuleCompiler.compileLayered(organizationRules, tenantRules), metrics));
    }
  }

  /**
   * Installs one tenant's rules.
   *
   * @param tenant whose rules these are
   * @param rules the tenant rules
   */
  public void installTenantRules(final TenantScope tenant, final List<PiiRule> rules) {
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonNull(rules, "rules");
    byTenant.put(
        keyOf(tenant),
        new PiiDetectionEngine(PiiRuleCompiler.compileLayered(organizationRules, rules), metrics));
  }

  /**
   * Removes one tenant's rules, returning it to the organization and built-in set.
   *
   * @param tenant whose rules to drop
   */
  public void clearTenantRules(final TenantScope tenant) {
    byTenant.remove(keyOf(Preconditions.requireNonNull(tenant, "tenant")));
  }

  /**
   * The engine for a tenant.
   *
   * @param tenant whose content is being scanned
   * @return that tenant's engine, or the organization default
   */
  public PiiDetectionEngine engineFor(final TenantScope tenant) {
    if (tenant == null) {
      return fallback;
    }
    final PiiDetectionEngine engine = byTenant.get(keyOf(tenant));
    return engine == null ? fallback : engine;
  }

  /**
   * The engine used when no tenant is known.
   *
   * <p>Built-ins plus organization rules. This is what the {@code PiiClassifierPort} implementation
   * has to use, because that port is handed a body and nothing else — see AD-029 §3.1 and B45.
   *
   * @return the default engine
   */
  public PiiDetectionEngine defaultEngine() {
    return fallback;
  }

  /**
   * How many tenants have rules of their own.
   *
   * @return the count
   */
  public int tenantCount() {
    return byTenant.size();
  }

  /**
   * Recovers the tenant-origin rules from a compiled engine, so an organization change can rebuild
   * it.
   *
   * @param engine the tenant's current engine
   * @return that tenant's own rules
   */
  private static List<PiiRule> tenantRulesOf(final PiiDetectionEngine engine) {
    return engine.rules().rules().stream()
        .map(PiiRuleSet.Compiled::rule)
        .filter(rule -> rule.origin() == PiiRule.Origin.TENANT)
        .toList();
  }

  /**
   * A collision-free key for a tenant.
   *
   * <p>Length-prefixed for the same reason {@code MemoryScope.key()} is: concatenating an
   * organization and a tenant with a separator lets a cunningly chosen name collide with another
   * pair, and here a collision means one tenant's rules applying to another's data.
   *
   * @param tenant the tenant
   * @return the key
   */
  private static String keyOf(final TenantScope tenant) {
    return tenant.org().length() + ":" + tenant.org() + ":" + tenant.tenant();
  }
}
