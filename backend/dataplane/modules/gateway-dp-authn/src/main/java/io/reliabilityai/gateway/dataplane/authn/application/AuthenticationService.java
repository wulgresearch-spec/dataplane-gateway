package io.reliabilityai.gateway.dataplane.authn.application;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.authn.api.AuthAuditPort;
import io.reliabilityai.gateway.dataplane.authn.api.AuthMetricsPort;
import io.reliabilityai.gateway.dataplane.authn.api.AuthenticationDecisionRecord;
import io.reliabilityai.gateway.dataplane.authn.api.IdentityVerifierPort;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import io.reliabilityai.gateway.dataplane.authn.domain.TenantResolver;
import io.reliabilityai.gateway.ports.AuthenticationPort;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.SnapshotSourcePort;
import java.time.Instant;
import java.util.Optional;

/**
 * The C6 authentication node (Doc 37 §6/§7, AD-012/AD-019). Verifies the forwarded caller identity
 * against the pinned public verification-key snapshot, resolves tenant scope <b>only after</b>
 * success (Doc 32 §SPT), and records a content-free decision. It performs <b>authentication
 * only</b> and <b>never authorization</b> (Doc 37 §ANZ, IAU-A1/A17): claims are attached read-only
 * and never evaluated for access. It holds no secret (verification keys are public, IAU-A9), makes
 * no online IdP call (VKR-3), and <b>fails closed</b> on every uncertainty (Doc 37 §14, IAU-A5).
 * Stateless and virtual-thread-friendly (no locks); observability is side-effect-free (never
 * affects the decision).
 */
public final class AuthenticationService implements AuthenticationPort {

  // A delimiter that cannot appear in an id, written as an explicit escape (no raw control byte).
  private static final String SEP = "\0"; // real NUL delimiter; combined with a length prefix below

  private final SnapshotSourcePort<VerificationKeySnapshot> keySnapshotSource;
  private final SnapshotSourcePort<TenantScopeSnapshot> tenantSnapshotSource;
  private final IdentityVerifierPort verifier;
  private final TenantResolver tenantResolver;
  private final AuthAuditPort audit;
  private final AuthMetricsPort metrics;
  private final ClockPort clock;

  /**
   * Creates the authentication node against its injected ports (AD-002).
   *
   * @param keySnapshotSource the read-only cached verification-key snapshot (Doc 37 §8, AD-022)
   * @param tenantSnapshotSource the read-only cached tenant-scope snapshot (Doc 37 §10, AD-022)
   * @param verifier the identity-verification seam (Doc 37 §9; no online IdP)
   * @param tenantResolver the post-auth tenant resolver (Doc 37 §TRF)
   * @param audit the content-free decision audit seam (Doc 37 §15)
   * @param metrics the content-free metrics seam (Doc 37 §17)
   * @param clock the deterministic time seam (Doc 37 §13)
   */
  public AuthenticationService(
      final SnapshotSourcePort<VerificationKeySnapshot> keySnapshotSource,
      final SnapshotSourcePort<TenantScopeSnapshot> tenantSnapshotSource,
      final IdentityVerifierPort verifier,
      final TenantResolver tenantResolver,
      final AuthAuditPort audit,
      final AuthMetricsPort metrics,
      final ClockPort clock) {
    this.keySnapshotSource = Preconditions.requireNonNull(keySnapshotSource, "keySnapshotSource");
    this.tenantSnapshotSource =
        Preconditions.requireNonNull(tenantSnapshotSource, "tenantSnapshotSource");
    this.verifier = Preconditions.requireNonNull(verifier, "verifier");
    this.tenantResolver = Preconditions.requireNonNull(tenantResolver, "tenantResolver");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  @Override
  public AuthenticationResult authenticate(
      final ForwardedTransportIdentity transportIdentity, final RequestContext requestContext) {
    Preconditions.requireNonNull(transportIdentity, "transportIdentity");
    Preconditions.requireNonNull(requestContext, "requestContext");
    final String decisionId = decisionId(requestContext);
    try {
      return doAuthenticate(transportIdentity, requestContext, decisionId);
    } catch (final RuntimeException e) {
      // IAU-A5/AD-012: any internal error fails closed; the exception never escapes and no detail
      // leaks. A raw verifier/provider exception is caught here and mapped to a typed denial.
      safeMetric(() -> metrics.unauthenticated(AuthenticationFailureReason.INTERNAL.code()));
      return new AuthenticationResult.Unauthenticated(AuthenticationFailureReason.INTERNAL.code());
    }
  }

  private AuthenticationResult doAuthenticate(
      final ForwardedTransportIdentity transportIdentity,
      final RequestContext requestContext,
      final String decisionId) {
    final Instant now = clock.now();

    // 1. Pin the verification-key snapshot (pre-auth, not tenant-scoped, Doc 37 KPO-1/KPO-3).
    final Optional<VerificationKeySnapshot> maybeKeys = keySnapshotSource.current();
    if (maybeKeys.isEmpty()) {
      return deny(
          AuthenticationFailureReason.KEY_SNAPSHOT_UNAVAILABLE, decisionId, null, null, now);
    }
    final VerificationKeySnapshot keys = maybeKeys.get();
    if (!keys.region().equals(requestContext.region())) {
      // Region confinement (Doc 37 §MRI, AD-014, IAU-A13).
      return deny(
          AuthenticationFailureReason.KEY_REGION_MISMATCH, decisionId, null, keys.version(), now);
    }

    // 2. Verify the forwarded identity against the pinned public keys (no online IdP, VKR-3).
    final VerificationOutcome outcome = verifier.verify(transportIdentity, keys);
    if (outcome instanceof VerificationOutcome.Rejected rejected) {
      return deny(rejected.reason(), decisionId, null, keys.version(), now);
    }
    if (!(outcome instanceof VerificationOutcome.Verified verified)) {
      // Defensive: a null/unexpected verifier return fails closed cleanly (Doc 37 IAU-A5).
      return deny(AuthenticationFailureReason.INTERNAL, decisionId, null, keys.version(), now);
    }
    final PrincipalId principalId = verified.principalId();

    // 3. Resolve tenant scope ONLY after successful authentication (Doc 32 §SPT, Doc 37 TRF-1).
    final Optional<TenantScopeSnapshot> maybeTenant = tenantSnapshotSource.current();
    if (maybeTenant.isEmpty()) {
      return deny(
          AuthenticationFailureReason.TENANT_SNAPSHOT_UNAVAILABLE,
          decisionId,
          principalId,
          keys.version(),
          now);
    }
    final TenantScopeSnapshot tenantSnapshot = maybeTenant.get();
    if (!tenantSnapshot.region().equals(requestContext.region())) {
      return deny(
          AuthenticationFailureReason.TENANT_SNAPSHOT_UNAVAILABLE,
          decisionId,
          principalId,
          keys.version(),
          now);
    }
    final Optional<TenantScope> maybeScope = tenantResolver.resolve(tenantSnapshot, principalId);
    if (maybeScope.isEmpty()) {
      // Authenticated but no resolvable tenant — distinct terminal, still fail closed (Doc 37
      // TRF-3).
      return deny(
          AuthenticationFailureReason.TENANT_UNRESOLVED,
          decisionId,
          principalId,
          keys.version(),
          now);
    }
    final TenantScope tenantScope = maybeScope.get();

    // 4. Success: build the principal + tenant context, record the decision content-free.
    final PrincipalContext principal =
        new PrincipalContext(principalId, verified.claims(), verified.authMethod(), decisionId);
    final TenantContext tenant = new TenantContext(tenantScope);
    safeAudit(
        new AuthenticationDecisionRecord(
            decisionId, true, principalId, null, keys.version(), tenantScope, now));
    safeMetric(metrics::authenticated);
    safeMetric(metrics::tenantResolved);
    return new AuthenticationResult.Authenticated(principal, tenant);
  }

  private AuthenticationResult deny(
      final AuthenticationFailureReason reason,
      final String decisionId,
      final PrincipalId principalId,
      final SnapshotVersion keyVersion,
      final Instant now) {
    safeMetric(() -> metrics.unauthenticated(reason.code()));
    safeAudit(
        new AuthenticationDecisionRecord(
            decisionId, false, principalId, reason.code(), keyVersion, null, now));
    return new AuthenticationResult.Unauthenticated(reason.code());
  }

  private static String decisionId(final RequestContext requestContext) {
    // Deterministic (no RNG/UUID): recorded so replay reproduces the same decision id (Doc 37
    // §RTS).
    // Length-prefixed, collision-free join: because idempotencyKey is client-supplied, a plain
    // delimiter could be injected to collide two distinct decisions; the length prefix fixes each
    // component's boundary unambiguously regardless of its content.
    final String correlationId = requestContext.correlationId().value();
    final String idempotencyKey = requestContext.idempotencyKey().value();
    return correlationId.length() + SEP + correlationId + SEP + idempotencyKey;
  }

  private void safeAudit(final AuthenticationDecisionRecord decision) {
    try {
      audit.record(decision);
    } catch (final RuntimeException e) {
      // Audit-sink faults never block the authentication decision; durability is the frozen RPO=0
      // mechanism's responsibility (Doc 07/Doc 08 §10). The record is content-free.
    }
  }

  private void safeMetric(final Runnable emit) {
    try {
      emit.run();
    } catch (final RuntimeException e) {
      // Telemetry is side-effect-free toward the decision (Doc 27 OT-A1).
    }
  }
}
