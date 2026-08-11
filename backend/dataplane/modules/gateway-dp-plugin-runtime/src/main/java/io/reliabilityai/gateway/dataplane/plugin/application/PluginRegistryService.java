package io.reliabilityai.gateway.dataplane.plugin.application;

import io.reliabilityai.gateway.canonical.audit.AuditRecord;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.Plugin;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDescriptor;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginRegistryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginSignaturePort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.RegistrationOutcome;
import io.reliabilityai.gateway.dataplane.plugin.api.RegistrationOutcome.RefusalReason;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxHostPort;
import io.reliabilityai.gateway.dataplane.plugin.api.VettedPluginSnapshot;
import io.reliabilityai.gateway.dataplane.plugin.domain.DependencyGraph;
import io.reliabilityai.gateway.dataplane.plugin.domain.ExecutionOrder;
import io.reliabilityai.gateway.dataplane.plugin.domain.ManifestValidator;
import io.reliabilityai.gateway.dataplane.plugin.internal.RegisteredPlugin;
import io.reliabilityai.gateway.ports.ClockPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The node's binding table of verified plugins (Doc 28 §9, §13, §ROC, §HRC).
 *
 * <p>Every path into this class runs the same gate in the same order: verify the signature against
 * the pinned anchor, validate the manifest against Doc 28's refusals, check dependencies, then
 * bind. There is no second entry point and no "trusted" shortcut, so the supply-chain check cannot
 * be skipped by calling a different method (Doc 28 STC-2, PRT-A11).
 *
 * <p><b>Concurrency.</b> Mutations take a lock; reads do not. The request path calls {@link #list}
 * and {@link #lookup} and must never block behind an operator reload, so the extension-point index
 * is rebuilt into a fresh immutable map and published by a single volatile write. A reader either
 * sees the whole old index or the whole new one.
 *
 * <p><b>What this is not.</b> Not the registry of record — that is the control plane's (Doc 28
 * ROC-1). Nothing here vets, signs, approves or publishes anything; {@code register} is a
 * verification gate followed by a map insert.
 */
public final class PluginRegistryService implements PluginRegistryPort {

  private final PluginSignaturePort signatures;
  private final SandboxHostPort sandbox;
  private final PluginAuditSinkPort audit;
  private final PluginTelemetryPort telemetry;
  private final ClockPort clock;
  private final int maxPlugins;

  private final ReentrantLock mutation = new ReentrantLock();
  private final Map<PluginId, RegisteredPlugin> plugins = new TreeMap<>();

  /** The request-path read view. Replaced wholesale on mutation, never edited in place. */
  private volatile Map<ExtensionPoint, List<RegisteredPlugin>> byExtensionPoint = emptyIndex();

  /** A second read view for id lookup, published by the same write as the index above. */
  private volatile Map<PluginId, RegisteredPlugin> byId = Map.of();

  /**
   * Creates the registry.
   *
   * @param signatures the verify-only supply-chain gate
   * @param sandbox the substrate plugins will run on, consulted for its isolation level
   * @param audit the content-free audit sink
   * @param telemetry the content-free telemetry sink
   * @param clock the injected clock
   * @param maxPlugins the ceiling on bound plugins
   */
  public PluginRegistryService(
      final PluginSignaturePort signatures,
      final SandboxHostPort sandbox,
      final PluginAuditSinkPort audit,
      final PluginTelemetryPort telemetry,
      final ClockPort clock,
      final int maxPlugins) {
    this.signatures = Preconditions.requireNonNull(signatures, "signatures");
    this.sandbox = Preconditions.requireNonNull(sandbox, "sandbox");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.telemetry = Preconditions.requireNonNull(telemetry, "telemetry");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    if (maxPlugins < 1) {
      throw new IllegalArgumentException("maxPlugins must be at least 1");
    }
    this.maxPlugins = maxPlugins;
  }

  @Override
  public RegistrationOutcome register(final VettedPluginSnapshot snapshot, final Plugin instance) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    Preconditions.requireNonNull(instance, "instance");

    mutation.lock();
    try {
      if (plugins.containsKey(snapshot.manifest().id())) {
        return refuse(snapshot.manifest(), RefusalReason.ALREADY_REGISTERED);
      }
      if (plugins.size() >= maxPlugins) {
        return refuse(snapshot.manifest(), RefusalReason.CAPACITY_EXCEEDED);
      }
      return bind(snapshot, instance, 0L);
    } finally {
      mutation.unlock();
    }
  }

  @Override
  public boolean unregister(final PluginId pluginId) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    mutation.lock();
    try {
      final RegisteredPlugin registered = plugins.get(pluginId);
      if (registered == null) {
        return false;
      }
      stopQuietly(registered);
      plugins.remove(pluginId);
      republish();
      recordAudit(registered.manifest(), "plugin_unloaded", "unbound");
      return true;
    } finally {
      mutation.unlock();
    }
  }

  @Override
  public Optional<PluginDescriptor> lookup(final PluginId pluginId) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    final RegisteredPlugin registered = byId.get(pluginId);
    return registered == null ? Optional.empty() : Optional.of(registered.describe());
  }

  @Override
  public RegistrationOutcome reload(final VettedPluginSnapshot snapshot, final Plugin instance) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    Preconditions.requireNonNull(instance, "instance");

    mutation.lock();
    try {
      final PluginId pluginId = snapshot.manifest().id();
      final RegisteredPlugin previous = plugins.get(pluginId);
      if (previous == null) {
        return bind(snapshot, instance, 0L);
      }

      // Verify and validate the replacement *before* touching the incumbent. Doc 28 HRC-6 wants
      // last-known-good preserved on a bad snapshot, and the only way to honour that is to discover
      // the snapshot is bad while the old one is still bound and serving.
      final RegistrationOutcome verified = verify(snapshot, instance, previous.generation() + 1);
      if (!verified.bound()) {
        return verified;
      }

      final RegisteredPlugin replacement =
          new RegisteredPlugin(
              snapshot.manifest(),
              instance,
              snapshot.digestHex(),
              clock.now(),
              previous.generation() + 1);

      // Swap first, then stop the old instance. The other order would leave a window in which the
      // plugin is bound but stopped, and a dispatch landing in that window would be isolated for no
      // reason the operator could explain.
      plugins.put(pluginId, replacement);
      republish();
      stopQuietly(previous);

      recordAudit(snapshot.manifest(), "plugin_loaded", "reloaded");
      telemetry.lifecycle(pluginId, previous.state(), replacement.state());
      return new RegistrationOutcome.Bound(replacement.describe());
    } finally {
      mutation.unlock();
    }
  }

  @Override
  public List<PluginDescriptor> snapshot() {
    mutation.lock();
    try {
      final List<PluginDescriptor> descriptors = new ArrayList<>(plugins.size());
      for (final RegisteredPlugin registered : plugins.values()) {
        descriptors.add(registered.describe());
      }
      descriptors.sort(Comparator.comparing(descriptor -> descriptor.id().value()));
      return List.copyOf(descriptors);
    } finally {
      mutation.unlock();
    }
  }

  @Override
  public List<PluginDescriptor> list(final ExtensionPoint extensionPoint) {
    Preconditions.requireNonNull(extensionPoint, "extensionPoint");
    final List<RegisteredPlugin> bound = byExtensionPoint.get(extensionPoint);
    if (bound == null || bound.isEmpty()) {
      return List.of();
    }
    // The index is already in Doc 28 POC-2 order, so filtering preserves it. State is read live
    // rather
    // than baked into the index, which is what lets a disable take effect without an index rebuild.
    final List<PluginDescriptor> ready = new ArrayList<>(bound.size());
    for (final RegisteredPlugin registered : bound) {
      if (registered.state().admitsExecution()) {
        ready.add(registered.describe());
      }
    }
    return List.copyOf(ready);
  }

  /**
   * The live binding for a plugin, for the execution services in this module.
   *
   * <p>Package-visible-by-convention rather than part of the port: handing a mutable binding to an
   * arbitrary caller would undo the descriptor's whole point.
   *
   * @param pluginId the plugin
   * @return the binding, or null when nothing is bound
   */
  public RegisteredPlugin binding(final PluginId pluginId) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    return byId.get(pluginId);
  }

  /**
   * Every live binding, in id order, for the lifecycle service.
   *
   * @return the bindings
   */
  public List<RegisteredPlugin> bindings() {
    mutation.lock();
    try {
      return List.copyOf(plugins.values());
    } finally {
      mutation.unlock();
    }
  }

  /**
   * The dependency graph over everything currently bound.
   *
   * @return the graph
   */
  public DependencyGraph dependencyGraph() {
    mutation.lock();
    try {
      final List<PluginManifest> manifests = new ArrayList<>(plugins.size());
      for (final RegisteredPlugin registered : plugins.values()) {
        manifests.add(registered.manifest());
      }
      return new DependencyGraph(manifests);
    } finally {
      mutation.unlock();
    }
  }

  /** The mutation lock, so the lifecycle service can make a multi-plugin change atomically. */
  ReentrantLock mutationLock() {
    return mutation;
  }

  /** Rebuilds and publishes the read views. Called under the mutation lock. */
  void republish() {
    final Map<ExtensionPoint, List<RegisteredPlugin>> index = new EnumMap<>(ExtensionPoint.class);
    for (final ExtensionPoint point : ExtensionPoint.values()) {
      final List<RegisteredPlugin> bound = new ArrayList<>();
      for (final RegisteredPlugin registered : plugins.values()) {
        if (registered.manifest().bindsTo(point)) {
          bound.add(registered);
        }
      }
      bound.sort(
          Comparator.<RegisteredPlugin>comparingInt(registered -> registered.manifest().priority())
              .thenComparing(registered -> registered.id().value()));
      index.put(point, List.copyOf(bound));
    }
    byExtensionPoint = Map.copyOf(index);
    byId = Map.copyOf(new TreeMap<>(plugins));
  }

  /** Verifies, validates and binds. Called under the mutation lock. */
  private RegistrationOutcome bind(
      final VettedPluginSnapshot snapshot, final Plugin instance, final long generation) {
    final RegistrationOutcome verified = verify(snapshot, instance, generation);
    if (!verified.bound()) {
      return verified;
    }
    final RegisteredPlugin registered =
        new RegisteredPlugin(
            snapshot.manifest(), instance, snapshot.digestHex(), clock.now(), generation);
    plugins.put(snapshot.manifest().id(), registered);
    republish();
    recordAudit(snapshot.manifest(), "plugin_loaded", "bound");
    telemetry.lifecycle(snapshot.manifest().id(), PluginState.REGISTERED, PluginState.REGISTERED);
    return new RegistrationOutcome.Bound(registered.describe());
  }

  /**
   * The full admission gate, in severity order: supply chain, then manifest, then dependencies.
   *
   * <p>Supply chain first and unconditionally. An unsigned snapshot is refused before its manifest
   * is even examined, because examining an unverified manifest means acting on data an attacker
   * chose.
   */
  private RegistrationOutcome verify(
      final VettedPluginSnapshot snapshot, final Plugin instance, final long generation) {
    final PluginManifest manifest = snapshot.manifest();

    final PluginSignaturePort.Verdict verdict = signatures.verify(snapshot);
    if (!verdict.trusted()) {
      telemetry.signatureReject(manifest.id(), verdict);
      recordAudit(
          manifest,
          "plugin_failed",
          "signature-" + verdict.name().toLowerCase(java.util.Locale.ROOT));
      return new RegistrationOutcome.Refused(RefusalReason.UNTRUSTED_SNAPSHOT);
    }

    if (!sandbox.supports(manifest.type())) {
      return refuse(manifest, RefusalReason.UNSUPPORTED_TYPE);
    }
    final Optional<RefusalReason> invalid =
        ManifestValidator.validate(manifest, instance, sandbox.isolation());
    if (invalid.isPresent()) {
      return refuse(manifest, invalid.orElseThrow());
    }

    // Dependencies are resolved against everything already bound plus this candidate, so a graph
    // can
    // be assembled in any order as long as it is acyclic and complete by the time it is started.
    final List<PluginManifest> manifests = new ArrayList<>();
    for (final RegisteredPlugin registered : plugins.values()) {
      if (!registered.id().equals(manifest.id())) {
        manifests.add(registered.manifest());
      }
    }
    manifests.add(manifest);
    final DependencyGraph graph = new DependencyGraph(manifests);
    if (graph.introducesCycle(manifest)) {
      return refuse(manifest, RefusalReason.DEPENDENCY_CYCLE);
    }
    if (!graph.unsatisfied(manifest).isEmpty()) {
      return refuse(manifest, RefusalReason.DEPENDENCY_UNSATISFIED);
    }
    return new RegistrationOutcome.Bound(
        new PluginDescriptor(
            manifest,
            PluginState.REGISTERED,
            io.reliabilityai.gateway.dataplane.plugin.api.PluginHealth.UNKNOWN,
            snapshot.digestHex(),
            clock.now(),
            generation));
  }

  private RegistrationOutcome refuse(final PluginManifest manifest, final RefusalReason reason) {
    recordAudit(manifest, "plugin_failed", reason.name().toLowerCase(java.util.Locale.ROOT));
    return new RegistrationOutcome.Refused(reason);
  }

  private void stopQuietly(final RegisteredPlugin registered) {
    if (registered.state() == PluginState.READY || registered.state() == PluginState.INITIALIZING) {
      registered.transition(registered.state(), PluginState.STOPPING);
    }
    try {
      registered.instance().stop();
      registered.transition(PluginState.STOPPING, PluginState.STOPPED);
    } catch (final RuntimeException stopFailure) {
      // A plugin refusing to stop cleanly does not get to stay bound: it is recorded FAILED and
      // unbound anyway. Shutdown must not depend on plugin cooperation.
      registered.markFailed();
    }
  }

  private void recordAudit(
      final PluginManifest manifest, final String action, final String outcome) {
    try {
      audit.record(
          new AuditRecord(
              new CorrelationId("plugin-registry"),
              null,
              action,
              manifest.id().value() + "@" + manifest.version(),
              outcome,
              null,
              clock.now()));
    } catch (final RuntimeException auditFailure) {
      // Audit is a side effect, never a gate (Doc 27 OT-A1).
    }
  }

  private static Map<ExtensionPoint, List<RegisteredPlugin>> emptyIndex() {
    final Map<ExtensionPoint, List<RegisteredPlugin>> index = new EnumMap<>(ExtensionPoint.class);
    for (final ExtensionPoint point : ExtensionPoint.values()) {
      index.put(point, List.of());
    }
    return Map.copyOf(index);
  }

  /**
   * Exposed so tests and operators can confirm the ordering contract without executing anything.
   */
  List<PluginDescriptor> orderedFor(final ExtensionPoint extensionPoint) {
    return ExecutionOrder.sort(list(extensionPoint));
  }
}
