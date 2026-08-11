package io.reliabilityai.gateway.dataplane.app.runtime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.ConfigSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.app.binding.CharacterBoundTokenEstimator;
import io.reliabilityai.gateway.dataplane.app.binding.ClaimBasedScopeResolver;
import io.reliabilityai.gateway.dataplane.app.binding.GovernanceScopeResolver;
import io.reliabilityai.gateway.dataplane.app.binding.PromptTokenEstimator;
import io.reliabilityai.gateway.dataplane.app.binding.SnapshotCapabilitySource;
import io.reliabilityai.gateway.dataplane.auth.jwt.JwtAuthenticationConfig;
import io.reliabilityai.gateway.dataplane.cost.domain.ContractEntitlement;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingSnapshot;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalCodec;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalConfig;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryBackoff;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryPolicy;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.api.EntitlementSnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.FeatureFlagSnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAudit;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.UsageStatePort;
import io.reliabilityai.gateway.dataplane.ingress.IngressConfig;
import io.reliabilityai.gateway.dataplane.metering.api.MeteringPolicy;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageDescriptor;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginSignaturePort;
import io.reliabilityai.gateway.dataplane.plugin.api.VettedPluginSnapshotPort;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModule;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicy;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistrySnapshot;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicy;
import io.reliabilityai.gateway.dataplane.schema.validator.api.SchemaValidatorConfig;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPolicy;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import io.reliabilityai.gateway.ports.ClockPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Immutable inputs to the single-VPS {@link GatewayRuntime} (AD-020) — every value supplied
 * explicitly by the operator, none discovered. Deterministic: no field is derived from a wall clock
 * or randomness, so two nodes given the same config initialize identically.
 *
 * <p>Grouped by concern so the composition reads as a pipeline rather than a flat argument list.
 * The externally-supplied adapters live in {@link ExternalAdapters}: those are the seams whose
 * production implementations are outside this repository, and what is present there decides which
 * mandatory pipeline stages the startup validator can consider bound.
 *
 * <p><b>Ownership:</b> {@code masterKey} ownership transfers to the runtime, which zeroizes it on
 * stop (Doc 26 §17.1) — the caller must not reuse the array afterwards.
 *
 * @param nodeId the stable per-node identifier (deterministic, unique event ids)
 * @param configSnapshot the C15-validated configuration snapshot to load as last-known-good
 * @param masterKey the AES-128/256 secrets master key (ownership transferred; zeroized on stop)
 * @param clock the injected clock — the single source of time for every module
 * @param eventing the durable eventing spine (WAL, broker, dead-letter, topics)
 * @param authn the verification-key and tenant-scope snapshots
 * @param authentication the JWT verification policy — absent leaves AUTHN to an operator-supplied
 *     verifier, and unbound if none is supplied
 * @param governance the policy/entitlement/usage snapshot readers backing the PEP — absent leaves
 *     the GOVERNANCE stage unbound, so the node fails closed at the activation gate rather than
 *     serving ungoverned traffic
 * @param provider the provider transport, capability and credential wiring — absent leaves the
 *     ADAPTER and RELIABILITY stages unbound
 * @param ingress the HTTP front door wiring — absent leaves the INGRESS stage to an
 *     operator-supplied {@link IngressLifecycle}, and unbound if none is supplied
 * @param routing the capability snapshot and routing policy inputs
 * @param reliability the retry/failover policy and node-local budget inputs
 * @param accounting the metering and cost inputs
 * @param correctness the schema-lock and stream-integrity policy inputs
 * @param plugins the sandboxed plugin-runtime inputs, or empty when no plugins are configured
 * @param secrets the credential-materialization inputs
 * @param externalAdapters the operator-supplied adapters for seams outside this repository
 */
public record GatewayRuntimeConfig(
    String nodeId,
    ConfigSnapshot configSnapshot,
    byte[] masterKey,
    ClockPort clock,
    EventingConfig eventing,
    AuthnConfig authn,
    Optional<AuthenticationConfig> authentication,
    Optional<GovernanceConfig> governance,
    Optional<ProviderConfig> provider,
    Optional<IngressConfig> ingress,
    Optional<PluginConfig> plugins,
    RoutingConfig routing,
    ReliabilityConfig reliability,
    AccountingConfig accounting,
    CorrectnessConfig correctness,
    SecretsConfig secrets,
    ExternalAdapters externalAdapters) {

  /**
   * Validates required wiring (no defensive copy of {@code masterKey}: ownership transfers).
   *
   * <p>The suppression is the narrowest form available and is not a licence to silence exposure
   * warnings generally — it is the one place in this repository where copying would be the bug.
   * {@code masterKey} ownership transfers to the runtime, which zeroizes the array on stop. A
   * defensive copy would give the runtime its own array to wipe while the caller's original key
   * bytes stayed live on the heap until GC, which is precisely the exposure zeroization exists to
   * prevent. Every other component of this record is copied normally.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "masterKey ownership transfers to the runtime, which zeroizes it on stop; copying it"
              + " would leave un-zeroized key material in the caller's heap. Doc 26 §17.1.")
  public GatewayRuntimeConfig {
    Preconditions.requireNonBlank(nodeId, "nodeId");
    Preconditions.requireNonNull(configSnapshot, "configSnapshot");
    Preconditions.requireNonNull(masterKey, "masterKey");
    Preconditions.requireNonNull(clock, "clock");
    Preconditions.requireNonNull(eventing, "eventing");
    Preconditions.requireNonNull(authn, "authn");
    Preconditions.requireNonNull(authentication, "authentication");
    Preconditions.requireNonNull(governance, "governance");
    Preconditions.requireNonNull(provider, "provider");
    Preconditions.requireNonNull(ingress, "ingress");
    Preconditions.requireNonNull(plugins, "plugins");
    Preconditions.requireNonNull(routing, "routing");
    Preconditions.requireNonNull(reliability, "reliability");
    Preconditions.requireNonNull(accounting, "accounting");
    Preconditions.requireNonNull(correctness, "correctness");
    Preconditions.requireNonNull(secrets, "secrets");
    Preconditions.requireNonNull(externalAdapters, "externalAdapters");
  }

  /**
   * The secrets master key, returned by reference.
   *
   * <p>Deliberately not copied, for the reason given on the constructor: the runtime owns this
   * array and zeroizes it on stop, and handing out copies would scatter un-zeroizable key material
   * across the heap. Callers must not retain or mutate it.
   *
   * @return the master key array itself, not a copy
   */
  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The runtime owns and zeroizes this array; returning a copy would create key material"
              + " nothing zeroizes. Doc 26 §17.1.")
  public byte[] masterKey() {
    return masterKey;
  }

  /**
   * The durable eventing spine: node-local WAL, in-process broker, dead-letter log and the topics
   * every accounting/audit sink publishes to (Doc 24).
   *
   * @param walDirectory the node-local WAL directory
   * @param walConfig the WAL rotation + fsync configuration
   * @param walCodec the WAL payload byte-codec
   * @param deadLetterFile the node-local dead-letter log path
   * @param brokerCapacity the in-process broker queue capacity (bounded backpressure)
   * @param brokerOfferTimeout the max backpressure wait before a send fails closed
   * @param brokerSubscriber the in-process consumer of delivered events
   * @param retryPolicy the bounded broker-send retry policy
   * @param retryBackoff the between-attempt backoff
   * @param topics the destination topic names
   */
  public record EventingConfig(
      Path walDirectory,
      WalConfig walConfig,
      WalCodec walCodec,
      Path deadLetterFile,
      int brokerCapacity,
      Duration brokerOfferTimeout,
      Consumer<BrokerRecord> brokerSubscriber,
      RetryPolicy retryPolicy,
      RetryBackoff retryBackoff,
      Topics topics) {

    /** Validates the eventing wiring. */
    public EventingConfig {
      Preconditions.requireNonNull(walDirectory, "walDirectory");
      Preconditions.requireNonNull(walConfig, "walConfig");
      Preconditions.requireNonNull(walCodec, "walCodec");
      Preconditions.requireNonNull(deadLetterFile, "deadLetterFile");
      if (brokerCapacity < 1) {
        throw new IllegalArgumentException("brokerCapacity must be >= 1");
      }
      Preconditions.requireNonNull(brokerOfferTimeout, "brokerOfferTimeout");
      Preconditions.requireNonNull(brokerSubscriber, "brokerSubscriber");
      Preconditions.requireNonNull(retryPolicy, "retryPolicy");
      Preconditions.requireNonNull(retryBackoff, "retryBackoff");
      Preconditions.requireNonNull(topics, "topics");
    }
  }

  /**
   * Destination topics for the durable sinks. Named explicitly so an operator can route accounting
   * and audit streams to different consumers without touching code.
   *
   * @param authAudit the authentication-decision audit topic
   * @param secretsAudit the credential-materialization audit topic
   * @param usageJournal the durable per-attempt usage journal topic
   * @param usageLedger the accounting usage-ledger topic
   * @param costFact the priced cost-fact (billing) topic
   */
  public record Topics(
      String authAudit,
      String secretsAudit,
      String usageJournal,
      String usageLedger,
      String costFact) {

    /** Validates every topic is named. */
    public Topics {
      Preconditions.requireNonBlank(authAudit, "authAudit");
      Preconditions.requireNonBlank(secretsAudit, "secretsAudit");
      Preconditions.requireNonBlank(usageJournal, "usageJournal");
      Preconditions.requireNonBlank(usageLedger, "usageLedger");
      Preconditions.requireNonBlank(costFact, "costFact");
    }

    /**
     * The conventional topic names for a single-VPS deployment.
     *
     * @return the default topic set
     */
    public static Topics defaults() {
      return new Topics(
          "gateway.audit.authn",
          "gateway.audit.secrets",
          "gateway.usage.journal",
          "gateway.usage.ledger",
          "gateway.cost.fact");
    }
  }

  /**
   * Authentication inputs (Doc 37). Both snapshots carry their own version, which is what a pinned
   * request resolves against — key rotation is a snapshot republish, never a live fetch on the
   * request path.
   *
   * @param keySnapshot the immutable JWS verification-key snapshot
   * @param tenantSnapshot the immutable principal → tenant-scope mapping snapshot
   */
  public record AuthnConfig(
      VerificationKeySnapshot keySnapshot, TenantScopeSnapshot tenantSnapshot) {

    /** Validates the authentication wiring. */
    public AuthnConfig {
      Preconditions.requireNonNull(keySnapshot, "keySnapshot");
      Preconditions.requireNonNull(tenantSnapshot, "tenantSnapshot");
    }
  }

  /**
   * Governance inputs (Doc 21): the cached snapshot readers the Policy Enforcement Point evaluates.
   *
   * <p>All five are constructor dependencies of the engine — supplied explicitly, never located or
   * discovered. The engine consumes these snapshots and never authors, fetches or mutates policy.
   *
   * <p>The clock is deliberately <b>not</b> repeated here: the runtime passes its single top-level
   * {@code clock} to the engine, so governance decisions and every other module share one source of
   * time. Two clocks would make a decision's recorded timestamp disagree with the request it
   * governed.
   *
   * @param policySnapshots the tenant policy snapshot reader
   * @param entitlementSnapshots the quota/budget ceiling reader
   * @param usageStates the bounded-staleness usage counter reader
   * @param featureFlags the flag-state reader
   * @param auditSink the governance decision audit sink
   * @param usageStalenessTolerance how old a usage reading may be and still be enforced against
   */
  /**
   * The Sandboxed Plugin Runtime's wiring (Doc 28, C12 data-plane presence).
   *
   * <p>Optional: a node with no plugin config runs no plugins, which is the correct default for a
   * Tier-0 host. Every field is required when it is present — there is no partially-wired plugin
   * runtime, because a missing signature verifier or a missing authorization port would each be a
   * silently-opened gate.
   *
   * <p>{@code authorization} is the operator's answer to "which tenants may run which plugins" (Doc
   * 28 §36.1). It is a separate question from the request-level Policy Enforcement Point, and it
   * has to be, because a plugin bound at {@code CLASSIFICATION} runs before that decision exists.
   * Supplying {@link
   * io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort#DENY_ALL} is the safe
   * default and makes the choice visible here rather than implicit.
   *
   * @param signatures the verify-only supply-chain gate, rooted in a pinned trust anchor (Doc 28
   *     §STC)
   * @param authorization the deny-by-default plugin authorization gate
   * @param snapshots the C12 vetted-snapshot feed, or empty when plugins are registered directly
   * @param systemScope the tenant scope node-level lifecycle actions are attributed to
   * @param maxPlugins the ceiling on bound plugins
   * @param maxConcurrentInvocations the ceiling on simultaneous in-flight plugin invocations
   * @param toolCostMicros the operator-declared cost of one tool invocation
   * @param processIsolated whether untrusted plugins run behind the OS-process boundary (Doc 28
   *     ISO-1)
   * @param extensionPointBudget the wall-clock budget shared by all plugins at one extension point
   */
  public record PluginConfig(
      PluginSignaturePort signatures,
      PluginAuthorizationPort authorization,
      Optional<VettedPluginSnapshotPort> snapshots,
      TenantScope systemScope,
      int maxPlugins,
      int maxConcurrentInvocations,
      long toolCostMicros,
      boolean processIsolated,
      Duration extensionPointBudget) {

    /**
     * The default wall-clock budget for all plugins at one extension point.
     *
     * <p>Five points on the request path means the worst case is five times this, so it is set well
     * below anything a caller would notice. Plugins are advisory; a slow one must cost latency,
     * never correctness, and the dispatcher additionally clamps this to the request's own deadline
     * so a plugin can never push a request past the deadline the caller was promised.
     */
    public static final Duration DEFAULT_EXTENSION_POINT_BUDGET = Duration.ofMillis(50);

    /** Validates the plugin wiring. */
    public PluginConfig {
      Preconditions.requireNonNull(extensionPointBudget, "extensionPointBudget");
      if (extensionPointBudget.isNegative() || extensionPointBudget.isZero()) {
        throw new IllegalArgumentException("extensionPointBudget must be positive");
      }
      Preconditions.requireNonNull(signatures, "signatures");
      Preconditions.requireNonNull(authorization, "authorization");
      Preconditions.requireNonNull(snapshots, "snapshots");
      Preconditions.requireNonNull(systemScope, "systemScope");
      if (maxPlugins < 1) {
        throw new IllegalArgumentException("maxPlugins must be at least 1");
      }
      if (maxConcurrentInvocations < 1) {
        throw new IllegalArgumentException("maxConcurrentInvocations must be at least 1");
      }
      Preconditions.requireNonNegative(toolCostMicros, "toolCostMicros");
    }

    /**
     * Wires plugins with the default per-point budget.
     *
     * <p>Retained so adding the budget did not break existing compositions.
     *
     * @param signatures the supply-chain verifier
     * @param authorization the deny-by-default authorization gate
     * @param snapshots the vetted-snapshot feed
     * @param systemScope the tenant node-level lifecycle actions are attributed to
     * @param maxPlugins the ceiling on bound plugins
     * @param maxConcurrentInvocations the ceiling on in-flight invocations
     * @param toolCostMicros the operator-declared cost of one tool invocation
     * @param processIsolated whether untrusted plugins run behind the process boundary
     */
    public PluginConfig(
        final PluginSignaturePort signatures,
        final PluginAuthorizationPort authorization,
        final Optional<VettedPluginSnapshotPort> snapshots,
        final TenantScope systemScope,
        final int maxPlugins,
        final int maxConcurrentInvocations,
        final long toolCostMicros,
        final boolean processIsolated) {
      this(
          signatures,
          authorization,
          snapshots,
          systemScope,
          maxPlugins,
          maxConcurrentInvocations,
          toolCostMicros,
          processIsolated,
          DEFAULT_EXTENSION_POINT_BUDGET);
    }
  }

  /**
   * Governance inputs (Doc 21): the cached snapshot readers the Policy Enforcement Point evaluates.
   *
   * @param policySnapshots the tenant policy snapshot reader
   * @param entitlementSnapshots the quota/budget ceiling reader
   * @param usageStates the bounded-staleness usage counter reader
   * @param featureFlags the flag-state reader
   * @param auditSink the governance decision audit sink
   * @param usageStalenessTolerance how old a usage reading may be and still be enforced against
   * @param policyEngine the hierarchical policy-engine inputs — when present, the node's Policy
   *     Enforcement Point is the full governance engine rather than the single-tenant evaluator
   * @param admission the admission-control inputs
   */
  public record GovernanceConfig(
      PolicySnapshotPort policySnapshots,
      EntitlementSnapshotPort entitlementSnapshots,
      UsageStatePort usageStates,
      FeatureFlagSnapshotPort featureFlags,
      AuditSinkPort auditSink,
      Duration usageStalenessTolerance,
      Optional<PolicyEngineConfig> policyEngine,
      AdmissionConfig admission) {

    /** Validates the governance wiring. */
    public GovernanceConfig {
      Preconditions.requireNonNull(admission, "admission");
      Preconditions.requireNonNull(policySnapshots, "policySnapshots");
      Preconditions.requireNonNull(entitlementSnapshots, "entitlementSnapshots");
      Preconditions.requireNonNull(usageStates, "usageStates");
      Preconditions.requireNonNull(featureFlags, "featureFlags");
      Preconditions.requireNonNull(auditSink, "auditSink");
      Preconditions.requireNonNull(usageStalenessTolerance, "usageStalenessTolerance");
      Preconditions.requireNonNull(policyEngine, "policyEngine");
      if (usageStalenessTolerance.isNegative()) {
        throw new IllegalArgumentException("usageStalenessTolerance must not be negative");
      }
    }

    /**
     * Wires governance without the hierarchical policy engine, leaving the node on the
     * single-tenant snapshot evaluator. Kept so that adding the policy engine was not a breaking
     * change for every existing composition.
     *
     * @param policySnapshots the tenant policy snapshot reader
     * @param entitlementSnapshots the quota/budget ceiling reader
     * @param usageStates the bounded-staleness usage counter reader
     * @param featureFlags the flag-state reader
     * @param auditSink the governance decision audit sink
     * @param usageStalenessTolerance how old a usage reading may be and still be enforced against
     */
    public GovernanceConfig(
        final PolicySnapshotPort policySnapshots,
        final EntitlementSnapshotPort entitlementSnapshots,
        final UsageStatePort usageStates,
        final FeatureFlagSnapshotPort featureFlags,
        final AuditSinkPort auditSink,
        final Duration usageStalenessTolerance) {
      this(
          policySnapshots,
          entitlementSnapshots,
          usageStates,
          featureFlags,
          auditSink,
          usageStalenessTolerance,
          Optional.empty(),
          AdmissionConfig.defaults());
    }

    /**
     * Wires governance with a policy engine but default admission assembly.
     *
     * @param policySnapshots the tenant policy snapshot reader
     * @param entitlementSnapshots the quota/budget ceiling reader
     * @param usageStates the bounded-staleness usage counter reader
     * @param featureFlags the flag-state reader
     * @param auditSink the governance decision audit sink
     * @param usageStalenessTolerance how old a usage reading may be and still be enforced against
     * @param policyEngine the hierarchical policy-engine inputs
     */
    public GovernanceConfig(
        final PolicySnapshotPort policySnapshots,
        final EntitlementSnapshotPort entitlementSnapshots,
        final UsageStatePort usageStates,
        final FeatureFlagSnapshotPort featureFlags,
        final AuditSinkPort auditSink,
        final Duration usageStalenessTolerance,
        final Optional<PolicyEngineConfig> policyEngine) {
      this(
          policySnapshots,
          entitlementSnapshots,
          usageStates,
          featureFlags,
          auditSink,
          usageStalenessTolerance,
          policyEngine,
          AdmissionConfig.defaults());
    }
  }

  /**
   * How the runtime assembles the admission question governance evaluates.
   *
   * <p>Both seams here answer questions the gateway cannot answer for itself. How many tokens a
   * prompt is depends on a tokenizer this gateway does not ship; which API key or principal node a
   * request hangs off depends on what an issuer calls its claims. Making both explicit
   * configuration means a deployment states its answer rather than inheriting one the gateway
   * inferred.
   *
   * @param tokenEstimator bounds prompt size; must never under-count, and may answer "unknown"
   * @param scopeResolver maps the authenticated identity onto the governance hierarchy
   */
  public record AdmissionConfig(
      PromptTokenEstimator tokenEstimator, GovernanceScopeResolver scopeResolver) {

    /**
     * The per-message token allowance the default estimator adds for chat-template markers — the
     * role tags and separators a provider wraps each message in, which no caller-supplied character
     * accounts for. Eight is comfortably above any current provider's template; an operator whose
     * provider is more verbose should raise it, because the bound is only safe while it is not
     * exceeded.
     */
    public static final long DEFAULT_PER_MESSAGE_TOKEN_OVERHEAD = 8L;

    /** Validates the admission wiring. */
    public AdmissionConfig {
      Preconditions.requireNonNull(tokenEstimator, "tokenEstimator");
      Preconditions.requireNonNull(scopeResolver, "scopeResolver");
    }

    /**
     * The starting configuration: a character-bound token estimator and tenant-only scoping.
     *
     * <p>Conservative on both counts. The estimator over-counts rather than risking a ceiling that
     * never fires, and the resolver attaches no key or principal node rather than guessing which
     * claim carries one. A deployment should replace both once it knows its tokenizer and its claim
     * names.
     *
     * @return the default admission wiring
     */
    public static AdmissionConfig defaults() {
      return new AdmissionConfig(
          new CharacterBoundTokenEstimator(DEFAULT_PER_MESSAGE_TOKEN_OVERHEAD),
          ClaimBasedScopeResolver.tenantOnly());
    }
  }

  /**
   * Hierarchical policy-engine inputs (Doc 21 GV-D2/GV-D3).
   *
   * <p>Supplying this makes the node's Policy Enforcement Point the full governance engine: policy
   * attached anywhere from the global node down to an individual API key, merged
   * most-restrictive-wins, compiled once at install time and evaluated per request against an
   * atomically-swappable snapshot.
   *
   * <p>The clock is deliberately not repeated here — the runtime passes its single top-level clock,
   * so a governance decision's timestamp agrees with every other module's view of time. The ticker
   * is a separate seam because a duration measured on a wall clock can come out negative when NTP
   * steps.
   *
   * @param source where authored policy bundles are read from; the engine consumes, never authors
   * @param usage the bounded-staleness consumption reader, keyed by hierarchy node
   * @param audit the decision audit sink — every decision, admissions included
   * @param metrics the governance counters
   * @param ticker the monotonic ticker used to measure evaluation cost
   * @param usageStalenessTolerance how old a consumption reading may be and still be enforced
   *     against
   * @param contextTtl how long an admitted request's resolved constraints stay valid before the
   *     pipeline must re-govern
   * @param cacheCapacity the per-generation bound on memoised scope-chain folds
   * @param rollbackDepth how many superseded generations stay available to roll back to
   * @param loadOnStart whether to pull and install a generation during startup
   */
  public record PolicyEngineConfig(
      PolicySourcePort source,
      PolicyUsagePort usage,
      PolicyAudit audit,
      PolicyMetrics metrics,
      TickerPort ticker,
      Duration usageStalenessTolerance,
      Duration contextTtl,
      int cacheCapacity,
      int rollbackDepth,
      boolean loadOnStart) {

    /** Validates the policy-engine wiring. */
    public PolicyEngineConfig {
      Preconditions.requireNonNull(source, "source");
      Preconditions.requireNonNull(usage, "usage");
      Preconditions.requireNonNull(audit, "audit");
      Preconditions.requireNonNull(metrics, "metrics");
      Preconditions.requireNonNull(ticker, "ticker");
      Preconditions.requireNonNull(usageStalenessTolerance, "usageStalenessTolerance");
      Preconditions.requireNonNull(contextTtl, "contextTtl");
      if (usageStalenessTolerance.isNegative() || usageStalenessTolerance.isZero()) {
        throw new IllegalArgumentException("usageStalenessTolerance must be positive");
      }
      if (contextTtl.isNegative() || contextTtl.isZero()) {
        throw new IllegalArgumentException("contextTtl must be positive");
      }
      if (cacheCapacity <= 0) {
        throw new IllegalArgumentException("cacheCapacity must be positive");
      }
      if (rollbackDepth < 0) {
        throw new IllegalArgumentException("rollbackDepth must not be negative");
      }
    }
  }

  /**
   * JWT authentication inputs (Doc 37).
   *
   * <p>{@code localPublicKey} is not repeated here: the verification keys an operator pins locally
   * already travel in {@link AuthnConfig#keySnapshot()}, and a second source of local keys would
   * make it ambiguous which one revocation applies to. The JWKS endpoint is an <em>additional</em>
   * source, consulted only for key ids the snapshot does not carry.
   *
   * @param jwt the verification policy — issuer, audience, algorithms, skew, token ceiling
   * @param jwksUri the JWKS endpoint, or empty to verify against snapshot keys only
   * @param jwksCacheTtl how long a fetched JWKS generation stays usable
   * @param jwksConnectTimeout the JWKS connect budget
   * @param jwksRequestTimeout the JWKS request budget
   */
  public record AuthenticationConfig(
      JwtAuthenticationConfig jwt,
      Optional<URI> jwksUri,
      Duration jwksCacheTtl,
      Duration jwksConnectTimeout,
      Duration jwksRequestTimeout) {

    /** Validates the authentication wiring. */
    public AuthenticationConfig {
      Preconditions.requireNonNull(jwt, "jwt");
      Preconditions.requireNonNull(jwksUri, "jwksUri");
      Preconditions.requireNonNull(jwksCacheTtl, "jwksCacheTtl");
      Preconditions.requireNonNull(jwksConnectTimeout, "jwksConnectTimeout");
      Preconditions.requireNonNull(jwksRequestTimeout, "jwksRequestTimeout");
      if (jwksCacheTtl.isZero() || jwksCacheTtl.isNegative()) {
        throw new IllegalArgumentException("jwksCacheTtl must be positive");
      }
    }

    /**
     * Snapshot-only authentication: no JWKS endpoint.
     *
     * @param jwt the verification policy
     * @return the configuration
     */
    public static AuthenticationConfig snapshotOnly(final JwtAuthenticationConfig jwt) {
      return new AuthenticationConfig(
          jwt,
          Optional.empty(),
          Duration.ofMinutes(15),
          Duration.ofSeconds(5),
          Duration.ofSeconds(10));
    }
  }

  /**
   * Provider transport inputs (Doc 25). Immutable; nothing here is read from the environment.
   *
   * @param endpoint the provider API root
   * @param apiVersion the pinned provider API version recorded on every request
   * @param organization the provider organisation header value, or {@code null}
   * @param projectId the provider project identifier, or {@code null}
   * @param connectTimeout the TCP/TLS connect budget
   * @param readTimeout the default per-request read budget
   * @param httpVersion the negotiated HTTP version
   * @param maxConnections the intended connection-pool ceiling
   * @param tlsRequired whether a non-TLS endpoint is refused at startup
   * @param compressionEnabled whether to request compressed responses
   */
  public record ProviderTransportConfig(
      URI endpoint,
      PinnedVersion apiVersion,
      String organization,
      String projectId,
      Duration connectTimeout,
      Duration readTimeout,
      HttpClient.Version httpVersion,
      int maxConnections,
      boolean tlsRequired,
      boolean compressionEnabled) {

    /** Validates the transport wiring, refusing a plaintext endpoint when TLS is required. */
    public ProviderTransportConfig {
      Preconditions.requireNonNull(endpoint, "endpoint");
      Preconditions.requireNonNull(apiVersion, "apiVersion");
      Preconditions.requireNonNull(connectTimeout, "connectTimeout");
      Preconditions.requireNonNull(readTimeout, "readTimeout");
      Preconditions.requireNonNull(httpVersion, "httpVersion");
      if (connectTimeout.isZero() || connectTimeout.isNegative()) {
        throw new IllegalArgumentException("connectTimeout must be positive");
      }
      if (readTimeout.isZero() || readTimeout.isNegative()) {
        throw new IllegalArgumentException("readTimeout must be positive");
      }
      if (maxConnections < 1) {
        throw new IllegalArgumentException("maxConnections must be >= 1");
      }
      if (tlsRequired && !"https".equalsIgnoreCase(endpoint.getScheme())) {
        // Shipping credentials over plaintext is not a warning-level mistake.
        throw new IllegalArgumentException("tlsRequired but endpoint is not https");
      }
    }
  }

  /**
   * The credential binding for the provider (Doc 26).
   *
   * @param credentialSnapshotVersion the credential snapshot version this node expects to
   *     materialize against — recorded so a decision can be replayed against the right key
   *     generation
   */
  public record CredentialBinding(SnapshotVersion credentialSnapshotVersion) {

    /** Validates the credential binding. */
    public CredentialBinding {
      Preconditions.requireNonNull(credentialSnapshotVersion, "credentialSnapshotVersion");
    }
  }

  /**
   * The startup liveness probe. Needs its own tenant because materializing a credential is always
   * tenant-scoped — there is no ambient identity at startup.
   *
   * @param tenantScope the tenant whose credential the probe materializes
   * @param route the provider route to probe
   */
  public record HealthProbe(TenantScope tenantScope, RouteTarget route) {

    /** Validates the probe wiring. */
    public HealthProbe {
      Preconditions.requireNonNull(tenantScope, "tenantScope");
      Preconditions.requireNonNull(route, "route");
    }
  }

  /**
   * Provider wiring (Doc 25). Absent, or missing either sub-binding, leaves the ADAPTER and
   * RELIABILITY stages unbound so the node fails closed at the activation gate rather than serving
   * a pipeline that cannot reach a provider.
   *
   * @param transport the endpoint, timeouts and protocol settings
   * @param capabilitySnapshot the published capability snapshot, or empty when none is available
   * @param credentials the credential binding, or empty when no credential source is configured
   * @param healthProbe the startup liveness probe, or empty to register health as unprobed
   * @param modules the provider modules to register
   */
  public record ProviderConfig(
      ProviderTransportConfig transport,
      Optional<SnapshotCapabilitySource.CapabilitySnapshot> capabilitySnapshot,
      Optional<CredentialBinding> credentials,
      Optional<HealthProbe> healthProbe,
      List<ProviderModule> modules) {

    /** Validates the provider wiring. */
    public ProviderConfig {
      Preconditions.requireNonNull(transport, "transport");
      Preconditions.requireNonNull(capabilitySnapshot, "capabilitySnapshot");
      Preconditions.requireNonNull(credentials, "credentials");
      Preconditions.requireNonNull(healthProbe, "healthProbe");
      modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /**
     * Wires providers without any module, leaving the ADAPTER stage unbound.
     *
     * <p>Retained so that adding the module list did not break existing compositions. A node built
     * this way has no provider and fails closed at the activation gate, which is the same outcome
     * it had before when a sub-binding was missing.
     *
     * @param transport the endpoint, timeouts and protocol settings
     * @param capabilitySnapshot the published capability snapshot
     * @param credentials the credential binding
     * @param healthProbe the startup liveness probe
     */
    public ProviderConfig(
        final ProviderTransportConfig transport,
        final Optional<SnapshotCapabilitySource.CapabilitySnapshot> capabilitySnapshot,
        final Optional<CredentialBinding> credentials,
        final Optional<HealthProbe> healthProbe) {
      this(transport, capabilitySnapshot, credentials, healthProbe, List.of());
    }

    /**
     * Whether everything the provider platform needs is present.
     *
     * <p>A capability snapshot, a credential binding, and at least one provider module. The module
     * requirement is what changed with the provider platform: a node with a snapshot and
     * credentials but no module has nothing to dispatch to, and previously that was
     * indistinguishable from a working node because the composition root constructed a specific
     * provider itself.
     *
     * @return {@code true} when the provider chain can be built
     */
    public boolean complete() {
      return capabilitySnapshot.isPresent() && credentials.isPresent() && !modules.isEmpty();
    }
  }

  /**
   * Router inputs (Doc 19).
   *
   * @param capabilitySnapshot the immutable provider-capability snapshot
   * @param defaultPolicy the routing policy for tenants without an override
   * @param policyOverrides the immutable per-tenant routing-policy overrides
   */
  public record RoutingConfig(
      CapabilityRegistrySnapshot capabilitySnapshot,
      RoutingPolicy defaultPolicy,
      Map<TenantScope, RoutingPolicy> policyOverrides) {

    /** Validates the routing wiring. */
    public RoutingConfig {
      Preconditions.requireNonNull(capabilitySnapshot, "capabilitySnapshot");
      Preconditions.requireNonNull(defaultPolicy, "defaultPolicy");
      policyOverrides =
          Map.copyOf(Preconditions.requireNonNull(policyOverrides, "policyOverrides"));
    }
  }

  /**
   * Reliability inputs (Doc 20).
   *
   * @param policy the retry/failover policy
   * @param retryBudgetPermits the retries allowed per budget window on this node
   * @param retryBudgetWindow the budget window length
   * @param circuitCoolOff how long a tripped provider circuit stays open
   */
  public record ReliabilityConfig(
      ReliabilityPolicy policy,
      int retryBudgetPermits,
      Duration retryBudgetWindow,
      Duration circuitCoolOff) {

    /** Validates the reliability wiring. */
    public ReliabilityConfig {
      Preconditions.requireNonNull(policy, "policy");
      if (retryBudgetPermits < 0) {
        throw new IllegalArgumentException("retryBudgetPermits must be >= 0");
      }
      Preconditions.requireNonNull(retryBudgetWindow, "retryBudgetWindow");
      Preconditions.requireNonNull(circuitCoolOff, "circuitCoolOff");
    }
  }

  /**
   * Metering and cost inputs (Doc 22, Doc 23).
   *
   * @param usageDescriptors the immutable per-model usage-normalization table
   * @param meteringPolicy whether estimated usage may be recorded
   * @param maxTrackedRequests the bound on concurrently tracked in-flight requests
   * @param pricingSnapshot the immutable rate card + FX table
   * @param contractEntitlements the immutable per-tenant pricing-class entitlements
   * @param defaultEntitlement the entitlement for tenants without a contract
   * @param costLedgerCapacity the bounded in-process priced-result tail retained for inspection
   */
  public record AccountingConfig(
      Map<CanonicalModelId, UsageDescriptor> usageDescriptors,
      MeteringPolicy meteringPolicy,
      int maxTrackedRequests,
      PricingSnapshot pricingSnapshot,
      Map<TenantScope, ContractEntitlement> contractEntitlements,
      ContractEntitlement defaultEntitlement,
      int costLedgerCapacity) {

    /** Validates the accounting wiring. */
    public AccountingConfig {
      usageDescriptors =
          Map.copyOf(Preconditions.requireNonNull(usageDescriptors, "usageDescriptors"));
      Preconditions.requireNonNull(meteringPolicy, "meteringPolicy");
      if (maxTrackedRequests < 1) {
        throw new IllegalArgumentException("maxTrackedRequests must be >= 1");
      }
      Preconditions.requireNonNull(pricingSnapshot, "pricingSnapshot");
      contractEntitlements =
          Map.copyOf(Preconditions.requireNonNull(contractEntitlements, "contractEntitlements"));
      Preconditions.requireNonNull(defaultEntitlement, "defaultEntitlement");
      if (costLedgerCapacity < 1) {
        throw new IllegalArgumentException("costLedgerCapacity must be >= 1");
      }
    }
  }

  /**
   * Correctness inputs: structured-output validation and streaming transport integrity (Doc 17, Doc
   * 18).
   *
   * @param schemaLockPolicy the attempt/size/violation ceilings for structured output
   * @param schemaCacheCapacity the compiled-schema cache size
   * @param defaultStreamGuardPolicy the node-default stream integrity policy
   * @param schemaValidation the schema-engine bounds — absent leaves SCHEMA_LOCK without a
   *     validator
   */
  public record CorrectnessConfig(
      SchemaLockPolicy schemaLockPolicy,
      int schemaCacheCapacity,
      StreamGuardPolicy defaultStreamGuardPolicy,
      Optional<SchemaValidatorConfig> schemaValidation) {

    /** Validates the correctness wiring. */
    public CorrectnessConfig {
      Preconditions.requireNonNull(schemaLockPolicy, "schemaLockPolicy");
      if (schemaCacheCapacity < 1) {
        throw new IllegalArgumentException("schemaCacheCapacity must be >= 1");
      }
      Preconditions.requireNonNull(defaultStreamGuardPolicy, "defaultStreamGuardPolicy");
      Preconditions.requireNonNull(schemaValidation, "schemaValidation");
    }
  }

  /**
   * Credential-materialization inputs (Doc 26).
   *
   * @param nearExpiryMargin how long before expiry a credential is refused as too close to expiring
   * @param maxCredentialLength the bound on materialized credential length
   */
  public record SecretsConfig(Duration nearExpiryMargin, int maxCredentialLength) {

    /** Validates the secrets wiring. */
    public SecretsConfig {
      Preconditions.requireNonNull(nearExpiryMargin, "nearExpiryMargin");
      if (maxCredentialLength < 1) {
        throw new IllegalArgumentException("maxCredentialLength must be >= 1");
      }
    }
  }
}
