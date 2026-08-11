package io.reliabilityai.gateway.dataplane.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.canonical.capability.ProviderCapability;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.ProviderMeta;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilitySnapshotPort;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderDescriptor;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderFault;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderHealth;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderInstance;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModule;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderProbe;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRegistration;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRoute;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRuntimeContext;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderState;
import io.reliabilityai.gateway.dataplane.provider.domain.CapabilityNegotiation;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests the provider platform: negotiation, discovery, the registry and the lifecycle.
 *
 * <p>The theme running through these is that a provider is described by what it can do and
 * identified by an opaque key. Nothing here — and nothing in the classes under test — compares a
 * provider to a name.
 */
class ProviderPlatformTest {

  private static final CanonicalModelId MODEL = new CanonicalModelId("model.test.v1");
  private static final CanonicalModelId OTHER_MODEL = new CanonicalModelId("model.other.v1");
  private static final PinnedVersion API = new PinnedVersion("2024-10-01");
  private static final AttemptBudget BUDGET = new AttemptBudget(Duration.ofSeconds(1));

  // ---- helpers ---------------------------------------------------------------------------------

  private static ProviderRoute route(
      final String routeRef,
      final CanonicalModelId model,
      final ProviderCapability... capabilities) {
    return new ProviderRoute(model, routeRef, CapabilitySet.of(capabilities), API, 8192L, 4096L);
  }

  /** A snapshot that grants exactly the tokens it is told to, for each declared route. */
  private static CapabilitySnapshotPort snapshot(final Map<String, CapabilitySet> byRouteAndModel) {
    return target ->
        Optional.ofNullable(
                byRouteAndModel.get(
                    target.providerRouteRef() + "|" + target.canonicalModelId().value()))
            .map(
                set ->
                    new CapabilityMapping(
                        target.canonicalModelId(),
                        new SnapshotVersion("capabilities", "v1"),
                        API,
                        set.tokenSet()));
  }

  private static ProviderRuntimeContext context() {
    return new ProviderRuntimeContext(
        target -> Optional.empty(),
        target -> Optional.empty(),
        io.reliabilityai.gateway.dataplane.provider.api.AdapterTelemetryPort.NO_OP,
        () -> java.time.Instant.parse("2026-01-01T00:00:00Z"));
  }

  /** A module that declares what it is told to and hands back a counting adapter. */
  private static final class StubModule implements ProviderModule {
    private final ProviderDescriptor descriptor;
    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final boolean failStart;
    private final ProviderProbe probe;

    StubModule(final ProviderId id, final List<ProviderRoute> routes) {
      this(id, routes, false, null);
    }

    StubModule(
        final ProviderId id,
        final List<ProviderRoute> routes,
        final boolean failStart,
        final ProviderProbe probe) {
      this.descriptor = new ProviderDescriptor(id, routes);
      this.failStart = failStart;
      this.probe = probe;
    }

    @Override
    public ProviderDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ProviderInstance start(final ProviderRuntimeContext runtimeContext) {
      if (failStart) {
        throw new IllegalStateException("cannot reach configured endpoint");
      }
      final ProviderAdapterPort adapter =
          (request, target, budget) -> {
            invocations.incrementAndGet();
            return new ProviderAdapterPort.ProviderInvocationResult.Unary(
                new CanonicalResponse(
                    "",
                    List.of(),
                    FinishReason.STOP,
                    new CanonicalUsage(1L, 1L, 0L, 0L, 0L, UsageClass.AUTHORITATIVE),
                    ProviderMeta.empty()));
          };
      return new ProviderInstance(
          descriptor.providerId(), adapter, Optional.ofNullable(probe), closes::incrementAndGet);
    }
  }

  private static CanonicalRequest request() {
    return new CanonicalRequest(MODEL, List.of(), List.of(), Map.of());
  }

  // ---- negotiation -----------------------------------------------------------------------------

  @Test
  void negotiationSucceedsWhenEverythingRequiredIsOffered() {
    final CapabilityNegotiation result =
        CapabilityNegotiation.negotiate(
            CapabilitySet.of(ProviderCapability.STREAMING),
            CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.VISION));

    assertThat(result.satisfied()).isTrue();
    assertThat(result.missing().isEmpty()).isTrue();
  }

  @Test
  void negotiationNamesWhatIsMissing() {
    final CapabilityNegotiation result =
        CapabilityNegotiation.negotiate(
            CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.JSON_SCHEMA),
            CapabilitySet.of(ProviderCapability.STREAMING));

    assertThat(result.satisfied()).isFalse();
    assertThat(result.missing().tokens()).containsExactly("json_schema");
  }

  @Test
  void theNegotiatedSetIsWhatTheRequestUsesNotEverythingTheRouteCanDo() {
    final CapabilityNegotiation result =
        CapabilityNegotiation.negotiate(
            CapabilitySet.of(ProviderCapability.STREAMING),
            CapabilitySet.of(
                ProviderCapability.STREAMING, ProviderCapability.BATCH, ProviderCapability.VISION));

    // Recording the whole declaration would claim the request used capabilities it never touched.
    assertThat(result.negotiated().tokens()).containsExactly("streaming");
  }

  @Test
  void aNegotiationCannotClaimSuccessWhileNamingAShortfall() {
    assertThatThrownBy(
            () ->
                new CapabilityNegotiation(
                    true,
                    CapabilitySet.of(ProviderCapability.STREAMING),
                    CapabilitySet.none(),
                    CapabilitySet.of(ProviderCapability.STREAMING)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- descriptors -----------------------------------------------------------------------------

  @Test
  void aDescriptorReportsTheRoutesItServes() {
    final ProviderDescriptor descriptor =
        new ProviderDescriptor(
            ProviderId.of("p1"),
            List.of(route("r1", MODEL, ProviderCapability.STREAMING), route("r2", OTHER_MODEL)));

    assertThat(descriptor.routeRefs()).containsExactlyInAnyOrder("r1", "r2");
    assertThat(descriptor.route("r1", MODEL)).isPresent();
    assertThat(descriptor.route("r1", OTHER_MODEL)).isEmpty();
  }

  @Test
  void aDescriptorCannotDeclareTheSameModelOnTheSameRouteTwice() {
    assertThatThrownBy(
            () ->
                new ProviderDescriptor(
                    ProviderId.of("p1"),
                    List.of(route("r1", MODEL, ProviderCapability.STREAMING), route("r1", MODEL))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aggregateCapabilitiesUnionEveryRoute() {
    final ProviderDescriptor descriptor =
        new ProviderDescriptor(
            ProviderId.of("p1"),
            List.of(
                route("r1", MODEL, ProviderCapability.STREAMING),
                route("r2", OTHER_MODEL, ProviderCapability.EMBEDDINGS)));

    assertThat(descriptor.aggregateCapabilities().tokens())
        .containsExactly("embeddings", "streaming");
    assertThat(descriptor.offersAnywhere(ProviderCapability.EMBEDDINGS)).isTrue();
    assertThat(descriptor.offersAnywhere(ProviderCapability.BATCH)).isFalse();
  }

  @Test
  void perRouteCapabilitiesAreNotTheProviderWideUnion() {
    final ProviderDescriptor descriptor =
        new ProviderDescriptor(
            ProviderId.of("p1"),
            List.of(
                route("chat", MODEL, ProviderCapability.VISION),
                route("embed", OTHER_MODEL, ProviderCapability.EMBEDDINGS)));

    // The whole reason capability is declared per model: the union would route a vision request to
    // the
    // embeddings endpoint.
    assertThat(
            descriptor
                .route("embed", OTHER_MODEL)
                .orElseThrow()
                .capabilities()
                .supports(ProviderCapability.VISION))
        .isFalse();
  }

  // ---- discovery -------------------------------------------------------------------------------

  @Test
  void discoveryValidatesAModuleThatAgreesWithTheSnapshot() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery()
            .discover(
                List.of(module),
                snapshot(
                    Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    assertThat(report.faults()).isEmpty();
    assertThat(report.validated()).hasSize(1);
    assertThat(report.hasFatalFaults()).isFalse();
  }

  @Test
  void aSnapshotGrantingWhatTheModuleCannotDoIsFatal() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery()
            .discover(
                List.of(module),
                snapshot(
                    Map.of(
                        "r1|" + MODEL.value(),
                        CapabilitySet.of(
                            ProviderCapability.STREAMING, ProviderCapability.JSON_SCHEMA))));

    // The bug class this whole check exists for: routing would select this route for
    // schema-constrained
    // work and the adapter would fail, once per matching request, in production.
    assertThat(report.faults())
        .singleElement()
        .satisfies(
            fault -> {
              assertThat(fault.kind()).isEqualTo(ProviderFault.Kind.CAPABILITY_OVERCLAIM);
              assertThat(fault.detail()).contains("json_schema");
              assertThat(fault.fatal()).isTrue();
            });
    assertThat(report.validated()).isEmpty();
  }

  @Test
  void aModuleOfferingMoreThanTheSnapshotIsReportedButNotFatal() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"),
            List.of(route("r1", MODEL, ProviderCapability.STREAMING, ProviderCapability.BATCH)));
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery()
            .discover(
                List.of(module),
                snapshot(
                    Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    // The extra ability is unused rather than wrong: routing follows the snapshot.
    assertThat(report.faults())
        .singleElement()
        .satisfies(
            fault -> {
              assertThat(fault.kind()).isEqualTo(ProviderFault.Kind.CAPABILITY_UNDERCLAIM);
              assertThat(fault.fatal()).isFalse();
            });
    assertThat(report.validated()).hasSize(1);
  }

  @Test
  void aRouteMissingFromTheSnapshotIsReportedButNotFatal() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery().discover(List.of(module), snapshot(Map.of()));

    assertThat(report.faults())
        .singleElement()
        .satisfies(
            fault -> assertThat(fault.kind()).isEqualTo(ProviderFault.Kind.ROUTE_NOT_PUBLISHED));
    assertThat(report.hasFatalFaults()).isFalse();
  }

  @Test
  void twoModulesClaimingTheSameRouteIsFatal() {
    final CapabilitySnapshotPort published =
        snapshot(Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING)));
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery()
            .discover(
                List.of(
                    new StubModule(
                        ProviderId.of("p1"),
                        List.of(route("r1", MODEL, ProviderCapability.STREAMING))),
                    new StubModule(
                        ProviderId.of("p2"),
                        List.of(route("r1", MODEL, ProviderCapability.STREAMING)))),
                published);

    // Dispatch is keyed on the route, so the second claimant would silently shadow the first.
    assertThat(report.faults())
        .anySatisfy(
            fault -> assertThat(fault.kind()).isEqualTo(ProviderFault.Kind.DUPLICATE_ROUTE));
    assertThat(report.hasFatalFaults()).isTrue();
  }

  @Test
  void twoModulesClaimingTheSameProviderIdIsFatal() {
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery()
            .discover(
                List.of(
                    new StubModule(ProviderId.of("p1"), List.of(route("r1", MODEL))),
                    new StubModule(ProviderId.of("p1"), List.of(route("r2", OTHER_MODEL)))),
                snapshot(Map.of()));

    assertThat(report.faults())
        .anySatisfy(
            fault -> assertThat(fault.kind()).isEqualTo(ProviderFault.Kind.DUPLICATE_PROVIDER));
  }

  @Test
  void discoveryStartsNothing() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    new ProviderDiscovery()
        .discover(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    // A node must be able to validate its provider wiring without opening a connection.
    assertThat(module.closes.get()).isZero();
    assertThat(module.invocations.get()).isZero();
  }

  @Test
  void discoveringNoModulesIsNotAnError() {
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery().discover(List.of(), snapshot(Map.of()));

    assertThat(report.registrations()).isEmpty();
    assertThat(report.hasFatalFaults()).isFalse();
  }

  // ---- lifecycle -------------------------------------------------------------------------------

  private static ProviderLifecycle started(
      final List<ProviderModule> modules, final CapabilitySnapshotPort published) {
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery().discover(modules, published);
    final ProviderLifecycle lifecycle = ProviderLifecycle.from(report, modules);
    lifecycle.startAll(context());
    return lifecycle;
  }

  @Test
  void aValidatedProviderStartsAndBecomesDispatchable() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    final ProviderRegistration registration =
        lifecycle.registry().registration(ProviderId.of("p1")).orElseThrow();
    assertThat(registration.state()).isEqualTo(ProviderState.READY);
    assertThat(registration.dispatchable()).isTrue();
    assertThat(lifecycle.registry().hasDispatchableProvider()).isTrue();
  }

  @Test
  void aRejectedProviderIsNeverStarted() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of(
                    "r1|" + MODEL.value(),
                    CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.BATCH))));

    assertThat(lifecycle.registry().registration(ProviderId.of("p1")).orElseThrow().state())
        .isEqualTo(ProviderState.FAILED);
    assertThat(lifecycle.registry().hasDispatchableProvider()).isFalse();
  }

  @Test
  void oneProviderFailingToStartDoesNotStopTheOthers() {
    final CapabilitySet streaming = CapabilitySet.of(ProviderCapability.STREAMING);
    final StubModule broken =
        new StubModule(
            ProviderId.of("broken"),
            List.of(route("r1", MODEL, ProviderCapability.STREAMING)),
            true,
            null);
    final StubModule working =
        new StubModule(
            ProviderId.of("working"),
            List.of(route("r2", OTHER_MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(broken, working),
            snapshot(
                Map.of("r1|" + MODEL.value(), streaming, "r2|" + OTHER_MODEL.value(), streaming)));

    // A node with three providers and one bad endpoint should serve the other two.
    assertThat(lifecycle.registry().registration(ProviderId.of("broken")).orElseThrow().state())
        .isEqualTo(ProviderState.FAILED);
    assertThat(lifecycle.registry().registration(ProviderId.of("working")).orElseThrow().state())
        .isEqualTo(ProviderState.READY);
    assertThat(lifecycle.registry().dispatchableCount()).isEqualTo(1L);
  }

  @Test
  void aFailedStartIsRecordedAsAFault() {
    final StubModule broken =
        new StubModule(
            ProviderId.of("broken"),
            List.of(route("r1", MODEL, ProviderCapability.STREAMING)),
            true,
            null);
    final ProviderDiscovery.DiscoveryReport report =
        new ProviderDiscovery()
            .discover(
                List.of(broken),
                snapshot(
                    Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));
    final List<ProviderFault> failures =
        ProviderLifecycle.from(report, List.of(broken)).startAll(context());

    assertThat(failures)
        .singleElement()
        .satisfies(fault -> assertThat(fault.kind()).isEqualTo(ProviderFault.Kind.START_FAILED));
  }

  @Test
  void aFailingProbeDegradesWithoutRemovingTheProviderFromService() {
    final ProviderId id = ProviderId.of("p1");
    final StubModule module =
        new StubModule(
            id,
            List.of(route("r1", MODEL, ProviderCapability.STREAMING)),
            false,
            (credential, budget) -> ProviderHealth.unhealthy(id, "unreachable"));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    lifecycle.probe(id, null, BUDGET);

    // Degraded still routes: health is a background observation, and refusing traffic on a stale
    // side-channel verdict would take the routing decision away from the live circuit breaker.
    final ProviderRegistration registration = lifecycle.registry().registration(id).orElseThrow();
    assertThat(registration.state()).isEqualTo(ProviderState.DEGRADED);
    assertThat(registration.dispatchable()).isTrue();
  }

  @Test
  void aRecoveringProbeReturnsAProviderToReady() {
    final ProviderId id = ProviderId.of("p1");
    final AtomicInteger calls = new AtomicInteger();
    final StubModule module =
        new StubModule(
            id,
            List.of(route("r1", MODEL, ProviderCapability.STREAMING)),
            false,
            (credential, budget) ->
                calls.incrementAndGet() == 1
                    ? ProviderHealth.unhealthy(id, "unreachable")
                    : new ProviderHealth(id, true, "ok", List.of()));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    lifecycle.probe(id, null, BUDGET);
    lifecycle.probe(id, null, BUDGET);

    assertThat(lifecycle.registry().registration(id).orElseThrow().state())
        .isEqualTo(ProviderState.READY);
  }

  @Test
  void aProbeThatThrowsBecomesAnUnhealthyVerdictRatherThanAnException() {
    final ProviderId id = ProviderId.of("p1");
    final StubModule module =
        new StubModule(
            id,
            List.of(route("r1", MODEL, ProviderCapability.STREAMING)),
            false,
            (credential, budget) -> {
              throw new IllegalStateException("connection reset");
            });
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    assertThat(lifecycle.probe(id, null, BUDGET))
        .get()
        .satisfies(health -> assertThat(health.detail()).isEqualTo("probe-failed"));
  }

  @Test
  void aProviderWithNoProbeIsRecordedAsNotProbedRatherThanHealthy() {
    final ProviderId id = ProviderId.of("p1");
    final StubModule module =
        new StubModule(id, List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    assertThat(lifecycle.probe(id, null, BUDGET))
        .get()
        .satisfies(
            health -> {
              assertThat(health.healthy()).isFalse();
              assertThat(health.detail()).isEqualTo("not-probed");
            });
    // Not-probed must not degrade a provider that has never been asked.
    assertThat(lifecycle.registry().registration(id).orElseThrow().dispatchable()).isTrue();
  }

  @Test
  void disablingAProviderWithdrawsItFromDispatchWithoutStoppingTheNode() {
    final ProviderId id = ProviderId.of("p1");
    final StubModule module =
        new StubModule(id, List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));

    assertThat(lifecycle.disable(id)).isTrue();

    assertThat(lifecycle.registry().hasDispatchableProvider()).isFalse();
    assertThat(lifecycle.registry().invoke(request(), new RouteTarget(MODEL, "r1"), BUDGET))
        .isInstanceOf(ProviderAdapterPort.ProviderInvocationResult.Failed.class);
  }

  @Test
  void aDisabledProviderCanBeReturnedToService() {
    final ProviderId id = ProviderId.of("p1");
    final StubModule module =
        new StubModule(id, List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));
    lifecycle.disable(id);

    assertThat(lifecycle.enable(id)).isTrue();
    assertThat(lifecycle.registry().hasDispatchableProvider()).isTrue();
  }

  @Test
  void aProviderRejectedAtValidationCannotBeEnabled() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of(
                    "r1|" + MODEL.value(),
                    CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.BATCH))));

    // Its declaration disagreed with the snapshot; enabling it would route traffic to an adapter
    // that
    // cannot serve it.
    assertThat(lifecycle.enable(ProviderId.of("p1"))).isFalse();
  }

  @Test
  void shutdownClosesEveryStartedProvider() {
    final CapabilitySet streaming = CapabilitySet.of(ProviderCapability.STREAMING);
    final StubModule one =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final StubModule two =
        new StubModule(
            ProviderId.of("p2"), List.of(route("r2", OTHER_MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(one, two),
            snapshot(
                Map.of("r1|" + MODEL.value(), streaming, "r2|" + OTHER_MODEL.value(), streaming)));

    lifecycle.stopAll();

    assertThat(one.closes.get()).isEqualTo(1);
    assertThat(two.closes.get()).isEqualTo(1);
    assertThat(lifecycle.registry().hasDispatchableProvider()).isFalse();
  }

  @Test
  void aProviderThatThrowsOnCloseDoesNotStopTheRestClosing() {
    final CapabilitySet streaming = CapabilitySet.of(ProviderCapability.STREAMING);
    final StubModule good =
        new StubModule(
            ProviderId.of("good"), List.of(route("r2", OTHER_MODEL, ProviderCapability.STREAMING)));
    final ProviderModule bad =
        new ProviderModule() {
          @Override
          public ProviderDescriptor descriptor() {
            return new ProviderDescriptor(
                ProviderId.of("bad"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
          }

          @Override
          public ProviderInstance start(final ProviderRuntimeContext runtimeContext) {
            return new ProviderInstance(
                ProviderId.of("bad"),
                (req, target, budget) ->
                    new ProviderAdapterPort.ProviderInvocationResult.Failed(
                        new CanonicalError(
                            io.reliabilityai.gateway.canonical.io.ErrorCategory.UNKNOWN,
                            false,
                            "x",
                            false)),
                Optional.empty(),
                () -> {
                  throw new IllegalStateException("close failed");
                });
          }
        };
    final ProviderLifecycle lifecycle =
        started(
            List.of(bad, good),
            snapshot(
                Map.of("r1|" + MODEL.value(), streaming, "r2|" + OTHER_MODEL.value(), streaming)));

    assertThatCode(lifecycle::stopAll).doesNotThrowAnyException();
    assertThat(good.closes.get()).isEqualTo(1);
  }

  // ---- the registry ----------------------------------------------------------------------------

  @Test
  void theRegistryDispatchesOnTheOpaqueRouteReference() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderRegistry registry =
        started(
                List.of(module),
                snapshot(
                    Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))))
            .registry();

    final ProviderAdapterPort.ProviderInvocationResult result =
        registry.invoke(request(), new RouteTarget(MODEL, "r1"), BUDGET);

    assertThat(result).isInstanceOf(ProviderAdapterPort.ProviderInvocationResult.Unary.class);
    assertThat(module.invocations.get()).isEqualTo(1);
  }

  @Test
  void anUnknownRouteFailsClosed() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderRegistry registry =
        started(
                List.of(module),
                snapshot(
                    Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))))
            .registry();

    final ProviderAdapterPort.ProviderInvocationResult result =
        registry.invoke(request(), new RouteTarget(MODEL, "nowhere"), BUDGET);

    assertThat(result).isInstanceOf(ProviderAdapterPort.ProviderInvocationResult.Failed.class);
  }

  @Test
  void theRegistryAnswersWhatARouteCanDoWithoutNamingItsProvider() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"),
            List.of(route("r1", MODEL, ProviderCapability.STREAMING, ProviderCapability.VISION)));
    final ProviderRegistry registry =
        started(
                List.of(module),
                snapshot(
                    Map.of(
                        "r1|" + MODEL.value(),
                        CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.VISION))))
            .registry();

    // The question the whole platform exists to answer.
    assertThat(registry.capabilitiesOf(new RouteTarget(MODEL, "r1")))
        .get()
        .satisfies(set -> assertThat(set.supports(ProviderCapability.VISION)).isTrue());
    assertThat(registry.capabilitiesOf(new RouteTarget(MODEL, "nowhere"))).isEmpty();
  }

  @Test
  void routesRemainQueryableEvenWhileAProviderIsDisabled() {
    final ProviderId id = ProviderId.of("p1");
    final StubModule module =
        new StubModule(id, List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));
    lifecycle.disable(id);

    // An operator asking "what could this route do" during an incident should still get an answer.
    assertThat(lifecycle.registry().capabilitiesOf(new RouteTarget(MODEL, "r1"))).isPresent();
    assertThat(lifecycle.registry().routes()).hasSize(1);
  }

  @Test
  void updatingAnUnregisteredProviderChangesNothing() {
    final StubModule module =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderRegistry registry =
        started(
                List.of(module),
                snapshot(
                    Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))))
            .registry();

    final ProviderRegistration stranger =
        new ProviderRegistration(
            new ProviderDescriptor(ProviderId.of("other"), List.of()),
            ProviderState.READY,
            Optional.empty(),
            Optional.empty(),
            List.of());

    assertThat(registry.update(stranger)).isFalse();
    assertThat(registry.registrations()).hasSize(1);
  }

  @Test
  void aRegistryWithNoProvidersDispatchesNothing() {
    final ProviderRegistry registry = new ProviderRegistry(List.of());

    assertThat(registry.hasDispatchableProvider()).isFalse();
    assertThat(registry.invoke(request(), new RouteTarget(MODEL, "r1"), BUDGET))
        .isInstanceOf(ProviderAdapterPort.ProviderInvocationResult.Failed.class);
  }

  @Test
  void severalProvidersEachServeTheirOwnRoutes() {
    final CapabilitySet streaming = CapabilitySet.of(ProviderCapability.STREAMING);
    final StubModule one =
        new StubModule(
            ProviderId.of("p1"), List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final StubModule two =
        new StubModule(
            ProviderId.of("p2"), List.of(route("r2", MODEL, ProviderCapability.STREAMING)));
    final ProviderRegistry registry =
        started(
                List.of(one, two),
                snapshot(
                    Map.of("r1|" + MODEL.value(), streaming, "r2|" + MODEL.value(), streaming)))
            .registry();

    registry.invoke(request(), new RouteTarget(MODEL, "r1"), BUDGET);
    registry.invoke(request(), new RouteTarget(MODEL, "r2"), BUDGET);
    registry.invoke(request(), new RouteTarget(MODEL, "r2"), BUDGET);

    // Two providers, one interface, no name comparison anywhere in the dispatch path.
    assertThat(one.invocations.get()).isEqualTo(1);
    assertThat(two.invocations.get()).isEqualTo(2);
  }

  @Test
  void concurrentDispatchAndLifecycleChangesNeverProduceAnUndecidedRequest() throws Exception {
    final ProviderId id = ProviderId.of("p1");
    final StubModule module =
        new StubModule(id, List.of(route("r1", MODEL, ProviderCapability.STREAMING)));
    final ProviderLifecycle lifecycle =
        started(
            List.of(module),
            snapshot(
                Map.of("r1|" + MODEL.value(), CapabilitySet.of(ProviderCapability.STREAMING))));
    final ProviderRegistry registry = lifecycle.registry();
    final List<ProviderAdapterPort.ProviderInvocationResult> results =
        java.util.Collections.synchronizedList(new ArrayList<>());

    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 100; i++) {
        final int index = i;
        pool.execute(
            () -> {
              if (index % 20 == 0) {
                lifecycle.disable(id);
                lifecycle.enable(id);
              }
              results.add(registry.invoke(request(), new RouteTarget(MODEL, "r1"), BUDGET));
            });
      }
    }

    // Every request got a decision: either the adapter answered, or the registry failed it closed.
    // A torn view would have produced a null adapter and an exception.
    assertThat(results).hasSize(100).allSatisfy(result -> assertThat(result).isNotNull());
  }
}
