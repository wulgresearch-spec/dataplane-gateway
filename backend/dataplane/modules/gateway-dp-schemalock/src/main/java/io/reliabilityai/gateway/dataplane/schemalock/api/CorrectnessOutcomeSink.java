package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import io.reliabilityai.gateway.dataplane.schemalock.domain.Strategy;

/**
 * The content-free correctness-outcome seam (Doc 17 §38/§46/§47). Each terminal outcome is fanned
 * out to Metering (C5), Audit (C10, WORM tamper-evident), and Observability (C9) — all
 * <b>content-free</b>: ids, classification, strategy, counts; <b>never</b> output/prompt/values or
 * schema text (Doc 17 §37, SL-A5). A no-op default lets composition omit the sink. Best-effort:
 * never alters the outcome.
 */
public interface CorrectnessOutcomeSink {

  /** A no-op sink (safe default). */
  CorrectnessOutcomeSink NO_OP = (schemaId, conformant, classification, strategy, attempts) -> {};

  /**
   * Records a terminal correctness outcome (Doc 17 §38) — content-free.
   *
   * @param schemaId the content-hash schema id (not schema text)
   * @param conformant whether the output was conformant
   * @param classification the neutral failure class (null iff conformant)
   * @param strategy the strategy used
   * @param attempts the attempts made
   */
  void onOutcome(
      SchemaId schemaId,
      boolean conformant,
      FailureClass classification,
      Strategy strategy,
      int attempts);
}
