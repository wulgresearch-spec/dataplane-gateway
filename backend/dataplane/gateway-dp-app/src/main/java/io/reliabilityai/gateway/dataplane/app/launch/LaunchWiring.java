package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.canonical.capability.ProviderCapability;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.ConfigSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.app.binding.ProviderCapabilityEntry;
import io.reliabilityai.gateway.dataplane.app.binding.SnapshotCapabilitySource;
import io.reliabilityai.gateway.dataplane.app.runtime.ExternalAdapters;
import io.reliabilityai.gateway.dataplane.app.runtime.GatewayRuntimeConfig;
import io.reliabilityai.gateway.dataplane.cost.domain.ContractEntitlement;
import io.reliabilityai.gateway.dataplane.cost.domain.FxRate;
import io.reliabilityai.gateway.dataplane.cost.domain.FxTable;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingClass;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingDescriptor;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingSnapshot;
import io.reliabilityai.gateway.dataplane.cost.domain.UnitRates;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalConfig;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalSyncPolicy;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryBackoff;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryPolicy;
import io.reliabilityai.gateway.dataplane.governance.domain.Entitlement;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySet;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageState;
import io.reliabilityai.gateway.dataplane.ingress.IngressConfig;
import io.reliabilityai.gateway.dataplane.metering.api.MeteringPolicy;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageDescriptor;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModule;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRoute;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicy;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistrySnapshot;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicy;
import io.reliabilityai.gateway.dataplane.schema.validator.api.SchemaValidatorConfig;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPolicy;
import io.reliabilityai.gateway.dataplane.secrets.adapter.SystemClock;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles a startable {@link GatewayRuntimeConfig} from {@link LaunchSettings}.
 *
 * <p>This is the operator-facing half of the composition root: the runtime knows how to wire
 * twenty-odd components together, but deliberately knows nothing about which provider, tenant,
 * policy or price list a given deployment runs. Those are supplied here.
 *
 * <p>Scope, stated plainly: this wires <b>one</b> tenant, on <b>one</b> route, against <b>one</b>
 * provider, with policy and pricing held in memory. That is a real governed path end to end, not a
 * mock — but a node serving many tenants needs those snapshots served from a control plane rather
 * than compiled in, and that is the boundary this class stops at.
 */
final class LaunchWiring {

  /** The single tenant this node serves. */
  static final TenantScope TENANT = TenantScope.of("org-local", "tenant-local");

  /** The principal an authenticated caller resolves to. */
  static final PrincipalId PRINCIPAL = new PrincipalId("principal-local");

  /** The region every snapshot is scoped to. */
  static final Region REGION = new Region("us-east-1");

  /** The provider route the model is reachable on. */
  static final String ROUTE_REF = "route-primary";

  /** The provider API version pinned on every request. */
  private static final PinnedVersion API_VERSION = new PinnedVersion("2024-10-01");

  private static final SnapshotVersion CREDENTIAL_VERSION =
      new SnapshotVersion("credentials", "v1");

  /** Seeded once and reused: a fresh instance per call reseeds needlessly. */
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private LaunchWiring() {}

  /**
   * The credential snapshot version the provider binding resolves against.
   *
   * @return the credential snapshot version
   */
  static SnapshotVersion credentialVersion() {
    return CREDENTIAL_VERSION;
  }

  /**
   * The route credentials are published for.
   *
   * @param settings the launch settings naming the model
   * @return the route target
   */
  static RouteTarget route(final LaunchSettings settings) {
    return new RouteTarget(settings.model(), ROUTE_REF);
  }

  /**
   * Builds the complete runtime configuration.
   *
   * @param settings the operator-supplied settings
   * @return a configuration the runtime can start
   */
  static GatewayRuntimeConfig config(final LaunchSettings settings) {
    return config(settings, ProviderDiscovery.module(settings));
  }

  /**
   * Builds the configuration around an already-chosen provider module.
   *
   * @param settings the operator-supplied settings
   * @param module the discovered provider adapter
   * @return a configuration the runtime can start
   */
  static GatewayRuntimeConfig config(final LaunchSettings settings, final ProviderModule module) {
    final Path walDirectory = settings.dataDirectory().resolve("wal");
    final Path deadLetterFile = settings.dataDirectory().resolve("dead-letter.log");
    return new GatewayRuntimeConfig(
        settings.nodeId(),
        configSnapshot(),
        masterKey(),
        new SystemClock(Clock.systemUTC()),
        new GatewayRuntimeConfig.EventingConfig(
            walDirectory,
            new WalConfig(4096, WalSyncPolicy.ALWAYS),
            new InProcessWalCodec(),
            deadLetterFile,
            64,
            Duration.ofSeconds(2),
            record -> {},
            new RetryPolicy(3),
            RetryBackoff.NONE,
            GatewayRuntimeConfig.Topics.defaults()),
        new GatewayRuntimeConfig.AuthnConfig(keySnapshot(), tenantSnapshot()),
        Optional.empty(),
        Optional.of(governance(settings)),
        Optional.of(provider(settings, module)),
        Optional.of(
            new IngressConfig(
                settings.bindHost(),
                settings.bindPort(),
                IngressConfig.DEFAULT_MAX_BODY_BYTES,
                Duration.ofSeconds(30),
                64,
                Duration.ofSeconds(5))),
        Optional.empty(),
        new GatewayRuntimeConfig.RoutingConfig(
            capabilityRegistry(module), new RoutingPolicy(1L, 1L, 0L, 0.0d, Set.of()), Map.of()),
        new GatewayRuntimeConfig.ReliabilityConfig(
            new ReliabilityPolicy(3, 1, 10L, 100L, Duration.ofSeconds(5)),
            10,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30)),
        new GatewayRuntimeConfig.AccountingConfig(
            Map.of(settings.model(), new UsageDescriptor(settings.model(), "usage-v1")),
            MeteringPolicy.FAIL_CLOSED,
            1024,
            pricing(settings),
            Map.of(),
            new ContractEntitlement("contract-local", Set.of(PricingClass.LIST)),
            64),
        new GatewayRuntimeConfig.CorrectnessConfig(
            new SchemaLockPolicy(3, 65_536, 32, Duration.ofSeconds(5)),
            64,
            new StreamGuardPolicy(
                1_048_576L, 65_536, 128, Duration.ofSeconds(30), Duration.ofMinutes(5)),
            Optional.of(SchemaValidatorConfig.defaults())),
        new GatewayRuntimeConfig.SecretsConfig(Duration.ofSeconds(30), 4096),
        externalAdapters(settings));
  }

  /**
   * The seam set.
   *
   * <p>Ingress, governance and the provider are configured above, and the runtime prefers
   * configuration over seams, so only two seams are supplied here: the identity verifier, and a
   * generation driver that declares structured-output repair unavailable. SCHEMA_LOCK is a
   * mandatory stage, so it must be bound to something for the node to start at all.
   */
  private static ExternalAdapters externalAdapters(final LaunchSettings settings) {
    return new ExternalAdapters(
        Optional.empty(),
        Optional.of(new StaticKeyIdentityVerifier(settings.clientApiKey(), PRINCIPAL)),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(new UnsupportedProviderGeneration()));
  }

  /**
   * Governance wiring backed by in-memory snapshots.
   *
   * <p>The usage reading is always zero and always current. That is truthful about what this node
   * knows — a single process with no usage coordination tier has no cross-node consumption to
   * report — but it has a consequence worth stating: <b>the quota, rate and budget ceilings in the
   * policy never trigger</b>, because nothing ever reports consumption against them. Model, region,
   * capability and tenant-enabled checks are fully enforced; the counting checks are not.
   *
   * <p>The reading must carry a current timestamp rather than a fixed one: the evaluator denies
   * with {@code policy-unavailable} when a usage reading is staler than its tolerance, which is the
   * correct response to a node that cannot see consumption but is asked to enforce a ceiling.
   */
  private static GatewayRuntimeConfig.GovernanceConfig governance(final LaunchSettings settings) {
    return new GatewayRuntimeConfig.GovernanceConfig(
        tenantScope -> Optional.of(policy(settings)),
        tenantScope ->
            Optional.of(
                new Entitlement(new SnapshotVersion("entitlement", "v1"), 500L, 1_000_000L)),
        tenantScope -> Optional.of(new UsageState(0L, 0L, Clock.systemUTC().instant())),
        (tenantScope, feature) -> Optional.of(Boolean.TRUE),
        new StdoutAuditSink(System.out),
        Duration.ofSeconds(60));
  }

  private static PolicySet policy(final LaunchSettings settings) {
    return new PolicySet(
        new SnapshotVersion("policy", "v1"),
        true,
        Set.of(settings.model()),
        Set.of(REGION.value()),
        Set.of("chat"),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        1_000L);
  }

  private static GatewayRuntimeConfig.ProviderConfig provider(
      final LaunchSettings settings, final ProviderModule module) {
    return new GatewayRuntimeConfig.ProviderConfig(
        new GatewayRuntimeConfig.ProviderTransportConfig(
            settings.providerBaseUri(),
            API_VERSION,
            null,
            null,
            settings.connectTimeout(),
            settings.requestTimeout(),
            HttpClient.Version.HTTP_1_1,
            8,
            settings.tlsRequired(),
            true),
        Optional.of(capabilities(module)),
        Optional.of(new GatewayRuntimeConfig.CredentialBinding(CREDENTIAL_VERSION)),
        Optional.empty(),
        List.of(module));
  }

  /**
   * The capability snapshot, derived from what the adapter declares.
   *
   * <p>Read from the module rather than written out here on purpose. The runtime compares the
   * operator snapshot against the adapter declaration and records a fault on either an over-claim
   * or an under-claim, so a hand-written copy is a second source of truth that silently rots the
   * first time a provider gains a capability.
   */
  private static SnapshotCapabilitySource.CapabilitySnapshot capabilities(
      final ProviderModule module) {
    final List<ProviderCapabilityEntry> entries = new ArrayList<>();
    for (final ProviderRoute route : module.descriptor().routes()) {
      entries.add(
          new ProviderCapabilityEntry(
              route.canonicalModelId(),
              route.providerRouteRef(),
              route.apiVersion(),
              route.capabilities().supports(ProviderCapability.FUNCTION_CALLING),
              route.capabilities().supports(ProviderCapability.STREAMING),
              route.capabilities().supports(ProviderCapability.JSON_MODE),
              route.capabilities().supports(ProviderCapability.REASONING),
              route.capabilities().supports(ProviderCapability.VISION),
              route.capabilities().supports(ProviderCapability.EMBEDDINGS),
              route.maxOutputTokens(),
              Map.of()));
    }
    return new SnapshotCapabilitySource.CapabilitySnapshot(
        new SnapshotVersion("provider-capabilities", "v1"), List.copyOf(entries));
  }

  /** The routing registry, likewise derived from the adapter declaration. */
  private static CapabilityRegistrySnapshot capabilityRegistry(final ProviderModule module) {
    final List<CapabilityDescriptor> descriptors = new ArrayList<>();
    for (final ProviderRoute route : module.descriptor().routes()) {
      descriptors.add(
          new CapabilityDescriptor(
              "candidate-" + route.providerRouteRef(),
              route.canonicalModelId(),
              route.providerRouteRef(),
              Set.of("chat"),
              Math.toIntExact(route.maxContextTokens()),
              Set.of(),
              Set.of(REGION.value()),
              1_000L,
              0.99d,
              100L,
              false));
    }
    return new CapabilityRegistrySnapshot(
        new SnapshotVersion("capabilities", "v1"), List.copyOf(descriptors));
  }

  /**
   * The price list the cost engine charges against.
   *
   * <p>These rates are placeholders, not the provider list price. Rates are integers in micro-units
   * per token, and this model costs a fraction of one micro-unit per token, which would round to
   * zero and make every cost record read {@code 0}. The 1:4 input-to-output ratio is kept so the
   * shape of the number is right; the magnitude is not. Billing a customer against this table would
   * be wrong.
   */
  private static PricingSnapshot pricing(final LaunchSettings settings) {
    return new PricingSnapshot(
        "pricing-local",
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2030-01-01T00:00:00Z"),
        Map.of(
            settings.model().value(),
            new PricingDescriptor(
                settings.model(),
                REGION,
                "USD",
                PricingClass.LIST,
                new UnitRates(1L, 4L, 1L, 0L),
                "pricing-local")),
        new FxTable(
            "fx-local",
            Instant.parse("2030-01-01T00:00:00Z"),
            "USD",
            Map.of("USD", FxRate.IDENTITY)));
  }

  private static ConfigSnapshot configSnapshot() {
    return new ConfigSnapshot(
        new SnapshotVersion("config", "v1"),
        1,
        REGION,
        Map.of("finalization.timeout.ms", "2000"),
        List.of(),
        Map.of("deny.default", "true"));
  }

  private static VerificationKeySnapshot keySnapshot() {
    return new VerificationKeySnapshot(
        new SnapshotVersion("verification-keys", "v1"), REGION, List.of(), List.of());
  }

  private static TenantScopeSnapshot tenantSnapshot() {
    return new TenantScopeSnapshot(
        new SnapshotVersion("tenant-scopes", "v1"), REGION, Map.of(PRINCIPAL.value(), TENANT));
  }

  /**
   * The secrets master key.
   *
   * <p>Generated per process and never persisted: it protects credential material held in memory
   * for the lifetime of this node, and the runtime zeroizes it on stop. A multi-node deployment
   * needs a shared key from a KMS instead, or two nodes cannot read the same published credential
   * snapshot.
   */
  private static byte[] masterKey() {
    final byte[] key = new byte[32];
    SECURE_RANDOM.nextBytes(key);
    return key;
  }
}
