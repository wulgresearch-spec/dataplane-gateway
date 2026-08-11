package io.reliabilityai.gateway.dataplane.app.runtime;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.canonical.capability.ProviderCapability;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.canonical.snapshot.ConfigSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.FeatureFlagDefinition;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.app.binding.ProviderCapabilityEntry;
import io.reliabilityai.gateway.dataplane.app.binding.SnapshotCapabilitySource;
import io.reliabilityai.gateway.dataplane.authn.api.IdentityVerifierPort;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import io.reliabilityai.gateway.dataplane.cost.domain.ContractEntitlement;
import io.reliabilityai.gateway.dataplane.cost.domain.FxRate;
import io.reliabilityai.gateway.dataplane.cost.domain.FxTable;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingClass;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingDescriptor;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingSnapshot;
import io.reliabilityai.gateway.dataplane.cost.domain.UnitRates;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalCodec;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalConfig;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalSyncPolicy;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryBackoff;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryPolicy;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.domain.Entitlement;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySet;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageState;
import io.reliabilityai.gateway.dataplane.ingress.IngressConfig;
import io.reliabilityai.gateway.dataplane.metering.api.MeteringPolicy;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageDescriptor;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilitySnapshotPort;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRoute;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTranslator;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTransportPort;
import io.reliabilityai.gateway.dataplane.provider.api.TransportException;
import io.reliabilityai.gateway.dataplane.provider.api.TransportRequest;
import io.reliabilityai.gateway.dataplane.provider.api.TransportResponse;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicy;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistrySnapshot;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicy;
import io.reliabilityai.gateway.dataplane.schema.validator.api.SchemaValidatorConfig;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.OutputSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.ProviderGenerationPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPolicy;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaValidatorPort;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import io.reliabilityai.gateway.dataplane.schemalock.domain.ValidationVerdict;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import io.reliabilityai.gateway.provider.openai.OpenAiConfiguration;
import io.reliabilityai.gateway.provider.openai.OpenAiProviderModule;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds a complete, valid {@link GatewayRuntimeConfig} for lifecycle tests, so each test states
 * only the one thing it varies. Every value is deterministic — the clock is fixed and advanced
 * explicitly — so a lifecycle assertion can never flake on timing.
 */
public final class RuntimeFixture {

  /**
   * A content-free test payload for publisher round-trips.
   *
   * @param value the counter, carrying no tenant content
   */
  public record Count(int value) implements ContentFree {}

  /** A manually advanced clock: startup ordering must never depend on wall-clock time. */
  public static final class TestClock implements ClockPort {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant now() {
      return now;
    }

    public void advance(final Duration by) {
      now = now.plus(by);
    }
  }

  /**
   * In-process registry backing the non-{@link Count} arm of {@link #CODEC}. A real node ships a
   * schema-based codec; a test only needs values to survive a WAL round-trip within one JVM, and
   * the runtime publishes several unrelated content-free types (usage facts, accounting facts,
   * audit records) that a hand-rolled encoder would otherwise have to enumerate.
   */
  private static final java.util.List<ContentFree> WAL_REGISTRY =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  /** Tag 0 encodes {@link Count} by value; tag 1 encodes anything else by registry index. */
  public static final WalCodec CODEC =
      new WalCodec() {
        @Override
        public byte[] encode(final ContentFree payload) {
          if (payload instanceof Count count) {
            return ByteBuffer.allocate(5).put((byte) 0).putInt(count.value()).array();
          }
          WAL_REGISTRY.add(payload);
          return ByteBuffer.allocate(5).put((byte) 1).putInt(WAL_REGISTRY.size() - 1).array();
        }

        @Override
        public ContentFree decode(final String topic, final byte[] bytes) {
          final ByteBuffer buffer = ByteBuffer.wrap(bytes);
          final byte tag = buffer.get();
          final int value = buffer.getInt();
          return tag == 0 ? new Count(value) : WAL_REGISTRY.get(value);
        }
      };

  private RuntimeFixture() {}

  public static byte[] masterKey() {
    final byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) 0x11);
    return key;
  }

  static ConfigSnapshot configSnapshot() {
    return new ConfigSnapshot(
        new SnapshotVersion("config", "v1"),
        1,
        new Region("us-east-1"),
        Map.of("finalization.timeout.ms", "2000"),
        List.of(new FeatureFlagDefinition("f-on", 10_000)),
        Map.of("deny.default", "true"));
  }

  public static GatewayRuntimeConfig config(
      final byte[] masterKey,
      final Path walDir,
      final Path dlqFile,
      final Consumer<BrokerRecord> subscriber,
      final ClockPort clock,
      final ExternalAdapters externalAdapters) {
    return config(
        masterKey,
        walDir,
        dlqFile,
        subscriber,
        clock,
        externalAdapters,
        Optional.of(governanceConfig(permittingPolicy(), AuditSinkPort.NO_OP)));
  }

  /**
   * Builds the runtime config with explicit governance wiring, so a composition test can vary the
   * policy the node enforces — or omit governance entirely to prove the node fails closed.
   *
   * @param masterKey the secrets master key
   * @param walDir the WAL directory
   * @param dlqFile the dead-letter file
   * @param subscriber the broker subscriber
   * @param clock the injected clock
   * @param externalAdapters the external seam set
   * @param governance the governance snapshot wiring, or empty to leave GOVERNANCE unbound
   * @return the runtime config
   */
  public static GatewayRuntimeConfig config(
      final byte[] masterKey,
      final Path walDir,
      final Path dlqFile,
      final Consumer<BrokerRecord> subscriber,
      final ClockPort clock,
      final ExternalAdapters externalAdapters,
      final Optional<GatewayRuntimeConfig.GovernanceConfig> governance) {
    // Mirrors the pre-wiring semantics for tests that vary only the external seam set: a seam set
    // without a provider transport still yields an unbound ADAPTER stage. Tests that exercise the
    // real
    // provider wiring pass provider config explicitly via the overload below.
    return config(
        masterKey,
        walDir,
        dlqFile,
        subscriber,
        clock,
        externalAdapters,
        governance,
        externalAdapters.providerAdapterComplete()
            ? Optional.of(providerConfig(URI.create("http://127.0.0.1:1")))
            : Optional.empty());
  }

  /**
   * Builds the runtime config with explicit governance and provider wiring.
   *
   * @param masterKey the secrets master key
   * @param walDir the WAL directory
   * @param dlqFile the dead-letter file
   * @param subscriber the broker subscriber
   * @param clock the injected clock
   * @param externalAdapters the external seam set
   * @param governance the governance snapshot wiring, or empty to leave GOVERNANCE unbound
   * @param provider the provider wiring, or empty to leave ADAPTER and RELIABILITY unbound
   * @return the runtime config
   */
  public static GatewayRuntimeConfig config(
      final byte[] masterKey,
      final Path walDir,
      final Path dlqFile,
      final Consumer<BrokerRecord> subscriber,
      final ClockPort clock,
      final ExternalAdapters externalAdapters,
      final Optional<GatewayRuntimeConfig.GovernanceConfig> governance,
      final Optional<GatewayRuntimeConfig.ProviderConfig> provider) {
    return config(
        masterKey,
        walDir,
        dlqFile,
        subscriber,
        clock,
        externalAdapters,
        governance,
        provider,
        Optional.empty());
  }

  /**
   * Builds the runtime config with explicit governance, provider and HTTP ingress wiring.
   *
   * @param masterKey the secrets master key
   * @param walDir the WAL directory
   * @param dlqFile the dead-letter file
   * @param subscriber the broker subscriber
   * @param clock the injected clock
   * @param externalAdapters the external seam set
   * @param governance the governance snapshot wiring, or empty to leave GOVERNANCE unbound
   * @param provider the provider wiring, or empty to leave ADAPTER and RELIABILITY unbound
   * @param ingress the HTTP ingress wiring, or empty to fall back to the external seam
   * @return the runtime config
   */
  public static GatewayRuntimeConfig config(
      final byte[] masterKey,
      final Path walDir,
      final Path dlqFile,
      final Consumer<BrokerRecord> subscriber,
      final ClockPort clock,
      final ExternalAdapters externalAdapters,
      final Optional<GatewayRuntimeConfig.GovernanceConfig> governance,
      final Optional<GatewayRuntimeConfig.ProviderConfig> provider,
      final Optional<IngressConfig> ingress) {
    return config(
        masterKey,
        walDir,
        dlqFile,
        subscriber,
        clock,
        externalAdapters,
        governance,
        provider,
        ingress,
        Optional.empty());
  }

  /**
   * Builds the runtime config with explicit governance, provider, ingress and authentication
   * wiring.
   *
   * @param masterKey the secrets master key
   * @param walDir the WAL directory
   * @param dlqFile the dead-letter file
   * @param subscriber the broker subscriber
   * @param clock the injected clock
   * @param externalAdapters the external seam set
   * @param governance the governance snapshot wiring, or empty to leave GOVERNANCE unbound
   * @param provider the provider wiring, or empty to leave ADAPTER and RELIABILITY unbound
   * @param ingress the HTTP ingress wiring, or empty to fall back to the external seam
   * @param authentication the JWT wiring, or empty to fall back to the external verifier seam
   * @return the runtime config
   */
  public static GatewayRuntimeConfig config(
      final byte[] masterKey,
      final Path walDir,
      final Path dlqFile,
      final Consumer<BrokerRecord> subscriber,
      final ClockPort clock,
      final ExternalAdapters externalAdapters,
      final Optional<GatewayRuntimeConfig.GovernanceConfig> governance,
      final Optional<GatewayRuntimeConfig.ProviderConfig> provider,
      final Optional<IngressConfig> ingress,
      final Optional<GatewayRuntimeConfig.AuthenticationConfig> authentication) {
    return config(
        masterKey,
        walDir,
        dlqFile,
        subscriber,
        clock,
        externalAdapters,
        governance,
        provider,
        ingress,
        authentication,
        Optional.empty());
  }

  /**
   * Builds the runtime config with explicit plugin wiring as well (Doc 28).
   *
   * @param masterKey the secrets master key
   * @param walDir the WAL directory
   * @param dlqFile the dead-letter file
   * @param subscriber the broker subscriber
   * @param clock the injected clock
   * @param externalAdapters the external seam set
   * @param governance the governance snapshot wiring, or empty to leave GOVERNANCE unbound
   * @param provider the provider wiring, or empty to leave ADAPTER and RELIABILITY unbound
   * @param ingress the HTTP ingress wiring, or empty to fall back to the external seam
   * @param authentication the JWT wiring, or empty to fall back to the external verifier seam
   * @param plugins the Plugin Runtime wiring, or empty to run no plugins
   * @return the runtime config
   */
  public static GatewayRuntimeConfig config(
      final byte[] masterKey,
      final Path walDir,
      final Path dlqFile,
      final Consumer<BrokerRecord> subscriber,
      final ClockPort clock,
      final ExternalAdapters externalAdapters,
      final Optional<GatewayRuntimeConfig.GovernanceConfig> governance,
      final Optional<GatewayRuntimeConfig.ProviderConfig> provider,
      final Optional<IngressConfig> ingress,
      final Optional<GatewayRuntimeConfig.AuthenticationConfig> authentication,
      final Optional<GatewayRuntimeConfig.PluginConfig> plugins) {
    return new GatewayRuntimeConfig(
        "node",
        configSnapshot(),
        masterKey,
        clock,
        new GatewayRuntimeConfig.EventingConfig(
            walDir,
            new WalConfig(4096, WalSyncPolicy.ALWAYS),
            CODEC,
            dlqFile,
            64,
            Duration.ofSeconds(2),
            subscriber,
            new RetryPolicy(3),
            RetryBackoff.NONE,
            GatewayRuntimeConfig.Topics.defaults()),
        new GatewayRuntimeConfig.AuthnConfig(keySnapshot(), tenantSnapshot()),
        authentication,
        governance,
        provider,
        ingress,
        plugins,
        new GatewayRuntimeConfig.RoutingConfig(
            capabilitySnapshot(), new RoutingPolicy(1L, 1L, 0L, 0.0d, Set.of()), Map.of()),
        new GatewayRuntimeConfig.ReliabilityConfig(
            new ReliabilityPolicy(3, 1, 10L, 100L, Duration.ofSeconds(5)),
            10,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30)),
        new GatewayRuntimeConfig.AccountingConfig(
            Map.of(MODEL, new UsageDescriptor(MODEL, "usage-v1")),
            MeteringPolicy.FAIL_CLOSED,
            1024,
            pricingSnapshot(),
            Map.of(),
            new ContractEntitlement("contract-v1", Set.of(PricingClass.LIST)),
            64),
        new GatewayRuntimeConfig.CorrectnessConfig(
            new SchemaLockPolicy(3, 65_536, 32, Duration.ofSeconds(5)),
            64,
            new StreamGuardPolicy(
                1_048_576L, 65_536, 128, Duration.ofSeconds(30), Duration.ofMinutes(5)),
            Optional.of(SchemaValidatorConfig.defaults())),
        new GatewayRuntimeConfig.SecretsConfig(Duration.ofSeconds(30), 4096),
        externalAdapters);
  }

  public static final CanonicalModelId MODEL = new CanonicalModelId("model.test.v1");

  /** The tenant {@code principal-a} resolves to via {@link #tenantSnapshot()}. */
  public static final TenantScope TENANT = TenantScope.of("org-a", "tenant-a");

  /** The provider route the fixture's capability snapshot publishes. */
  public static final RouteTarget ROUTE = new RouteTarget(MODEL, "route-a");

  /**
   * A capability snapshot granting the fixture model every capability on {@link #ROUTE}.
   *
   * @return the capability snapshot
   */
  public static SnapshotCapabilitySource.CapabilitySnapshot capabilities() {
    return new SnapshotCapabilitySource.CapabilitySnapshot(
        new SnapshotVersion("provider-capabilities", "v1"),
        List.of(
            new ProviderCapabilityEntry(
                MODEL,
                "route-a",
                new PinnedVersion("2024-10-01"),
                true,
                true,
                true,
                true,
                true,
                false,
                4096L,
                Map.of("family", "test"))));
  }

  /**
   * Provider wiring pointed at a caller-supplied endpoint.
   *
   * @param endpoint the provider API root (loopback in tests)
   * @return the provider config
   */
  /**
   * The permitting governance wiring, for tests that need the node to reach READY.
   *
   * @return governance config that admits the fixture tenant
   */
  public static Optional<GatewayRuntimeConfig.GovernanceConfig> governanceConfigured() {
    return Optional.of(governanceConfig(permittingPolicy(), AuditSinkPort.NO_OP));
  }

  /**
   * Provider wiring pointed at a port nothing listens on, for tests that need ADAPTER and
   * RELIABILITY bound but never send a request.
   *
   * @return provider config
   */
  public static Optional<GatewayRuntimeConfig.ProviderConfig> providerConfigured() {
    return Optional.of(providerConfig(URI.create("http://127.0.0.1:1")));
  }

  public static GatewayRuntimeConfig.ProviderConfig providerConfig(final URI endpoint) {
    return new GatewayRuntimeConfig.ProviderConfig(
        new GatewayRuntimeConfig.ProviderTransportConfig(
            endpoint,
            new PinnedVersion("2024-10-01"),
            null,
            null,
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            java.net.http.HttpClient.Version.HTTP_1_1,
            8,
            false, // loopback test endpoints are plaintext; production config sets tlsRequired
            true),
        Optional.of(capabilities()),
        Optional.of(
            new GatewayRuntimeConfig.CredentialBinding(new SnapshotVersion("credentials", "v1"))),
        Optional.empty(),
        List.of(openAiModule(endpoint)));
  }

  /**
   * The reference provider, wired as a module.
   *
   * <p>Test code may name a provider; the composition root may not. This is where the fixture
   * supplies what an operator would supply, and it is the whole of what "add a provider" costs —
   * one module, declaring its routes, handed to the runtime.
   *
   * @param endpoint the loopback endpoint the transport should target
   * @return the provider module
   */
  public static OpenAiProviderModule openAiModule(final URI endpoint) {
    final OpenAiConfiguration configuration =
        new OpenAiConfiguration(
            endpoint,
            new PinnedVersion("2024-10-01"),
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            null,
            true);
    return new OpenAiProviderModule(
        configuration,
        java.net.http.HttpClient.Version.HTTP_1_1,
        List.of(
            new ProviderRoute(
                MODEL,
                "route-a",
                // Exactly the tokens capabilities() publishes, so discovery finds neither an
                // over-claim nor an under-claim and the fixture's fault list stays empty.
                CapabilitySet.of(
                    ProviderCapability.STREAMING,
                    ProviderCapability.FUNCTION_CALLING,
                    ProviderCapability.JSON_MODE,
                    ProviderCapability.REASONING,
                    ProviderCapability.VISION),
                new PinnedVersion("2024-10-01"),
                8192L,
                4096L)));
  }

  /**
   * A policy permitting the fixture's model, region and capabilities.
   *
   * @return the permitting policy set
   */
  public static PolicySet permittingPolicy() {
    return new PolicySet(
        new SnapshotVersion("policy", "v1"),
        true,
        Set.of(MODEL),
        Set.of("us-east-1"),
        Set.of("chat"),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        1_000L);
  }

  /**
   * A policy whose tenant is switched off — every request for it must be denied.
   *
   * @return the disabled-tenant policy set
   */
  public static PolicySet disabledTenantPolicy() {
    final PolicySet base = permittingPolicy();
    return new PolicySet(
        base.version(),
        false,
        base.allowedModels(),
        base.allowedRegions(),
        base.allowedCapabilities(),
        base.allowedTools(),
        base.complianceRegimes(),
        base.approvalRequiredCapabilities(),
        base.gatedFeatures(),
        base.maxRequestsPerWindow());
  }

  /**
   * A policy placing the {@code chat} capability behind human approval.
   *
   * @return the approval-gated policy set
   */
  public static PolicySet approvalRequiredPolicy() {
    final PolicySet base = permittingPolicy();
    return new PolicySet(
        base.version(),
        base.tenantEnabled(),
        base.allowedModels(),
        base.allowedRegions(),
        base.allowedCapabilities(),
        base.allowedTools(),
        base.complianceRegimes(),
        Set.of("chat"),
        base.gatedFeatures(),
        base.maxRequestsPerWindow());
  }

  /**
   * Governance wiring backed by in-memory snapshots.
   *
   * @param policy the policy served for {@link #TENANT}, or {@code null} to serve none
   *     (fail-closed)
   * @param audit the audit sink to observe decisions with
   * @return the governance config
   */
  public static GatewayRuntimeConfig.GovernanceConfig governanceConfig(
      final PolicySet policy, final AuditSinkPort audit) {
    return new GatewayRuntimeConfig.GovernanceConfig(
        tenantScope -> Optional.ofNullable(policy),
        tenantScope ->
            Optional.of(
                new Entitlement(new SnapshotVersion("entitlement", "v1"), 500L, 1_000_000L)),
        tenantScope -> Optional.of(new UsageState(0L, 0L, Instant.parse("2026-01-01T00:00:00Z"))),
        (tenantScope, feature) -> Optional.of(Boolean.TRUE),
        audit,
        Duration.ofSeconds(60));
  }

  /**
   * The full seam set with an identity verifier that authenticates {@code principal-a}, so a
   * composition test reaches the GOVERNANCE stage instead of stopping at AUTHN.
   *
   * @return an all-present seam set whose verifier succeeds
   */
  public static ExternalAdapters authenticatingExternalAdapters() {
    final ExternalAdapters base = allExternalAdapters();
    return new ExternalAdapters(
        base.ingress(),
        Optional.of(
            (transportIdentity, keySnapshot) ->
                new VerificationOutcome.Verified(
                    new io.reliabilityai.gateway.canonical.identity.PrincipalId("principal-a"),
                    Map.of(),
                    "jws")),
        base.governance(),
        base.providerTransport(),
        base.providerTranslator(),
        base.credentialPort(),
        base.capabilitySnapshot(),
        base.schemaValidator(),
        base.providerGeneration());
  }

  static CapabilityRegistrySnapshot capabilitySnapshot() {
    return new CapabilityRegistrySnapshot(
        new SnapshotVersion("capabilities", "v1"),
        List.of(
            new CapabilityDescriptor(
                "candidate-a",
                MODEL,
                "route-a",
                Set.of("chat"),
                8192,
                Set.of(),
                Set.of("us-east-1"),
                1_000L,
                0.99d,
                100L,
                false)));
  }

  static PricingSnapshot pricingSnapshot() {
    return new PricingSnapshot(
        "pricing-v1",
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2027-01-01T00:00:00Z"),
        Map.of(
            MODEL.value(),
            new PricingDescriptor(
                MODEL,
                new Region("us-east-1"),
                "USD",
                PricingClass.LIST,
                new UnitRates(10L, 30L, 1L, 0L),
                "pricing-v1")),
        new FxTable(
            "fx-v1", Instant.parse("2027-01-01T00:00:00Z"), "USD", Map.of("USD", FxRate.IDENTITY)));
  }

  static VerificationKeySnapshot keySnapshot() {
    return new VerificationKeySnapshot(
        new SnapshotVersion("verification-keys", "v1"),
        new Region("us-east-1"),
        List.of(),
        List.of());
  }

  static TenantScopeSnapshot tenantSnapshot() {
    return new TenantScopeSnapshot(
        new SnapshotVersion("tenant-scopes", "v1"),
        new Region("us-east-1"),
        Map.of("principal-a", TenantScope.of("org-a", "tenant-a")));
  }

  /**
   * A full set of external seams. These are inert test doubles standing in for adapters that live
   * outside this repository — they exist to prove the composition can reach {@code READY} when
   * every seam is supplied, and are never invoked by a lifecycle test.
   *
   * @return an all-present seam set
   */
  public static ExternalAdapters allExternalAdapters() {
    return allExternalAdapters(new RecordingIngress());
  }

  /**
   * A full seam set using a caller-supplied ingress, so a test can assert on ingress ordering.
   *
   * @param ingress the ingress lifecycle to wire
   * @return an all-present seam set
   */
  public static ExternalAdapters allExternalAdapters(final IngressLifecycle ingress) {
    return new ExternalAdapters(
        Optional.of(ingress),
        Optional.of(new StubIdentityVerifier()),
        Optional.of(
            (requestContext, principal, tenant) -> {
              throw new UnsupportedOperationException("stub governance is never invoked");
            }),
        Optional.of(new StubTransport()),
        Optional.of(new StubTranslator()),
        Optional.of(routeTarget -> Optional.<CredentialLease>empty()),
        Optional.of(new StubCapabilitySnapshot()),
        Optional.of(new StubSchemaValidator()),
        Optional.of(new StubGeneration()));
  }

  /** Records ingress lifecycle edges so ordering against the broker can be asserted. */
  public static final class RecordingIngress implements IngressLifecycle {
    private final List<String> edges = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void startAccepting() {
      edges.add("start");
    }

    @Override
    public void stopAccepting() {
      edges.add("stop");
    }

    List<String> edges() {
      return List.copyOf(edges);
    }
  }

  private static final class StubIdentityVerifier implements IdentityVerifierPort {
    @Override
    public VerificationOutcome verify(
        final io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity transportIdentity,
        final VerificationKeySnapshot keySnapshot) {
      return new VerificationOutcome.Rejected(AuthenticationFailureReason.MALFORMED_TOKEN);
    }
  }

  private static final class StubTransport implements ProviderTransportPort {
    @Override
    public TransportResponse exchange(
        final TransportRequest request,
        final CredentialLease credential,
        final AttemptBudget budget)
        throws TransportException {
      throw new TransportException(
          io.reliabilityai.gateway.dataplane.provider.domain.TransportFailureKind.CONNECT, "stub");
    }
  }

  private static final class StubTranslator implements ProviderTranslator {
    @Override
    public TransportRequest translateOut(
        final CanonicalRequest request,
        final CapabilityMapping capabilities,
        final PinnedVersion pinnedVersion) {
      return new TransportRequest("endpoint", Map.of(), new byte[0], pinnedVersion);
    }

    @Override
    public ProviderInvocationResult translateIn(final TransportResponse response) {
      return new ProviderInvocationResult.Failed(error());
    }

    @Override
    public CanonicalError classifyTransportFailure(final TransportException failure) {
      return error();
    }

    private static CanonicalError error() {
      return new CanonicalError(
          io.reliabilityai.gateway.canonical.io.ErrorCategory.PROVIDER_UNAVAILABLE,
          Boolean.FALSE,
          "stub",
          false);
    }
  }

  private static final class StubCapabilitySnapshot implements CapabilitySnapshotPort {
    @Override
    public Optional<CapabilityMapping> mappingFor(
        final io.reliabilityai.gateway.canonical.decision.RouteTarget routeTarget) {
      return Optional.empty();
    }
  }

  private static final class StubSchemaValidator implements SchemaValidatorPort {
    @Override
    public CompiledSchema compile(final OutputSchema schema) {
      return new CompiledSchema(new SchemaId("stub"), "v1", 1);
    }

    @Override
    public ValidationVerdict validate(final CompiledSchema schema, final String outputJson) {
      return ValidationVerdict.nonConformant(FailureClass.NON_CONFORMANT, List.of());
    }

    @Override
    public boolean isStructurallyImpossible(final CompiledSchema schema, final String partialJson) {
      return false;
    }
  }

  private static final class StubGeneration implements ProviderGenerationPort {
    @Override
    public GenerationOutcome generate(final GenerationCommand command) {
      return new GenerationOutcome.Failed(FailureClass.PROVIDER_ERROR);
    }

    @Override
    public void cancel() {
      // nothing to cancel in a stub
    }
  }
}
