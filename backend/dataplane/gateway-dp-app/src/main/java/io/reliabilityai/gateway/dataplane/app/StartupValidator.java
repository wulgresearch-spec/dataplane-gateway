package io.reliabilityai.gateway.dataplane.app;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates the activation DAG (Doc 29 §DAG, AD-018): every mandatory pipeline stage must be bound
 * before the deployable may activate. Pure and deterministic — the missing set is reported in
 * frozen pipeline order so diagnostics are stable across runs and reproducible (Doc 32
 * §CRS-adjacent).
 */
public final class StartupValidator {

  /**
   * Validates the set of bound stages against the mandatory pipeline.
   *
   * @param boundStages the stages that have a bound implementation at composition
   * @return the activation report (ready, or the ordered set of missing stages)
   */
  public StartupReport validate(final Set<MandatoryStage> boundStages) {
    Preconditions.requireNonNull(boundStages, "boundStages");
    final List<MandatoryStage> missing = new ArrayList<>();
    for (final MandatoryStage stage : MandatoryStage.values()) { // enum order = pipeline order
      if (!boundStages.contains(stage)) {
        missing.add(stage);
      }
    }
    return new StartupReport(List.copyOf(missing));
  }

  /**
   * The deterministic activation report.
   *
   * @param missingStages the mandatory stages that are unbound, in pipeline order (empty ⇒ ready)
   */
  public record StartupReport(List<MandatoryStage> missingStages) {

    /** Compact constructor defensively copying the missing list. */
    public StartupReport {
      missingStages = List.copyOf(Preconditions.requireNonNull(missingStages, "missingStages"));
    }

    /**
     * Whether all mandatory stages are bound and the deployable may activate.
     *
     * @return {@code true} iff no mandatory stage is missing
     */
    public boolean isReadyToActivate() {
      return missingStages.isEmpty();
    }
  }
}
