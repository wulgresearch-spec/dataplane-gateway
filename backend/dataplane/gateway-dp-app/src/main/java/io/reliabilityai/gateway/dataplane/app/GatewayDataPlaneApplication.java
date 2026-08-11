package io.reliabilityai.gateway.dataplane.app;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.app.StartupValidator.StartupReport;
import java.util.List;
import java.util.Set;

/**
 * The single data-plane deployable's composition root (AD-020, Doc 10 §5, Doc 38 §5). It activates
 * the mandatory non-bypass pipeline (Doc 06 §8, AD-018) only when <b>every</b> mandatory stage is
 * bound; if any stage is unbound it <b>fails closed at startup</b> with a deterministic diagnostic
 * and never serves a bypassed pipeline. This makes the non-bypass guarantee a startup invariant,
 * not a hope.
 *
 * <p>No framework, no reflection, no auto-wiring — bindings are supplied explicitly by the caller,
 * so the activation is fully deterministic and auditable.
 */
public final class GatewayDataPlaneApplication {

  private final StartupValidator validator;

  /** Creates the application with a default validator. */
  public GatewayDataPlaneApplication() {
    this(new StartupValidator());
  }

  /**
   * Creates the application with an explicit validator (for testing).
   *
   * @param validator the startup validator
   */
  public GatewayDataPlaneApplication(final StartupValidator validator) {
    this.validator = Preconditions.requireNonNull(validator, "validator");
  }

  /**
   * Activates the deployable, or fails closed if any mandatory stage is unbound (AD-018).
   *
   * @param boundStages the stages bound at composition
   * @throws StartupValidationException if any mandatory stage is missing — activation is refused
   */
  public void activate(final Set<MandatoryStage> boundStages) {
    final StartupReport report = validator.validate(boundStages);
    if (!report.isReadyToActivate()) {
      throw new StartupValidationException(diagnostic(report.missingStages()));
    }
    // All mandatory stages bound: the frozen activation DAG (Doc 29 §DAG) proceeds here once every
    // stage's production implementation exists. Substrate-only builds intentionally never reach
    // this.
  }

  private static String diagnostic(final List<MandatoryStage> missing) {
    final StringBuilder builder =
        new StringBuilder("data-plane activation refused (AD-018 non-bypass): ")
            .append(missing.size())
            .append(" mandatory stage(s) unbound: ");
    for (int i = 0; i < missing.size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      builder.append(missing.get(i).name());
    }
    return builder.toString();
  }
}
