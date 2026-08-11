package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDependency;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import io.reliabilityai.gateway.dataplane.plugin.domain.DependencyGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dependency resolution, ordering and cycle detection (Doc 28 §14). */
@DisplayName("plugin dependency graph")
class DependencyGraphTest {

  private static PluginManifest depending(final String id, final String... dependsOn) {
    final List<PluginDependency> dependencies = new ArrayList<>();
    for (final String target : dependsOn) {
      dependencies.add(new PluginDependency(PluginId.of(target), new PluginVersion(1, 0, 0)));
    }
    return PluginFixture.manifest(
        id, Set.of(), List.of(), PluginCapabilities.none(), 0, dependencies);
  }

  @Test
  @DisplayName("a plugin starts after everything it depends on")
  void startOrderRespectsDependencies() {
    final DependencyGraph graph =
        new DependencyGraph(
            List.of(depending("app", "database"), depending("database"), depending("cache")));
    final List<PluginId> order = graph.startOrder();
    assertThat(order.indexOf(PluginId.of("database")))
        .isLessThan(order.indexOf(PluginId.of("app")));
  }

  @Test
  @DisplayName("stop order is the exact reverse of start order")
  void stopOrderIsReversedStartOrder() {
    final DependencyGraph graph =
        new DependencyGraph(
            List.of(depending("app", "database"), depending("database"), depending("cache")));
    final List<PluginId> reversed = new ArrayList<>(graph.startOrder());
    Collections.reverse(reversed);
    assertThat(graph.stopOrder()).isEqualTo(reversed);
  }

  @Test
  @DisplayName("independent plugins are ordered by id, so the sort is deterministic")
  void independentPluginsOrderById() {
    // Kahn's algorithm is free to pick any ready node. Picking arbitrarily would make startup order
    // vary between two nodes running identical config.
    final DependencyGraph graph =
        new DependencyGraph(List.of(depending("zulu"), depending("alpha"), depending("mike")));
    assertThat(graph.startOrder())
        .extracting(PluginId::value)
        .containsExactly("alpha", "mike", "zulu");
  }

  @Test
  @DisplayName("the same manifest set always produces the same order, whatever order it arrives in")
  void orderIsIndependentOfInputOrdering() {
    final List<PluginManifest> forwards =
        List.of(depending("a"), depending("b", "a"), depending("c", "b"));
    final List<PluginManifest> backwards = new ArrayList<>(forwards);
    Collections.reverse(backwards);
    assertThat(new DependencyGraph(forwards).startOrder())
        .isEqualTo(new DependencyGraph(backwards).startOrder());
  }

  @Test
  @DisplayName("a direct cycle is detected")
  void directCycleIsDetected() {
    final DependencyGraph graph = new DependencyGraph(List.of(depending("a", "b")));
    assertThat(graph.introducesCycle(depending("b", "a"))).isTrue();
  }

  @Test
  @DisplayName("a transitive cycle is detected")
  void transitiveCycleIsDetected() {
    final DependencyGraph graph =
        new DependencyGraph(List.of(depending("a", "b"), depending("b", "c")));
    assertThat(graph.introducesCycle(depending("c", "a"))).isTrue();
  }

  @Test
  @DisplayName("a diamond is not a cycle")
  void diamondIsNotACycle() {
    final DependencyGraph graph =
        new DependencyGraph(List.of(depending("base"), depending("left", "base")));
    assertThat(graph.introducesCycle(depending("right", "base"))).isFalse();
  }

  @Test
  @DisplayName("a cyclic graph reports itself as not acyclic and drops the cycle from the order")
  void cyclicGraphIsIncomplete() {
    final DependencyGraph graph =
        new DependencyGraph(List.of(depending("a", "b"), depending("b", "a"), depending("free")));
    assertThat(graph.acyclic()).isFalse();
    // Everything outside the cycle can still be started.
    assertThat(graph.startOrder()).extracting(PluginId::value).containsExactly("free");
  }

  @Test
  @DisplayName("a missing dependency is reported")
  void missingDependencyIsReported() {
    final DependencyGraph graph = new DependencyGraph(List.of(depending("app", "absent")));
    assertThat(graph.unsatisfied(depending("app", "absent")))
        .extracting(dependency -> dependency.pluginId().value())
        .containsExactly("absent");
  }

  @Test
  @DisplayName("a dependency present at an older version is unsatisfied")
  void olderVersionIsUnsatisfied() {
    final PluginManifest oldLibrary =
        PluginFixture.manifest(
            "library", Set.of(), List.of(), PluginCapabilities.none(), 0, List.of());
    final PluginManifest consumer =
        PluginFixture.manifest(
            "consumer",
            Set.of(),
            List.of(),
            PluginCapabilities.none(),
            0,
            List.of(new PluginDependency(PluginId.of("library"), new PluginVersion(1, 5, 0))));
    // The fixture builds every manifest at 1.0.0, so a 1.5.0 requirement cannot be met.
    assertThat(new DependencyGraph(List.of(oldLibrary, consumer)).unsatisfied(consumer)).hasSize(1);
  }

  @Test
  @DisplayName("a satisfied dependency is not reported")
  void satisfiedDependencyIsSilent() {
    final DependencyGraph graph =
        new DependencyGraph(List.of(depending("library"), depending("consumer", "library")));
    assertThat(graph.unsatisfied(depending("consumer", "library"))).isEmpty();
  }

  @Test
  @DisplayName("a deep chain orders end to end")
  void deepChainOrdersEndToEnd() {
    final List<PluginManifest> chain = new ArrayList<>();
    chain.add(depending("p00"));
    for (int index = 1; index < 25; index++) {
      chain.add(depending(String.format("p%02d", index), String.format("p%02d", index - 1)));
    }
    final List<PluginId> order = new DependencyGraph(chain).startOrder();
    assertThat(order).hasSize(25);
    assertThat(order.get(0).value()).isEqualTo("p00");
    assertThat(order.get(24).value()).isEqualTo("p24");
  }
}
