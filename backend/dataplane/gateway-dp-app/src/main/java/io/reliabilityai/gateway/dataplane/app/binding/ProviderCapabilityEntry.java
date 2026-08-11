package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.canonical.capability.ProviderCapability;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import java.util.Map;
import java.util.Set;

/**
 * One model's capabilities on one provider route, as published in the capability snapshot (Doc 25).
 *
 * <p>Capabilities are declared, never probed. The gateway must know what a model supports
 * <em>before</em> it routes to it — discovering mid-request that a model cannot stream or cannot
 * honour JSON mode would mean a failure the caller pays for. A model absent from the snapshot is
 * simply unusable, which is the correct fail-closed default.
 *
 * @param canonicalModelId the canonical model this entry describes
 * @param providerRouteRef the provider route the model is reachable on
 * @param providerApiVersion the pinned provider API version for this route
 * @param toolSupport whether the model accepts tool definitions
 * @param streaming whether the model can stream responses
 * @param jsonMode whether the model honours structured JSON output mode
 * @param reasoning whether the model emits reasoning tokens
 * @param vision whether the model accepts image input
 * @param embeddings whether the route serves embeddings rather than chat completions
 * @param maxTokens the model's output token ceiling
 * @param providerMetadata opaque, content-free provider annotations
 * @param capabilities the declared capability tokens this route satisfies
 */
public record ProviderCapabilityEntry(
    CanonicalModelId canonicalModelId,
    String providerRouteRef,
    PinnedVersion providerApiVersion,
    boolean toolSupport,
    boolean streaming,
    boolean jsonMode,
    boolean reasoning,
    boolean vision,
    boolean embeddings,
    long maxTokens,
    Map<String, String> providerMetadata,
    CapabilitySet capabilities) {

  /** Canonical capability token for tool calling. */
  public static final String CAPABILITY_TOOLS = "tools";

  /** Canonical capability token for streaming. */
  public static final String CAPABILITY_STREAMING = "streaming";

  /** Canonical capability token for JSON mode. */
  public static final String CAPABILITY_JSON_MODE = "json_mode";

  /** Canonical capability token for reasoning support. */
  public static final String CAPABILITY_REASONING = "reasoning";

  /** Canonical capability token for vision input. */
  public static final String CAPABILITY_VISION = "vision";

  /** Canonical capability token selecting the embeddings endpoint. */
  public static final String CAPABILITY_EMBEDDINGS = "embeddings";

  /**
   * Publishes an entry from the typed capability model.
   *
   * <p>The preferred way to author a snapshot. The boolean form below predates {@link
   * ProviderCapability} and can only express the six capabilities that happened to exist when it
   * was written — a snapshot needing {@code audio_input} or {@code batch} cannot be written with it
   * at all. This overload takes the open-ended typed set, so publishing a new capability needs no
   * change here.
   *
   * @param canonicalModelId the canonical model
   * @param providerRouteRef the provider route
   * @param providerApiVersion the pinned provider API version
   * @param capabilities the typed capability set
   * @param maxTokens the model's output token ceiling
   * @param providerMetadata opaque, content-free provider annotations
   * @return the capability entry
   */
  public static ProviderCapabilityEntry of(
      final CanonicalModelId canonicalModelId,
      final String providerRouteRef,
      final PinnedVersion providerApiVersion,
      final CapabilitySet capabilities,
      final long maxTokens,
      final Map<String, String> providerMetadata) {
    Preconditions.requireNonNull(capabilities, "capabilities");
    return new ProviderCapabilityEntry(
        canonicalModelId,
        providerRouteRef,
        providerApiVersion,
        capabilities.supports(ProviderCapability.FUNCTION_CALLING),
        capabilities.supports(ProviderCapability.STREAMING),
        capabilities.supports(ProviderCapability.JSON_MODE),
        capabilities.supports(ProviderCapability.REASONING),
        capabilities.supports(ProviderCapability.VISION),
        capabilities.supports(ProviderCapability.EMBEDDINGS),
        maxTokens,
        providerMetadata,
        capabilities);
  }

  /** Validates the entry. */
  public ProviderCapabilityEntry {
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonBlank(providerRouteRef, "providerRouteRef");
    Preconditions.requireNonNull(providerApiVersion, "providerApiVersion");
    Preconditions.requireNonNegative(maxTokens, "maxTokens");
    providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
    Preconditions.requireNonNull(capabilities, "capabilities");
  }

  /**
   * The boolean form, retained so existing snapshots keep compiling.
   *
   * <p>Derives the typed set from the flags, so both forms produce the same tokens. Limited to the
   * six capabilities the flags can express — reach for {@link #of} to publish anything else.
   *
   * @param canonicalModelId the canonical model
   * @param providerRouteRef the provider route
   * @param providerApiVersion the pinned provider API version
   * @param toolSupport whether the model accepts tool definitions
   * @param streaming whether the model can stream
   * @param jsonMode whether the model honours JSON output mode
   * @param reasoning whether the model emits reasoning tokens
   * @param vision whether the model accepts image input
   * @param embeddings whether the route serves embeddings
   * @param maxTokens the model's output token ceiling
   * @param providerMetadata opaque provider annotations
   */
  public ProviderCapabilityEntry(
      final CanonicalModelId canonicalModelId,
      final String providerRouteRef,
      final PinnedVersion providerApiVersion,
      final boolean toolSupport,
      final boolean streaming,
      final boolean jsonMode,
      final boolean reasoning,
      final boolean vision,
      final boolean embeddings,
      final long maxTokens,
      final Map<String, String> providerMetadata) {
    this(
        canonicalModelId,
        providerRouteRef,
        providerApiVersion,
        toolSupport,
        streaming,
        jsonMode,
        reasoning,
        vision,
        embeddings,
        maxTokens,
        providerMetadata,
        fromFlags(toolSupport, streaming, jsonMode, reasoning, vision, embeddings));
  }

  private static CapabilitySet fromFlags(
      final boolean toolSupport,
      final boolean streaming,
      final boolean jsonMode,
      final boolean reasoning,
      final boolean vision,
      final boolean embeddings) {
    final java.util.List<ProviderCapability> present = new java.util.ArrayList<>(6);
    if (toolSupport) {
      present.add(ProviderCapability.FUNCTION_CALLING);
    }
    if (streaming) {
      present.add(ProviderCapability.STREAMING);
    }
    if (jsonMode) {
      present.add(ProviderCapability.JSON_MODE);
    }
    if (reasoning) {
      present.add(ProviderCapability.REASONING);
    }
    if (vision) {
      present.add(ProviderCapability.VISION);
    }
    if (embeddings) {
      present.add(ProviderCapability.EMBEDDINGS);
    }
    return CapabilitySet.copyOf(present);
  }

  /**
   * The canonical capability tokens this entry grants, in a fixed order so two identical snapshots
   * always produce an identical mapping.
   *
   * @return the capability token set
   */
  public Set<String> capabilityTokens() {
    return capabilities.tokenSet();
  }
}
