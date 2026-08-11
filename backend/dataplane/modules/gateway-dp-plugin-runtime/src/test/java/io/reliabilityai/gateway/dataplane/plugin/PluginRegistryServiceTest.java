package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDependency;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDescriptor;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginSignaturePort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import io.reliabilityai.gateway.dataplane.plugin.api.RegistrationOutcome;
import io.reliabilityai.gateway.dataplane.plugin.api.RegistrationOutcome.RefusalReason;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolDefinition;
import io.reliabilityai.gateway.dataplane.plugin.api.TrustTier;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRegistryService;
import io.reliabilityai.gateway.dataplane.plugin.internal.InProcessSandbox;
import io.reliabilityai.gateway.dataplane.plugin.internal.ProcessSandbox;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verification, binding, refusal and reload (Doc 28 §9, §13, §STC, §HRC, §ROC). */
@DisplayName("plugin registry")
class PluginRegistryServiceTest {

  private final PluginFixture.TestClock clock = new PluginFixture.TestClock();
  private final InProcessSandbox sandbox = new InProcessSandbox(64);

  private PluginRegistryService registry(final PluginSignaturePort signatures) {
    return new PluginRegistryService(
        signatures, sandbox, PluginAuditSinkPort.NO_OP, PluginTelemetryPort.NO_OP, clock, 32);
  }

  private static RefusalReason reasonOf(final RegistrationOutcome outcome) {
    return ((RegistrationOutcome.Refused) outcome).reason();
  }

  @Test
  @DisplayName("a verified plugin binds in REGISTERED, not READY")
  void verifiedPluginBindsRegistered() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest = PluginFixture.toolManifest("alpha");
    final RegistrationOutcome outcome =
        registry.register(
            PluginFixture.snapshot(manifest),
            new TestPlugins.FunctionTool(manifest, invocation -> null));

    assertThat(outcome.bound()).isTrue();
    // Binding is not starting: a plugin that has not run its start hook must not be dispatchable.
    assertThat(((RegistrationOutcome.Bound) outcome).descriptor().state())
        .isEqualTo(PluginState.REGISTERED);
  }

  @Test
  @DisplayName("an unsigned snapshot is refused before its manifest is examined")
  void unsignedSnapshotIsRefused() {
    final PluginRegistryService registry =
        registry(PluginFixture.refuseWith(PluginSignaturePort.Verdict.SIGNATURE_INVALID));
    final PluginManifest manifest = PluginFixture.toolManifest("untrusted");
    final RegistrationOutcome outcome =
        registry.register(
            PluginFixture.snapshot(manifest),
            new TestPlugins.FunctionTool(manifest, invocation -> null));

    assertThat(reasonOf(outcome)).isEqualTo(RefusalReason.UNTRUSTED_SNAPSHOT);
    assertThat(registry.lookup(PluginId.of("untrusted"))).isEmpty();
  }

  @Test
  @DisplayName("every untrusted verdict refuses, not just an invalid signature")
  void everyUntrustedVerdictRefuses() {
    for (final PluginSignaturePort.Verdict verdict : PluginSignaturePort.Verdict.values()) {
      if (verdict.trusted()) {
        continue;
      }
      final PluginRegistryService registry = registry(PluginFixture.refuseWith(verdict));
      final PluginManifest manifest = PluginFixture.toolManifest("v" + verdict.ordinal());
      final RegistrationOutcome outcome =
          registry.register(
              PluginFixture.snapshot(manifest),
              new TestPlugins.FunctionTool(manifest, invocation -> null));
      assertThat(reasonOf(outcome))
          .as("verdict %s", verdict)
          .isEqualTo(RefusalReason.UNTRUSTED_SNAPSHOT);
    }
  }

  @Test
  @DisplayName("a manifest requesting a forbidden capability is refused")
  void forbiddenCapabilityIsRefused() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest =
        PluginFixture.manifest(
            "greedy",
            Set.of(),
            List.of(),
            PluginCapabilities.of(PluginCapabilities.EMIT_TELEMETRY, "secret.read"),
            0,
            List.of());
    final RegistrationOutcome outcome =
        registry.register(
            PluginFixture.snapshot(manifest),
            new TestPlugins.FunctionTool(manifest, invocation -> null));

    assertThat(reasonOf(outcome)).isEqualTo(RefusalReason.FORBIDDEN_CAPABILITY);
  }

  @Test
  @DisplayName("each forbidden capability is individually refused")
  void everyForbiddenCapabilityIsRefused() {
    for (final String forbidden : PluginCapabilities.FORBIDDEN) {
      final PluginRegistryService registry = registry(PluginFixture.trustAll());
      final PluginManifest manifest =
          PluginFixture.manifest(
              "greedy-" + Math.abs(forbidden.hashCode()),
              Set.of(),
              List.of(),
              PluginCapabilities.of(forbidden),
              0,
              List.of());
      final RegistrationOutcome outcome =
          registry.register(
              PluginFixture.snapshot(manifest),
              new TestPlugins.FunctionTool(manifest, invocation -> null));
      assertThat(reasonOf(outcome))
          .as("capability %s", forbidden)
          .isEqualTo(RefusalReason.FORBIDDEN_CAPABILITY);
    }
  }

  @Test
  @DisplayName("an unrecognized capability is refused rather than ignored")
  void unknownCapabilityIsRefused() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest =
        PluginFixture.manifest(
            "novel", Set.of(), List.of(), PluginCapabilities.of("do.anything"), 0, List.of());
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FunctionTool(manifest, invocation -> null))))
        .isEqualTo(RefusalReason.UNKNOWN_CAPABILITY);
  }

  @Test
  @DisplayName("third-party code is refused on the in-process substrate")
  void thirdPartyCodeIsRefusedInProcess() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest =
        new PluginManifest(
            PluginId.of("third-party"),
            "third-party",
            new PluginVersion(1, 0, 0),
            "someone-else",
            "untrusted code",
            PluginType.INTERNAL,
            TrustTier.THIRD_PARTY,
            Set.of(),
            0,
            PluginPermissions.none(),
            PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION),
            List.of(new ToolDefinition("t", "d", "{}")),
            PluginFixture.budget(Duration.ofSeconds(1)),
            Map.of(),
            PluginFixture.health(),
            List.of());

    // Doc 28 ISO-1: untrusted code never runs in the host JVM, whatever its manifest declares.
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FunctionTool(manifest, invocation -> null))))
        .isEqualTo(RefusalReason.ISOLATION_INSUFFICIENT);
  }

  @Test
  @DisplayName("the in-process substrate refuses every type that needs a process boundary")
  void inProcessSubstrateRefusesProcessTypes() {
    for (final PluginType type : PluginType.values()) {
      if (type == PluginType.INTERNAL) {
        continue;
      }
      final PluginRegistryService registry = registry(PluginFixture.trustAll());
      final PluginManifest manifest =
          new PluginManifest(
              PluginId.of("typed-" + type.name().toLowerCase(java.util.Locale.ROOT)),
              "typed",
              new PluginVersion(1, 0, 0),
              "acme",
              "d",
              type,
              TrustTier.FIRST_PARTY,
              Set.of(),
              0,
              PluginPermissions.none(),
              PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION),
              List.of(new ToolDefinition("t", "d", "{}")),
              PluginFixture.budget(Duration.ofSeconds(1)),
              Map.of(),
              PluginFixture.health(),
              List.of());
      assertThat(
              reasonOf(
                  registry.register(
                      PluginFixture.snapshot(manifest),
                      new TestPlugins.FunctionTool(manifest, invocation -> null))))
          .as("type %s", type)
          .isEqualTo(RefusalReason.UNSUPPORTED_TYPE);
    }
  }

  @Test
  @DisplayName("a reserved type is refused even on the process substrate")
  void reservedTypesAreRefusedEverywhere() {
    final ProcessSandbox processSandbox = new ProcessSandbox(4);
    final PluginRegistryService registry =
        new PluginRegistryService(
            PluginFixture.trustAll(),
            processSandbox,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            clock,
            8);
    final PluginManifest manifest =
        new PluginManifest(
            PluginId.of("wasm-plugin"),
            "wasm",
            new PluginVersion(1, 0, 0),
            "acme",
            "d",
            PluginType.WASM,
            TrustTier.THIRD_PARTY,
            Set.of(),
            0,
            PluginPermissions.none(),
            PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION),
            List.of(new ToolDefinition("t", "d", "{}")),
            PluginFixture.budget(Duration.ofSeconds(1)),
            Map.of(),
            PluginFixture.health(),
            List.of());
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FunctionTool(manifest, invocation -> null))))
        .isEqualTo(RefusalReason.UNSUPPORTED_TYPE);
    processSandbox.shutdown();
  }

  @Test
  @DisplayName("a capability with no backing permission is refused")
  void ungrantedCapabilityIsRefused() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest =
        PluginFixture.manifest(
            "reachless",
            Set.of(),
            List.of(),
            PluginCapabilities.of(PluginCapabilities.NETWORK_EGRESS),
            0,
            List.of());
    // net.egress with no host allow-list is an entitlement that reads broader than it is.
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FunctionTool(manifest, invocation -> null))))
        .isEqualTo(RefusalReason.UNGRANTED_CAPABILITY);
  }

  @Test
  @DisplayName("a permission with no matching capability is refused")
  void unusedPermissionIsRefused() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest =
        new PluginManifest(
            PluginId.of("stale-allowlist"),
            "stale",
            new PluginVersion(1, 0, 0),
            "acme",
            "d",
            PluginType.INTERNAL,
            TrustTier.FIRST_PARTY,
            Set.of(),
            0,
            new PluginPermissions(Set.of(), Set.of("legacy.example.com"), Set.of()),
            PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION),
            List.of(new ToolDefinition("t", "d", "{}")),
            PluginFixture.budget(Duration.ofSeconds(1)),
            Map.of(),
            PluginFixture.health(),
            List.of());
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FunctionTool(manifest, invocation -> null))))
        .isEqualTo(RefusalReason.UNGRANTED_CAPABILITY);
  }

  @Test
  @DisplayName("an instance whose manifest disagrees with the signed one is refused")
  void manifestMismatchIsRefused() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest signed = PluginFixture.toolManifest("honest");
    final PluginManifest claimed = PluginFixture.toolManifest("dishonest");
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(signed),
                    new TestPlugins.FunctionTool(claimed, invocation -> null))))
        .isEqualTo(RefusalReason.MANIFEST_MISMATCH);
  }

  @Test
  @DisplayName("a manifest declaring tools needs an instance that can run them")
  void toolManifestNeedsAToolPlugin() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest = PluginFixture.toolManifest("tools-declared");
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest), new PluginFixture.RecordingPlugin(manifest))))
        .isEqualTo(RefusalReason.NO_CAPABILITY_INTERFACE);
  }

  @Test
  @DisplayName("a manifest declaring extension points needs an extension plugin")
  void extensionManifestNeedsAnExtensionPlugin() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest =
        PluginFixture.extensionManifest("points-declared", ExtensionPoint.PRE_ROUTING, 0);
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FunctionTool(manifest, invocation -> null))))
        .isEqualTo(RefusalReason.NO_CAPABILITY_INTERFACE);
  }

  @Test
  @DisplayName("a missing dependency refuses the binding")
  void missingDependencyIsRefused() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest =
        PluginFixture.manifest(
            "needs-library",
            Set.of(),
            List.of(),
            PluginCapabilities.none(),
            0,
            List.of(new PluginDependency(PluginId.of("library"), new PluginVersion(1, 0, 0))));
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FixedExtension(manifest, Map.of()))))
        .isEqualTo(RefusalReason.DEPENDENCY_UNSATISFIED);
  }

  @Test
  @DisplayName("a dependency cycle refuses the plugin that closes the loop")
  void dependencyCycleIsRefused() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest first =
        PluginFixture.manifest(
            "first", Set.of(), List.of(), PluginCapabilities.none(), 0, List.of());
    registry.register(
        PluginFixture.snapshot(first), new TestPlugins.FixedExtension(first, Map.of()));

    // Re-registering "first" with a dependency back on "second" would close the loop; instead build
    // second -> first, then attempt first -> second via reload.
    final PluginManifest second =
        PluginFixture.manifest(
            "second",
            Set.of(),
            List.of(),
            PluginCapabilities.none(),
            0,
            List.of(new PluginDependency(PluginId.of("first"), new PluginVersion(1, 0, 0))));
    assertThat(
            registry
                .register(
                    PluginFixture.snapshot(second),
                    new TestPlugins.FixedExtension(second, Map.of()))
                .bound())
        .isTrue();

    final PluginManifest firstCyclic =
        PluginFixture.manifest(
            "first",
            Set.of(),
            List.of(),
            PluginCapabilities.none(),
            0,
            List.of(new PluginDependency(PluginId.of("second"), new PluginVersion(1, 0, 0))));
    assertThat(
            reasonOf(
                registry.reload(
                    PluginFixture.snapshot(firstCyclic),
                    new TestPlugins.FixedExtension(firstCyclic, Map.of()))))
        .isEqualTo(RefusalReason.DEPENDENCY_CYCLE);
  }

  @Test
  @DisplayName("registering the same id twice is refused")
  void duplicateRegistrationIsRefused() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest = PluginFixture.toolManifest("twice");
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FunctionTool(manifest, invocation -> null));
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FunctionTool(manifest, invocation -> null))))
        .isEqualTo(RefusalReason.ALREADY_REGISTERED);
  }

  @Test
  @DisplayName("the registry refuses to exceed its capacity")
  void capacityIsEnforced() {
    final PluginRegistryService registry =
        new PluginRegistryService(
            PluginFixture.trustAll(),
            sandbox,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            clock,
            2);
    for (int index = 0; index < 2; index++) {
      final PluginManifest manifest = PluginFixture.toolManifest("cap" + index);
      assertThat(
              registry
                  .register(
                      PluginFixture.snapshot(manifest),
                      new TestPlugins.FunctionTool(manifest, invocation -> null))
                  .bound())
          .isTrue();
    }
    final PluginManifest overflow = PluginFixture.toolManifest("cap-overflow");
    assertThat(
            reasonOf(
                registry.register(
                    PluginFixture.snapshot(overflow),
                    new TestPlugins.FunctionTool(overflow, invocation -> null))))
        .isEqualTo(RefusalReason.CAPACITY_EXCEEDED);
  }

  @Test
  @DisplayName("unregistering removes the binding and reports whether one existed")
  void unregisterRemovesTheBinding() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest = PluginFixture.toolManifest("transient");
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FunctionTool(manifest, invocation -> null));

    assertThat(registry.unregister(PluginId.of("transient"))).isTrue();
    assertThat(registry.lookup(PluginId.of("transient"))).isEmpty();
    assertThat(registry.unregister(PluginId.of("transient"))).isFalse();
  }

  @Test
  @DisplayName("a reload increments the generation and replaces the instance")
  void reloadReplacesTheInstance() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest = PluginFixture.toolManifest("reloadable");
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FunctionTool(manifest, invocation -> null));

    final RegistrationOutcome reloaded =
        registry.reload(
            PluginFixture.snapshot(manifest),
            new TestPlugins.FunctionTool(manifest, invocation -> null));
    assertThat(reloaded.bound()).isTrue();
    assertThat(((RegistrationOutcome.Bound) reloaded).descriptor().generation()).isEqualTo(1L);
  }

  @Test
  @DisplayName("a bad reload leaves the previous version bound (last-known-good)")
  void badReloadKeepsLastKnownGood() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest good = PluginFixture.toolManifest("resilient");
    registry.register(
        PluginFixture.snapshot(good), new TestPlugins.FunctionTool(good, invocation -> null));

    // A replacement whose manifest disagrees with its instance is refused; Doc 28 HRC-6 requires
    // the
    // incumbent to keep serving rather than the node being left with nothing.
    final PluginManifest other = PluginFixture.toolManifest("someone-else");
    final RegistrationOutcome refused =
        registry.reload(
            PluginFixture.snapshot(good), new TestPlugins.FunctionTool(other, invocation -> null));

    assertThat(refused.bound()).isFalse();
    final PluginDescriptor still = registry.lookup(PluginId.of("resilient")).orElseThrow();
    assertThat(still.generation()).isEqualTo(0L);
  }

  @Test
  @DisplayName("reloading an unregistered plugin simply binds it")
  void reloadOfAbsentPluginBinds() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest = PluginFixture.toolManifest("fresh");
    assertThat(
            registry
                .reload(
                    PluginFixture.snapshot(manifest),
                    new TestPlugins.FunctionTool(manifest, invocation -> null))
                .bound())
        .isTrue();
  }

  @Test
  @DisplayName("list orders by manifest priority, then plugin id")
  void listOrdersByPriorityThenId() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    // Registered out of order and with a priority tie, so neither insertion order nor priority
    // alone
    // could produce the expected sequence.
    register(registry, "zulu", ExtensionPoint.CLASSIFICATION, 5);
    register(registry, "alpha", ExtensionPoint.CLASSIFICATION, 5);
    register(registry, "first", ExtensionPoint.CLASSIFICATION, 1);

    startAll(registry);
    assertThat(registry.list(ExtensionPoint.CLASSIFICATION))
        .extracting(descriptor -> descriptor.id().value())
        .containsExactly("first", "alpha", "zulu");
  }

  @Test
  @DisplayName("list only returns plugins bound to the requested point")
  void listFiltersByExtensionPoint() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    register(registry, "router-hint", ExtensionPoint.PRE_ROUTING, 0);
    register(registry, "classifier", ExtensionPoint.CLASSIFICATION, 0);
    startAll(registry);

    assertThat(registry.list(ExtensionPoint.PRE_ROUTING))
        .extracting(descriptor -> descriptor.id().value())
        .containsExactly("router-hint");
    assertThat(registry.list(ExtensionPoint.TELEMETRY)).isEmpty();
  }

  @Test
  @DisplayName("list excludes plugins that are not READY")
  void listExcludesUnstartedPlugins() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    register(registry, "unstarted", ExtensionPoint.VALIDATION, 0);
    // Bound but never started, so it must not appear in a dispatch list.
    assertThat(registry.list(ExtensionPoint.VALIDATION)).isEmpty();
  }

  @Test
  @DisplayName("snapshot returns every binding in id order")
  void snapshotIsOrderedById() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    register(registry, "yankee", ExtensionPoint.TELEMETRY, 0);
    register(registry, "bravo", ExtensionPoint.TELEMETRY, 0);

    assertThat(registry.snapshot())
        .extracting(descriptor -> descriptor.id().value())
        .containsExactly("bravo", "yankee");
  }

  @Test
  @DisplayName("a descriptor is a value, so it never shows a later state change")
  void descriptorsAreImmutableViews() {
    final PluginRegistryService registry = registry(PluginFixture.trustAll());
    final PluginManifest manifest = PluginFixture.toolManifest("frozen-view");
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FunctionTool(manifest, invocation -> null));

    final PluginDescriptor taken = registry.lookup(PluginId.of("frozen-view")).orElseThrow();
    registry.unregister(PluginId.of("frozen-view"));
    assertThat(taken.state()).isEqualTo(PluginState.REGISTERED);
  }

  private static void register(
      final PluginRegistryService registry,
      final String id,
      final ExtensionPoint point,
      final int priority) {
    final PluginManifest manifest = PluginFixture.extensionManifest(id, point, priority);
    registry.register(
        PluginFixture.snapshot(manifest), new TestPlugins.FixedExtension(manifest, Map.of()));
  }

  private void startAll(final PluginRegistryService registry) {
    new io.reliabilityai.gateway.dataplane.plugin.application.PluginLifecycleService(
            registry,
            sandbox,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            clock,
            PluginFixture.TENANT)
        .startAll();
  }
}
