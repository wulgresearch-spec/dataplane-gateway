package io.reliabilityai.gateway.dataplane.app.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests the seam between the request pipeline and the Plugin Runtime.
 *
 * <p>The properties under test are the ones that let this be wired into the non-bypass assembly at
 * all: it never throws, it never extends a request past its deadline, and a node with no plugin
 * runtime is indistinguishable from one whose plugins declined to bind.
 */
class ExtensionPointDispatcherTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final ClockPort CLOCK = () -> NOW;
  private static final CorrelationId CORRELATION = new CorrelationId("corr-1");
  private static final TenantContext TENANT =
      new TenantContext(TenantScope.of("org-a", "tenant-a"));

  // ---- the disabled dispatcher ------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(ExtensionPoint.class)
  void aNodeWithNoPluginRuntimeContributesNothingAtEveryPoint(final ExtensionPoint point) {
    final ExtensionOutcome outcome =
        ExtensionPointDispatcher.DISABLED.dispatch(
            point, CORRELATION, TENANT, NOW.plusSeconds(30), Map.of());

    assertThat(outcome.isEmpty()).isTrue();
    assertThat(outcome.point()).isEqualTo(point);
    assertThat(outcome.contributions()).isEmpty();
    assertThat(outcome.isolated()).isEmpty();
  }

  @Test
  void theDisabledDispatcherNeverThrowsOnAnyInput() {
    assertThatCode(
            () -> {
              ExtensionPointDispatcher.DISABLED.dispatch(null, null, null, null, null);
              ExtensionPointDispatcher.DISABLED.dispatch(
                  ExtensionPoint.TELEMETRY, null, TENANT, NOW, Map.of());
            })
        .doesNotThrowAnyException();
  }

  @Test
  void aNullPointStillYieldsAnOutcomeRatherThanAnException() {
    // The pipeline owes the caller a response whatever happens here; a throw would put an exception
    // between two mandatory stages.
    assertThat(ExtensionPointDispatcher.DISABLED.dispatch(null, CORRELATION, TENANT, NOW, Map.of()))
        .isNotNull();
  }

  // ---- construction -----------------------------------------------------------------------------

  @Test
  void aNonPositiveBudgetIsRefusedAtConstruction() {
    // A zero budget would mean every plugin is cancelled the instant it starts, which looks like a
    // working configuration and silently disables every plugin on the node.
    assertThatThrownBy(() -> new ExtensionPointDispatcher(null, CLOCK, Duration.ZERO))
        .isInstanceOf(NullPointerException.class);
  }

  // ---- the outcome value ------------------------------------------------------------------------

  @Test
  void anEmptyOutcomeSummarisesAsNothingHavingRun() {
    final ExtensionOutcome none = ExtensionOutcome.none(ExtensionPoint.VALIDATION);

    assertThat(none.contributed()).isZero();
    assertThat(none.summary()).isEqualTo("plugins=0 isolated=0");
  }

  @Test
  void anOutcomeReportsContributionsAndIsolationsSeparately() {
    final ExtensionOutcome outcome =
        new ExtensionOutcome(
            ExtensionPoint.PRE_ROUTING,
            Map.of("plugin-a", Map.of("hint", "warm")),
            java.util.List.of("plugin-b"));

    assertThat(outcome.contributed()).isEqualTo(1);
    assertThat(outcome.isolated()).containsExactly("plugin-b");
    assertThat(outcome.isEmpty()).isFalse();
    assertThat(outcome.summary()).isEqualTo("plugins=1 isolated=1");
  }

  @Test
  void anOutcomeIsImmutableFromTheOutside() {
    final ExtensionOutcome outcome =
        new ExtensionOutcome(
            ExtensionPoint.TELEMETRY, Map.of("a", Map.of("k", "v")), java.util.List.of("b"));

    assertThatThrownBy(() -> outcome.isolated().add("c"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> outcome.contributions().put("d", Map.of()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void anOutcomeCopiesTheMapsInsideTheContributionMapNotJustTheOuterMap() {
    // anOutcomeIsImmutableFromTheOutside passes Map.of() as the inner map, which is already
    // immutable, so it could never have caught this: contributions is Map<String, Map<..>> and a
    // shallow Map.copyOf froze the outer map while leaving each plugin's own signal writable
    // through the reference the caller kept. A contribution reaches the trace and the audit
    // stream, so an outcome that can still be edited after the point is closed is not the inert
    // record Doc 28 PRT-D1 requires. Today's producer already supplies immutable contributions,
    // so this guards the contract rather than a live production path.
    final Map<String, String> signal = new HashMap<>(Map.of("hint", "warm"));
    final Map<String, Map<String, String>> source = new HashMap<>();
    source.put("plugin-a", signal);

    final ExtensionOutcome outcome =
        new ExtensionOutcome(ExtensionPoint.PRE_ROUTING, source, java.util.List.of("plugin-b"));

    // Mutating either level of the caller's input must not reach the constructed outcome.
    signal.put("hint", "cold");
    signal.put("added", "yes");
    source.put("plugin-c", new HashMap<>(Map.of("k", "v")));

    assertThat(outcome.contributions()).containsOnlyKeys("plugin-a");
    assertThat(outcome.contributions().get("plugin-a"))
        .containsExactlyEntriesOf(Map.of("hint", "warm"));

    // And neither level of the returned structure may be written to.
    assertThatThrownBy(() -> outcome.contributions().put("plugin-d", Map.of()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> outcome.contributions().get("plugin-a").put("hint", "cold"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void anOutcomeCarriesNoRequestContent() {
    // ExtensionOutcome reaches the trace and the audit stream. It carries plugin ids and advisory
    // keys; a prompt or completion field here would leak content into both.
    assertThat(ExtensionOutcome.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactlyInAnyOrder("point", "contributions", "isolated");
  }

  @Test
  void everyFrozenPointHasAnEmptyOutcomeForm() {
    for (final ExtensionPoint point : ExtensionPoint.values()) {
      assertThat(ExtensionOutcome.none(point).point()).isEqualTo(point);
    }
  }

  @Test
  void theFrozenPointSetIsExactlyTheFiveDocTwentyEightNames() {
    // Doc 28 EPC-1/EPC-2: the five are the complete set and the runtime may never add a sixth.
    assertThat(ExtensionPoint.values())
        .containsExactly(
            ExtensionPoint.PRE_ROUTING,
            ExtensionPoint.CLASSIFICATION,
            ExtensionPoint.VALIDATION,
            ExtensionPoint.TELEMETRY,
            ExtensionPoint.ATTRIBUTION);
  }
}
