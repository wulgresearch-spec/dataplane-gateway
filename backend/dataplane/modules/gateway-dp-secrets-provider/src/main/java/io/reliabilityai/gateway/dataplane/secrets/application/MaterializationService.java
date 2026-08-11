package io.reliabilityai.gateway.dataplane.secrets.application;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.secrets.api.CredentialMaterialSource;
import io.reliabilityai.gateway.dataplane.secrets.api.MaterializationRecord;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretSnapshotPort;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretsAuditPort;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretsMetricsPort;
import io.reliabilityai.gateway.dataplane.secrets.domain.SanitizableCredentialLease;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.SecretsProviderPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The credential materialization use-case (Doc 26 §3/§7/§8). Resolves the cached C14 credential
 * snapshot read-only (Doc 26 SP-A2 — no synchronous control-plane call), validates the lease
 * contract (tenant binding, TTL/near-expiry, non-empty; Doc 26 §N CLC), materializes a single-use
 * zeroizable lease (Doc 26 §17.1), and emits a content-free audit record (Doc 26 SP-D12). Every
 * failure or uncertainty is fail-closed to {@code CredentialUnavailable} (Doc 26 SP-INV/SP-D11).
 *
 * <p>Time is read only via {@link ClockPort} (Doc 26 §19, Doc 11 R-063). The near-expiry safety
 * margin is an injected operational baseline (Doc 26 §14.1, SP-A14) — never a hard-coded numeric.
 */
public final class MaterializationService implements SecretsProviderPort {

  private final SecretSnapshotPort snapshotPort;
  private final ClockPort clock;
  private final SecretsAuditPort audit;
  private final SecretsMetricsPort metrics;
  private final Duration nearExpiryMargin;
  private final int maxCredentialLength;
  private final AtomicLong leaseSequence = new AtomicLong();

  /**
   * Creates the materialization service against its injected ports (AD-002).
   *
   * @param snapshotPort the read-only cached credential snapshot source (Doc 26 §7, AD-022)
   * @param clock the deterministic time seam (Doc 26 §19)
   * @param audit the content-free audit seam (Doc 26 §24)
   * @param metrics the content-free metrics seam (Doc 26 §25)
   * @param nearExpiryMargin the near-expiry refusal margin, an operational baseline (Doc 26 §14.1)
   * @param maxCredentialLength the maximum credential material length; a defense-in-depth bound on
   *     the allocation size taken from the snapshot source, an operational baseline (Doc 26 §14.1,
   *     Doc 16 §I.1) — never a hard-coded numeric (SP-A14)
   */
  public MaterializationService(
      final SecretSnapshotPort snapshotPort,
      final ClockPort clock,
      final SecretsAuditPort audit,
      final SecretsMetricsPort metrics,
      final Duration nearExpiryMargin,
      final int maxCredentialLength) {
    this.snapshotPort = Preconditions.requireNonNull(snapshotPort, "snapshotPort");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.nearExpiryMargin = Preconditions.requireNonNull(nearExpiryMargin, "nearExpiryMargin");
    this.maxCredentialLength = maxCredentialLength;
    if (maxCredentialLength <= 0) {
      throw new IllegalArgumentException("maxCredentialLength must be positive");
    }
    if (nearExpiryMargin.isNegative()) {
      throw new IllegalArgumentException("nearExpiryMargin must be non-negative");
    }
  }

  @Override
  public MaterializationResult materialize(final CredentialRequest request) {
    Preconditions.requireNonNull(request, "request");
    try {
      return doMaterialize(request);
    } catch (final RuntimeException e) {
      // Doc 26 §21 / SP-D11: any unknown/internal error fails closed — the exception never escapes
      // and no detail leaks. The metric emission is guarded (H-1) so it cannot re-throw out of
      // here.
      safeMetric(() -> metrics.credentialUnavailable("internal-error"));
      return new MaterializationResult.CredentialUnavailable("internal-error");
    }
  }

  private MaterializationResult doMaterialize(final CredentialRequest request) {
    final Instant now = clock.now();
    final String routeRef = request.routeTarget().providerRouteRef();

    final Optional<CredentialMaterialSource> maybeSource =
        snapshotPort.resolve(request.tenantScope(), request.routeTarget());
    if (maybeSource.isEmpty()) {
      return unavailable("snapshot-missing", request, routeRef, null, now);
    }
    final CredentialMaterialSource source = maybeSource.get();
    final CredentialSnapshotRef ref = source.ref();

    // CLC-4: tenant binding must match; never materialize cross-tenant (AD-021, Doc 26 SP-D7).
    if (!ref.tenantScope().equals(request.tenantScope())) {
      return unavailable("tenant-scope-mismatch", request, routeRef, ref.version(), now);
    }
    // TTL: never reuse expired material (Doc 26 SP-D3). Valid strictly before notAfter.
    if (!now.isBefore(ref.notAfter())) {
      return unavailable("credential-expired", request, routeRef, ref.version(), now);
    }
    // Near-expiry safety margin (Doc 26 §14.1): refuse material too close to expiry.
    if (!now.plus(nearExpiryMargin).isBefore(ref.notAfter())) {
      return unavailable("credential-near-expiry", request, routeRef, ref.version(), now);
    }
    final int length = source.length();
    if (length <= 0) {
      return unavailable("credential-empty", request, routeRef, ref.version(), now);
    }
    if (length > maxCredentialLength) {
      // H-1 defense-in-depth (CWE-789): never allocate an unbounded array from a snapshot-supplied
      // size — a corrupt/oversized source must fail closed, not OOM the instance. The bound is an
      // injected operational baseline (Doc 26 §14.1), not a hard-coded numeric (SP-A14).
      return unavailable("credential-oversized", request, routeRef, ref.version(), now);
    }

    final char[] material = new char[length];
    final SanitizableCredentialLease lease;
    try {
      source.copyInto(material);
      final String leaseId =
          request.correlationId().value() + "-" + leaseSequence.incrementAndGet();
      lease =
          new SanitizableCredentialLease(
              leaseId,
              request.tenantScope(),
              ref.notAfter(),
              material,
              metrics::leaseLeakDetected,
              clock);
    } catch (final RuntimeException e) {
      // F-2: any failure after the buffer is populated (copy or lease construction) must sanitize
      // the material before failing closed — a secret never escapes sanitization (Doc 26
      // MSC-10/SP-INV).
      Arrays.fill(material, (char) 0);
      safeMetric(() -> metrics.sanitization(true));
      return unavailable("materialization-error", request, routeRef, ref.version(), now);
    }

    // H-1: observability is side-effect-free (Doc 27 OT-A1) — audit AND metrics are guarded so a
    // throwing sink can neither orphan the just-created lease (secret leak) nor break fail-closed.
    safeAudit(
        new MaterializationRecord(
            lease.leaseId(), request.tenantScope(), routeRef, "leased", ref.version(), now));
    safeMetric(metrics::materialized);
    return new MaterializationResult.Leased(lease);
  }

  /**
   * Emits a metric without letting a telemetry-sink failure affect the materialization outcome (Doc
   * 27 OT-A1) — a throwing metrics adapter must never orphan a lease or break fail-closed.
   */
  private void safeMetric(final Runnable emit) {
    try {
      emit.run();
    } catch (final RuntimeException e) {
      // Telemetry never affects the request (Doc 27 OT-A1). Swallowed; no sensitive data is
      // present.
    }
  }

  /**
   * Records an audit fact without letting an audit-sink failure block or fail the materialization
   * flow (Doc 26 SP-D12): durability is the frozen RPO=0 audit mechanism's responsibility (Doc 07/
   * Doc 08 §10), not a reason to discard a valid lease or crash the hot path.
   */
  private void safeAudit(final MaterializationRecord record) {
    try {
      audit.record(record);
    } catch (final RuntimeException e) {
      // Never block sanitization / the flow on an audit-sink fault (Doc 26 SP-D12). Swallowed here;
      // the AuditSinkPort implementation owns durable, non-lossy delivery (loss-detector=0, IR-5).
      // No credential value is present in the record, so nothing sensitive is dropped.
    }
  }

  private MaterializationResult unavailable(
      final String reason,
      final CredentialRequest request,
      final String routeRef,
      final SnapshotVersion version,
      final Instant now) {
    safeMetric(() -> metrics.credentialUnavailable(reason));
    safeAudit(
        new MaterializationRecord(
            "none", request.tenantScope(), routeRef, "unavailable:" + reason, version, now));
    return new MaterializationResult.CredentialUnavailable(reason);
  }
}
