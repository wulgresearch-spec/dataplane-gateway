package io.reliabilityai.gateway.dataplane.app.runtime;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.app.GatewayDataPlaneApplication;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.binding.AdmissionCostProjector;
import io.reliabilityai.gateway.dataplane.app.binding.BoundedAttemptRetryDecision;
import io.reliabilityai.gateway.dataplane.app.binding.CostEngineUsageBridge;
import io.reliabilityai.gateway.dataplane.app.binding.ExtensionPointDispatcher;
import io.reliabilityai.gateway.dataplane.app.binding.FixedWindowRetryBudget;
import io.reliabilityai.gateway.dataplane.app.binding.GovernanceAdmissionAssembler;
import io.reliabilityai.gateway.dataplane.app.binding.InProcessAuthMetrics;
import io.reliabilityai.gateway.dataplane.app.binding.InProcessCircuitRegistry;
import io.reliabilityai.gateway.dataplane.app.binding.InProcessCostLedger;
import io.reliabilityai.gateway.dataplane.app.binding.InProcessQuotaCounters;
import io.reliabilityai.gateway.dataplane.app.binding.InProcessReliabilityMetrics;
import io.reliabilityai.gateway.dataplane.app.binding.InProcessSecretsMetrics;
import io.reliabilityai.gateway.dataplane.app.binding.InterruptibleSleeper;
import io.reliabilityai.gateway.dataplane.app.binding.LegacyGovernanceAdmission;
import io.reliabilityai.gateway.dataplane.app.binding.PinnedSnapshotSource;
import io.reliabilityai.gateway.dataplane.app.binding.PublishingAuthAudit;
import io.reliabilityai.gateway.dataplane.app.binding.PublishingSecretsAudit;
import io.reliabilityai.gateway.dataplane.app.binding.PublishingUsageJournal;
import io.reliabilityai.gateway.dataplane.app.binding.PublishingUsageLedger;
import io.reliabilityai.gateway.dataplane.app.binding.RequestCredentialScope;
import io.reliabilityai.gateway.dataplane.app.binding.ScopedSecretsProvider;
import io.reliabilityai.gateway.dataplane.app.binding.SecretsCredentialPort;
import io.reliabilityai.gateway.dataplane.app.binding.SnapshotCapabilityRegistry;
import io.reliabilityai.gateway.dataplane.app.binding.SnapshotCapabilitySource;
import io.reliabilityai.gateway.dataplane.app.binding.StaticContractSnapshotSource;
import io.reliabilityai.gateway.dataplane.app.binding.StaticPricingSnapshotSource;
import io.reliabilityai.gateway.dataplane.app.binding.StaticReliabilityPolicySource;
import io.reliabilityai.gateway.dataplane.app.binding.StaticUsageDescriptorSource;
import io.reliabilityai.gateway.dataplane.app.binding.TenantRoutingPolicySource;
import io.reliabilityai.gateway.dataplane.app.ingress.PipelineIngress;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestPipeline;
import io.reliabilityai.gateway.dataplane.auth.jwt.JwksHttpSource;
import io.reliabilityai.gateway.dataplane.auth.jwt.JwksKeyCache;
import io.reliabilityai.gateway.dataplane.auth.jwt.JwtIdentityVerifier;
import io.reliabilityai.gateway.dataplane.authn.api.IdentityVerifierPort;
import io.reliabilityai.gateway.dataplane.authn.application.AuthenticationService;
import io.reliabilityai.gateway.dataplane.authn.domain.TenantResolver;
import io.reliabilityai.gateway.dataplane.config.adapter.LastKnownGoodConfigCache;
import io.reliabilityai.gateway.dataplane.cost.api.CostEnginePort;
import io.reliabilityai.gateway.dataplane.cost.api.CostOutcomeSink;
import io.reliabilityai.gateway.dataplane.cost.application.CostEngineService;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.InMemoryBroker;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.LocalFileDeadLetter;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.LocalWal;
import io.reliabilityai.gateway.dataplane.eventpublisher.composition.VpsEventPublisherRuntime;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceAdmissionPort;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngine;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngineService;
import io.reliabilityai.gateway.dataplane.governance.domain.GovernanceEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyEvaluator;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyCompiler;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyLoader;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyRegistry;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyStore;
import io.reliabilityai.gateway.dataplane.ingress.RuntimeStateProbe;
import io.reliabilityai.gateway.dataplane.metering.api.MeteringOutcomeSink;
import io.reliabilityai.gateway.dataplane.metering.api.UsageMeteringEnginePort;
import io.reliabilityai.gateway.dataplane.metering.application.UsageMeteringService;
import io.reliabilityai.gateway.dataplane.observability.application.TelemetryEmitService;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCostSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginRegistryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxHostPort;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolExecutionPort;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginLifecycleService;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRegistryService;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRuntimeService;
import io.reliabilityai.gateway.dataplane.plugin.application.ToolExecutionService;
import io.reliabilityai.gateway.dataplane.plugin.internal.InProcessSandbox;
import io.reliabilityai.gateway.dataplane.plugin.internal.ProcessSandbox;
import io.reliabilityai.gateway.dataplane.provider.api.AdapterTelemetryPort;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderFault;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderHealth;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRegistration;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRuntimeContext;
import io.reliabilityai.gateway.dataplane.provider.application.ProviderDiscovery;
import io.reliabilityai.gateway.dataplane.provider.application.ProviderLifecycle;
import io.reliabilityai.gateway.dataplane.provider.application.ProviderRegistry;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityEnginePort;
import io.reliabilityai.gateway.dataplane.reliability.application.ReliabilityEngineService;
import io.reliabilityai.gateway.dataplane.router.api.ProviderRouterPort;
import io.reliabilityai.gateway.dataplane.router.application.ProviderRouterService;
import io.reliabilityai.gateway.dataplane.schema.validator.application.JsonSchemaValidator;
import io.reliabilityai.gateway.dataplane.schemalock.api.CorrectnessOutcomeSink;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaValidatorPort;
import io.reliabilityai.gateway.dataplane.schemalock.application.SchemaLockService;
import io.reliabilityai.gateway.dataplane.secrets.adapter.LastKnownGoodCredentialSnapshotCache;
import io.reliabilityai.gateway.dataplane.secrets.adapter.LocalMasterKeyKmsUnwrapAdapter;
import io.reliabilityai.gateway.dataplane.secrets.application.MaterializationService;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPort;
import io.reliabilityai.gateway.dataplane.streamguard.application.StreamGuardService;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.AuthenticationPort;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.GovernancePort;
import io.reliabilityai.gateway.ports.PluginRuntimePort;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import io.reliabilityai.gateway.ports.SecretsProviderPort;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single-VPS gateway runtime and composition root (AD-020, Doc 10 §5, Doc 38 §5).
 *
 * <p>It assembles every data-plane module with <b>explicit constructor wiring only</b> — no
 * framework, no reflection, no {@code ServiceLoader}, no DI container. Reading {@link #start()} top
 * to bottom is reading the whole object graph; there is no hidden edge, and the compiler enforces
 * that every dependency is satisfied before the node can run.
 *
 * <p><b>Deterministic startup</b> ({@link #start()}): validate config → load secrets → load
 * snapshots → validate pipeline → create runtime → replay WAL → start broker → ready.
 * <b>Deterministic shutdown</b> ({@link #stop()}): stop accepting → flush publisher → drain broker
 * → close WAL → close adapters → zeroize secrets → exit. Both are single-threaded under one lock;
 * the broker dispatcher is the only background thread. {@link #startupPhases()} / {@link
 * #shutdownPhases()} expose the completed order so the ordering is an asserted invariant rather
 * than a comment.
 *
 * <p><b>Fail-closed activation:</b> the set of bound stages is <em>derived from the object graph
 * actually constructed</em>, never declared by the operator, so the validator's input cannot drift
 * from reality. A node missing a provider transport refuses to activate instead of serving a
 * pipeline that silently skips a mandatory stage (AD-018).
 *
 * <p><b>AWS migration:</b> replace the local adapters wired here (broker → Kafka, WAL → durable
 * outbox, KMS unwrap → cloud KMS, no-op telemetry sinks → CloudWatch) at this ring only; no module
 * changes.
 */
public final class GatewayRuntime {

  /** Deterministic startup milestones, in frozen order (Doc 32). */
  public enum StartupPhase {
    VALIDATE_CONFIG,
    LOAD_SECRETS,
    LOAD_SNAPSHOTS,
    VALIDATE_PIPELINE,
    CREATE_RUNTIME,
    REPLAY_WAL,
    START_BROKER,
    READY
  }

  /** Deterministic shutdown milestones, in frozen order. */
  public enum ShutdownPhase {
    STOP_ACCEPTING,
    FLUSH_PUBLISHER,
    DRAIN_BROKER,
    CLOSE_WAL,
    CLOSE_ADAPTERS,
    ZEROIZE_SECRETS,
    EXIT
  }

  /** The runtime lifecycle state. */
  public enum State {
    NEW,
    STARTING,
    READY,
    STOPPING,
    STOPPED,
    FAILED
  }

  private final GatewayRuntimeConfig config;
  private final GatewayDataPlaneApplication application;
  private final ReentrantLock lock = new ReentrantLock();
  private final List<StartupPhase> startupPhases = new CopyOnWriteArrayList<>();
  private final List<ShutdownPhase> shutdownPhases = new CopyOnWriteArrayList<>();
  private final DeferredEventPublisher publisherHandle = new DeferredEventPublisher();
  private final RequestCredentialScope credentialScope = new RequestCredentialScope();

  /**
   * The per-request cost ceiling handed to the router for HTTP traffic. Deliberately permissive:
   * the binding spend limit is the tenant's budget, enforced by governance against the tenant's
   * actual entitlement. A second ceiling here would silently override that contract for every HTTP
   * caller.
   */
  private static final long HTTP_REQUEST_COST_CEILING_MICROS = Long.MAX_VALUE / 4;

  private volatile State state = State.NEW;

  // Owned key material — zeroized on stop and on failed start (Doc 26 §17.1).
  private byte[] masterKey;

  // Wired subsystems, in construction order.
  private LastKnownGoodConfigCache configCache;
  private LocalMasterKeyKmsUnwrapAdapter kmsUnwrap;
  private LastKnownGoodCredentialSnapshotCache credentialCache;
  private TelemetryEmitService telemetry;
  private SnapshotCapabilityRegistry capabilityRegistry;
  private AuthenticationService authentication;
  private PinnedSnapshotSource<VerificationKeySnapshot> keySnapshotSource;
  private PinnedSnapshotSource<TenantScopeSnapshot> tenantSnapshotSource;
  private Optional<IdentityVerifierPort> identityVerifier = Optional.empty();
  private Optional<SchemaValidatorPort> schemaValidator = Optional.empty();
  private JwksKeyCache jwksCache;
  private GovernancePort governanceEngine;
  private GovernanceEngine policyEngine;
  private MaterializationService secretsProvider;
  private ScopedSecretsProvider scopedSecretsProvider;
  private SecretsCredentialPort credentialPort;
  private SnapshotCapabilitySource capabilitySource;
  private ProviderLifecycle providerLifecycle;
  private ProviderRegistry providerRegistry;
  private ProviderDiscovery.DiscoveryReport discoveryReport;
  private List<ProviderFault> providerStartFaults = List.of();
  private volatile ProviderHealth providerHealth;
  private ProviderRouterService router;
  private ReliabilityEngineService reliabilityEngine;
  private ProviderAdapterPort providerAdapter;
  private StreamGuardService streamGuard;
  private SchemaLockService schemaLock;
  // Held as the port, not the implementation: metering() already returns the port, RequestPipeline
  // already consumes the port, and nothing here needs a UsageMeteringService-specific method. The
  // concrete type only ever widened what a holder of this field could reach into.
  private UsageMeteringEnginePort metering;
  private CostEngineService costEngine;
  private InProcessCostLedger costLedger;
  private InProcessReliabilityMetrics reliabilityMetrics;
  private InProcessSecretsMetrics secretsMetrics;
  private InProcessAuthMetrics authMetrics;
  private InProcessQuotaCounters quotaCounters;
  private PublishingUsageLedger usageLedger;
  private InProcessCircuitRegistry circuits;
  private SandboxHostPort pluginSandbox;
  private PluginRegistryService pluginRegistry;
  private PluginLifecycleService pluginLifecycle;
  private PluginRuntimeService pluginRuntime;
  private ToolExecutionService toolExecution;
  private RequestPipeline pipeline;
  private IngressLifecycle ingressLifecycle;
  private VpsEventPublisherRuntime eventRuntime;
  private Set<MandatoryStage> boundStages = EnumSet.noneOf(MandatoryStage.class);

  /**
   * Creates the runtime with explicit wiring inputs and a default activation validator.
   *
   * @param config the immutable runtime wiring inputs
   */
  public GatewayRuntime(final GatewayRuntimeConfig config) {
    this(config, new GatewayDataPlaneApplication());
  }

  /**
   * Creates the runtime with an explicit application/validator (for testing the activation gate).
   *
   * @param config the immutable runtime wiring inputs
   * @param application the frozen activation-gate application
   */
  public GatewayRuntime(
      final GatewayRuntimeConfig config, final GatewayDataPlaneApplication application) {
    this.config = Preconditions.requireNonNull(config, "config");
    this.application = Preconditions.requireNonNull(application, "application");
  }

  /**
   * Starts the runtime deterministically, failing closed if configuration is unavailable or any
   * mandatory pipeline stage is unbound (AD-018). On failure, secrets are zeroized and state
   * becomes {@link State#FAILED}.
   *
   * @throws IllegalStateException if already started
   */
  public void start() {
    lock.lock();
    try {
      if (state != State.NEW) {
        throw new IllegalStateException("runtime already started (state=" + state + ")");
      }
      state = State.STARTING;
      try {
        validateConfig();
        loadSecrets();
        loadSnapshots();
        validatePipeline();
        createRuntime();
        replayWalAndStartBroker();

        // The ingress opens last: only now is the pipeline assembled, the WAL replayed and the
        // broker
        // live, so the first request cannot race a half-built node.
        probeProviderHealth();
        // READY is set before the door opens: the readiness probe must answer READY to the very
        // first
        // request the ingress accepts, not one request later.
        state = State.READY;
        if (ingressLifecycle != null) {
          ingressLifecycle.startAccepting();
        }
        startupPhases.add(StartupPhase.READY);
      } catch (final RuntimeException startupFailure) {
        state = State.FAILED;
        closeQuietly();
        zeroizeSecrets(); // never leave key material resident after a failed start (Doc 26 §17.1)
        throw startupFailure;
      }
    } finally {
      lock.unlock();
    }
  }

  /** Step 1 — load the last-known-good configuration snapshot; fail closed if unavailable. */
  private void validateConfig() {
    configCache = new LastKnownGoodConfigCache(new NoOpConfigMetrics());
    configCache.applyPublished(config.configSnapshot());
    if (configCache.current().isEmpty()) {
      throw new IllegalStateException("config-snapshot-unavailable");
    }
    startupPhases.add(StartupPhase.VALIDATE_CONFIG);
  }

  /** Step 2 — take ownership of the master key and build the envelope-unwrap adapter. */
  private void loadSecrets() {
    masterKey = config.masterKey();
    kmsUnwrap = new LocalMasterKeyKmsUnwrapAdapter(masterKey);
    credentialCache = new LastKnownGoodCredentialSnapshotCache();
    startupPhases.add(StartupPhase.LOAD_SECRETS);
  }

  /** Step 3 — pin the immutable snapshots and build the passive, side-effect-free modules. */
  private void loadSnapshots() {
    telemetry = LocalNoOpTelemetry.emitter();
    capabilityRegistry = new SnapshotCapabilityRegistry(config.routing().capabilitySnapshot());
    reliabilityMetrics = new InProcessReliabilityMetrics();
    secretsMetrics = new InProcessSecretsMetrics();
    authMetrics = new InProcessAuthMetrics();
    quotaCounters = new InProcessQuotaCounters();
    costLedger = new InProcessCostLedger(config.accounting().costLedgerCapacity());
    circuits = new InProcessCircuitRegistry(config.reliability().circuitCoolOff(), config.clock());

    // Secrets: materialization audit is durable, so it goes through the publisher handle. Built
    // here
    // because the provider's credential bridge, constructed below, depends on it.
    secretsProvider =
        new MaterializationService(
            credentialCache,
            config.clock(),
            new PublishingSecretsAudit(publisherHandle, config.eventing().topics().secretsAudit()),
            secretsMetrics,
            config.secrets().nearExpiryMargin(),
            config.secrets().maxCredentialLength());
    // The pipeline gets the scoped wrapper, so its SECRETS stage publishes the tenant identity the
    // provider adapter's CredentialPort needs (see RequestCredentialScope).
    scopedSecretsProvider = new ScopedSecretsProvider(secretsProvider, credentialScope);

    // The Policy Enforcement Point (Doc 21). Built here with the rest of the passive,
    // snapshot-backed
    // modules: it performs no I/O, so it is safe to construct before the durable adapters exist. It
    // takes the runtime's single clock, so a governance decision's timestamp agrees with every
    // other
    // module's view of time.
    governanceEngine = config.governance().map(this::constructGovernance).orElse(null);

    constructIdentityVerifier();
    // The schema validator is snapshot-free and side-effect-free: compiling happens on demand, so
    // it
    // is safe to build with the other passive modules.
    schemaValidator = config.correctness().schemaValidation().map(JsonSchemaValidator::new);
    constructProviderChain();
    constructPluginRuntime();

    startupPhases.add(StartupPhase.LOAD_SNAPSHOTS);
  }

  /**
   * Builds the Sandboxed Plugin Runtime (Doc 28, C12), when the operator configured one.
   *
   * <p>Constructed here with the other snapshot-backed modules because none of it performs I/O at
   * construction: the substrate spawns nothing until an invocation arrives, and the registry binds
   * nothing until an operator registers a verified snapshot.
   *
   * <p>The substrate choice is the load-bearing decision. {@code processIsolated} selects the
   * OS-process boundary Doc 28 ISO-1 requires for untrusted code; the in-JVM substrate is offered
   * only for nodes that run first-party plugins exclusively, and the registry refuses third-party
   * manifests on it rather than trusting the operator to have chosen correctly.
   *
   * <p><b>Nothing here touches the request pipeline.</b> The Plugin Runtime is constructed,
   * governed and observable, but {@link RequestPipeline} does not call it: dispatching at the
   * frozen five extension points is a change to the non-bypass assembly and is deliberately left as
   * its own step.
   */
  private void constructPluginRuntime() {
    if (config.plugins().isEmpty()) {
      return;
    }
    final GatewayRuntimeConfig.PluginConfig plugins = config.plugins().orElseThrow();

    pluginSandbox =
        plugins.processIsolated()
            ? new ProcessSandbox(plugins.maxConcurrentInvocations())
            : new InProcessSandbox(plugins.maxConcurrentInvocations());

    pluginRegistry =
        new PluginRegistryService(
            plugins.signatures(),
            pluginSandbox,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            config.clock(),
            plugins.maxPlugins());
    pluginLifecycle =
        new PluginLifecycleService(
            pluginRegistry,
            pluginSandbox,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            config.clock(),
            plugins.systemScope());
    pluginRuntime =
        new PluginRuntimeService(
            pluginRegistry,
            pluginSandbox,
            plugins.authorization(),
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            PluginCostSinkPort.NO_OP,
            config.clock());
    toolExecution =
        new ToolExecutionService(
            pluginRegistry,
            pluginSandbox,
            plugins.authorization(),
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            PluginCostSinkPort.NO_OP,
            config.clock(),
            plugins.toolCostMicros());
  }

  /**
   * Builds the JWT verifier and, when a JWKS endpoint is configured, its key cache.
   *
   * <p>The cache is primed once here. A failed prime is <b>not</b> fatal: the node still verifies
   * tokens signed by keys the operator pinned in the snapshot, and refusing to start because an
   * identity provider was briefly unreachable would hand that provider the power to keep the
   * gateway down. Tokens whose key ids live only in JWKS simply fail closed until a refresh
   * succeeds.
   */
  private void constructIdentityVerifier() {
    if (config.authentication().isEmpty()) {
      identityVerifier = Optional.empty();
      return;
    }
    final GatewayRuntimeConfig.AuthenticationConfig authenticationConfig =
        config.authentication().orElseThrow();

    jwksCache =
        authenticationConfig
            .jwksUri()
            .map(
                uri ->
                    new JwksKeyCache(
                        new JwksHttpSource(
                            uri,
                            authenticationConfig.jwksConnectTimeout(),
                            authenticationConfig.jwksRequestTimeout()),
                        authenticationConfig.jwksCacheTtl(),
                        config.clock()))
            .orElse(null);
    if (jwksCache != null) {
      try {
        jwksCache.refresh();
      } catch (final RuntimeException primeFailure) {
        // see method javadoc — never fatal
      }
    }

    identityVerifier =
        Optional.of(
            new JwtIdentityVerifier(
                authenticationConfig.jwt(),
                config.clock(),
                Optional.ofNullable(jwksCache),
                governanceClaimNames()));
  }

  /**
   * The claim names the wired scope resolver needs, taken from the resolver itself.
   *
   * <p>Derived rather than configured a second time: the verifier must forward exactly what the
   * resolver reads, and any second declaration of those names could drift from this one. A drifted
   * name is invisible — the claim never arrives, the governance node is never built, and the policy
   * attached to it silently stops participating in the merge, which is the failure this wiring
   * exists to prevent.
   *
   * <p>Asked of the resolver contract rather than of one implementation of it. A resolver an
   * operator wrote reads claims just as the shipped one does, and narrowing to a known type would
   * hand it an empty set — its claims would never arrive and its nodes would never be built, which
   * is exactly the silent omission this wiring exists to close.
   *
   * @return the required claim names, empty when the resolver reads none
   */
  private Set<String> governanceClaimNames() {
    return admissionConfig().scopeResolver().requiredClaims();
  }

  /**
   * The admission wiring in force, defaulted once so every reader sees the same answer.
   *
   * @return the configured admission wiring, or the conservative default
   */
  private GatewayRuntimeConfig.AdmissionConfig admissionConfig() {
    return config
        .governance()
        .map(GatewayRuntimeConfig.GovernanceConfig::admission)
        .orElseGet(GatewayRuntimeConfig.AdmissionConfig::defaults);
  }

  /**
   * Builds the provider chain: credential bridge → capability source → transport → translator →
   * adapter. Constructed here, with the other snapshot-backed modules, because none of it performs
   * I/O — the HTTP client opens no connection until a request is sent.
   *
   * <p>Every link is required. If the operator supplied no provider config, or omitted either the
   * capability snapshot or the credential binding, the whole chain stays null and the ADAPTER stage
   * is derived as unbound — the node then refuses activation instead of starting up unable to reach
   * a provider.
   */
  private void constructProviderChain() {
    if (config.provider().isEmpty() || !config.provider().orElseThrow().complete()) {
      return;
    }
    final GatewayRuntimeConfig.ProviderConfig provider = config.provider().orElseThrow();
    final GatewayRuntimeConfig.ProviderTransportConfig transportConfig = provider.transport();

    // Credential bridge over the UNDECORATED secrets service: the decorated one publishes the
    // request
    // scope, and re-entering it here would release the scope when the adapter closes its lease.
    credentialPort = new SecretsCredentialPort(secretsProvider, credentialScope);
    capabilitySource = new SnapshotCapabilitySource(provider.capabilitySnapshot().orElseThrow());

    // Discovery: enumerate what this node was composed with and check each declaration against the
    // snapshot the operator published. Nothing is probed — capabilities are declared (Doc 25
    // CAP-3).
    // The check that earns its place is over-claim: a snapshot granting a capability the module
    // cannot
    // deliver would otherwise fail once per matching request, in production, at the caller's
    // expense.
    discoveryReport = new ProviderDiscovery().discover(provider.modules(), capabilitySource);
    providerLifecycle = ProviderLifecycle.from(discoveryReport, provider.modules());

    // Start every provider that validated. One failing to start does not stop the others: a node
    // with
    // three providers and one bad endpoint should serve the other two, not refuse to come up.
    providerStartFaults =
        providerLifecycle.startAll(
            new ProviderRuntimeContext(
                credentialPort, capabilitySource, AdapterTelemetryPort.NO_OP, config.clock()));

    // The registry is itself a ProviderAdapterPort, so Reliability holds one interface and never
    // learns
    // how many providers exist. Dispatch is a lookup on the opaque route reference.
    providerRegistry = providerLifecycle.registry();
    providerAdapter = providerRegistry.hasDispatchableProvider() ? providerRegistry : null;
  }

  /**
   * Step 4 — derive which mandatory stages the constructed graph can bind, then refuse activation
   * if any is missing. Deriving rather than trusting a declared set is what keeps the non-bypass
   * guarantee honest: an operator cannot claim ADAPTER is bound while supplying no transport.
   */
  private void validatePipeline() {
    boundStages = deriveBoundStages();
    application.activate(boundStages);
    startupPhases.add(StartupPhase.VALIDATE_PIPELINE);
  }

  /**
   * Step 5 — construct the durable adapters and every remaining module, publisher-backed sinks
   * included.
   */
  private void createRuntime() {
    // Authentication — the node's own JWT verifier when configured, otherwise an operator-supplied
    // one.
    final Optional<IdentityVerifierPort> verifier =
        identityVerifier.isPresent()
            ? identityVerifier
            : config.externalAdapters().identityVerifier();
    // The two snapshot sources are retained rather than constructed inline: an operator republishes
    // a verification-key or tenant-scope snapshot through these same instances, so a revocation
    // takes effect on the next request instead of waiting for the process to be rebuilt (Doc 37
    // §VKR, Doc 36 §HPP). Dropping the references is what made applyPublished unreachable.
    if (verifier.isPresent()) {
      keySnapshotSource =
          new PinnedSnapshotSource<>(
              config.authn().keySnapshot().version(), config.authn().keySnapshot());
      tenantSnapshotSource =
          new PinnedSnapshotSource<>(
              config.authn().tenantSnapshot().version(), config.authn().tenantSnapshot());
    }
    authentication =
        verifier.isEmpty()
            ? null
            : new AuthenticationService(
                keySnapshotSource,
                tenantSnapshotSource,
                verifier.orElseThrow(),
                new TenantResolver(),
                new PublishingAuthAudit(publisherHandle, config.eventing().topics().authAudit()),
                authMetrics,
                config.clock());

    // Router.
    router =
        new ProviderRouterService(
            capabilityRegistry,
            new TenantRoutingPolicySource(
                config.routing().defaultPolicy(), config.routing().policyOverrides()));

    // Reliability wraps the adapter, so it exists only when the adapter does.
    reliabilityEngine =
        providerAdapter == null
            ? null
            : new ReliabilityEngineService(
                providerAdapter,
                new StaticReliabilityPolicySource(config.reliability().policy()),
                new FixedWindowRetryBudget(
                    config.reliability().retryBudgetPermits(),
                    config.reliability().retryBudgetWindow(),
                    config.clock()),
                circuits,
                new InterruptibleSleeper(),
                config.clock(),
                reliabilityMetrics);

    // StreamGuard: fully local, always bound.
    streamGuard = new StreamGuardService(config.clock(), verdict -> {});

    // SchemaLock — the node's own validator when configured, plus a generation driver, which
    // remains
    // an external seam (see the SCHEMA_LOCK note in deriveBoundStages).
    schemaLock =
        schemaValidator.isPresent() && config.externalAdapters().providerGeneration().isPresent()
            ? new SchemaLockService(
                schemaValidator.orElseThrow(),
                config.externalAdapters().providerGeneration().orElseThrow(),
                new BoundedAttemptRetryDecision(config.correctness().schemaLockPolicy()),
                CorrectnessOutcomeSink.NO_OP,
                config.correctness().schemaLockPolicy(),
                config.clock(),
                config.correctness().schemaCacheCapacity())
            : null;

    // Cost engine, then metering — metering feeds cost through the bridge, so cost is built first.
    costEngine =
        new CostEngineService(
            new StaticPricingSnapshotSource(config.accounting().pricingSnapshot()),
            new StaticContractSnapshotSource(
                config.accounting().contractEntitlements(),
                config.accounting().defaultEntitlement()),
            costLedger,
            CostOutcomeSink.NO_OP,
            config.clock());

    usageLedger =
        new PublishingUsageLedger(publisherHandle, config.eventing().topics().usageLedger());
    metering =
        new UsageMeteringService(
            new StaticUsageDescriptorSource(config.accounting().usageDescriptors()),
            new PublishingUsageJournal(publisherHandle, config.eventing().topics().usageJournal()),
            usageLedger,
            quotaCounters,
            new CostEngineUsageBridge(
                costEngine, publisherHandle, config.eventing().topics().costFact()),
            MeteringOutcomeSink.NO_OP,
            config.clock(),
            config.accounting().meteringPolicy(),
            config.accounting().maxTrackedRequests());

    // The pipeline assembly. Constructed last, because it is the one component that depends on
    // every
    // other. It is only assemblable at all because the activation gate (step 4) already proved
    // every
    // mandatory stage is bound — so none of these arguments can be null here.
    pipeline =
        new RequestPipeline(
            authentication,
            admissionPort(),
            admissionAssembler(),
            extensionDispatcher(),
            router,
            scopedSecretsProvider,
            reliabilityEngine,
            streamGuard,
            schemaLock,
            metering,
            publisherHandle,
            config.correctness().defaultStreamGuardPolicy(),
            // The request-finalized AccountingFact is an accounting record, so it goes to the
            // existing
            // accounting stream. A dedicated topic would separate it from per-attempt usage facts
            // for
            // consumers; that is a Topics change, deliberately not made here.
            config.eventing().topics().usageLedger(),
            config.clock());

    // The HTTP front door, constructed last because it is the only component that needs the
    // finished
    // pipeline. It binds no socket until startAccepting() runs, immediately before READY.
    ingressLifecycle =
        config
            .ingress()
            .<IngressLifecycle>map(
                ingressConfig ->
                    new PipelineIngress(
                        ingressConfig,
                        pipeline,
                        new RuntimeStateProbe() {
                          @Override
                          public String state() {
                            return GatewayRuntime.this.state.name();
                          }

                          @Override
                          public boolean ready() {
                            return GatewayRuntime.this.state == State.READY;
                          }
                        },
                        config.clock(),
                        config.configSnapshot().region(),
                        HTTP_REQUEST_COST_CEILING_MICROS))
            .orElseGet(() -> config.externalAdapters().ingress().orElse(null));

    startupPhases.add(StartupPhase.CREATE_RUNTIME);
  }

  /**
   * Steps 6 and 7 — replay the pre-crash pending records in append order, then go live. Replay must
   * complete before the publisher accepts new traffic, or a fresh event could overtake a replayed
   * one; the publisher handle stays closed until the bind below.
   */
  private void replayWalAndStartBroker() {
    final InMemoryBroker broker =
        new InMemoryBroker(
            config.eventing().brokerCapacity(),
            config.eventing().brokerOfferTimeout(),
            config.eventing().brokerSubscriber());
    final LocalWal wal =
        new LocalWal(
            config.eventing().walDirectory(),
            config.eventing().walConfig(),
            config.eventing().walCodec());
    final LocalFileDeadLetter deadLetter =
        new LocalFileDeadLetter(config.eventing().deadLetterFile());

    eventRuntime =
        VpsEventPublisherRuntime.start(
            broker,
            wal,
            deadLetter,
            config.eventing().retryPolicy(),
            config.eventing().retryBackoff(),
            config.nodeId());
    startupPhases.add(StartupPhase.REPLAY_WAL);

    publisherHandle.bind(eventRuntime.publisher()); // sinks wired in step 5 start accepting here
    startupPhases.add(StartupPhase.START_BROKER);
  }

  /**
   * Stops the runtime deterministically and gracefully: stop accepting, flush the (synchronous)
   * publisher, drain the broker with no in-process loss, close the WAL and adapters, then zeroize
   * the secrets. Idempotent — a second call is a no-op.
   */
  public void stop() {
    lock.lock();
    try {
      if (state == State.STOPPED || state == State.NEW) {
        return; // nothing started, or already stopped
      }
      state = State.STOPPING;

      // Close the door first, so the broker below drains a bounded backlog rather than a moving
      // one.
      // The ingress finishes its in-flight requests inside stopAccepting before returning.
      if (ingressLifecycle != null) {
        ingressLifecycle.stopAccepting();
      }
      // Plugins stop with the door: they are extension behaviour, and a plugin still running while
      // the WAL drains would be emitting audit and cost records into a publisher that is closing.
      // They stop in reverse dependency order, and a plugin that will not stop is recorded FAILED
      // rather than waited on — shutdown must not be something a plugin can veto.
      stopPluginsQuietly();
      publisherHandle.unbind(); // new events are refused from here on, not silently dropped
      shutdownPhases.add(ShutdownPhase.STOP_ACCEPTING);

      // Publication is synchronous (append → send → markSent), so nothing is buffered in the
      // publisher
      // itself; the broker queue is the only buffer and is drained next.
      shutdownPhases.add(ShutdownPhase.FLUSH_PUBLISHER);

      if (eventRuntime != null) {
        eventRuntime.close(); // drains the broker (no in-process loss), then closes the WAL + DLQ
      }
      shutdownPhases.add(ShutdownPhase.DRAIN_BROKER);
      shutdownPhases.add(ShutdownPhase.CLOSE_WAL);

      // Close adapters: the provider transport's HTTP client first (draining its connection pool),
      // then
      // the credential cache, which wipes any retained material before the master key is destroyed.
      stopProviders();
      closePluginSandbox();
      if (credentialCache != null) {
        credentialCache.invalidate();
      }
      shutdownPhases.add(ShutdownPhase.CLOSE_ADAPTERS);

      zeroizeSecrets();
      shutdownPhases.add(ShutdownPhase.ZEROIZE_SECRETS);

      state = State.STOPPED;
      shutdownPhases.add(ShutdownPhase.EXIT);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Builds the node's Policy Enforcement Point.
   *
   * <p>Two shapes, one contract. With {@code policyEngine} configured the node runs the
   * hierarchical governance engine — policy attached anywhere from the global node down to an API
   * key, merged most-restrictive-wins, compiled once and swapped atomically. Without it the node
   * keeps the single-tenant snapshot evaluator. Both implement {@link GovernancePort}, so the
   * pipeline is unchanged either way and the GOVERNANCE stage is bound in both cases — there is no
   * configuration that produces a node serving traffic with the gate absent.
   *
   * @param governance the governance wiring
   * @return the Policy Enforcement Point
   */
  private GovernancePort constructGovernance(
      final GatewayRuntimeConfig.GovernanceConfig governance) {
    final var engineConfig = governance.policyEngine();
    if (engineConfig.isEmpty()) {
      return new GovernanceEngineService(
          governance.policySnapshots(),
          governance.entitlementSnapshots(),
          governance.usageStates(),
          governance.featureFlags(),
          governance.auditSink(),
          new GovernanceEvaluator(governance.usageStalenessTolerance()),
          config.clock());
    }
    final var settings = engineConfig.orElseThrow();
    final PolicyRegistry registry =
        new PolicyRegistry(
            new PolicyStore(settings.cacheCapacity(), settings.rollbackDepth()),
            settings.metrics());
    final PolicyLoader loader =
        new PolicyLoader(settings.source(), new PolicyCompiler(), registry, settings.metrics());
    policyEngine =
        new GovernanceEngine(
            registry,
            new PolicyEvaluator(settings.usageStalenessTolerance(), settings.contextTtl()),
            settings.usage(),
            settings.audit(),
            settings.metrics(),
            config.clock(),
            settings.ticker(),
            loader);
    if (settings.loadOnStart()) {
      // A failed initial load is not a startup failure. The node comes up with no generation
      // installed, which makes it refuse every request until policy arrives — visibly closed rather
      // than quietly open, and recoverable by a later reload without a restart.
      policyEngine.reload();
    }
    return policyEngine;
  }

  /**
   * The admission seam the pipeline calls.
   *
   * <p>The hierarchical engine implements it directly. The legacy evaluator is wrapped, so a
   * deployment that has not migrated still runs through the same single governance call site — the
   * pipeline never learns which enforcement point it is talking to, which is what makes the
   * migration a wiring change rather than a pipeline change.
   *
   * @return the admission port, or null when governance is unbound (the activation gate then
   *     refuses to start the node)
   */
  private GovernanceAdmissionPort admissionPort() {
    if (policyEngine != null) {
      return policyEngine;
    }
    return governanceEngine == null
        ? null
        : new LegacyGovernanceAdmission(governanceEngine, config.clock());
  }

  /**
   * Builds the assembler that sources every governed fact.
   *
   * <p>The canonical currency is read from the pricing snapshot's own FX table rather than
   * configured separately, so a budget authored in canonical micros and a projection denominated in
   * canonical micros are guaranteed to be the same unit. Two independent settings could drift, and
   * a drifted currency comparison fails open exactly when the projection currency is the weaker
   * one.
   *
   * @return the admission assembler
   */
  private GovernanceAdmissionAssembler admissionAssembler() {
    final GatewayRuntimeConfig.AdmissionConfig admission = admissionConfig();
    return new GovernanceAdmissionAssembler(
        capabilityRegistry,
        new AdmissionCostProjector(
            costEngine,
            admission.tokenEstimator(),
            config.accounting().pricingSnapshot().fx().canonicalCurrency()),
        admission.scopeResolver());
  }

  /**
   * The dispatcher that runs plugins at the frozen extension points.
   *
   * <p>Absent plugin config yields {@link ExtensionPointDispatcher#DISABLED}, which contributes
   * nothing at every point. That is what makes a node without plugins behave identically to a node
   * whose plugins all declined to bind — Doc 28 PRT-D1 requires the difference to be unobservable
   * from the request path.
   *
   * @return the dispatcher, never null
   */
  private ExtensionPointDispatcher extensionDispatcher() {
    if (pluginRuntime == null || config.plugins().isEmpty()) {
      return ExtensionPointDispatcher.DISABLED;
    }
    return new ExtensionPointDispatcher(
        pluginRuntime, config.clock(), config.plugins().orElseThrow().extensionPointBudget());
  }

  /**
   * Probes the provider once at startup, purely for observability.
   *
   * <p>A failed probe never blocks startup. A node that cannot reach its provider right now must
   * still come up: the provider may recover, and refusing to start would turn a transient provider
   * outage into a gateway outage — exactly the coupling this gateway exists to prevent. The verdict
   * is recorded and readable via {@link #providerHealth()}; nothing in the request path consults
   * it.
   */
  private void probeProviderHealth() {
    final ProviderId providerId = firstProviderId();
    if (providerId == null) {
      return;
    }
    final Optional<GatewayRuntimeConfig.HealthProbe> probe =
        config.provider().orElseThrow().healthProbe();
    if (probe.isEmpty()) {
      providerHealth = ProviderHealth.notProbed(providerId);
      return;
    }
    try {
      final SecretsProviderPort.MaterializationResult credential =
          secretsProvider.materialize(
              new CredentialRequest(
                  probe.orElseThrow().tenantScope(),
                  probe.orElseThrow().route(),
                  new CorrelationId(config.nodeId() + "-health")));
      if (!(credential instanceof SecretsProviderPort.MaterializationResult.Leased leased)) {
        providerHealth = ProviderHealth.unhealthy(providerId, "credential-unavailable");
        return;
      }
      try (CredentialLease lease = leased.lease()) {
        providerHealth =
            providerLifecycle
                .probe(
                    providerId,
                    lease,
                    new AttemptBudget(config.provider().orElseThrow().transport().readTimeout()))
                .orElseGet(() -> ProviderHealth.unhealthy(providerId, "provider-unregistered"));
      }
    } catch (final RuntimeException probeFailure) {
      providerHealth = ProviderHealth.unhealthy(providerId, "probe-failed");
    }
  }

  /**
   * The provider the startup probe targets.
   *
   * <p>The first registered provider, because the probe's credential binding names exactly one
   * route and there is no per-provider probe configuration yet. Honest limitation on a
   * multi-provider node: only one provider is probed at startup. Nothing in the request path
   * consults health, so the consequence is an incomplete operator report rather than a routing
   * effect — and Reliability's circuit breaker learns about every provider from live traffic
   * regardless.
   */
  private ProviderId firstProviderId() {
    if (providerRegistry == null) {
      return null;
    }
    return providerRegistry.registrations().stream()
        .findFirst()
        .map(ProviderRegistration::providerId)
        .orElse(null);
  }

  /** Closes every started provider, draining the transports they opened. Never throws. */
  private void stopProviders() {
    if (providerLifecycle == null) {
      return;
    }
    try {
      providerLifecycle.stopAll();
    } catch (final RuntimeException ignored) {
      // shutdown must still reach secret zeroization
    }
  }

  /** Stops every running plugin in reverse dependency order. Never throws. */
  private void stopPluginsQuietly() {
    if (pluginLifecycle == null) {
      return;
    }
    try {
      pluginLifecycle.stopAll();
    } catch (final RuntimeException stopFailure) {
      // A misbehaving plugin must not prevent the node from reaching secret zeroization.
    }
  }

  /** Releases the plugin substrate, destroying any surviving child process. Never throws. */
  private void closePluginSandbox() {
    if (pluginSandbox == null) {
      return;
    }
    try {
      pluginSandbox.shutdown();
    } catch (final RuntimeException shutdownFailure) {
      // shutdown must still reach secret zeroization
    }
  }

  /** Best-effort teardown of anything already constructed when a start fails partway. */
  private void closeQuietly() {
    publisherHandle.unbind();
    stopProviders();
    stopPluginsQuietly();
    closePluginSandbox();
    if (eventRuntime != null) {
      try {
        eventRuntime.close();
      } catch (final RuntimeException ignored) {
        // a failed start must still reach secret zeroization
      }
    }
    if (credentialCache != null) {
      credentialCache.invalidate();
    }
  }

  private void zeroizeSecrets() {
    if (masterKey != null) {
      Arrays.fill(masterKey, (byte) 0);
    }
  }

  /**
   * Derives the bound mandatory stages from the wiring actually supplied.
   *
   * <p>Every stage whose implementation lives outside this repository — ingress, identity
   * verification, provider transport, schema validation — binds only when its adapter is actually
   * supplied. GOVERNANCE now binds from the engine this runtime constructs, so it is enforced by
   * the node itself rather than delegated to whatever an operator happened to pass in. Nothing here
   * is assumed.
   *
   * @return the stages this composition can legitimately claim
   */
  private Set<MandatoryStage> deriveBoundStages() {
    final Set<MandatoryStage> bound = EnumSet.noneOf(MandatoryStage.class);
    if (config.ingress().isPresent() || config.externalAdapters().ingress().isPresent()) {
      bound.add(MandatoryStage.INGRESS);
    }
    if (identityVerifier.isPresent() || config.externalAdapters().identityVerifier().isPresent()) {
      bound.add(MandatoryStage.AUTHN);
    }
    if (governanceEngine != null) {
      bound.add(MandatoryStage.GOVERNANCE);
    }
    bound.add(MandatoryStage.ROUTER);
    bound.add(MandatoryStage.SECRETS);
    if (providerAdapter != null) {
      bound.add(MandatoryStage.ADAPTER);
      bound.add(
          MandatoryStage.RELIABILITY); // reliability drives the adapter; it needs one to exist
    }
    bound.add(MandatoryStage.STREAM_GUARD);
    // SCHEMA_LOCK needs two things: something that validates output, and something that can ask the
    // provider to try again. The validator is now the node's own; the generation driver still has
    // to
    // come from outside, because GenerationCommand carries no request or route to re-invoke with.
    if (schemaValidator.isPresent() && config.externalAdapters().providerGeneration().isPresent()) {
      bound.add(MandatoryStage.SCHEMA_LOCK);
    }
    bound.add(MandatoryStage.METERING);
    bound.add(MandatoryStage.COST);
    bound.add(MandatoryStage.EMITTER);
    return bound;
  }

  /**
   * The live event-publisher port (available only once {@link State#READY}).
   *
   * @return the event publisher
   * @throws IllegalStateException if the runtime is not ready
   */
  public EventPublisherPort publisher() {
    requireReady();
    return eventRuntime.publisher();
  }

  /**
   * The authentication service, present only when an external identity verifier is supplied.
   *
   * @return the authentication service, or empty when the verifier seam is unbound
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<AuthenticationPort> authentication() {
    requireReady();
    return Optional.ofNullable(authentication);
  }

  /**
   * The request execution pipeline — the single entry point for serving a request (Doc 06 §8).
   *
   * <p>Available only once {@link State#READY}, and refused again after {@link #stop()}: a request
   * must never enter a node whose WAL has not been replayed or whose broker has been drained.
   *
   * @return the pipeline
   * @throws IllegalStateException if the runtime is not ready
   */
  public RequestPipeline pipeline() {
    requireReady();
    return pipeline;
  }

  /**
   * The constructed HTTP ingress, when one was configured (Doc 30).
   *
   * @return the ingress, or empty when the front door is operator-supplied or absent
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<PipelineIngress> httpIngress() {
    requireReady();
    return ingressLifecycle instanceof PipelineIngress http ? Optional.of(http) : Optional.empty();
  }

  /**
   * The Policy Enforcement Point this runtime constructed (Doc 21), present only when governance
   * snapshot readers were configured.
   *
   * @return the governance engine, or empty when governance was not configured
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<GovernancePort> governance() {
    requireReady();
    return Optional.ofNullable(governanceEngine);
  }

  /**
   * The hierarchical governance engine, when this node was configured with one.
   *
   * <p>Exposed so an operator surface can reload a generation, roll one back, or simulate a
   * candidate without restarting the node. Present only when {@code governance().policyEngine()}
   * was supplied; a node on the single-tenant evaluator returns empty.
   *
   * @return the policy engine, or empty when this node does not run one
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<GovernanceEngine> policyEngine() {
    requireReady();
    return Optional.ofNullable(policyEngine);
  }

  /**
   * The provider router (Doc 19).
   *
   * @return the router
   * @throws IllegalStateException if the runtime is not ready
   */
  public ProviderRouterPort router() {
    requireReady();
    return router;
  }

  /**
   * The reliability engine, present only when a provider adapter is wired.
   *
   * @return the reliability engine, or empty when the adapter seam is unbound
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<ReliabilityEnginePort> reliabilityEngine() {
    requireReady();
    return Optional.ofNullable(reliabilityEngine);
  }

  /**
   * The provider adapter, present only when transport, translator, credentials and capability
   * mapping are all supplied.
   *
   * @return the provider adapter, or empty when any seam is unbound
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<ProviderAdapterPort> providerAdapter() {
    requireReady();
    return Optional.ofNullable(providerAdapter);
  }

  /**
   * The provider registry — what this node discovered, what state each provider is in, and what
   * each route can do.
   *
   * <p>The operator surface for the provider platform. Answering "why is nothing routing to that
   * model" previously meant reading configuration and inferring; now it is a registration with a
   * state and a fault list.
   *
   * @return the registry, or empty when no provider is wired
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<ProviderRegistry> providerRegistry() {
    requireReady();
    return Optional.ofNullable(providerRegistry);
  }

  /**
   * The lifecycle service, for enabling or disabling a provider without restarting the node.
   *
   * @return the lifecycle service, or empty when no provider is wired
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<ProviderLifecycle> providerLifecycle() {
    requireReady();
    return Optional.ofNullable(providerLifecycle);
  }

  /**
   * Every disagreement found while validating provider declarations, plus any start failure.
   *
   * <p>Empty on a correctly-configured node. A non-empty list is the startup report an operator
   * should read: a capability over-claim here is a request-time failure that has not happened yet.
   *
   * @return the faults, in discovery order
   * @throws IllegalStateException if the runtime is not ready
   */
  public List<ProviderFault> providerFaults() {
    requireReady();
    if (discoveryReport == null) {
      return List.of();
    }
    final List<ProviderFault> all = new java.util.ArrayList<>(discoveryReport.faults());
    all.addAll(providerStartFaults);
    return List.copyOf(all);
  }

  /**
   * The streaming transport integrity guard (Doc 18).
   *
   * @return StreamGuard
   * @throws IllegalStateException if the runtime is not ready
   */
  public StreamGuardPort streamGuard() {
    requireReady();
    return streamGuard;
  }

  /**
   * The structured-output validator, present only when an external schema validator is supplied.
   *
   * @return SchemaLock, or empty when the validator seam is unbound
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<SchemaLockPort> schemaLock() {
    requireReady();
    return Optional.ofNullable(schemaLock);
  }

  /**
   * The usage metering engine (Doc 23).
   *
   * @return the metering engine
   * @throws IllegalStateException if the runtime is not ready
   */
  public UsageMeteringEnginePort metering() {
    requireReady();
    return metering;
  }

  /**
   * The cost engine (Doc 22).
   *
   * @return the cost engine
   * @throws IllegalStateException if the runtime is not ready
   */
  public CostEnginePort costEngine() {
    requireReady();
    return costEngine;
  }

  /**
   * The credential materialization service (Doc 26).
   *
   * @return the secrets provider
   * @throws IllegalStateException if the runtime is not ready
   */
  public SecretsProviderPort secretsProvider() {
    requireReady();
    return scopedSecretsProvider;
  }

  /**
   * The provider's last recorded health verdict (Doc 25). Observability only — no request-path
   * decision reads it, and a failing probe never blocked startup.
   *
   * @return the health verdict, or empty when no provider is wired
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<ProviderHealth> providerHealth() {
    requireReady();
    return Optional.ofNullable(providerHealth);
  }

  /**
   * Re-probes the provider on demand (operator action). Never throws and never changes runtime
   * state.
   *
   * @return the fresh verdict, or empty when no provider is wired
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<ProviderHealth> refreshProviderHealth() {
    requireReady();
    probeProviderHealth();
    return Optional.ofNullable(providerHealth);
  }

  /**
   * The snapshot-backed capability source, for operators inspecting what a route may do.
   *
   * @return the capability source, or empty when no provider is wired
   * @throws IllegalStateException if the runtime is not ready
   */
  /**
   * The JWKS key cache, when a JWKS endpoint is configured. Exposed so an operator or scheduler can
   * drive {@code refresh()} out of band; the request path never fetches.
   *
   * @return the cache, or empty when verification uses snapshot keys only
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<JwksKeyCache> jwksCache() {
    requireReady();
    return Optional.ofNullable(jwksCache);
  }

  /**
   * The verification-key snapshot source, when authentication is wired. Exposed so an operator or
   * control plane can republish a key snapshot out of band via {@code applyPublished}; the request
   * path never fetches. Republishing a snapshot whose {@code revokedKeyIds} names a {@code kid}
   * makes that key resolve as revoked on the next request (Doc 37 §VKR).
   *
   * @return the key snapshot source, or empty when no identity verifier is bound
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<PinnedSnapshotSource<VerificationKeySnapshot>> keySnapshotSource() {
    requireReady();
    return Optional.ofNullable(keySnapshotSource);
  }

  /**
   * The tenant-scope snapshot source, when authentication is wired. Exposed so an operator or
   * control plane can republish a tenant-scope snapshot out of band via {@code applyPublished}, so
   * that a membership revocation or move takes effect on the next request rather than at the next
   * process rebuild (Doc 37 §TRF, Doc 36 §HPP).
   *
   * <p>The tenant a principal resolves to stays server-derived: republishing changes the {@code
   * principalId → TenantScope} mapping this node trusts, and never lets a token's claims select a
   * tenant.
   *
   * @return the tenant snapshot source, or empty when no identity verifier is bound
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<PinnedSnapshotSource<TenantScopeSnapshot>> tenantSnapshotSource() {
    requireReady();
    return Optional.ofNullable(tenantSnapshotSource);
  }

  /**
   * The snapshot-backed capability source, for operators inspecting what a route may do.
   *
   * @return the capability source, or empty when no provider is wired
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<SnapshotCapabilitySource> capabilitySource() {
    requireReady();
    return Optional.ofNullable(capabilitySource);
  }

  /**
   * The plugin binding table (Doc 28 §ROC), present only when plugins are configured.
   *
   * <p>This is the node's local table of verified plugins, not the registry of record — that is the
   * C12 control plane's (Doc 28 ROC-1). An operator registers an already-vetted, signed snapshot
   * here; nothing this returns can approve a plugin.
   *
   * @return the registry, or empty when the node runs no plugins
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<PluginRegistryPort> pluginRegistry() {
    requireReady();
    return Optional.ofNullable(pluginRegistry);
  }

  /**
   * The plugin lifecycle controller (Doc 28 §D, §HRC), present only when plugins are configured.
   *
   * <p>Exposed so an operator can start, stop, enable, disable, reload or restart a plugin without
   * restarting the node (Doc 28 HRC-5).
   *
   * @return the lifecycle service, or empty when the node runs no plugins
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<PluginLifecycleService> pluginLifecycle() {
    requireReady();
    return Optional.ofNullable(pluginLifecycle);
  }

  /**
   * The frozen extension-point dispatch seam (Doc 28 §EPC), present only when plugins are
   * configured.
   *
   * <p>Not currently called by {@link RequestPipeline}. Binding it into the request path at the
   * frozen five is a change to the non-bypass assembly and is its own step.
   *
   * @return the dispatch service, or empty when the node runs no plugins
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<PluginRuntimePort> pluginRuntime() {
    requireReady();
    return Optional.ofNullable(pluginRuntime);
  }

  /**
   * The tool execution engine (Doc 28 §15), present only when plugins are configured.
   *
   * <p>Deliberately unbound from the request pipeline: model-driven tool calling needs a
   * post-invoke or response-transform hook, and Doc 28 EPC-3 forbids both by name. The engine is
   * finished and governed; the caller that drives a tool loop arrives with the ADR that Doc 28
   * EPC-6 requires.
   *
   * @return the tool executor, or empty when the node runs no plugins
   * @throws IllegalStateException if the runtime is not ready
   */
  public Optional<ToolExecutionPort> toolExecution() {
    requireReady();
    return Optional.ofNullable(toolExecution);
  }

  /**
   * The telemetry emitter (Doc 27). Passive: removing it changes no decision.
   *
   * @return the telemetry emitter
   * @throws IllegalStateException if the runtime is not ready
   */
  public TelemetryEmitService telemetry() {
    requireReady();
    return telemetry;
  }

  /**
   * The envelope-unwrap adapter backing credential decryption (Doc 26).
   *
   * @return the KMS unwrap adapter
   * @throws IllegalStateException if the runtime is not ready
   */
  public LocalMasterKeyKmsUnwrapAdapter kmsUnwrap() {
    requireReady();
    return kmsUnwrap;
  }

  /**
   * The credential snapshot cache the operator publishes encrypted material into (Doc 26).
   *
   * @return the credential cache
   * @throws IllegalStateException if the runtime is not ready
   */
  public LastKnownGoodCredentialSnapshotCache credentialCache() {
    requireReady();
    return credentialCache;
  }

  /**
   * The provider circuit registry, so the caller that observes an outcome can trip or reset a
   * route.
   *
   * @return the circuit registry
   * @throws IllegalStateException if the runtime is not ready
   */
  public InProcessCircuitRegistry circuits() {
    requireReady();
    return circuits;
  }

  /**
   * The mandatory stages this composition derived as bound.
   *
   * @return the bound stages, in pipeline order
   */
  public Set<MandatoryStage> boundStages() {
    return Set.copyOf(boundStages);
  }

  /**
   * The mandatory stages still unbound — the node's remaining blockers to full activation.
   *
   * @return the unbound stages, in pipeline order
   */
  public Set<MandatoryStage> unboundStages() {
    final Set<MandatoryStage> missing = EnumSet.allOf(MandatoryStage.class);
    missing.removeAll(boundStages);
    return Set.copyOf(missing);
  }

  /**
   * The current lifecycle state.
   *
   * @return the state
   */
  public State state() {
    return state;
  }

  /**
   * The completed startup milestones, in order (for deterministic-order audit/tests).
   *
   * @return the startup phases reached
   */
  public List<StartupPhase> startupPhases() {
    return List.copyOf(startupPhases);
  }

  /**
   * The completed shutdown milestones, in order (for deterministic-order audit/tests).
   *
   * @return the shutdown phases reached
   */
  public List<ShutdownPhase> shutdownPhases() {
    return List.copyOf(shutdownPhases);
  }

  private void requireReady() {
    if (state != State.READY) {
      throw new IllegalStateException("runtime not ready (state=" + state + ")");
    }
  }
}
