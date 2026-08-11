package io.reliabilityai.gateway.dataplane.authn.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.authn.api.AuthAuditPort;
import io.reliabilityai.gateway.dataplane.authn.api.AuthMetricsPort;
import io.reliabilityai.gateway.dataplane.authn.api.AuthenticationDecisionRecord;
import io.reliabilityai.gateway.dataplane.authn.api.IdentityVerifierPort;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import io.reliabilityai.gateway.dataplane.authn.domain.TenantResolver;
import io.reliabilityai.gateway.ports.AuthenticationPort.AuthenticationResult;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.SnapshotSourcePort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Behaviour tests for the C6 authentication node (Doc 37 §6/§14, fail-closed, SPT, ANZ). */
class AuthenticationServiceTest {

  private static final Region REGION = new Region("us-east-1");
  private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
  private static final RequestContext REQUEST =
      new RequestContext(
          new CorrelationId("corr-1"),
          new IdempotencyKey("idem-1"),
          new CausationId("c"),
          null,
          REGION);
  private static final ForwardedTransportIdentity TRANSPORT =
      new ForwardedTransportIdentity("bearer", "token-material", Map.of());
  private static final PrincipalId PRINCIPAL = new PrincipalId("principal-1");

  private final FakeSnapshot<VerificationKeySnapshot> keys = new FakeSnapshot<>();
  private final FakeSnapshot<TenantScopeSnapshot> tenants = new FakeSnapshot<>();
  private final FakeVerifier verifier = new FakeVerifier();
  private final RecordingAudit audit = new RecordingAudit();
  private final CountingMetrics metrics = new CountingMetrics();
  private final ClockPort clock = () -> NOW;
  private final AuthenticationService service =
      new AuthenticationService(
          keys, tenants, verifier, new TenantResolver(), audit, metrics, clock);

  private static VerificationKeySnapshot keySnapshot(final Region region) {
    // The service delegates verification to the injected IdentityVerifierPort (a fake here), so
    // this
    // snapshot only needs valid identity/region; key material is exercised by
    // VerificationKeyRegistryTest.
    return new VerificationKeySnapshot(
        new SnapshotVersion("verification-key", "v1"), region, List.of(), List.of());
  }

  private static TenantScopeSnapshot tenantSnapshot(final Region region, final boolean mapped) {
    return new TenantScopeSnapshot(
        new SnapshotVersion("tenant-scope", "v1"),
        region,
        mapped ? Map.of("principal-1", TenantScope.of("org-1", "tenant-1")) : Map.of());
  }

  private static VerificationOutcome.Verified verified() {
    return new VerificationOutcome.Verified(PRINCIPAL, Map.of("sub", "principal-1"), "bearer");
  }

  @Test
  void authenticatesAndResolvesTenantAfterSuccess() {
    keys.value = keySnapshot(REGION);
    tenants.value = tenantSnapshot(REGION, true);
    verifier.outcome = verified();

    final AuthenticationResult result = service.authenticate(TRANSPORT, REQUEST);

    assertThat(result).isInstanceOf(AuthenticationResult.Authenticated.class);
    final var auth = (AuthenticationResult.Authenticated) result;
    assertThat(auth.principal().principalId()).isEqualTo(PRINCIPAL);
    assertThat(auth.principal().authMethod()).isEqualTo("bearer");
    assertThat(auth.tenant().tenantScope()).isEqualTo(TenantScope.of("org-1", "tenant-1"));
    assertThat(metrics.authenticated).isEqualTo(1);
    assertThat(metrics.tenantResolved).isEqualTo(1);
    assertThat(audit.last.authenticated()).isTrue();
    assertThat(audit.last.keySnapshotVersion().version()).isEqualTo("v1");
    assertThat(audit.last.reason()).isNull(); // no reason on success; content-free
  }

  @Test
  void failsClosedWhenNoKeySnapshotAndNeverVerifies() {
    keys.value = null;
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "key-snapshot-unavailable");
    assertThat(verifier.calls).isZero(); // never verify without pinned keys (VKR-4)
  }

  @Test
  void failsClosedOnKeyRegionMismatch() {
    keys.value = keySnapshot(new Region("eu-west-1"));
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "key-region-mismatch");
  }

  @Test
  void failsClosedWhenVerifierRejectsAndNeverResolvesTenant() {
    keys.value = keySnapshot(REGION);
    tenants.value = tenantSnapshot(REGION, true);
    verifier.outcome =
        new VerificationOutcome.Rejected(AuthenticationFailureReason.INVALID_SIGNATURE);
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "invalid-signature");
    assertThat(tenants.reads).isZero(); // tenant scope resolved ONLY after auth (SPT)
  }

  @Test
  void failsClosedOnRevokedKey() {
    keys.value = keySnapshot(REGION);
    verifier.outcome = new VerificationOutcome.Rejected(AuthenticationFailureReason.KEY_REVOKED);
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "key-revoked");
  }

  @Test
  void failsClosedWhenNoTenantSnapshot() {
    keys.value = keySnapshot(REGION);
    verifier.outcome = verified();
    tenants.value = null;
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "tenant-snapshot-unavailable");
    assertThat(audit.last.principalId())
        .isEqualTo(PRINCIPAL); // authenticated-but-tenant-unresolved
  }

  @Test
  void failsClosedWhenTenantUnresolved() {
    keys.value = keySnapshot(REGION);
    verifier.outcome = verified();
    tenants.value = tenantSnapshot(REGION, false); // principal not mapped
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "tenant-unresolved");
  }

  @Test
  void failsClosedOnTenantRegionMismatch() {
    keys.value = keySnapshot(REGION);
    verifier.outcome = verified();
    tenants.value = tenantSnapshot(new Region("eu-west-1"), true);
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "tenant-snapshot-unavailable");
  }

  @Test
  void failsClosedOnInternalErrorFromVerifier() {
    keys.value = keySnapshot(REGION);
    verifier.throwOnVerify = true; // a raw provider/IdP exception must not escape
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "internal-error");
  }

  @Test
  void failsClosedOnNullVerifierOutcome() {
    keys.value = keySnapshot(REGION);
    verifier.outcome = null; // an ill-behaved verifier returns nothing
    assertUnauthenticated(service.authenticate(TRANSPORT, REQUEST), "internal-error");
    assertThat(tenants.reads).isZero();
  }

  @Test
  void decisionIdIsDeterministic() {
    keys.value = keySnapshot(REGION);
    verifier.outcome = new VerificationOutcome.Rejected(AuthenticationFailureReason.EXPIRED);
    service.authenticate(TRANSPORT, REQUEST);
    final String firstId = audit.last.decisionId();
    service.authenticate(TRANSPORT, REQUEST);
    assertThat(audit.last.decisionId()).isEqualTo(firstId);
  }

  @Test
  void auditAndMetricsFailuresDoNotBlockTheDecision() {
    keys.value = keySnapshot(REGION);
    tenants.value = tenantSnapshot(REGION, true);
    verifier.outcome = verified();
    audit.throwOnRecord = true;
    metrics.throwing = true;
    assertThat(service.authenticate(TRANSPORT, REQUEST))
        .isInstanceOf(AuthenticationResult.Authenticated.class);
  }

  @Test
  void rejectsNullArguments() {
    assertThatThrownBy(() -> service.authenticate(null, REQUEST))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.authenticate(TRANSPORT, null))
        .isInstanceOf(NullPointerException.class);
  }

  private void assertUnauthenticated(final AuthenticationResult result, final String reason) {
    assertThat(result).isInstanceOf(AuthenticationResult.Unauthenticated.class);
    assertThat(((AuthenticationResult.Unauthenticated) result).reason()).isEqualTo(reason);
  }

  private static final class FakeSnapshot<T> implements SnapshotSourcePort<T> {
    private T value;
    private int reads;

    @Override
    public Optional<T> current() {
      reads++;
      return Optional.ofNullable(value);
    }

    @Override
    public Optional<T> pinned(final SnapshotVersion version) {
      return current();
    }
  }

  private static final class FakeVerifier implements IdentityVerifierPort {
    private VerificationOutcome outcome;
    private boolean throwOnVerify;
    private int calls;

    @Override
    public VerificationOutcome verify(
        final ForwardedTransportIdentity transportIdentity,
        final VerificationKeySnapshot keySnapshot) {
      calls++;
      if (throwOnVerify) {
        throw new IllegalStateException("provider verifier blew up");
      }
      return outcome;
    }
  }

  private static final class RecordingAudit implements AuthAuditPort {
    private AuthenticationDecisionRecord last;
    private boolean throwOnRecord;

    @Override
    public void record(final AuthenticationDecisionRecord decision) {
      if (throwOnRecord) {
        throw new IllegalStateException("audit sink down");
      }
      last = decision;
    }
  }

  private static final class CountingMetrics implements AuthMetricsPort {
    private int authenticated;
    private int tenantResolved;
    private boolean throwing;

    @Override
    public void authenticated() {
      if (throwing) {
        throw new IllegalStateException("metrics down");
      }
      authenticated++;
    }

    @Override
    public void unauthenticated(final String reason) {
      if (throwing) {
        throw new IllegalStateException("metrics down");
      }
    }

    @Override
    public void tenantResolved() {
      if (throwing) {
        throw new IllegalStateException("metrics down");
      }
      tenantResolved++;
    }
  }
}
