package io.reliabilityai.gateway.dataplane.memory.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.EventType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.MemoryAuditEvent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCaller;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryContent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryMetricsPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryOutcome;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStage;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryWriteRequest;
import io.reliabilityai.gateway.dataplane.memory.api.PiiAction;
import io.reliabilityai.gateway.dataplane.memory.api.PiiClassifierPort;
import io.reliabilityai.gateway.dataplane.memory.api.VectorIndexPort;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryDigest;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The write path: admit → governance → policy → storage → audit (AD-026 §5).
 *
 * <p>The order is the order of the statements in {@link #write}. There is no stage registry and no
 * dynamic composition, so a reviewer reads the method top to bottom and sees the whole contract,
 * and a stage cannot be inserted, removed or reordered without editing code the pipeline-order test
 * guards.
 *
 * <p><b>Fail closed at every stage</b> (MEM-11). The first refusal returns immediately: nothing is
 * stored, nothing is indexed, and the refusal is audited. There is no path on which a partial write
 * survives a refusal.
 */
public final class MemoryWritePipeline {

  private final MemoryPolicyStore policies;
  private final MemoryGovernancePort governance;
  private final PiiClassifierPort classifier;
  private final MemoryCryptoPort crypto;
  private final MemoryStorePort store;
  private final VectorIndexPort index;
  private final MemoryEmbedder embedder;
  private final MemoryAuditPort audit;
  private final MemoryMetricsPort metrics;
  private final ClockPort clock;
  private final MemoryIdFactory ids;

  /**
   * Creates the write pipeline.
   *
   * @param policies the compiled policy store
   * @param governance the seam to the governance engine
   * @param classifier the PII classifier
   * @param crypto the sealing seam
   * @param store the durable record store
   * @param index the vector index
   * @param embedder the embedding seam, used only for types that need one
   * @param audit where facts are recorded
   * @param metrics where operational signals go
   * @param clock the injected clock; the only source of time on this path
   * @param ids how record identities are minted
   */
  public MemoryWritePipeline(
      final MemoryPolicyStore policies,
      final MemoryGovernancePort governance,
      final PiiClassifierPort classifier,
      final MemoryCryptoPort crypto,
      final MemoryStorePort store,
      final VectorIndexPort index,
      final MemoryEmbedder embedder,
      final MemoryAuditPort audit,
      final MemoryMetricsPort metrics,
      final ClockPort clock,
      final MemoryIdFactory ids) {
    this.policies = Preconditions.requireNonNull(policies, "policies");
    this.governance = Preconditions.requireNonNull(governance, "governance");
    this.classifier = Preconditions.requireNonNull(classifier, "classifier");
    this.crypto = Preconditions.requireNonNull(crypto, "crypto");
    this.store = Preconditions.requireNonNull(store, "store");
    this.index = Preconditions.requireNonNull(index, "index");
    this.embedder = Preconditions.requireNonNull(embedder, "embedder");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.ids = Preconditions.requireNonNull(ids, "ids");
  }

  /**
   * Stores one memory.
   *
   * @param caller who is writing, and what they are authorized for
   * @param request what they want stored
   * @return what happened
   */
  public MemoryOutcome write(final MemoryCaller caller, final MemoryWriteRequest request) {
    Preconditions.requireNonNull(caller, "caller");
    Preconditions.requireNonNull(request, "request");

    final long startNanos = System.nanoTime();
    final Instant now = clock.now();

    // ---- ADMIT ---------------------------------------------------------------------------------
    if (!caller.mayUse(request.type())) {
      return refuse(
          caller,
          request.scope(),
          "type.forbidden",
          MemoryStage.ADMIT,
          "caller may not use " + request.type(),
          now);
    }
    final Optional<MemoryScope> narrowedScope = caller.narrow(request.scope());
    if (narrowedScope.isEmpty()) {
      // Cross-tenant, or outside the caller's workspace or owner. Refused before any adapter exists
      // in
      // the picture at all (MEM-16).
      return refuse(
          caller,
          request.scope(),
          "scope.unauthorized",
          MemoryStage.ADMIT,
          "requested scope does not intersect the caller's authority",
          now);
    }
    final MemoryWriteRequest scoped = request.withScope(narrowedScope.get());

    // ---- CLASSIFY ------------------------------------------------------------------------------
    // Before governance, because governance is told the classification and cannot decide without
    // it;
    // and before sealing, because sealing first would encrypt content that policy may refuse
    // outright
    // and would put the decision behind a ciphertext the classifier cannot read (AD-026 §5.1).
    final PiiClassifierPort.Classification classified;
    try {
      classified = classifier.classify(scoped.body());
    } catch (final RuntimeException failure) {
      metrics.unenforceable(MemoryStage.POLICY);
      return refuse(
          caller,
          scoped.scope(),
          "classification.unavailable",
          MemoryStage.POLICY,
          "classifier failed: " + failure.getClass().getSimpleName(),
          now);
    }
    if (!classified.classification().established()) {
      // Unclassified is not "safe" — it means nobody looked (MEM-21).
      metrics.unenforceable(MemoryStage.POLICY);
      return refuse(
          caller,
          scoped.scope(),
          "classification.unenforceable",
          MemoryStage.POLICY,
          "content could not be classified",
          now);
    }
    if (!classified.classification().storable()) {
      // Credentials are never storable as memory, whatever any policy says.
      return refuse(
          caller,
          scoped.scope(),
          "classification.forbidden",
          MemoryStage.POLICY,
          classified.classification() + " may never be stored as memory",
          now);
    }

    // ---- GOVERNANCE ----------------------------------------------------------------------------
    final MemoryGovernancePort.Decision decision =
        governance.admitWrite(
            caller.principal(),
            scoped.scope(),
            scoped.type(),
            classified.classification(),
            scoped.body().length());
    if (!decision.admits()) {
      if (decision.verdict() == MemoryGovernancePort.Verdict.UNENFORCEABLE) {
        metrics.unenforceable(MemoryStage.GOVERNANCE);
      }
      return refuse(
          caller,
          scoped.scope(),
          decision.reasonCode(),
          MemoryStage.GOVERNANCE,
          "governance returned " + decision.verdict(),
          now);
    }

    // ---- MEMORY POLICY -------------------------------------------------------------------------
    final EffectiveMemoryPolicy policy = policies.current().resolve(scoped.scope(), scoped.type());
    if (!policy.enforceable()) {
      metrics.unenforceable(MemoryStage.POLICY);
      return refuse(
          caller,
          scoped.scope(),
          "policy.unenforceable",
          MemoryStage.POLICY,
          "no enforceable memory policy resolved for this scope",
          now);
    }
    if (!policy.permitsClassification(classified.classification())) {
      return refuse(
          caller,
          scoped.scope(),
          "policy.classification",
          MemoryStage.POLICY,
          classified.classification() + " exceeds the ceiling for this scope",
          now);
    }
    if (!policy.hasPermittedRegion()) {
      return refuse(
          caller,
          scoped.scope(),
          "policy.residency.none",
          MemoryStage.POLICY,
          "no region is permitted for this scope",
          now);
    }
    // Residency before the adapter is chosen (MEM-22). Writing first and discovering the region is
    // forbidden afterwards leaves only a compensating delete, and a compensating delete of data
    // that
    // should never have crossed a border is an incident with extra steps.
    if (!policy.permitsRegion(scoped.region())) {
      return refuse(
          caller,
          scoped.scope(),
          "policy.residency.region",
          MemoryStage.POLICY,
          "region " + scoped.region() + " is not permitted for this scope",
          now);
    }

    MemoryWriteRequest shaped = scoped;
    boolean redacted = false;
    if (classified.classification().personal()) {
      final PiiAction action = policy.piiAction();
      if (action.refuses()) {
        return refuse(
            caller,
            scoped.scope(),
            "policy.pii.refused",
            MemoryStage.POLICY,
            "policy refuses personal data in this scope",
            now);
      }
      if (action == PiiAction.REDACT) {
        shaped = scoped.withBody(classified.redactedBody());
        redacted = true;
      }
    }

    // ---- SEAL ----------------------------------------------------------------------------------
    final String plaintext = shaped.body();
    final String plaintextDigest = MemoryDigest.of(plaintext);
    MemoryContent content = MemoryContent.plain(plaintext, plaintextDigest);
    if (policy.requiresSealing(classified.classification())) {
      try {
        final MemoryCryptoPort.Sealed sealed = crypto.seal(shaped.scope(), plaintext);
        content =
            MemoryContent.sealed(
                sealed.ciphertext(), plaintextDigest, sealed.keyRef(), plaintext.length());
      } catch (final RuntimeException failure) {
        // Storing plaintext because sealing failed is the whole breach. Refuse instead.
        metrics.storeUnavailable("seal");
        return refuse(
            caller,
            shaped.scope(),
            "crypto.unavailable",
            MemoryStage.POLICY,
            "sealing is required and unavailable: " + failure.getClass().getSimpleName(),
            now);
      }
    }

    // ---- STORAGE -------------------------------------------------------------------------------
    final MemoryRecordId id = ids.next(shaped, now);
    final boolean needsIndex = shaped.type().requiresEmbedding();
    final MemoryRecord candidate =
        new MemoryRecord(
            id,
            shaped.scope(),
            shaped.type(),
            content,
            classified.classification(),
            shaped.metadata(),
            now,
            policy.expiryFor(now, shaped.requestedTtl()),
            1,
            Math.min(shaped.requestedImportance(), 1.0d),
            shaped.type().alwaysTainted(),
            policy.legalHold(),
            false,
            needsIndex,
            Optional.empty(),
            0L);

    final MemoryStorePort.PutResult put;
    try {
      put = store.put(candidate, shaped.idempotencyKey());
    } catch (final MemoryStoreUnavailableException unavailable) {
      metrics.storeUnavailable("put");
      return refuse(
          caller,
          shaped.scope(),
          "store.unavailable",
          MemoryStage.STORAGE,
          unavailable.getMessage(),
          now);
    }
    final MemoryRecord stored = put.record();

    // Taken from the store rather than inferred by comparing identities. A deterministic id factory
    // gives a retried write the same id on purpose, so an identity comparison can never detect a
    // replay — a defect the idempotence test caught.
    final boolean deduplicated = !put.created();

    // Store first, then index (AD-026 §11.2). A crash between the two leaves a record that is
    // keyword-visible but not yet semantically findable, which is strictly better than an index
    // entry
    // pointing at a record that does not exist.
    boolean indexPending = needsIndex;
    if (needsIndex && !deduplicated) {
      try {
        index.index(stored.scope(), stored.id(), embedder.embed(plaintext));
        indexPending = false;
      } catch (final RuntimeException failure) {
        // The record survives, marked for the sweeper to reconcile. Failing the write here would
        // throw
        // away a durable record because a secondary index was briefly unavailable.
        metrics.storeUnavailable("index");
      }
    }

    // ---- AUDIT ---------------------------------------------------------------------------------
    // After durability, so no audit event can claim a write that a crash then unmakes (MEM-12).
    emit(
        EventType.WRITE,
        MemoryStage.AUDIT,
        caller,
        stored.scope(),
        Optional.of(stored.type()),
        Optional.of(stored.id()),
        Optional.of(plaintextDigest),
        Optional.of(classified.classification()),
        deduplicated ? "written.deduplicated" : "written",
        1,
        now);

    metrics.written(
        stored.type(),
        classified.classification(),
        content.sealed(),
        Duration.ofNanos(System.nanoTime() - startNanos));

    return new MemoryOutcome.Written(
        stored.id(), stored.version(), content.sealed(), redacted, indexPending, deduplicated);
  }

  private MemoryOutcome refuse(
      final MemoryCaller caller,
      final MemoryScope scope,
      final String code,
      final MemoryStage stage,
      final String detail,
      final Instant now) {
    metrics.refused(stage, code);
    emit(
        EventType.REFUSAL,
        stage,
        caller,
        scope,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        code,
        0,
        now);
    return new MemoryOutcome.Refused(code, stage, detail);
  }

  private void emit(
      final EventType type,
      final MemoryStage stage,
      final MemoryCaller caller,
      final MemoryScope scope,
      final Optional<io.reliabilityai.gateway.dataplane.memory.api.MemoryType> memoryType,
      final Optional<MemoryRecordId> recordId,
      final Optional<String> digest,
      final Optional<DataClassification> classification,
      final String reasonCode,
      final int resultCount,
      final Instant now) {
    try {
      audit.record(
          new MemoryAuditEvent(
              type,
              stage,
              caller.principal(),
              scope.key(),
              memoryType,
              recordId,
              digest,
              classification,
              reasonCode,
              resultCount,
              caller.correlationId(),
              now));
    } catch (final RuntimeException sinkFailure) {
      // A write that cannot be audited has already been stored by this point, so refusing now would
      // leave a stored record reported as a failure — the worst of both. The gap is counted
      // instead,
      // and AD-026 §11.1 records this asymmetry rather than burying it.
      metrics.storeUnavailable("audit");
    }
  }
}
