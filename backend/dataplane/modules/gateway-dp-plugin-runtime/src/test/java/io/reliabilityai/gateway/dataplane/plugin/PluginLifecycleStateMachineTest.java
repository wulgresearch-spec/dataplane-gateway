package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import io.reliabilityai.gateway.dataplane.plugin.domain.PluginLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lifecycle state machine (Doc 28 §D). */
@DisplayName("plugin lifecycle state machine")
class PluginLifecycleStateMachineTest {

  @Test
  @DisplayName("the ordinary path is REGISTERED to INITIALIZING to READY")
  void ordinaryStartPath() {
    assertThat(PluginLifecycle.permits(PluginState.REGISTERED, PluginState.INITIALIZING)).isTrue();
    assertThat(PluginLifecycle.permits(PluginState.INITIALIZING, PluginState.READY)).isTrue();
  }

  @Test
  @DisplayName("a plugin can never reach READY without initializing")
  void readyIsUnreachableWithoutInitializing() {
    // Skipping INITIALIZING means serving traffic before the start hook ran.
    assertThat(PluginLifecycle.permits(PluginState.REGISTERED, PluginState.READY)).isFalse();
    assertThat(PluginLifecycle.permits(PluginState.STOPPED, PluginState.READY)).isFalse();
    assertThat(PluginLifecycle.permits(PluginState.DISABLED, PluginState.READY)).isFalse();
  }

  @Test
  @DisplayName("FAILED is terminal: nothing transitions out of it")
  void failedIsTerminal() {
    assertThat(PluginLifecycle.nextStates(PluginState.FAILED)).isEmpty();
    for (final PluginState target : PluginState.values()) {
      assertThat(PluginLifecycle.permits(PluginState.FAILED, target))
          .as("FAILED -> %s must be illegal", target)
          .isFalse();
    }
  }

  @Test
  @DisplayName("the ordinary stop path is READY to STOPPING to STOPPED")
  void ordinaryStopPath() {
    assertThat(PluginLifecycle.permits(PluginState.READY, PluginState.STOPPING)).isTrue();
    assertThat(PluginLifecycle.permits(PluginState.STOPPING, PluginState.STOPPED)).isTrue();
  }

  @Test
  @DisplayName("a stopped plugin can start again")
  void stoppedCanRestart() {
    assertThat(PluginLifecycle.permits(PluginState.STOPPED, PluginState.INITIALIZING)).isTrue();
  }

  @Test
  @DisplayName("re-enabling returns to REGISTERED, so the start hook runs again")
  void enablingReturnsToRegistered() {
    assertThat(PluginLifecycle.nextStates(PluginState.DISABLED))
        .containsExactly(PluginState.REGISTERED);
  }

  @Test
  @DisplayName("a failing stop hook lands the plugin in FAILED, not STOPPED")
  void stoppingCanFail() {
    assertThat(PluginLifecycle.permits(PluginState.STOPPING, PluginState.FAILED)).isTrue();
  }

  @Test
  @DisplayName("no state may transition to itself")
  void noSelfTransitions() {
    for (final PluginState state : PluginState.values()) {
      assertThat(PluginLifecycle.permits(state, state))
          .as("%s -> %s must be illegal", state, state)
          .isFalse();
    }
  }

  @Test
  @DisplayName("an illegal transition throws rather than being applied")
  void illegalTransitionThrows() {
    assertThatThrownBy(() -> PluginLifecycle.transition(PluginState.REGISTERED, PluginState.READY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("illegal plugin transition");
  }

  @Test
  @DisplayName("a legal transition returns the new state")
  void legalTransitionReturnsTarget() {
    assertThat(PluginLifecycle.transition(PluginState.READY, PluginState.STOPPING))
        .isEqualTo(PluginState.STOPPING);
  }

  @Test
  @DisplayName("STOPPED, FAILED and DISABLED are the states that refuse work until acted on")
  void terminalStatesAreClassified() {
    assertThat(PluginState.STOPPED.terminal()).isTrue();
    assertThat(PluginState.FAILED.terminal()).isTrue();
    assertThat(PluginState.DISABLED.terminal()).isTrue();
    assertThat(PluginState.READY.terminal()).isFalse();
    assertThat(PluginState.INITIALIZING.terminal()).isFalse();
  }
}
