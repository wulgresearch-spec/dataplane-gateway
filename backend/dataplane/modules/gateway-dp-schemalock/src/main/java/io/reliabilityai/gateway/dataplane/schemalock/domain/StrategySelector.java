package io.reliabilityai.gateway.dataplane.schemalock.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * Deterministic structured-output strategy selection (Doc 17 §14, SL-D2). Given (capability, schema
 * complexity, mode) it picks the safest viable strategy — {@code NATIVE_SCHEMA} &gt; {@code
 * TOOL_CALL} &gt; {@code PROMPT_CONSTRAINED} — downgrading (never silently truncating the schema)
 * when a provider's native complexity limit is exceeded. If no strategy is viable (e.g. streaming
 * requested but unsupported), it returns empty ⇒ {@code CAPABILITY_UNSUPPORTED} (Doc 17 §25). Pure
 * and deterministic (Doc 17 §28); never branches on provider identity (AD-007).
 */
public final class StrategySelector {

  private StrategySelector() {}

  /**
   * Selects the safest viable strategy (Doc 17 §14).
   *
   * @param capability the validated (fail-safe) capability descriptor
   * @param schemaComplexity the compiled schema's complexity score
   * @param streaming whether the request is a streaming request
   * @return the selected strategy, or empty if none is viable ({@code CAPABILITY_UNSUPPORTED})
   */
  public static Optional<Strategy> select(
      final CapabilityDescriptor capability, final int schemaComplexity, final boolean streaming) {
    Preconditions.requireNonNull(capability, "capability");
    if (streaming && !capability.streaming()) {
      return Optional.empty(); // streaming requested but provider cannot stream (Doc 17 §25)
    }
    if (capability.nativeSchema() && schemaComplexity <= capability.maxSchemaComplexity()) {
      return Optional.of(Strategy.NATIVE_SCHEMA);
    }
    if (capability.toolCalling() && schemaComplexity <= capability.maxSchemaComplexity()) {
      return Optional.of(Strategy.TOOL_CALL);
    }
    // Prompt-constrained is the always-available last resort (validation + guided retry, Doc 17
    // §16).
    return Optional.of(Strategy.PROMPT_CONSTRAINED);
  }
}
