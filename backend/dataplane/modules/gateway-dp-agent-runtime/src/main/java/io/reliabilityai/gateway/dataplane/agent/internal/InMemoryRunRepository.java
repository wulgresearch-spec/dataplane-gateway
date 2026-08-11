package io.reliabilityai.gateway.dataplane.agent.internal;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.dataplane.agent.api.RunStoreUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A run store held in memory.
 *
 * <p>Not durable, and named so. It exists for tests and for a single-process deployment that
 * accepts losing in-flight runs on restart; {@link JournalRunRepository} is the durable one.
 *
 * <p>It implements the same conditional-append contract, and the tests exercise both through it, so
 * the semantics a durable store must provide are pinned down here rather than only described.
 *
 * <p>Thread-safe. The append is serialised per run under that run's own lock, which is the
 * narrowest scope that still makes the offset check and the write atomic — a global lock would make
 * the store the bottleneck for a whole fleet.
 */
public final class InMemoryRunRepository implements RunRepository {

  private static final class Entry {
    private final List<RunEvent> events = new ArrayList<>();
    private final Object lock = new Object();
    private final TenantScope tenant;
    private volatile String leaseOwner;
    private volatile Instant leaseUntil;
    private volatile boolean terminated;
    private volatile Instant wakeAt;

    Entry(final TenantScope tenant) {
      this.tenant = tenant;
    }
  }

  private final Map<RunId, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicBoolean available = new AtomicBoolean(true);

  /**
   * Makes every subsequent operation report unavailability.
   *
   * <p>Present so that fail-closed behaviour is testable. AD-025 §44.4 requires an executor to
   * stall rather than proceed unrecorded when the store is unreachable, and a claim like that is
   * worth nothing without a way to actually take the store away.
   *
   * @param up whether the store should respond
   */
  public void setAvailable(final boolean up) {
    available.set(up);
  }

  @Override
  public AppendResult create(final RunId runId, final RunEvent.RunCreated created) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(created, "created");
    if (!available.get()) {
      return new AppendResult.Unavailable("in-memory store marked unavailable");
    }
    final Entry entry = new Entry(created.security().tenant());
    final Entry existing = entries.putIfAbsent(runId, entry);
    if (existing != null) {
      return new AppendResult.Conflict(existing.events.size());
    }
    synchronized (entry.lock) {
      entry.events.add(created);
      return new AppendResult.Appended(entry.events.size());
    }
  }

  @Override
  public AppendResult append(final RunId runId, final long expectedOffset, final RunEvent event) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(event, "event");
    Preconditions.requireNonNegative(expectedOffset, "expectedOffset");
    if (!available.get()) {
      return new AppendResult.Unavailable("in-memory store marked unavailable");
    }
    final Entry entry = entries.get(runId);
    if (entry == null) {
      return new AppendResult.Conflict(0L);
    }
    synchronized (entry.lock) {
      // The conditional write. Two executors that both believe they hold the lease arrive here; the
      // second sees a longer history and loses. This, not the lease, is what makes double execution
      // impossible.
      if (entry.events.size() != expectedOffset) {
        return new AppendResult.Conflict(entry.events.size());
      }
      entry.events.add(event);
      if (event instanceof RunEvent.RunTerminated) {
        entry.terminated = true;
      }
      if (event instanceof RunEvent.RunParked parked) {
        entry.wakeAt = parked.wakeAt();
      } else if (!(event instanceof RunEvent.RunCheckpointed)) {
        entry.wakeAt = null;
      }
      return new AppendResult.Appended(entry.events.size());
    }
  }

  @Override
  public Optional<RunHistory> load(final RunId runId) {
    Preconditions.requireNonNull(runId, "runId");
    if (!available.get()) {
      throw new RunStoreUnavailableException("in-memory store marked unavailable");
    }
    final Entry entry = entries.get(runId);
    if (entry == null) {
      return Optional.empty();
    }
    synchronized (entry.lock) {
      return Optional.of(new RunHistory(runId, List.copyOf(entry.events)));
    }
  }

  @Override
  public List<RunId> claimable(final TenantScope tenant, final Instant now, final int limit) {
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonNull(now, "now");
    if (!available.get()) {
      throw new RunStoreUnavailableException("in-memory store marked unavailable");
    }
    final List<RunId> claimableRuns = new ArrayList<>();
    for (final Map.Entry<RunId, Entry> candidate : entries.entrySet()) {
      if (claimableRuns.size() >= limit) {
        break;
      }
      final Entry entry = candidate.getValue();
      if (!entry.tenant.equals(tenant) || entry.terminated) {
        continue;
      }
      final Instant wake = entry.wakeAt;
      if (wake != null && now.isBefore(wake)) {
        continue;
      }
      if (leaseHeld(entry, now)) {
        continue;
      }
      claimableRuns.add(candidate.getKey());
    }
    return List.copyOf(claimableRuns);
  }

  @Override
  public List<RunId> unfinished(final TenantScope tenant, final int limit) {
    Preconditions.requireNonNull(tenant, "tenant");
    if (!available.get()) {
      throw new RunStoreUnavailableException("in-memory store marked unavailable");
    }
    final List<RunId> open = new ArrayList<>();
    for (final Map.Entry<RunId, Entry> candidate : entries.entrySet()) {
      if (open.size() >= limit) {
        break;
      }
      final Entry entry = candidate.getValue();
      if (entry.tenant.equals(tenant) && !entry.terminated) {
        open.add(candidate.getKey());
      }
    }
    return List.copyOf(open);
  }

  @Override
  public boolean claim(
      final RunId runId, final String owner, final Instant now, final Instant until) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonBlank(owner, "owner");
    Preconditions.requireNonNull(now, "now");
    Preconditions.requireNonNull(until, "until");
    final Entry entry = entries.get(runId);
    if (entry == null) {
      return false;
    }
    synchronized (entry.lock) {
      if (leaseHeld(entry, now)) {
        return false;
      }
      entry.leaseOwner = owner;
      entry.leaseUntil = until;
      return true;
    }
  }

  @Override
  public void release(final RunId runId, final String owner) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(owner, "owner");
    final Entry entry = entries.get(runId);
    if (entry == null) {
      return;
    }
    synchronized (entry.lock) {
      // Only the holder may release. A stale node whose lease already expired must not be able to
      // knock the current holder off the run.
      if (owner.equals(entry.leaseOwner)) {
        entry.leaseOwner = null;
        entry.leaseUntil = null;
      }
    }
  }

  /**
   * Returns how many runs the store holds.
   *
   * @return the run count
   */
  public int size() {
    return entries.size();
  }

  /** Forgets everything. For tests that want a clean store without building a new one. */
  public void clear() {
    entries.clear();
  }

  private static boolean leaseHeld(final Entry entry, final Instant now) {
    final Instant until = entry.leaseUntil;
    return entry.leaseOwner != null && until != null && now.isBefore(until);
  }
}
