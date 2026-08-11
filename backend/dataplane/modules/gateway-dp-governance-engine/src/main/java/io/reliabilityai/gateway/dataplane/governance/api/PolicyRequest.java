package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything governance is allowed to know about a request (Doc 21 §6 {@code GovernanceRequest}).
 *
 * <p>Note what is <em>not</em> here: no prompt, no messages, no completion, no headers, no
 * credentials. The engine decides admission from the request's shape — which model, which region,
 * which tools, how many tokens, how much projected spend — and never from its content. That is not
 * a stylistic choice; it is what lets every decision, violation and audit record be content-free by
 * construction rather than by careful redaction downstream (Doc 14 §7.1, Doc 21 §24).
 *
 * <p>Built through {@link #builder()} because there are two dozen governed attributes and a
 * positional constructor with twenty booleans is a defect waiting to happen — transposing {@code
 * vision} and {@code audio} in a call site would compile, pass review, and quietly govern the wrong
 * thing.
 *
 * <p><b>Some facts can be unknown, and unknown is not zero.</b> Prompt size, declared output
 * ceiling, projected spend and the candidate provider set all come from upstream systems that can
 * fail to answer — nothing counted the prompt, the Cost Engine could not price the model, the
 * capability registry has not published. Those four are therefore optional, and absence is a
 * distinct state from "zero" or "empty".
 *
 * <p>This distinction is load-bearing. A ceiling compared against an assumed zero passes trivially,
 * so a pipeline that silently substituted zero for an uncounted prompt would disable every context,
 * rate and budget ceiling while appearing to enforce them — the exact silent-permit failure GV-INV
 * exists to rule out. An absent quantity instead makes the policies that depend on it
 * unenforceable, and an unenforceable mandatory policy refuses, on the same path an unreadable
 * consumption counter already takes (Doc 21 §42). Governance gets stricter when it is told less,
 * never looser.
 */
public final class PolicyRequest {

  private final RequestContext requestContext;
  private final PrincipalContext principal;
  private final TenantContext tenant;
  private final ScopeChain scopeChain;
  private final CanonicalModelId model;
  private final Region region;
  private final List<String> candidateProviders;
  private final boolean candidateProvidersKnown;
  private final List<String> tools;
  private final List<String> requiredComplianceRegimes;
  private final boolean streaming;
  private final boolean jsonMode;
  private final boolean reasoning;
  private final boolean vision;
  private final boolean imageGeneration;
  private final boolean audio;
  private final boolean embedding;
  private final boolean fineTuning;
  private final boolean batch;
  private final boolean containsPii;
  private final Long contextTokens;
  private final Long outputTokens;
  private final Long projectedCostMicros;

  private PolicyRequest(final Builder builder) {
    this.requestContext = Preconditions.requireNonNull(builder.requestContext, "requestContext");
    this.principal = Preconditions.requireNonNull(builder.principal, "principal");
    this.tenant = Preconditions.requireNonNull(builder.tenant, "tenant");
    this.scopeChain = Preconditions.requireNonNull(builder.scopeChain, "scopeChain");
    this.model = builder.model;
    this.region = Preconditions.requireNonNull(builder.region, "region");
    this.candidateProviders =
        builder.candidateProviders == null
            ? List.of()
            : List.copyOf(sorted(builder.candidateProviders));
    this.candidateProvidersKnown = builder.candidateProviders != null;
    this.tools = List.copyOf(sorted(builder.tools));
    this.requiredComplianceRegimes = List.copyOf(sorted(builder.requiredComplianceRegimes));
    this.streaming = builder.streaming;
    this.jsonMode = builder.jsonMode;
    this.reasoning = builder.reasoning;
    this.vision = builder.vision;
    this.imageGeneration = builder.imageGeneration;
    this.audio = builder.audio;
    this.embedding = builder.embedding;
    this.fineTuning = builder.fineTuning;
    this.batch = builder.batch;
    this.containsPii = builder.containsPii;
    this.contextTokens = requireNonNegativeOrUnknown(builder.contextTokens, "contextTokens");
    this.outputTokens = requireNonNegativeOrUnknown(builder.outputTokens, "outputTokens");
    this.projectedCostMicros =
        requireNonNegativeOrUnknown(builder.projectedCostMicros, "projectedCostMicros");
  }

  private static Long requireNonNegativeOrUnknown(final Long value, final String field) {
    if (value == null) {
      return null;
    }
    Preconditions.requireNonNegative(value, field);
    return value;
  }

  /**
   * Sorts the values into a fresh set. The caller applies {@code List.copyOf} at the field
   * assignment, because SpotBugs judges exposure one frame at a time and cannot see a copy made
   * here.
   *
   * @param values the declared values
   * @return a sorted, still-modifiable set for the caller to freeze
   */
  private static Set<String> sorted(final Set<String> values) {
    return new TreeSet<>(values);
  }

  /**
   * Starts building a request.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The request identity and correlation ids.
   *
   * @return the request context
   */
  public RequestContext requestContext() {
    return requestContext;
  }

  /**
   * The authenticated principal, consumed from C6.
   *
   * @return the principal context
   */
  public PrincipalContext principal() {
    return principal;
  }

  /**
   * The resolved tenant scope.
   *
   * @return the tenant context
   */
  public TenantContext tenant() {
    return tenant;
  }

  /**
   * The hierarchy address this request is governed at.
   *
   * @return the scope chain
   */
  public ScopeChain scopeChain() {
    return scopeChain;
  }

  /**
   * The canonical model addressed, when the request addresses one.
   *
   * <p>Optional because not every admission question names a model. The frozen {@code
   * GovernancePort.authorize} façade carries no model field at all, and a fine-tuning or batch
   * administrative call may legitimately address none. An absent model means the model allow- and
   * deny-lists have nothing to compare against and so contribute no violation — the same treatment
   * every other governed attribute already gets when the request does not use it, rather than a
   * special case. The honest consequence is stated on {@code GovernanceEngine#authorize}: an
   * evaluation that cannot see a model cannot enforce model policy, so a caller needing that must
   * use {@code govern(PolicyRequest)} with the model supplied.
   *
   * @return the model id, or empty when the request addresses no model
   */
  public Optional<CanonicalModelId> model() {
    return Optional.ofNullable(model);
  }

  /**
   * The region the request would execute in.
   *
   * @return the region
   */
  public Region region() {
    return region;
  }

  /**
   * Providers the Router could route this request to, as opaque ids — when the set is known.
   *
   * <p>Governance runs <em>before</em> routing, so this is deliberately the candidate set and never
   * the selected route: the selection does not exist yet. Governance narrows the candidates; the
   * Router then chooses within what governance left (Doc 21 §36.1 RGC-5).
   *
   * <p>Empty and absent mean different things, and conflating them would be a silent bypass. An
   * empty <em>known</em> set means the caller offered no candidates, so a provider allow-list has
   * nothing to exclude. An <em>absent</em> set means the capability registry could not be read, so
   * a provider allow-list cannot be checked at all — and a check that cannot run must refuse, not
   * pass.
   *
   * @return the candidate provider ids sorted, or empty when the candidate set is unknown
   */
  public Optional<List<String>> candidateProviders() {
    return candidateProvidersKnown ? Optional.of(candidateProviders) : Optional.empty();
  }

  /**
   * Canonical tools the request may invoke.
   *
   * @return the tool ids, sorted
   */
  public List<String> tools() {
    return tools;
  }

  /**
   * Compliance regimes the execution path must satisfy.
   *
   * @return the regime ids, sorted
   */
  public List<String> requiredComplianceRegimes() {
    return requiredComplianceRegimes;
  }

  /**
   * Whether the response is to be streamed.
   *
   * @return {@code true} when streaming was requested
   */
  public boolean streaming() {
    return streaming;
  }

  /**
   * Whether a response schema is pinned.
   *
   * @return {@code true} when JSON mode was requested
   */
  public boolean jsonMode() {
    return jsonMode;
  }

  /**
   * Whether extended reasoning was requested.
   *
   * @return {@code true} when reasoning was requested
   */
  public boolean reasoning() {
    return reasoning;
  }

  /**
   * Whether image inputs are present.
   *
   * @return {@code true} when the request carries image input
   */
  public boolean vision() {
    return vision;
  }

  /**
   * Whether image generation was requested.
   *
   * @return {@code true} when the request generates images
   */
  public boolean imageGeneration() {
    return imageGeneration;
  }

  /**
   * Whether audio input or output is involved.
   *
   * @return {@code true} when the request involves audio
   */
  public boolean audio() {
    return audio;
  }

  /**
   * Whether this is an embedding request.
   *
   * @return {@code true} for embedding requests
   */
  public boolean embedding() {
    return embedding;
  }

  /**
   * Whether this is a fine-tuning operation.
   *
   * @return {@code true} for fine-tuning operations
   */
  public boolean fineTuning() {
    return fineTuning;
  }

  /**
   * Whether this is a batch submission.
   *
   * @return {@code true} for batch submissions
   */
  public boolean batch() {
    return batch;
  }

  /**
   * Whether the caller classified this request as carrying personal data.
   *
   * @return {@code true} when the request is PII-classified
   */
  public boolean containsPii() {
    return containsPii;
  }

  /**
   * Input size in tokens, counted upstream — when it is known.
   *
   * <p>Optional because governance must be able to tell "this prompt is 400 tokens" from "nobody
   * counted". Treating an uncounted prompt as zero would make every context and rate ceiling pass
   * trivially, which is indistinguishable from having no ceiling at all. Absent therefore refuses
   * any mandatory policy that depends on it, exactly as an unreadable consumption counter does (Doc
   * 21 §42).
   *
   * @return the context token count, or empty when nothing counted it
   */
  public OptionalLong contextTokens() {
    return optional(contextTokens);
  }

  /**
   * The completion ceiling the caller declared — when one was declared.
   *
   * <p>The caller's declared maximum, not a prediction of what the model will emit. That is the
   * only honest input available before the provider runs, and it is the same bound the Cost Engine
   * projects against (Doc 22 §23.1).
   *
   * @return the declared output ceiling, or empty when the caller declared none
   */
  public OptionalLong outputTokens() {
    return optional(outputTokens);
  }

  /**
   * Normalized spend this request would add — when the Cost Engine could project it.
   *
   * <p>Governance never computes cost (GV-D6). This is the Cost Engine's never-underestimated upper
   * bound, so a budget check against it can refuse an affordable request but can never admit an
   * unaffordable one. Absent means the Cost Engine failed closed — unknown pricing, unbounded
   * output — and an unpriceable request cannot be checked against a budget, so it is refused.
   *
   * @return the projected spend in micros, or empty when it could not be projected
   */
  public OptionalLong projectedCostMicros() {
    return optional(projectedCostMicros);
  }

  private static OptionalLong optional(final Long value) {
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /** The quantities a request may or may not know, for {@link Builder#quantity}. */
  public enum Quantity {
    /** The counted prompt size. */
    CONTEXT_TOKENS,
    /** The caller's declared completion ceiling. */
    OUTPUT_TOKENS,
    /** The Cost Engine's projected spend. */
    PROJECTED_COST_MICROS
  }

  /** Collects the governed attributes of one request. */
  public static final class Builder {

    private RequestContext requestContext;
    private PrincipalContext principal;
    private TenantContext tenant;
    private ScopeChain scopeChain;
    private CanonicalModelId model;
    private Region region;
    private Set<String> candidateProviders;
    private Set<String> tools = Set.of();
    private Set<String> requiredComplianceRegimes = Set.of();
    private boolean streaming;
    private boolean jsonMode;
    private boolean reasoning;
    private boolean vision;
    private boolean imageGeneration;
    private boolean audio;
    private boolean embedding;
    private boolean fineTuning;
    private boolean batch;
    private boolean containsPii;
    private Long contextTokens;
    private Long outputTokens;
    private Long projectedCostMicros;

    private Builder() {}

    /**
     * Sets the request context.
     *
     * @param value the request context
     * @return this builder
     */
    public Builder requestContext(final RequestContext value) {
      this.requestContext = value;
      return this;
    }

    /**
     * Sets the authenticated principal.
     *
     * @param value the principal context
     * @return this builder
     */
    public Builder principal(final PrincipalContext value) {
      this.principal = value;
      return this;
    }

    /**
     * Sets the tenant context.
     *
     * @param value the tenant context
     * @return this builder
     */
    public Builder tenant(final TenantContext value) {
      this.tenant = value;
      return this;
    }

    /**
     * Sets the hierarchy address.
     *
     * @param value the scope chain
     * @return this builder
     */
    public Builder scopeChain(final ScopeChain value) {
      this.scopeChain = value;
      return this;
    }

    /**
     * Sets the addressed model.
     *
     * @param value the canonical model id
     * @return this builder
     */
    public Builder model(final CanonicalModelId value) {
      this.model = value;
      return this;
    }

    /**
     * Sets the execution region.
     *
     * @param value the region
     * @return this builder
     */
    public Builder region(final Region value) {
      this.region = value;
      return this;
    }

    /**
     * Declares the candidate providers as known. An empty set is a legitimate known answer.
     *
     * @param value the opaque provider ids
     * @return this builder
     */
    public Builder candidateProviders(final Set<String> value) {
      this.candidateProviders = Preconditions.requireNonNull(value, "candidateProviders");
      return this;
    }

    /**
     * Declares the candidate set unknown, so provider policy cannot be evaluated and refuses.
     *
     * @return this builder
     */
    public Builder unknownCandidateProviders() {
      this.candidateProviders = null;
      return this;
    }

    /**
     * Sets the requested tools.
     *
     * @param value the canonical tool ids
     * @return this builder
     */
    public Builder tools(final Set<String> value) {
      this.tools = Preconditions.requireNonNull(value, "tools");
      return this;
    }

    /**
     * Sets the compliance regimes the request must satisfy.
     *
     * @param value the regime ids
     * @return this builder
     */
    public Builder requiredComplianceRegimes(final Set<String> value) {
      this.requiredComplianceRegimes =
          Preconditions.requireNonNull(value, "requiredComplianceRegimes");
      return this;
    }

    /**
     * Marks the request as streaming.
     *
     * @param value whether streaming was requested
     * @return this builder
     */
    public Builder streaming(final boolean value) {
      this.streaming = value;
      return this;
    }

    /**
     * Marks the request as schema-pinned.
     *
     * @param value whether JSON mode was requested
     * @return this builder
     */
    public Builder jsonMode(final boolean value) {
      this.jsonMode = value;
      return this;
    }

    /**
     * Marks the request as using extended reasoning.
     *
     * @param value whether reasoning was requested
     * @return this builder
     */
    public Builder reasoning(final boolean value) {
      this.reasoning = value;
      return this;
    }

    /**
     * Marks the request as carrying image input.
     *
     * @param value whether vision input is present
     * @return this builder
     */
    public Builder vision(final boolean value) {
      this.vision = value;
      return this;
    }

    /**
     * Marks the request as generating images.
     *
     * @param value whether image generation was requested
     * @return this builder
     */
    public Builder imageGeneration(final boolean value) {
      this.imageGeneration = value;
      return this;
    }

    /**
     * Marks the request as involving audio.
     *
     * @param value whether audio is involved
     * @return this builder
     */
    public Builder audio(final boolean value) {
      this.audio = value;
      return this;
    }

    /**
     * Marks the request as an embedding call.
     *
     * @param value whether this is an embedding request
     * @return this builder
     */
    public Builder embedding(final boolean value) {
      this.embedding = value;
      return this;
    }

    /**
     * Marks the request as a fine-tuning operation.
     *
     * @param value whether this is fine-tuning
     * @return this builder
     */
    public Builder fineTuning(final boolean value) {
      this.fineTuning = value;
      return this;
    }

    /**
     * Marks the request as a batch submission.
     *
     * @param value whether this is a batch submission
     * @return this builder
     */
    public Builder batch(final boolean value) {
      this.batch = value;
      return this;
    }

    /**
     * Marks the request as PII-classified.
     *
     * @param value whether the request carries personal data
     * @return this builder
     */
    public Builder containsPii(final boolean value) {
      this.containsPii = value;
      return this;
    }

    /**
     * Sets the counted input size. Leaving it unset means "nobody counted", which refuses any
     * mandatory policy that depends on it.
     *
     * @param value the context token count
     * @return this builder
     */
    public Builder contextTokens(final long value) {
      this.contextTokens = value;
      return this;
    }

    /**
     * Sets the caller's declared completion ceiling. Leaving it unset means the caller declared
     * none.
     *
     * @param value the output token ceiling asked for
     * @return this builder
     */
    public Builder outputTokens(final long value) {
      this.outputTokens = value;
      return this;
    }

    /**
     * Sets the Cost Engine's projected spend. Leaving it unset means the projection failed closed.
     *
     * @param value the projected spend in micros
     * @return this builder
     */
    public Builder projectedCostMicros(final long value) {
      this.projectedCostMicros = value;
      return this;
    }

    /**
     * Sets a quantity that may or may not be known, so a caller assembling from an upstream that
     * returns {@link OptionalLong} does not have to branch.
     *
     * @param target which quantity to set
     * @param value the value, or empty when unknown
     * @return this builder
     */
    public Builder quantity(final Quantity target, final OptionalLong value) {
      final Long boxed = value.isPresent() ? value.getAsLong() : null;
      switch (target) {
        case CONTEXT_TOKENS -> this.contextTokens = boxed;
        case OUTPUT_TOKENS -> this.outputTokens = boxed;
        case PROJECTED_COST_MICROS -> this.projectedCostMicros = boxed;
      }
      return this;
    }

    /**
     * Freezes the request.
     *
     * @return the immutable governance question
     */
    public PolicyRequest build() {
      return new PolicyRequest(this);
    }
  }
}
