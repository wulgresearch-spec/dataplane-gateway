package io.reliabilityai.gateway.dataplane.governance.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyCompilationException;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Pulls the current bundle from the control plane, compiles it, and puts it in force.
 *
 * <p>This is the entire hot-reload path: fetch, compile, atomic swap. It runs off the request path,
 * on an operator action or a scheduled refresh, and it never touches a running evaluation. Policy
 * changes therefore need no redeploy and no restart, which matters because the alternative —
 * rolling a fleet to revoke a tenant's access — makes the revocation take as long as a deployment.
 *
 * <p><b>A failed reload leaves the previous generation serving.</b> A source that cannot answer, a
 * bundle that will not compile, a version older than the one in force: all three return "not
 * reloaded" and change nothing. That is the right failure direction for governance. The last
 * generation this node successfully compiled is a known-good policy authored by a real operator;
 * replacing it with nothing because a distribution channel hiccuped would either open the gate or
 * close it entirely, and both are worse than continuing to enforce yesterday's rules for another
 * few seconds (AD-022 last-known-good).
 *
 * <p>The one thing a failed reload must not do is stay quiet — {@link
 * PolicyMetrics#snapshotRejected()} fires on every refusal, because a node silently frozen on an
 * old generation is a node whose policy changes appear to work and do not.
 */
public final class PolicyLoader {

  /**
   * Scopes the policy model declares but no shipped code path ever puts into a {@code ScopeChain}.
   *
   * <p>{@code REQUEST} is caller self-restriction (see {@link PolicyScope#REQUEST}). {@code
   * ScopeChain.Builder} can express it, but nothing in the shipped runtime calls that builder
   * method, so a document attached to it is compiled, stored, and then never folded — {@code
   * PolicySnapshot.effectiveFor} visits only the nodes a request's chain actually contains.
   *
   * <p>Kept as one constant at the single place that reports it. Promoting it to a capability
   * registry would be more architecture than one entry justifies; the moment a second consumer
   * needs it, that is the time to move it.
   */
  private static final Set<PolicyScope> NEVER_CONSTRUCTED = EnumSet.of(PolicyScope.REQUEST);

  private final PolicySourcePort source;
  private final PolicyCompiler compiler;
  private final PolicyRegistry registry;
  private final PolicyMetrics metrics;

  /**
   * Creates the loader.
   *
   * @param source where authored bundles come from
   * @param compiler the bundle-to-snapshot translator
   * @param registry the registry to install into
   * @param metrics the counters to publish to
   */
  public PolicyLoader(
      final PolicySourcePort source,
      final PolicyCompiler compiler,
      final PolicyRegistry registry,
      final PolicyMetrics metrics) {
    this.source = Preconditions.requireNonNull(source, "source");
    this.compiler = Preconditions.requireNonNull(compiler, "compiler");
    this.registry = Preconditions.requireNonNull(registry, "registry");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
  }

  /**
   * Fetches, compiles and installs the current bundle.
   *
   * @return {@code true} when a new generation was put in force
   */
  public boolean reload() {
    final Optional<PolicySourcePort.PolicyBundle> bundle = fetch();
    if (bundle.isEmpty()) {
      metrics.snapshotRejected();
      return false;
    }
    final PolicySnapshot snapshot;
    try {
      snapshot = compiler.compile(bundle.orElseThrow());
    } catch (final PolicyCompilationException | IllegalArgumentException rejected) {
      // An incoherent bundle is a control-plane defect, not a reason to stop governing traffic.
      metrics.snapshotRejected();
      return false;
    }
    final boolean installed = registry.install(snapshot);
    if (installed) {
      reportUnenforceableScopes(bundle.orElseThrow());
    }
    return installed;
  }

  /**
   * Counts, once per generation put in force, each declared scope this build never constructs.
   *
   * <p>Reported here rather than at compile time because this is the earliest point at which the
   * configuration is known to be valid <em>and</em> actually in force: a bundle that fails to
   * compile, or one the registry declines as stale, has not been accepted and should not be
   * reported as accepted-but-unenforceable. Emitting per installed generation rather than per
   * document also keeps a republished bundle from producing a burst.
   *
   * <p>Request-time reporting would be useless for exactly the reason this counter exists — the
   * node is never built, so no request ever reaches the point where its absence could be noticed.
   *
   * @param bundle the generation just installed
   */
  private void reportUnenforceableScopes(final PolicySourcePort.PolicyBundle bundle) {
    final Set<PolicyScope> reported = EnumSet.noneOf(PolicyScope.class);
    for (final GovernancePolicy policy : bundle.policies()) {
      // Disabled documents are skipped by the compiler too; they are not in force and not a
      // surprise to anyone.
      if (policy.enabled()
          && NEVER_CONSTRUCTED.contains(policy.scope().scope())
          && reported.add(policy.scope().scope())) {
        metrics.unenforceableScope(policy.scope().scope());
      }
    }
  }

  private Optional<PolicySourcePort.PolicyBundle> fetch() {
    try {
      return source.load();
    } catch (final RuntimeException unavailable) {
      return Optional.empty();
    }
  }
}
