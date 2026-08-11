package io.reliabilityai.gateway.dataplane.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fail-closed activation tests (AD-018 non-bypass; Doc 29 §DAG). */
class StartupValidatorTest {

  private final GatewayDataPlaneApplication app = new GatewayDataPlaneApplication();
  private final StartupValidator validator = new StartupValidator();

  @Test
  void failsClosedWhenMandatoryStagesAreUnbound() {
    // Only the substrate authn stage is bound today; the pipeline must refuse to activate.
    assertThatThrownBy(() -> app.activate(Set.of(MandatoryStage.AUTHN)))
        .isInstanceOf(StartupValidationException.class)
        .hasMessageContaining("AD-018 non-bypass")
        .hasMessageContaining("GOVERNANCE")
        .hasMessageContaining("ROUTER");
  }

  @Test
  void activatesOnlyWhenEveryMandatoryStageIsBound() {
    assertThatCode(() -> app.activate(EnumSet.allOf(MandatoryStage.class)))
        .doesNotThrowAnyException();
  }

  @Test
  void reportListsMissingStagesInPipelineOrder() {
    final var report = validator.validate(EnumSet.of(MandatoryStage.AUTHN, MandatoryStage.EMITTER));
    assertThat(report.isReadyToActivate()).isFalse();
    // INGRESS precedes GOVERNANCE precedes ROUTER … in the reported missing set (pipeline order).
    assertThat(report.missingStages().get(0)).isEqualTo(MandatoryStage.INGRESS);
    assertThat(report.missingStages())
        .containsSubsequence(
            MandatoryStage.GOVERNANCE, MandatoryStage.ROUTER, MandatoryStage.RELIABILITY);
    assertThat(report.missingStages()).doesNotContain(MandatoryStage.AUTHN, MandatoryStage.EMITTER);
  }

  @Test
  void diagnosticsAreDeterministic() {
    final Set<MandatoryStage> bound = Set.of(MandatoryStage.AUTHN);
    String first = null;
    for (int i = 0; i < 50; i++) {
      try {
        app.activate(bound);
      } catch (final StartupValidationException e) {
        if (first == null) {
          first = e.getMessage();
        }
        assertThat(e.getMessage()).isEqualTo(first);
      }
    }
    assertThat(first).isNotNull();
  }

  @Test
  void emptyBindingReportsAllStagesMissing() {
    assertThat(validator.validate(Set.of()).missingStages())
        .hasSize(MandatoryStage.values().length);
  }

  @Test
  void secretsIsMandatoryAndBlocksActivationWhenUnbound() {
    // The credential-materialization (Secrets, C14) stage is a mandatory fail-closed stage (Doc 32
    // line 74/§13). A pipeline missing only Secrets must NOT activate (non-bypass, AD-018).
    final Set<MandatoryStage> allButSecrets =
        java.util.EnumSet.complementOf(java.util.EnumSet.of(MandatoryStage.SECRETS));
    final StartupValidator.StartupReport report = validator.validate(allButSecrets);
    assertThat(report.isReadyToActivate()).isFalse();
    assertThat(report.missingStages()).containsExactly(MandatoryStage.SECRETS);
  }

  @Test
  void stagesAreInFrozenPipelineOrderStreamGuardBeforeSchemaLock() {
    final java.util.List<MandatoryStage> order = java.util.List.of(MandatoryStage.values());
    assertThat(order.indexOf(MandatoryStage.SECRETS))
        .isLessThan(order.indexOf(MandatoryStage.ADAPTER));
    assertThat(order.indexOf(MandatoryStage.STREAM_GUARD))
        .isLessThan(order.indexOf(MandatoryStage.SCHEMA_LOCK)); // Doc 32 line 74: 18 before 17
  }
}
