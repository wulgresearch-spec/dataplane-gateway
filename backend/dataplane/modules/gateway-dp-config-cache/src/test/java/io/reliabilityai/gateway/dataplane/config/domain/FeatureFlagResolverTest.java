package io.reliabilityai.gateway.dataplane.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.FeatureFlagDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Determinism contract tests for feature-flag resolution (Doc 36 §FFC). */
class FeatureFlagResolverTest {

  private final FeatureFlagResolver resolver = new FeatureFlagResolver();
  private final TenantScope tenant = TenantScope.of("org-1", "tenant-1");

  @Test
  void fullyOnAndFullyOffResolveDeterministicallyRegardlessOfTenant() {
    // FFC-3: 0 basis points ⇒ off for all; 10000 ⇒ on for all — no tenant dependence.
    final var defs =
        List.of(new FeatureFlagDefinition("f-on", 10_000), new FeatureFlagDefinition("f-off", 0));
    for (final String org : List.of("a", "b", "c")) {
      final var resolved = resolver.resolve(defs, TenantScope.of(org, "t"));
      assertThat(resolved.value("f-on")).isTrue();
      assertThat(resolved.value("f-off")).isFalse();
    }
  }

  @Test
  void resolutionIsDeterministicForIdenticalInputs() {
    // FFC-2/FFC-5: identical inputs ⇒ identical resolved set and identical recorded id.
    final var defs =
        List.of(
            new FeatureFlagDefinition("f-half", 5_000),
            new FeatureFlagDefinition("f-quarter", 2_500));
    final var first = resolver.resolve(defs, tenant);
    final var second = resolver.resolve(defs, tenant);
    assertThat(first.resolved()).isEqualTo(second.resolved());
    assertThat(first.id()).isEqualTo(second.id());
  }

  @Test
  void percentageRolloutIsTenantScopedAndStable() {
    // A partial rollout resolves per (flag, org, tenant) and is stable across calls (Doc 36 §TFI).
    final var defs = List.of(new FeatureFlagDefinition("f-half", 5_000));
    final boolean firstCall = resolver.resolve(defs, tenant).value("f-half");
    final boolean secondCall = resolver.resolve(defs, tenant).value("f-half");
    assertThat(firstCall).isEqualTo(secondCall);
  }

  @Test
  void rolloutDistributesAcrossTenantsWithinTolerance() {
    // Deterministic bucketing must spread ~50% of tenants true for a 5000bp rollout (sanity of the
    // hash distribution), proving resolution is neither constant nor RNG-based.
    final var defs = List.of(new FeatureFlagDefinition("f-half", 5_000));
    int trueCount = 0;
    final int n = 400;
    for (int i = 0; i < n; i++) {
      if (resolver.resolve(defs, TenantScope.of("org", "tenant-" + i)).value("f-half")) {
        trueCount++;
      }
    }
    assertThat(trueCount).isBetween((int) (n * 0.35), (int) (n * 0.65));
  }

  @Test
  void resolverRejectsNullArguments() {
    assertThatThrownBy(() -> resolver.resolve(null, tenant))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> resolver.resolve(List.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void resolvedSetDeniesUnknownFlagByDefault() {
    final var resolved = resolver.resolve(List.of(), tenant);
    assertThat(resolved.value("never-defined")).isFalse();
  }
}
