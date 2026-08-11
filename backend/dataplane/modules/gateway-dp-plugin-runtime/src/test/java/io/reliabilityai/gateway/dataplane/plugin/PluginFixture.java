package io.reliabilityai.gateway.dataplane.plugin;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.dataplane.plugin.api.HealthPolicy;
import io.reliabilityai.gateway.dataplane.plugin.api.Plugin;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDependency;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginHealth;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginSignaturePort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import io.reliabilityai.gateway.dataplane.plugin.api.ResourceBudget;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolDefinition;
import io.reliabilityai.gateway.dataplane.plugin.api.TrustTier;
import io.reliabilityai.gateway.dataplane.plugin.api.VettedPluginSnapshot;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Shared builders and doubles for the Plugin Runtime tests. */
final class PluginFixture {

  static final TenantScope TENANT = TenantScope.of("acme", "tenant-1");
  static final TenantScope OTHER_TENANT = TenantScope.of("acme", "tenant-2");
  static final Instant EPOCH = Instant.parse("2026-07-28T00:00:00Z");

  private PluginFixture() {}

  /** A clock the test moves by hand, so nothing depends on real time. */
  static final class TestClock implements ClockPort {
    private final AtomicReference<Instant> now = new AtomicReference<>(EPOCH);

    @Override
    public Instant now() {
      return now.get();
    }

    void advance(final Duration amount) {
      now.updateAndGet(current -> current.plus(amount));
    }
  }

  /** A verifier that trusts everything, for tests not about the supply chain. */
  static PluginSignaturePort trustAll() {
    return snapshot -> PluginSignaturePort.Verdict.TRUSTED;
  }

  /** A verifier that refuses everything with the given verdict. */
  static PluginSignaturePort refuseWith(final PluginSignaturePort.Verdict verdict) {
    return snapshot -> verdict;
  }

  static ResourceBudget budget(final Duration wallClock) {
    return new ResourceBudget(wallClock, 1_000L, 64L * 1024 * 1024, 1024L * 1024);
  }

  static HealthPolicy health() {
    return new HealthPolicy(Duration.ofSeconds(30), Duration.ofSeconds(2), 3);
  }

  /** A first-party INTERNAL manifest with no extension points and one tool. */
  static PluginManifest toolManifest(final String id) {
    return manifest(
        id,
        Set.of(),
        List.of(new ToolDefinition("echo", "echoes its input", "{}")),
        PluginCapabilities.of(
            PluginCapabilities.TOOL_EXECUTION, PluginCapabilities.READ_TENANT_SCOPE),
        0,
        List.of());
  }

  /** A first-party INTERNAL manifest bound to one extension point. */
  static PluginManifest extensionManifest(
      final String id, final ExtensionPoint point, final int priority) {
    return manifest(
        id,
        Set.of(point),
        List.of(),
        PluginCapabilities.of(PluginCapabilities.READ_ROUTING_HINTS),
        priority,
        List.of());
  }

  static PluginManifest manifest(
      final String id,
      final Set<ExtensionPoint> points,
      final List<ToolDefinition> tools,
      final PluginCapabilities capabilities,
      final int priority,
      final List<PluginDependency> dependencies) {
    return new PluginManifest(
        PluginId.of(id),
        id,
        new PluginVersion(1, 0, 0),
        "acme-corp",
        "a test plugin",
        PluginType.INTERNAL,
        TrustTier.FIRST_PARTY,
        points,
        priority,
        PluginPermissions.none(),
        capabilities,
        tools,
        budget(Duration.ofSeconds(2)),
        Map.of(),
        health(),
        dependencies);
  }

  /** Wraps a manifest in a snapshot the trust-all verifier accepts. */
  static VettedPluginSnapshot snapshot(final PluginManifest manifest) {
    return new VettedPluginSnapshot(
        manifest,
        ("artifact:" + manifest.id()).getBytes(StandardCharsets.UTF_8),
        new byte[] {1, 2, 3, 4},
        new byte[] {9, 9, 9, 9},
        "vetted-by=c12;build=test");
  }

  /** A plugin that records lifecycle calls and answers tools from a supplied function. */
  static final class RecordingPlugin implements Plugin {
    private final PluginManifest manifest;
    volatile boolean startCalled;
    volatile boolean stopCalled;
    volatile PluginHealth reported = PluginHealth.READY;

    RecordingPlugin(final PluginManifest manifest) {
      this.manifest = manifest;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final io.reliabilityai.gateway.dataplane.plugin.api.ToolContext context) {
      startCalled = true;
    }

    @Override
    public void stop() {
      stopCalled = true;
    }

    @Override
    public PluginHealth health() {
      return reported;
    }
  }
}
