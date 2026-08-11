package io.reliabilityai.gateway.dataplane.memory.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.EventType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.MemoryAuditEvent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryMetricsPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.memory.api.VectorIndexPort;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.List;

/**
 * Expires, archives and reconciles records, out of band (AD-026 §5, §11).
 *
 * <p>Deliberately <b>not</b> on the request path. A read that had to sweep before answering would
 * pay an unbounded cost for someone else's expired data, and the cost would land on whichever
 * unlucky caller happened to arrive first.
 *
 * <p><b>Idempotent by construction.</b> Running a pass twice expires nothing twice, because
 * expiring an already-deleted record is a no-op at the store. That is what makes a crash mid-pass
 * survivable: the next pass simply resumes, and no bookkeeping is needed to remember where it
 * stopped.
 *
 * <p>Correctness does not depend on the sweeper running. TTL is evaluated again on every read
 * (AD-026 §11), so a late or dead sweeper costs storage, never visibility of expired data.
 */
public final class MemoryLifecycleSweeper {

  /**
   * What one pass did.
   *
   * @param expired records whose TTL had elapsed and were removed
   * @param archived records moved to archive rather than deleted
   * @param held records left in place because a legal hold forbade touching them
   * @param reconciled records whose index entry was brought back into agreement with the store
   */
  public record Pass(int expired, int archived, int held, int reconciled) {

    /**
     * Reports whether the pass changed anything.
     *
     * @return true when at least one record was expired, archived or reconciled
     */
    public boolean productive() {
      return expired > 0 || archived > 0 || reconciled > 0;
    }
  }

  private final MemoryStorePort store;
  private final VectorIndexPort index;
  private final MemoryPolicyStore policies;
  private final MemoryAuditPort audit;
  private final MemoryMetricsPort metrics;
  private final ClockPort clock;
  private final io.reliabilityai.gateway.canonical.identity.PrincipalId sweeperPrincipal;
  private final io.reliabilityai.gateway.canonical.identity.CorrelationId sweeperCorrelation;

  /**
   * Creates the sweeper.
   *
   * @param store the durable record store
   * @param index the vector index
   * @param policies the compiled policy store
   * @param audit where facts are recorded
   * @param metrics where operational signals go
   * @param clock the injected clock
   * @param sweeperPrincipal the identity the sweeper acts as, so its actions are attributable
   * @param sweeperCorrelation the correlation stamped on the sweeper's audit events
   */
  public MemoryLifecycleSweeper(
      final MemoryStorePort store,
      final VectorIndexPort index,
      final MemoryPolicyStore policies,
      final MemoryAuditPort audit,
      final MemoryMetricsPort metrics,
      final ClockPort clock,
      final io.reliabilityai.gateway.canonical.identity.PrincipalId sweeperPrincipal,
      final io.reliabilityai.gateway.canonical.identity.CorrelationId sweeperCorrelation) {
    this.store = Preconditions.requireNonNull(store, "store");
    this.index = Preconditions.requireNonNull(index, "index");
    this.policies = Preconditions.requireNonNull(policies, "policies");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.sweeperPrincipal = Preconditions.requireNonNull(sweeperPrincipal, "sweeperPrincipal");
    this.sweeperCorrelation =
        Preconditions.requireNonNull(sweeperCorrelation, "sweeperCorrelation");
  }

  /**
   * Runs one pass over expired records.
   *
   * @param batch the most records to consider in this pass
   * @return what the pass did
   */
  public Pass sweep(final int batch) {
    if (batch < 1) {
      throw new IllegalArgumentException("batch must be >= 1, was " + batch);
    }
    final Instant now = clock.now();

    final List<MemoryRecord> candidates;
    try {
      candidates = store.expired(now, batch);
    } catch (final MemoryStoreUnavailableException unavailable) {
      metrics.storeUnavailable("expired");
      return new Pass(0, 0, 0, 0);
    }

    int expired = 0;
    int archived = 0;
    int held = 0;
    int reconciled = 0;

    for (final MemoryRecord record : candidates) {
      // MEM-20, checked before anything else and without exception. A held record survives expiry
      // the
      // same way it survives an explicit delete.
      if (!record.removable()) {
        held++;
        continue;
      }

      final EffectiveMemoryPolicy policy =
          policies.current().resolve(record.scope(), record.type());

      // A retention floor that outlives the TTL ceiling is a conflict, not a licence to delete
      // (AD-026 §8.2). The record is kept and counted so an operator can see the conflict exists.
      if (policy.retentionConflictsWithTtl()) {
        held++;
        continue;
      }

      if (policy.archiveAfter().isPresent() && !record.archived()) {
        final boolean dueForArchive = record.ageAt(now).compareTo(policy.archiveAfter().get()) >= 0;
        if (dueForArchive) {
          try {
            store.put(record.archivedRecord(), archiveKey(record));
            archived++;
            emit(EventType.ARCHIVE, record, "archived", now);
            continue;
          } catch (final MemoryStoreUnavailableException unavailable) {
            metrics.storeUnavailable("archive");
            continue;
          }
        }
      }

      try {
        if (store.delete(record.scope(), record.id())) {
          expired++;
        }
        index.remove(record.scope(), record.id());
        reconciled++;
        emit(EventType.EXPIRY, record, "expired", now);
      } catch (final MemoryStoreUnavailableException unavailable) {
        metrics.storeUnavailable("expire");
      } catch (final RuntimeException indexFailure) {
        // The record is gone from the store; the index entry is not. Counted, and the next pass
        // will
        // find nothing to delete but will still clear the index — which is why remove is
        // idempotent.
        metrics.storeUnavailable("index.remove");
      }
    }

    metrics.swept(expired, archived, held);
    return new Pass(expired, archived, held, reconciled);
  }

  /**
   * The idempotency key an archival write uses.
   *
   * <p>Derived from the record and the word "archive" so that re-archiving the same record in a
   * later pass is recognised as the same write rather than creating a second copy in cold storage.
   */
  private static String archiveKey(final MemoryRecord record) {
    return record.scope().key() + "|archive|" + record.id().value();
  }

  private void emit(
      final EventType type, final MemoryRecord record, final String reasonCode, final Instant now) {
    try {
      audit.record(
          new MemoryAuditEvent(
              type,
              io.reliabilityai.gateway.dataplane.memory.api.MemoryStage.AUDIT,
              sweeperPrincipal,
              record.scope().key(),
              java.util.Optional.of(record.type()),
              java.util.Optional.of(record.id()),
              java.util.Optional.of(record.content().digest()),
              java.util.Optional.of(record.classification()),
              reasonCode,
              1,
              sweeperCorrelation,
              now));
    } catch (final RuntimeException sinkFailure) {
      metrics.storeUnavailable("audit");
    }
  }
}
