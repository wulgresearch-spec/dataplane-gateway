package io.reliabilityai.gateway.dataplane.memory.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.DeleteProof;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.EventType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.MemoryAuditEvent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCaller;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryMetricsPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryOutcome;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStage;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryWriteRequest;
import io.reliabilityai.gateway.dataplane.memory.api.VectorIndexPort;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryDigest;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.Optional;

/**
 * The Memory Runtime's front door (C17, AD-026).
 *
 * <p>Write, read, delete, hold, and install policy. Nothing else: this class holds no loop, starts
 * no thread and keeps no state beyond its collaborators. A caller that writes a memory and walks
 * away has left nothing running here.
 *
 * <p><b>What it cannot do, structurally.</b> It has no reference to a provider, a database, a
 * vector library, an embedding model, the Agent Runtime, the Plugin Runtime, the Governance Engine
 * or {@code RequestPipeline}. MEM-1, MEM-2 and MEM-5 are consequences of the module's dependency
 * graph rather than rules a reviewer has to enforce.
 *
 * <p><b>What it deliberately does not implement:</b> orchestration of any kind. It answers an
 * operation and returns. A memory plane that could drive a conversation would be an agent runtime
 * wearing a store's name.
 */
public final class MemoryRuntime {

  private final MemoryWritePipeline writes;
  private final MemoryReadPipeline reads;
  private final MemoryPolicyStore policies;
  private final MemoryGovernancePort governance;
  private final MemoryStorePort store;
  private final VectorIndexPort index;
  private final MemoryAuditPort audit;
  private final MemoryMetricsPort metrics;
  private final ClockPort clock;

  /**
   * Creates the runtime.
   *
   * @param writes the write pipeline
   * @param reads the read pipeline
   * @param policies the compiled policy store
   * @param governance the seam to the governance engine
   * @param store the durable record store
   * @param index the vector index
   * @param audit where facts are recorded
   * @param metrics where operational signals go
   * @param clock the injected clock
   */
  public MemoryRuntime(
      final MemoryWritePipeline writes,
      final MemoryReadPipeline reads,
      final MemoryPolicyStore policies,
      final MemoryGovernancePort governance,
      final MemoryStorePort store,
      final VectorIndexPort index,
      final MemoryAuditPort audit,
      final MemoryMetricsPort metrics,
      final ClockPort clock) {
    this.writes = Preconditions.requireNonNull(writes, "writes");
    this.reads = Preconditions.requireNonNull(reads, "reads");
    this.policies = Preconditions.requireNonNull(policies, "policies");
    this.governance = Preconditions.requireNonNull(governance, "governance");
    this.store = Preconditions.requireNonNull(store, "store");
    this.index = Preconditions.requireNonNull(index, "index");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  /**
   * Stores one memory.
   *
   * @param caller who is writing
   * @param request what to store
   * @return what happened
   */
  public MemoryOutcome write(final MemoryCaller caller, final MemoryWriteRequest request) {
    return writes.write(caller, request);
  }

  /**
   * Runs one query.
   *
   * @param caller who is reading
   * @param query what to look for
   * @return the ranked results, or a refusal
   */
  public MemoryOutcome read(final MemoryCaller caller, final MemoryQuery query) {
    return reads.read(caller, query);
  }

  /**
   * Destroys one memory and leaves proof (MEM-25).
   *
   * <p>The content goes; the proof stays. Without the proof, "we deleted it" is an assertion — and
   * an assertion is not what a regulator, an auditor or a customer exercising erasure rights is
   * asking for.
   *
   * @param caller who is deleting
   * @param scope where the record lives
   * @param id which record
   * @param reason a stable, low-cardinality reason code
   * @return the proof, or a refusal
   */
  public MemoryOutcome delete(
      final MemoryCaller caller,
      final MemoryScope scope,
      final MemoryRecordId id,
      final String reason) {

    Preconditions.requireNonNull(caller, "caller");
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    Preconditions.requireNonBlank(reason, "reason");

    final Instant now = clock.now();
    final Optional<MemoryScope> narrowed = caller.narrow(scope);
    if (narrowed.isEmpty()) {
      return refuse(
          caller,
          scope,
          "scope.unauthorized",
          MemoryStage.NARROW,
          "requested scope does not intersect the caller's authority",
          now);
    }

    final Optional<MemoryRecord> found;
    try {
      found = store.get(narrowed.get(), id);
    } catch (final MemoryStoreUnavailableException unavailable) {
      metrics.storeUnavailable("get");
      return refuse(
          caller,
          narrowed.get(),
          "store.unavailable",
          MemoryStage.STORAGE,
          unavailable.getMessage(),
          now);
    }
    if (found.isEmpty()) {
      // Absent and forbidden are deliberately indistinguishable to the caller (AD-026 §10.3).
      // Distinguishing them would make this an oracle for the existence of other tenants' records.
      return refuse(
          caller,
          narrowed.get(),
          "not.found",
          MemoryStage.STORAGE,
          "no such record in this scope",
          now);
    }
    final MemoryRecord record = found.get();

    if (!caller.mayUse(record.type())) {
      return refuse(
          caller,
          narrowed.get(),
          "type.forbidden",
          MemoryStage.ADMIT,
          "caller may not delete " + record.type(),
          now);
    }

    final MemoryGovernancePort.Decision decision =
        governance.admitDelete(caller.principal(), narrowed.get(), record.type());
    if (!decision.admits()) {
      if (decision.verdict() == MemoryGovernancePort.Verdict.UNENFORCEABLE) {
        metrics.unenforceable(MemoryStage.GOVERNANCE);
      }
      return refuse(
          caller,
          narrowed.get(),
          decision.reasonCode(),
          MemoryStage.GOVERNANCE,
          "governance returned " + decision.verdict(),
          now);
    }

    // MEM-20, unconditional. A hold survives explicit deletion just as it survives expiry, archival
    // and supersession — a hold honoured on three of four paths is not a hold.
    if (!record.removable()) {
      return refuse(
          caller,
          narrowed.get(),
          "legal.hold",
          MemoryStage.POLICY,
          "record is under legal hold and cannot be deleted",
          now);
    }
    if (!policies.current().resolve(narrowed.get(), record.type()).deleteAllowed()) {
      return refuse(
          caller,
          narrowed.get(),
          "policy.delete.forbidden",
          MemoryStage.POLICY,
          "policy forbids deletion in this scope",
          now);
    }

    try {
      store.delete(narrowed.get(), id);
    } catch (final MemoryStoreUnavailableException unavailable) {
      metrics.storeUnavailable("delete");
      return refuse(
          caller,
          narrowed.get(),
          "store.unavailable",
          MemoryStage.STORAGE,
          unavailable.getMessage(),
          now);
    }
    // Always attempted, even for a record that was never indexed. An index entry that dereferences
    // to
    // nothing must never survive the record it pointed at (AD-026 §11.2).
    try {
      index.remove(narrowed.get(), id);
    } catch (final RuntimeException ignored) {
      metrics.storeUnavailable("index.remove");
    }

    final DeleteProof proof = proofFor(record, caller, reason, now);
    emit(
        EventType.DELETE,
        MemoryStage.AUDIT,
        caller,
        narrowed.get(),
        Optional.of(record.id()),
        Optional.of(record.content().digest()),
        "deleted",
        1,
        now);
    metrics.deleted(record.type());
    return new MemoryOutcome.Deleted(proof);
  }

  /**
   * Builds the delete proof.
   *
   * <p>Binds every field with a digest so that altering any of them afterwards is detectable. Note
   * what it does not bind: the content. A proof that quoted what it deleted would be a copy of the
   * thing that was supposed to be gone.
   */
  private static DeleteProof proofFor(
      final MemoryRecord record,
      final MemoryCaller caller,
      final String reason,
      final Instant now) {
    final String binding =
        DeleteProof.bindingString(
            record.id(),
            record.scope().key(),
            record.content().digest(),
            caller.principal(),
            reason,
            now);
    return new DeleteProof(
        record.id(),
        record.scope().key(),
        record.content().digest(),
        caller.principal(),
        reason,
        now,
        MemoryDigest.of(binding));
  }

  /**
   * Verifies that a delete proof has not been altered since it was written.
   *
   * @param proof the proof to check
   * @return true when it is internally consistent
   */
  public static boolean verifyProof(final DeleteProof proof) {
    Preconditions.requireNonNull(proof, "proof");
    return proof.verify(MemoryDigest::of);
  }

  /**
   * Installs a compiled policy snapshot.
   *
   * <p>Atomic (MEM-18). Readers see the old snapshot or the new one, never a mixture, and no reader
   * blocks while it happens.
   *
   * @param snapshot the compiled snapshot
   * @return true when it was installed, false when it was not newer than the live one
   */
  public boolean installPolicy(final MemoryPolicySnapshot snapshot) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    final boolean installed = policies.install(snapshot);
    if (installed) {
      metrics.snapshotInstalled(snapshot.scopeCount());
    }
    return installed;
  }

  /**
   * Returns the live policy snapshot.
   *
   * @return the snapshot currently in force
   */
  public MemoryPolicySnapshot currentPolicy() {
    return policies.current();
  }

  /**
   * Returns the current instant from the injected clock.
   *
   * @return now, as this runtime sees it
   */
  public Instant now() {
    return clock.now();
  }

  private MemoryOutcome refuse(
      final MemoryCaller caller,
      final MemoryScope scope,
      final String code,
      final MemoryStage stage,
      final String detail,
      final Instant now) {
    metrics.refused(stage, code);
    emit(EventType.REFUSAL, stage, caller, scope, Optional.empty(), Optional.empty(), code, 0, now);
    return new MemoryOutcome.Refused(code, stage, detail);
  }

  private void emit(
      final EventType type,
      final MemoryStage stage,
      final MemoryCaller caller,
      final MemoryScope scope,
      final Optional<MemoryRecordId> recordId,
      final Optional<String> digest,
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
              Optional.empty(),
              recordId,
              digest,
              Optional.empty(),
              reasonCode,
              resultCount,
              caller.correlationId(),
              now));
    } catch (final RuntimeException sinkFailure) {
      metrics.storeUnavailable("audit");
    }
  }
}
