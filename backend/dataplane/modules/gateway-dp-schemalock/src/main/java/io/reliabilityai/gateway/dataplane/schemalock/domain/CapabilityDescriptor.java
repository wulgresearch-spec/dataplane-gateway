package io.reliabilityai.gateway.dataplane.schemalock.domain;

/**
 * The neutral provider structured-output capability descriptor (Doc 17 §13/§13.1, SL-D2). Produced
 * by the C1 provider adapter from its last-known-good snapshot (AD-022) and consumed
 * <b>neutrally</b> — SchemaLock never inspects provider-native fields (Doc 12 §16.10). On any
 * capability uncertainty SchemaLock <b>fails safe to least-capable</b> (Doc 17 §13.1), never
 * assuming a capability it cannot confirm. Immutable.
 *
 * @param nativeSchema whether the provider natively enforces JSON Schema
 * @param toolCalling whether the provider supports tool-calling
 * @param streaming whether the provider supports streaming
 * @param maxSchemaComplexity the max schema complexity the provider can enforce natively/via tools
 */
public record CapabilityDescriptor(
    boolean nativeSchema, boolean toolCalling, boolean streaming, int maxSchemaComplexity) {

  /** The least-capable descriptor: prompt-constrained only, no streaming, no native enforcement. */
  public static final CapabilityDescriptor LEAST_CAPABLE =
      new CapabilityDescriptor(false, false, false, 0);

  /**
   * Validates a raw descriptor and falls safe to least-capable on any uncertainty (Doc 17 §13.1) —
   * a null or structurally-insane descriptor (negative complexity) is treated as least-capable,
   * never assumed more capable than confirmed.
   *
   * @param raw the raw descriptor (may be null)
   * @return the validated descriptor, or {@link #LEAST_CAPABLE} on uncertainty
   */
  public static CapabilityDescriptor validatedOrLeastCapable(final CapabilityDescriptor raw) {
    if (raw == null || raw.maxSchemaComplexity < 0) {
      return LEAST_CAPABLE;
    }
    return raw;
  }
}
