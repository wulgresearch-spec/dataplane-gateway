package io.reliabilityai.gateway.dataplane.schemalock.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure SchemaLock domain primitives (Doc 17 §14/§23.1/§13.1). */
class SchemaLockDomainTest {

  // --- ConservativeRepair NR-1 (§23.1): value-preserving envelope normalization ---

  @Test
  void stripsCodeFenceEnvelope() {
    assertThat(ConservativeRepair.stripEnvelope("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    assertThat(ConservativeRepair.stripEnvelope("```\n[1,2,3]\n```")).isEqualTo("[1,2,3]");
  }

  @Test
  void trimsSurroundingWhitespaceOnly() {
    assertThat(ConservativeRepair.stripEnvelope("   {\"a\":1}   ")).isEqualTo("{\"a\":1}");
  }

  @Test
  void leavesPlainJsonUnchangedAndPreservesInnerContent() {
    // NR-1 never alters a JSON value/key — inner braces and quotes are preserved verbatim.
    final String json = "{\"k\":\"a\\nb {x}\"}";
    assertThat(ConservativeRepair.stripEnvelope(json)).isEqualTo(json);
  }

  @Test
  void leavesAmbiguousFenceUntouched() {
    // No closing fence — not an unambiguous wrapper; never guess.
    assertThat(ConservativeRepair.stripEnvelope("```json\n{\"a\":1}"))
        .isEqualTo("```json\n{\"a\":1}");
  }

  // --- StrategySelector (§14): deterministic, safest-first, downgrade ---

  @Test
  void selectsNativeWhenSupportedAndWithinComplexity() {
    final CapabilityDescriptor cap = new CapabilityDescriptor(true, true, true, 10);
    assertThat(StrategySelector.select(cap, 5, false)).contains(Strategy.NATIVE_SCHEMA);
  }

  @Test
  void downgradesToToolWhenNativeOverComplex() {
    final CapabilityDescriptor cap = new CapabilityDescriptor(false, true, true, 10);
    assertThat(StrategySelector.select(cap, 5, false)).contains(Strategy.TOOL_CALL);
  }

  @Test
  void downgradesToPromptWhenComplexityExceedsNativeAndTool() {
    final CapabilityDescriptor cap = new CapabilityDescriptor(true, true, true, 3);
    assertThat(StrategySelector.select(cap, 99, false)).contains(Strategy.PROMPT_CONSTRAINED);
  }

  @Test
  void streamingUnsupportedYieldsNoStrategy() {
    final CapabilityDescriptor cap = new CapabilityDescriptor(true, true, false, 10);
    assertThat(StrategySelector.select(cap, 5, true)).isEmpty();
  }

  // --- CapabilityDescriptor (§13.1): fail-safe to least-capable ---

  @Test
  void nullOrInsaneDescriptorFallsToLeastCapable() {
    assertThat(CapabilityDescriptor.validatedOrLeastCapable(null))
        .isEqualTo(CapabilityDescriptor.LEAST_CAPABLE);
    assertThat(
            CapabilityDescriptor.validatedOrLeastCapable(
                new CapabilityDescriptor(true, true, true, -1)))
        .isEqualTo(CapabilityDescriptor.LEAST_CAPABLE);
  }

  // --- SchemaId (§10): deterministic content hash ---

  @Test
  void schemaIdIsContentHashDeterministic() {
    assertThat(SchemaId.of("{\"type\":\"object\"}"))
        .isEqualTo(SchemaId.of("{\"type\":\"object\"}"));
    assertThat(SchemaId.of("{\"type\":\"object\"}"))
        .isNotEqualTo(SchemaId.of("{\"type\":\"array\"}"));
  }
}
