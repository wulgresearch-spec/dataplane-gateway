package io.reliabilityai.gateway.canonical.capability;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The closed vocabulary of things a provider route can do (AD-007, Doc 25 §14.1).
 *
 * <p>This enum is the whole point of provider abstraction. The gateway asks <em>"can this route do
 * function calling?"</em> and never <em>"is this OpenAI?"</em>, and because the question is a typed
 * constant rather than a string, a route that cannot answer it is a compile error somewhere rather
 * than a silent mismatch at request time. Before this existed the same six capabilities were
 * spelled as string literals in three unrelated places — the router's filter, the adapter's
 * mapping, and the snapshot that fed both — and a typo in any one of them disabled a capability
 * with no diagnostic.
 *
 * <p><b>Declared, never probed.</b> A capability is published by the Provider Registry and consumed
 * read-only (CAP-1…CAP-6). Nothing in this codebase asks a provider's API what it supports:
 * discovering mid-request that a model cannot stream means a failure the caller already paid for,
 * and a runtime probe would make the answer depend on when you asked.
 *
 * <p><b>Wire tokens are frozen.</b> Each constant carries a stable {@code token} that is what
 * actually travels in capability snapshots and in the router's existing {@code Set<String>} filter.
 * The first six tokens deliberately match the strings that were already in use, so introducing this
 * type changed no routing decision — the vocabulary became typed without becoming different.
 * Renaming a token is a breaking change to every published snapshot.
 */
public enum ProviderCapability {

  // ---- generation shapes -----------------------------------------------------------------------

  /** Produces text at all — the base capability every generative route has. */
  TEXT_GENERATION("text_generation"),

  /** Serves the chat-shaped API: a message list in, a message out. */
  CHAT_COMPLETION("chat_completion"),

  /** Serves the older prompt-completion-shaped API. */
  TEXT_COMPLETION("text_completion"),

  /** Serves a stateful responses-shaped API that carries its own conversation handle. */
  RESPONSES("responses"),

  /** Can deliver a response incrementally rather than only when complete. */
  STREAMING("streaming"),

  // ---- tools and structure ---------------------------------------------------------------------

  /** Accepts tool definitions and can emit tool calls. */
  FUNCTION_CALLING("tools"),

  /** Can emit more than one tool call per turn. */
  PARALLEL_TOOL_CALLS("parallel_tool_calls"),

  /** Can be asked for syntactically valid JSON without a schema. */
  JSON_MODE("json_mode"),

  /**
   * Can be constrained to a caller-supplied JSON schema — strictly stronger than {@link
   * #JSON_MODE}.
   */
  JSON_SCHEMA("json_schema"),

  // ---- modalities ------------------------------------------------------------------------------

  /** Produces vector embeddings. */
  EMBEDDINGS("embeddings"),

  /** Generates images. */
  IMAGE_GENERATION("image_generation"),

  /** Accepts image input. */
  VISION("vision"),

  /** Accepts audio input — transcription and speech understanding. */
  AUDIO_INPUT("audio_input"),

  /** Produces audio output — speech synthesis. */
  AUDIO_OUTPUT("audio_output"),

  /** Accepts more than one input modality in a single request. */
  MULTIMODAL("multimodal"),

  // ---- execution characteristics ---------------------------------------------------------------

  /** Performs extended reasoning and may bill reasoning tokens separately. */
  REASONING("reasoning"),

  /** Serves context windows large enough to need their own routing treatment. */
  LONG_CONTEXT("long_context"),

  /**
   * Offers provider-side prompt caching.
   *
   * <p>Declarable but <b>not consumed</b>: this gateway implements no caching, and building one is
   * a separate milestone deliberately out of scope. The constant exists so a provider can describe
   * itself completely today and the routing work can consume it later without a snapshot migration.
   * It is recorded honestly as an unconsumed declaration rather than quietly omitted.
   */
  PROMPT_CACHE("cache"),

  /** Accepts asynchronous batch submissions. */
  BATCH("batch"),

  /** Accepts file uploads that later requests can reference. */
  FILES("files"),

  /** Serves a hosted assistants API with server-side state. */
  ASSISTANTS("assistants"),

  /** Can count tokens for a request without executing it. */
  TOKEN_COUNTING("token_counting");

  private static final Map<String, ProviderCapability> BY_TOKEN = index();

  private final String token;

  ProviderCapability(final String token) {
    this.token = token;
  }

  private static Map<String, ProviderCapability> index() {
    final Map<String, ProviderCapability> byToken = new TreeMap<>();
    for (final ProviderCapability capability : values()) {
      byToken.put(capability.token, capability);
    }
    return Map.copyOf(byToken);
  }

  /**
   * The stable wire token published in capability snapshots.
   *
   * @return the token
   */
  public String token() {
    return token;
  }

  /**
   * Resolves a capability from its wire token.
   *
   * <p>Returns empty for an unrecognised token rather than throwing, because a snapshot published
   * by a newer control plane may legitimately name capabilities this node has never heard of. The
   * caller decides what to do; the safe reading is that an uninterpretable capability is one this
   * node cannot honour, so it is dropped rather than assumed.
   *
   * @param token the wire token
   * @return the capability, or empty when the token is unknown
   */
  public static Optional<ProviderCapability> fromToken(final String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_TOKEN.get(token.toLowerCase(Locale.ROOT)));
  }

  /**
   * Resolves a capability from its wire token, refusing an unknown one.
   *
   * @param token the wire token
   * @return the capability
   * @throws IllegalArgumentException when the token names no known capability
   */
  public static ProviderCapability requireToken(final String token) {
    Preconditions.requireNonBlank(token, "token");
    return fromToken(token)
        .orElseThrow(() -> new IllegalArgumentException("unknown capability token: " + token));
  }
}
