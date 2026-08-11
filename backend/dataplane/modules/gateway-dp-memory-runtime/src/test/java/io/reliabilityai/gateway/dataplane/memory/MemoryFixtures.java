package io.reliabilityai.gateway.dataplane.memory;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCaller;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryWriteRequest;
import io.reliabilityai.gateway.dataplane.memory.api.PiiAction;
import io.reliabilityai.gateway.dataplane.memory.api.RetrievalMode;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy.VersioningMode;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Shared builders and controllable fakes for the Memory Runtime's tests. */
public final class MemoryFixtures {

  /**
   * A fixed origin, so every assertion about time is about a difference rather than about today.
   */
  public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  /** The region most tests write from. */
  public static final String REGION = "eu-west-1";

  /** A second region, for residency tests. */
  public static final String OTHER_REGION = "us-east-1";

  /** The tenant most tests run in. */
  public static final TenantScope TENANT = TenantScope.of("acme", "core");

  /** A second tenant, for isolation tests. */
  public static final TenantScope OTHER_TENANT = TenantScope.of("globex", "core");

  private MemoryFixtures() {
    throw new AssertionError("no instances");
  }

  /** A clock the test drives by hand. Nothing in the runtime may read time any other way. */
  public static final class TestClock implements ClockPort {
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @Override
    public Instant now() {
      return now.get();
    }

    /**
     * Moves the clock forward.
     *
     * @param by how far
     */
    public void advance(final Duration by) {
      now.updateAndGet(instant -> instant.plus(by));
    }
  }

  /** A governance seam whose answers the test chooses. */
  public static final class FakeGovernance implements MemoryGovernancePort {
    private Decision writeDecision = Decision.ALLOWED;
    private Decision readDecision = Decision.ALLOWED;
    private Decision deleteDecision = Decision.ALLOWED;
    private int writeCalls;
    private int readCalls;
    private int deleteCalls;

    /**
     * Sets the answer for writes.
     *
     * @param decision what to return
     * @return this fake
     */
    public FakeGovernance onWrite(final Decision decision) {
      this.writeDecision = decision;
      return this;
    }

    /**
     * Sets the answer for reads.
     *
     * @param decision what to return
     * @return this fake
     */
    public FakeGovernance onRead(final Decision decision) {
      this.readDecision = decision;
      return this;
    }

    /**
     * Sets the answer for deletes.
     *
     * @param decision what to return
     * @return this fake
     */
    public FakeGovernance onDelete(final Decision decision) {
      this.deleteDecision = decision;
      return this;
    }

    @Override
    public Decision admitWrite(
        final PrincipalId principal,
        final MemoryScope scope,
        final MemoryType type,
        final DataClassification classification,
        final int sizeBytes) {
      writeCalls++;
      return writeDecision;
    }

    @Override
    public Decision admitRead(
        final PrincipalId principal,
        final MemoryScope scope,
        final Set<MemoryType> types,
        final RetrievalMode mode) {
      readCalls++;
      return readDecision;
    }

    @Override
    public Decision admitDelete(
        final PrincipalId principal, final MemoryScope scope, final MemoryType type) {
      deleteCalls++;
      return deleteDecision;
    }

    /**
     * Returns how many write decisions were asked for.
     *
     * @return the call count
     */
    public int writeCalls() {
      return writeCalls;
    }

    /**
     * Returns how many read decisions were asked for.
     *
     * @return the call count
     */
    public int readCalls() {
      return readCalls;
    }

    /**
     * Returns how many delete decisions were asked for.
     *
     * @return the call count
     */
    public int deleteCalls() {
      return deleteCalls;
    }
  }

  /** An audit sink that keeps what it is given, so tests can assert the trail has no gaps. */
  public static final class RecordingAudit implements MemoryAuditPort {
    private final List<MemoryAuditEvent> events = new ArrayList<>();
    private boolean exploding;

    /**
     * Makes every subsequent call throw, to test that an audit outage never becomes a memory
     * outage.
     *
     * @param on whether the sink should fail
     * @return this fake
     */
    public RecordingAudit exploding(final boolean on) {
      this.exploding = on;
      return this;
    }

    @Override
    public synchronized void record(final MemoryAuditEvent event) {
      if (exploding) {
        throw new IllegalStateException("audit sink down");
      }
      events.add(event);
    }

    /**
     * Returns everything recorded.
     *
     * @return the events, in order
     */
    public synchronized List<MemoryAuditEvent> events() {
      return List.copyOf(events);
    }

    /**
     * Counts events of one kind.
     *
     * @param type the event kind
     * @return how many were recorded
     */
    public synchronized long countOf(final EventType type) {
      return events.stream().filter(event -> event.type() == type).count();
    }
  }

  /** An embedding seam producing a deterministic vector, so semantic tests are reproducible. */
  public static final class FakeEmbedding
      implements io.reliabilityai.gateway.dataplane.memory.api.EmbeddingPort {
    private boolean available = true;

    /**
     * Makes the provider report unavailability.
     *
     * @param up whether it should respond
     * @return this fake
     */
    public FakeEmbedding available(final boolean up) {
      this.available = up;
      return this;
    }

    @Override
    public float[] embed(final String text) {
      if (!available) {
        throw new io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException(
            "embedding provider down");
      }
      // A character-histogram vector: deterministic, cheap, and similar for similar text — enough
      // for
      // the ranker's ordering to be meaningfully testable without pretending to be a real model.
      final float[] vector = new float[16];
      for (int i = 0; i < text.length(); i++) {
        vector[Character.toLowerCase(text.charAt(i)) % 16] += 1.0f;
      }
      return vector;
    }
  }

  /** A caller authorized for a whole tenant, permitted every memory type. */
  public static MemoryCaller caller() {
    return MemoryCaller.of(
        new PrincipalId("p-1"), MemoryScope.ofTenant(TENANT), new CorrelationId("corr-1"));
  }

  /** A caller authorized for exactly one scope. */
  public static MemoryCaller callerFor(final MemoryScope scope) {
    return MemoryCaller.of(new PrincipalId("p-1"), scope, new CorrelationId("corr-1"));
  }

  /** A caller permitted only the given memory types. */
  public static MemoryCaller callerWithTypes(final MemoryType... types) {
    return new MemoryCaller(
        new PrincipalId("p-1"),
        MemoryScope.ofTenant(TENANT),
        Set.of(),
        Set.of(types),
        new CorrelationId("corr-1"));
  }

  /** A permissive but fully enforceable policy: the baseline most tests vary from. */
  public static EffectiveMemoryPolicy permissivePolicy() {
    return new EffectiveMemoryPolicy(
        Optional.of(Duration.ofDays(30)),
        Optional.empty(),
        PiiAction.ALLOW,
        Set.of(REGION, OTHER_REGION),
        false,
        false,
        true,
        Optional.empty(),
        VersioningMode.APPEND,
        Optional.empty(),
        DataClassification.SENSITIVE_PII,
        true);
  }

  /** A snapshot installing one policy as the fallback for every scope. */
  public static MemoryPolicySnapshot snapshotOf(final EffectiveMemoryPolicy policy) {
    return new MemoryPolicySnapshot(1L, Map.of(), Map.of(), policy);
  }

  /** A write request with the given body. */
  public static MemoryWriteRequest write(
      final MemoryScope scope, final MemoryType type, final String body, final String key) {
    return MemoryWriteRequest.of(scope, type, body, key, REGION);
  }
}
