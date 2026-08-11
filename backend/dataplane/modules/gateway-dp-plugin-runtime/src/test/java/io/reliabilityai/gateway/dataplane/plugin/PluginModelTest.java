package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.dataplane.plugin.api.HealthPolicy;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDependency;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginInvocationCost;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import io.reliabilityai.gateway.dataplane.plugin.api.ResourceBudget;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolDefinition;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolRequest;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolResponse;
import io.reliabilityai.gateway.dataplane.plugin.api.VettedPluginSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Plugin Runtime's value objects and the invariants they enforce (Doc 28 §6). */
@DisplayName("plugin model")
class PluginModelTest {

  @Test
  @DisplayName("a plugin id rejects characters that would break a metric label")
  void pluginIdRejectsUnsafeCharacters() {
    assertThat(PluginId.of("acme.classifier-v2_1").value()).isEqualTo("acme.classifier-v2_1");
    assertThatThrownBy(() -> PluginId.of("acme/classifier"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PluginId.of("acme classifier"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PluginId.of("acme\nclassifier"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a plugin id is bounded so metric cardinality stays bounded")
  void pluginIdIsBounded() {
    assertThatThrownBy(() -> PluginId.of("a".repeat(PluginId.MAX_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(PluginId.of("a".repeat(PluginId.MAX_LENGTH)).value()).hasSize(PluginId.MAX_LENGTH);
  }

  @Test
  @DisplayName("versions order by major, then minor, then patch")
  void versionsAreTotallyOrdered() {
    assertThat(new PluginVersion(1, 2, 3)).isGreaterThan(new PluginVersion(1, 2, 2));
    assertThat(new PluginVersion(1, 3, 0)).isGreaterThan(new PluginVersion(1, 2, 99));
    assertThat(new PluginVersion(2, 0, 0)).isGreaterThan(new PluginVersion(1, 99, 99));
  }

  @Test
  @DisplayName("a newer major never satisfies an older requirement")
  void majorVersionsAreNeverCompatible() {
    // The tempting shortcut is "newer is always fine". It is not: a major bump is exactly the
    // announcement that something a dependent relied on was removed.
    assertThat(new PluginVersion(2, 0, 0).satisfies(new PluginVersion(1, 0, 0))).isFalse();
    assertThat(new PluginVersion(1, 5, 0).satisfies(new PluginVersion(1, 2, 0))).isTrue();
    assertThat(new PluginVersion(1, 1, 0).satisfies(new PluginVersion(1, 2, 0))).isFalse();
  }

  @Test
  @DisplayName("a version parses only as major.minor.patch")
  void versionParsingIsStrict() {
    assertThat(PluginVersion.parse("3.14.15")).isEqualTo(new PluginVersion(3, 14, 15));
    assertThatThrownBy(() -> PluginVersion.parse("1.0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PluginVersion.parse("1.0.0.0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PluginVersion.parse("1.0.x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("permissions grant nothing by default")
  void permissionsAreDenyByDefault() {
    final PluginPermissions none = PluginPermissions.none();
    assertThat(none.empty()).isTrue();
    assertThat(none.allowsHost("example.com")).isFalse();
    assertThat(none.allowsPath("/etc/passwd")).isFalse();
    assertThat(none.allowsEnvironment("PATH")).isFalse();
  }

  @Test
  @DisplayName("a host grant matches exactly, never by suffix")
  void hostGrantsDoNotMatchBySuffix() {
    final PluginPermissions permissions =
        new PluginPermissions(Set.of(), Set.of("example.com"), Set.of());
    assertThat(permissions.allowsHost("example.com")).isTrue();
    // The classic mistake: a suffix match here would let evil-example.com through a grant that was
    // only ever meant for example.com.
    assertThat(permissions.allowsHost("evil-example.com")).isFalse();
    assertThat(permissions.allowsHost("sub.example.com")).isFalse();
  }

  @Test
  @DisplayName("a path grant refuses anything containing a parent traversal")
  void pathGrantsRefuseTraversal() {
    final PluginPermissions permissions =
        new PluginPermissions(Set.of("/var/plugin-data"), Set.of(), Set.of());
    assertThat(permissions.allowsPath("/var/plugin-data/cache")).isTrue();
    assertThat(permissions.allowsPath("/var/plugin-data")).isTrue();
    assertThat(permissions.allowsPath("/var/plugin-data/../../etc/shadow")).isFalse();
    assertThat(permissions.allowsPath("/var/plugin-database/secret")).isFalse();
  }

  @Test
  @DisplayName("the forbidden capability list names every Doc 28 exclusion")
  void forbiddenCapabilitiesCoverTheDocumentedExclusions() {
    assertThat(PluginCapabilities.FORBIDDEN)
        .contains(
            "provider.invoke",
            "provider.intercept",
            "stream.write",
            "stream.mutate",
            "response.transform",
            "secret.read",
            "secret.materialize",
            "pipeline.reenter",
            "stage.override");
  }

  @Test
  @DisplayName("a capability set reports which forbidden names it requested")
  void capabilitiesReportForbiddenRequests() {
    final PluginCapabilities capabilities =
        PluginCapabilities.of(PluginCapabilities.EMIT_TELEMETRY, "secret.read");
    assertThat(capabilities.forbidden()).containsExactly("secret.read");
    assertThat(capabilities.unknown()).isEmpty();
  }

  @Test
  @DisplayName("an unrecognized capability is reported rather than silently ignored")
  void capabilitiesReportUnknownRequests() {
    final PluginCapabilities capabilities = PluginCapabilities.of("do.whatever");
    assertThat(capabilities.unknown()).containsExactly("do.whatever");
    assertThat(capabilities.forbidden()).isEmpty();
  }

  @Test
  @DisplayName("a resource budget has no unlimited representation")
  void resourceBudgetsAreAlwaysBounded() {
    assertThatThrownBy(() -> new ResourceBudget(Duration.ZERO, 1, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResourceBudget(Duration.ofSeconds(1), 0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResourceBudget(Duration.ofSeconds(1), 1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResourceBudget(Duration.ofSeconds(1), 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a budget clamps down to the caller's remaining time but never up")
  void budgetClampsToRemainingTime() {
    final ResourceBudget budget = PluginFixture.budget(Duration.ofSeconds(30));
    assertThat(budget.clampedTo(Duration.ofSeconds(2)).wallClock())
        .isEqualTo(Duration.ofSeconds(2));
    // A caller with more time left than the manifest allows does not get to extend the plugin.
    assertThat(budget.clampedTo(Duration.ofMinutes(5)).wallClock())
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("a manifest refuses two tools with the same name")
  void manifestRefusesDuplicateToolNames() {
    assertThatThrownBy(
            () ->
                PluginFixture.manifest(
                    "dup",
                    Set.of(),
                    List.of(
                        new ToolDefinition("search", "a", "{}"),
                        new ToolDefinition("search", "b", "{}")),
                    PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION),
                    0,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a manifest refuses a dependency on itself")
  void manifestRefusesSelfDependency() {
    assertThatThrownBy(
            () ->
                PluginFixture.manifest(
                    "narcissus",
                    Set.of(),
                    List.of(),
                    PluginCapabilities.none(),
                    0,
                    List.of(
                        new PluginDependency(
                            PluginId.of("narcissus"), new PluginVersion(1, 0, 0)))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a manifest sorts its dependencies so derived order is input-independent")
  void manifestSortsDependencies() {
    final var manifest =
        PluginFixture.manifest(
            "consumer",
            Set.of(),
            List.of(),
            PluginCapabilities.none(),
            0,
            List.of(
                new PluginDependency(PluginId.of("zulu"), new PluginVersion(1, 0, 0)),
                new PluginDependency(PluginId.of("alpha"), new PluginVersion(1, 0, 0))));
    assertThat(manifest.dependencies())
        .extracting(dependency -> dependency.pluginId().value())
        .containsExactly("alpha", "zulu");
  }

  @Test
  @DisplayName("a manifest summary carries only ids, counts and categories")
  void manifestSummaryIsContentFree() {
    final var manifest =
        PluginFixture.manifest(
            "summary-test",
            Set.of(ExtensionPoint.CLASSIFICATION),
            List.of(new ToolDefinition("t", "d", "{}")),
            PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION),
            0,
            List.of());
    final Map<String, String> summary = manifest.summary();
    assertThat(summary)
        .containsEntry("pluginId", "summary-test")
        .containsEntry("type", PluginType.INTERNAL.name())
        .containsEntry("extensionPoints", "1")
        .containsEntry("tools", "1");
    // No configuration values: the runtime does not know what they contain.
    assertThat(summary.keySet()).doesNotContain("configuration");
  }

  @Test
  @DisplayName("a snapshot copies its byte arrays in and out")
  void snapshotDefendsItsBytes() {
    final byte[] digest = {1, 2, 3};
    final VettedPluginSnapshot snapshot =
        new VettedPluginSnapshot(
            PluginFixture.toolManifest("copy-test"),
            new byte[] {7},
            digest,
            new byte[] {8},
            "vetted");
    digest[0] = 42; // mutating the caller's array must not change what was verified
    assertThat(snapshot.digest()).containsExactly(1, 2, 3);

    final byte[] borrowed = snapshot.digest();
    borrowed[0] = 99; // nor may mutating what the accessor handed back
    assertThat(snapshot.digest()).containsExactly(1, 2, 3);
  }

  @Test
  @DisplayName("a snapshot renders its digest as lowercase hex")
  void snapshotRendersHexDigest() {
    final VettedPluginSnapshot snapshot =
        new VettedPluginSnapshot(
            PluginFixture.toolManifest("hex-test"),
            new byte[0],
            new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef},
            new byte[] {1},
            "vetted");
    assertThat(snapshot.digestHex()).isEqualTo("deadbeef");
  }

  @Test
  @DisplayName("a snapshot refuses an empty digest or signature")
  void snapshotRefusesMissingProof() {
    assertThatThrownBy(
            () ->
                new VettedPluginSnapshot(
                    PluginFixture.toolManifest("no-digest"),
                    new byte[0],
                    new byte[0],
                    new byte[] {1},
                    "vetted"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new VettedPluginSnapshot(
                    PluginFixture.toolManifest("no-signature"),
                    new byte[0],
                    new byte[] {1},
                    new byte[0],
                    "vetted"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("only READY admits execution")
  void onlyReadyAdmitsExecution() {
    assertThat(PluginState.READY.admitsExecution()).isTrue();
    for (final PluginState state : PluginState.values()) {
      if (state != PluginState.READY) {
        assertThat(state.admitsExecution()).as("%s must not admit execution", state).isFalse();
      }
    }
  }

  @Test
  @DisplayName("a tool request and response are bounded")
  void toolPayloadsAreBounded() {
    assertThatThrownBy(() -> ToolRequest.of("big", "x".repeat(ToolRequest.MAX_ARGUMENT_BYTES + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ToolResponse.of("y".repeat(ToolResponse.MAX_OUTPUT_BYTES + 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a plugin invocation cost cannot claim provider spend")
  void pluginCostCannotClaimProviderSpend() {
    // Doc 28 §31.1 denies plugins any provider capability, so a non-zero provider cost would be an
    // accounting record describing something that cannot have happened.
    assertThatThrownBy(
            () ->
                new PluginInvocationCost(
                    PluginId.of("p"),
                    "t",
                    PluginFixture.TENANT,
                    Duration.ofMillis(1),
                    1L,
                    1L,
                    100L,
                    5L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("providerCostMicros must be zero");
  }

  @Test
  @DisplayName("tenant cost is the sum of tool and provider cost")
  void tenantCostSumsTheComponents() {
    final PluginInvocationCost cost =
        new PluginInvocationCost(
            PluginId.of("p"), "t", PluginFixture.TENANT, Duration.ofMillis(1), 1L, 1L, 250L, 0L);
    assertThat(cost.tenantCostMicros()).isEqualTo(250L);
  }

  @Test
  @DisplayName("a health policy refuses a probe timeout longer than its interval")
  void healthPolicyRefusesOverlappingProbes() {
    assertThatThrownBy(() -> new HealthPolicy(Duration.ofSeconds(5), Duration.ofSeconds(10), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HealthPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("reserved plugin types are recognized but unsupported")
  void reservedTypesAreRecognizedAndRefused() {
    assertThat(PluginType.WASM.supported()).isFalse();
    assertThat(PluginType.PYTHON.supported()).isFalse();
    assertThat(PluginType.PROCESS.supported()).isTrue();
    assertThat(PluginType.MCP.supported()).isTrue();
  }

  @Test
  @DisplayName("only INTERNAL may run without a process boundary")
  void onlyInternalMayRunInProcess() {
    assertThat(PluginType.INTERNAL.requiresProcessIsolation()).isFalse();
    for (final PluginType type : PluginType.values()) {
      if (type != PluginType.INTERNAL) {
        assertThat(type.requiresProcessIsolation())
            .as("%s must require the process boundary", type)
            .isTrue();
      }
    }
  }
}
