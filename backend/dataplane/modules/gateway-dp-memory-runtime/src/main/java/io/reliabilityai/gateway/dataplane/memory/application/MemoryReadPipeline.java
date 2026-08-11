package io.reliabilityai.gateway.dataplane.memory.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.EventType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.MemoryAuditEvent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCaller;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryHit;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryMetricsPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryOutcome;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStage;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort.ScoredRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.memory.api.RetrievalMode;
import io.reliabilityai.gateway.dataplane.memory.api.VectorIndexPort;
import io.reliabilityai.gateway.dataplane.memory.api.VectorIndexPort.Similarity;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryDigest;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryRanker;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read path: admit → governance → narrow → retrieval → policy filter → ranking → audit (AD-026
 * §6).
 *
 * <p>Two properties here carry most of the weight.
 *
 * <p><b>Narrow before query</b> (MEM-15). The requested scope is intersected with the caller's
 * authority before an adapter is touched, so an adapter is never handed a query that could match
 * another tenant. Filtering afterwards would mean the records had already been read into this
 * process.
 *
 * <p><b>Re-verify what comes back</b> (AD-026 §6.1). An adapter is trusted to answer a query, not
 * to enforce policy. Every returned record is checked for scope, expiry, archive state and digest.
 * This is defence in depth, not redundancy: an adapter that needs it to be correct is broken, and
 * this is how the runtime finds out rather than serving the result.
 */
public final class MemoryReadPipeline {

  private final MemoryPolicyStore policies;
  private final MemoryGovernancePort governance;
  private final MemoryStorePort store;
  private final VectorIndexPort index;
  private final MemoryEmbedder embedder;
  private final MemoryCryptoPort crypto;
  private final MemoryRanker ranker;
  private final MemoryAuditPort audit;
  private final MemoryMetricsPort metrics;
  private final ClockPort clock;

  /**
   * Creates the read pipeline.
   *
   * @param policies the compiled policy store
   * @param governance the seam to the governance engine
   * @param store the durable record store
   * @param index the vector index
   * @param embedder the embedding seam
   * @param crypto the unsealing seam
   * @param ranker the deterministic ranker
   * @param audit where facts are recorded
   * @param metrics where operational signals go
   * @param clock the injected clock
   */
  public MemoryReadPipeline(
      final MemoryPolicyStore policies,
      final MemoryGovernancePort governance,
      final MemoryStorePort store,
      final VectorIndexPort index,
      final MemoryEmbedder embedder,
      final MemoryCryptoPort crypto,
      final MemoryRanker ranker,
      final MemoryAuditPort audit,
      final MemoryMetricsPort metrics,
      final ClockPort clock) {
    this.policies = Preconditions.requireNonNull(policies, "policies");
    this.governance = Preconditions.requireNonNull(governance, "governance");
    this.store = Preconditions.requireNonNull(store, "store");
    this.index = Preconditions.requireNonNull(index, "index");
    this.embedder = Preconditions.requireNonNull(embedder, "embedder");
    this.crypto = Preconditions.requireNonNull(crypto, "crypto");
    this.ranker = Preconditions.requireNonNull(ranker, "ranker");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  /**
   * Runs one query.
   *
   * @param caller who is reading, and what they are authorized for
   * @param query what they are asking for
   * @return the ranked results, or a refusal
   */
  public MemoryOutcome read(final MemoryCaller caller, final MemoryQuery query) {
    Preconditions.requireNonNull(caller, "caller");
    Preconditions.requireNonNull(query, "query");

    final long startNanos = System.nanoTime();
    final Instant now = clock.now();

    // ---- ADMIT ---------------------------------------------------------------------------------
    if (!caller.mayUseAll(query.types())) {
      return refuse(
          caller,
          query.scope(),
          "type.forbidden",
          MemoryStage.ADMIT,
          "caller may not read one of the requested types",
          now);
    }

    // ---- NARROW --------------------------------------------------------------------------------
    final Optional<MemoryScope> narrowed = caller.narrow(query.scope());
    if (narrowed.isEmpty()) {
      return refuse(
          caller,
          query.scope(),
          "scope.unauthorized",
          MemoryStage.NARROW,
          "requested scope does not intersect the caller's authority",
          now);
    }
    MemoryQuery scoped = query.withScope(narrowed.get());

    // ---- GOVERNANCE ----------------------------------------------------------------------------
    final MemoryGovernancePort.Decision decision =
        governance.admitRead(caller.principal(), scoped.scope(), scoped.types(), scoped.mode());
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

    // ---- RETRIEVAL -----------------------------------------------------------------------------
    boolean degraded = false;
    Optional<float[]> vector = Optional.empty();
    if (scoped.mode().needsVector()) {
      final String queryText = scoped.text().orElse("");
      vector = scoped.vector().isPresent() ? scoped.vector() : embedder.tryEmbed(queryText);
      if (vector.isEmpty()) {
        if (!scoped.mode().degradesToKeyword()) {
          // A semantic query answered from keyword would answer a different question than the one
          // asked, and would look like a correct answer while doing it.
          return refuse(
              caller,
              scoped.scope(),
              "embedding.unavailable",
              MemoryStage.RETRIEVAL,
              "semantic retrieval requires an embedding and none could be produced",
              now);
        }
        degraded = true;
        metrics.degraded(scoped.mode());
        scoped = scoped.withMode(RetrievalMode.KEYWORD);
      }
    }

    List<ScoredRecord> keywordHits = List.of();
    List<Similarity> semanticHits = List.of();
    try {
      if (scoped.mode() != RetrievalMode.SEMANTIC) {
        keywordHits = store.search(scoped);
      }
      if (scoped.mode().needsVector() && vector.isPresent()) {
        semanticHits = index.similar(scoped.scope(), scoped.types(), vector.get(), scoped.limit());
      }
    } catch (final MemoryStoreUnavailableException unavailable) {
      // Never a partial result. A half-answered memory query is a wrong answer that looks right,
      // and
      // the caller has no way to tell (AD-026 §11).
      metrics.storeUnavailable("search");
      return refuse(
          caller,
          scoped.scope(),
          "store.unavailable",
          MemoryStage.RETRIEVAL,
          unavailable.getMessage(),
          now);
    }

    // ---- POLICY FILTER -------------------------------------------------------------------------
    final Map<String, MemoryRecord> byId = new LinkedHashMap<>();
    final List<ScoredRecord> admissible = new ArrayList<>(keywordHits.size());
    int examined = keywordHits.size();

    for (final ScoredRecord scored : keywordHits) {
      final Optional<MemoryRecord> kept = admit(scored.record(), scoped, now);
      if (kept.isPresent()) {
        admissible.add(new ScoredRecord(kept.get(), scored.score()));
        byId.put(kept.get().id().value(), kept.get());
      }
    }

    // Semantic hits arrive as ids and must be resolved through the store, which is also where they
    // get
    // re-verified. An index that returns an id from another tenant cannot leak anything, because
    // the
    // fetch is scoped and the record is checked again on the way through.
    final List<Similarity> admissibleSemantic = new ArrayList<>(semanticHits.size());
    for (final Similarity similarity : semanticHits) {
      examined++;
      final MemoryRecord known = byId.get(similarity.id().value());
      if (known != null) {
        admissibleSemantic.add(similarity);
        continue;
      }
      final Optional<MemoryRecord> fetched = fetch(scoped.scope(), similarity.id());
      if (fetched.isEmpty()) {
        continue;
      }
      final Optional<MemoryRecord> kept = admit(fetched.get(), scoped, now);
      if (kept.isPresent()) {
        byId.put(kept.get().id().value(), kept.get());
        admissibleSemantic.add(similarity);
      }
    }

    // ---- RANKING -------------------------------------------------------------------------------
    final List<MemoryHit> ranked =
        switch (scoped.mode()) {
          case SEMANTIC -> ranker.rankSemantic(byId, admissibleSemantic, now, scoped.limit());
          case HYBRID ->
              ranker.rankHybrid(admissible, admissibleSemantic, byId, now, scoped.limit());
          case KEYWORD, METADATA, TIME, SCOPE ->
              ranker.rankKeyword(admissible, now, scoped.limit());
        };

    final List<MemoryHit> unsealed = unsealAll(ranked, scoped.scope());
    recordAccess(scoped.scope(), unsealed, now);

    // ---- AUDIT ---------------------------------------------------------------------------------
    // Every read, including one that matched nothing (MEM-12). A miss is precisely the signal that
    // matters when someone is probing for records they cannot see.
    emit(
        EventType.READ,
        MemoryStage.AUDIT,
        caller,
        scoped.scope(),
        degraded ? "retrieved.degraded" : "retrieved",
        unsealed.size(),
        now);

    metrics.read(
        scoped.mode(), examined, unsealed.size(), Duration.ofNanos(System.nanoTime() - startNanos));

    return new MemoryOutcome.Retrieved(unsealed, scoped.mode(), degraded, examined);
  }

  /**
   * Re-verifies one record an adapter returned.
   *
   * <p>Four checks, each catching a different adapter or timing failure:
   *
   * <ul>
   *   <li><b>Scope</b> — an adapter that returned something outside the narrowed scope. Counted as
   *       an isolation violation, which must always read zero.
   *   <li><b>Expiry</b> — a record past its TTL that the sweeper has not reached yet. A late
   *       sweeper must not make expired data visible.
   *   <li><b>Archive</b> — cold-storage records, unless the query asked for them.
   *   <li><b>Integrity</b> — a digest that no longer matches its content (MEM-24).
   * </ul>
   */
  private Optional<MemoryRecord> admit(
      final MemoryRecord record, final MemoryQuery query, final Instant now) {

    if (!record.scope().visibleTo(query.scope())) {
      metrics.isolationViolation("store");
      return Optional.empty();
    }
    if (record.expiredAt(now)) {
      return Optional.empty();
    }
    if (record.archived() && !query.includeArchived()) {
      return Optional.empty();
    }
    if (!query.types().contains(record.type())) {
      return Optional.empty();
    }
    if (!query.withinWindow(record.createdAt())) {
      return Optional.empty();
    }
    if (!query.matchesMetadata(record.metadata())) {
      return Optional.empty();
    }
    // Integrity is only checkable in the clear. A sealed body is verified after unsealing, by
    // whoever
    // can open it; digesting ciphertext would prove nothing about the plaintext.
    if (record.content().readable()
        && !MemoryDigest.matches(record.content().body(), record.content().digest())) {
      metrics.integrityFailure(record.type());
      return Optional.empty();
    }
    return Optional.of(record);
  }

  private Optional<MemoryRecord> fetch(final MemoryScope scope, final MemoryRecordId id) {
    try {
      return store.get(scope, id);
    } catch (final MemoryStoreUnavailableException unavailable) {
      metrics.storeUnavailable("get");
      return Optional.empty();
    }
  }

  /**
   * Opens sealed bodies where the key permits.
   *
   * <p>A record that cannot be opened is returned as-is rather than dropped (AD-026 §11): the
   * ciphertext is not a leak, and hiding the record would lose availability for nothing while
   * telling the caller their memory has vanished.
   */
  private List<MemoryHit> unsealAll(final List<MemoryHit> hits, final MemoryScope scope) {
    final List<MemoryHit> opened = new ArrayList<>(hits.size());
    for (final MemoryHit hit : hits) {
      final MemoryRecord record = hit.record();
      if (!record.content().sealed()) {
        opened.add(hit);
        continue;
      }
      final Optional<String> plaintext =
          crypto.unseal(scope, record.content().body(), record.content().keyRef().orElseThrow());
      if (plaintext.isEmpty()) {
        opened.add(hit);
        continue;
      }
      final MemoryRecord clear =
          new MemoryRecord(
              record.id(),
              record.scope(),
              record.type(),
              io.reliabilityai.gateway.dataplane.memory.api.MemoryContent.plain(
                  plaintext.get(), record.content().digest()),
              record.classification(),
              record.metadata(),
              record.createdAt(),
              record.expiresAt(),
              record.version(),
              record.importance(),
              record.tainted(),
              record.legalHold(),
              record.archived(),
              record.indexPending(),
              record.lastAccessedAt(),
              record.accessCount());
      opened.add(new MemoryHit(clear, hit.score(), hit.signals(), hit.redacted()));
    }
    return List.copyOf(opened);
  }

  /**
   * Records that these records were read, for the usage ranking signals.
   *
   * <p>Best-effort. A failure here must not fail the read that triggered it: losing an access count
   * degrades ranking slightly, while failing the read loses the answer entirely.
   */
  private void recordAccess(
      final MemoryScope scope, final List<MemoryHit> hits, final Instant now) {
    for (final MemoryHit hit : hits) {
      try {
        store.touch(scope, hit.record().id(), now);
      } catch (final RuntimeException ignored) {
        // Deliberately swallowed; see above.
      }
    }
  }

  private MemoryOutcome refuse(
      final MemoryCaller caller,
      final MemoryScope scope,
      final String code,
      final MemoryStage stage,
      final String detail,
      final Instant now) {
    metrics.refused(stage, code);
    emit(EventType.REFUSAL, stage, caller, scope, code, 0, now);
    return new MemoryOutcome.Refused(code, stage, detail);
  }

  private void emit(
      final EventType type,
      final MemoryStage stage,
      final MemoryCaller caller,
      final MemoryScope scope,
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
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              reasonCode,
              resultCount,
              caller.correlationId(),
              now));
    } catch (final RuntimeException sinkFailure) {
      // Reads proceed when the audit sink is down, and the gap is counted (AD-026 §11.1). Refusing
      // reads on an observability outage would turn it into a total memory outage for data the
      // caller
      // was already lawfully entitled to.
      metrics.storeUnavailable("audit");
    }
  }
}
