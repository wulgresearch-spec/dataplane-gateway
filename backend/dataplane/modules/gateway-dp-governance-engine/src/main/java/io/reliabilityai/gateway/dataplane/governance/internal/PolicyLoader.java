package io.reliabilityai.gateway.dataplane.governance.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyCompilationException;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import java.util.Optional;

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
    return registry.install(snapshot);
  }

  private Optional<PolicySourcePort.PolicyBundle> fetch() {
    try {
      return source.load();
    } catch (final RuntimeException unavailable) {
      return Optional.empty();
    }
  }
}
