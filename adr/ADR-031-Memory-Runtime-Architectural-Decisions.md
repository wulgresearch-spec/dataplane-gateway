# AD-031 — Memory Runtime: Four Architectural Decisions

**Status:** Proposed — **decision document only, no implementation**
**Date:** 2026-08-08
**Supersedes:** nothing. **Superseded by:** nothing.
**Closes:** nothing. **Decides the direction for:** B65, B67, B68, B69.
**Depends on:** AD-026 (Memory Runtime, C17), AD-027 (journal store), AD-028 (memory cryptography), AD-029 (PII detection), AD-030 (embedding provider).
**Requires ratification of:** one frozen-contract amendment (§19.1) before any of Decision 2 may be built.

---

## 1. Executive decision summary

| # | Question | Decision | Contract change? |
|---|---|---|---|
| **1** | Vector index technology | **PostgreSQL + pgvector**, with Qdrant as the documented escape hatch at scale | No |
| **1b** | B69 — type filter applied after `limit` | **Adapter over-fetches.** Port contract stays frozen. A future index-time type is a *separate* ADR | No, now |
| **2** | B65 — `EmbeddingPort` carries no scope | **Widen to `embed(MemoryScope, String)`** | **YES — needs Council ratification** |
| **3** | B68 — encrypted memory, plaintext embedding | **Default deny — but not because it is encrypted.** Deny until the index offers *equivalent* protection; `SECRET` denied unconditionally | No, now |
| **4** | B67 — index reconciliation | **Memory Runtime worker, at-least-once.** Effectively-once *only* where one technology serves both ports | No |

**Nothing in this document is implemented.** Every decision below is `DESIGNED`. None is `IMPLEMENTED`, `TESTED`, `PROVEN-CONNECTABLE`, `WIRED` or `LIVE`. **B65, B67, B68 and B69 remain open** — an ADR that decides a direction has not closed a gap, and §28 restates each one unchanged.

**The single sharpest finding.** Decisions 1 and 4 are not independent. `indexPending` can only be cleared in the same transaction that writes the vector **if one technology serves both `MemoryStorePort` and `VectorIndexPort`** — which AD-026 §12.1 explicitly contemplates ("A technology serving both roles — Elasticsearch, OpenSearch, Postgres with pgvector — implements both ports"). Choosing Postgres therefore converts B67 from an unavoidable at-least-once retry loop into a transactional operation. That coupling, not benchmark throughput, is the strongest argument in this document.

---

## 2. Current architecture

```
MemoryRuntime.write(caller, request)
  └─ MemoryWritePipeline
       ├─ PiiClassifierPort.classify(body)              AD-029
       ├─ MemoryGovernancePort.admitWrite(...)          governance, before any side effect
       ├─ policy: classification ceiling, residency, PiiAction
       ├─ shaped = scoped.withBody(redactedBody)        line 197 — REDACTION
       ├─ final String plaintext = shaped.body()        line 203 — capture, post-redaction
       ├─ MemoryCryptoPort.seal(scope, plaintext)       AD-028, when policy requires
       ├─ MemoryStorePort.put(record, idempotencyKey)   DURABLE
       └─ if (needsIndex && !deduplicated)              line 261
              index.index(scope, id, embedder.embed(plaintext))
            catch RuntimeException → indexPending stays true, metrics.storeUnavailable("index")
```

`VectorIndexPort` today has exactly one implementation, `InMemoryVectorIndex` — exact cosine over a `ConcurrentHashMap`, explicitly "**Not a vector database**" in its own javadoc.

---

## 3. Verified repository facts

Every claim below was read from source for this document, not carried from a summary.

| Fact | Evidence |
|---|---|
| `EmbeddingPort` is `float[] embed(String)` | `memory/api/EmbeddingPort.java` — one method, no scope |
| `VectorIndexPort` is `index(scope,id,vec)` / `similar(scope,types,vec,limit)` / `remove(scope,id)` | `memory/api/VectorIndexPort.java` |
| `index()` is **never told the record's `MemoryType`** | same file — the parameter does not exist |
| `InMemoryVectorIndex` **ignores** the `types` argument | `internal/InMemoryVectorIndex.java` — `types` is null-checked and never read |
| The pipeline re-filters by type **after** `limit` | `MemoryReadPipeline.java:277` `if (!query.types().contains(record.type()))` |
| **Redaction precedes embedding** | `MemoryWritePipeline.java:197` then `:203` |
| Sealing and embedding are independent | `requiresSealing(classification)` vs `MemoryType.requiresEmbedding()` — no shared condition |
| `EffectiveMemoryPolicy` has 12 fields, none about indexing | `domain/EffectiveMemoryPolicy.java` |
| `indexPending` is durable | `JournalCodec` writes `idxp`; surfaces on `MemoryOutcome` |
| `MemoryRecord.indexed()` exists and **has no caller** | `grep -rn "\.indexed()"` → empty |
| `MemoryLifecycleSweeper` only ever `index.remove(...)` | `application/MemoryLifecycleSweeper.java:151` |
| Isolation predicate is `MemoryScope.visibleTo` | org+tenant, then workspace, project, owner privacy, partition |
| Shipped models are **1536 and 3072** dimensions | `OpenAiEmbeddingProvider` capability |
| B35: "Metadata, embeddings, digests and unsealed records are plaintext on disk" | `ADR-028:484`, severity **High** |
| B42: sealed content is invisible to keyword retrieval | `ADR-028:491` |

**A correction to the task brief.** It names `ADR-027-Memory-Durability.md`. No such file exists; the document is `ADR-027-Memory-Store-Journal-Adapter.md`. Read and used under its real name.

---

## 4. Problem statement

Phase 5 of AD-026 §15.2 cannot complete, and Phase 6/B27 is gated behind it. Four unresolved questions block it, and each is an architecture decision rather than a coding task:

1. There is no durable `VectorIndexPort`, and no decision about which technology should provide one.
2. The embedding seam cannot express tenant identity, so its cache cannot be tenant-safe in a shared runtime.
3. Encrypted content is embedded from plaintext into an unprotected index, and no policy can forbid it.
4. Failed indexing is durable but never retried.

---

## 5. Constraints

| # | Constraint | Source |
|---|---|---|
| C1 | Deploy on **one Ubuntu VPS** (Docker Compose, Nginx, Java 21, Postgres, optional Redis, local disk). AWS only when a proven scaling need cannot be met on one box | standing deployment directive |
| C2 | MEM-1 — the runtime must not name a provider or database technology | AD-026 §3 |
| C3 | MEM-2 — no database logic, query construction, vector arithmetic or embedding model in the runtime | AD-026 §3 |
| C4 | MEM-3 — embeddings are opaque `float[]`; the runtime never interprets one | AD-026 §3 |
| C5 | Adapter guarantees A1–A6 (durable-or-throw, idempotent, scope-correct, unavailability≠empty, bounded, byte-identical) | AD-026 §12.3 |
| C6 | EPC-3, EPC-6, GV-D10, PRT-D1 are frozen and **not amended here** | Doc 28, Doc 21 |
| C7 | No ThreadLocal, ambient context, or side channel | standing rule, reaffirmed |
| C8 | Phase 6/B27 must not be brought forward | AD-026 §15.2 |

---

## 6. Decision drivers

1. **Security before capability.** A semantic feature that leaks protected content is worse than no semantic feature.
2. **Operational surface is a cost.** On one VPS, every additional daemon is a thing that can be down at 03:00.
3. **Portability over performance.** AD-026 §12.4 already accepts a slower adapter that keeps the record portable.
4. **Honest failure.** Unavailability must never present as an empty result (A4).
5. **Don't decide what can be deferred.** Every decision here is the *minimum* needed to unblock Phase 5.

---

## 7 & 8. Decision 1 — vector index technology

### 7.1 Comparison matrix

Researched August 2026 against primary documentation; versions and licences quoted.

| | **pgvector 0.8.6** | **Qdrant** | **OpenSearch** | **Elasticsearch** | **Milvus** | **Pinecone** |
|---|---|---|---|---|---|---|
| Licence | PostgreSQL (permissive) | **Apache 2.0** | **Apache 2.0** (Linux Foundation, Sept 2024) | AGPLv3 / ELv2 / SSPL (triple, Aug 2024) | Apache 2.0 | Proprietary SaaS |
| Deployment | **Extension in Postgres already in the stack** | Single Rust binary, no external deps | JVM cluster | JVM cluster | Historically etcd + MinIO (+Pulsar/Kafka); standalone can embed since 2.1 | Vendor-hosted only |
| New daemons on the VPS | **0** | 1 | 1 (heavy) | 1 (heavy) | 1–4 | n/a |
| Memory, ~1M vectors | Shares Postgres buffers | ~1.2 GB; runs from 0.5 CPU / 1 GB | JVM heap, GBs | JVM heap, GBs | Highest | n/a |
| Persistence | Postgres WAL + backups | Own storage engine | Lucene segments | Lucene segments | Object store | Vendor |
| **Filtering** | **Post-filter after index scan**; `hnsw.iterative_scan` (0.8.0+) over-fetches to restore recall | **Filterable HNSW** — filter-aware graph edges; cardinality-based pre-filter | Filtered kNN | Filtered kNN (strongest of the two on filtered queries) | Filtered search | Metadata filter |
| Tenant isolation model | **SQL `WHERE`** — expresses `visibleTo` exactly | Payload partitioning (collection-per-tenant discouraged; ≤1000 collections) | Index/alias per tenant or filter | same | Partition key | Namespace |
| Deletes / updates | Transactional `DELETE` / `UPSERT` | Point delete / upsert | Doc delete, segment merge | same | Delete + compaction | Upsert/delete |
| **Same technology can serve `MemoryStorePort` too** | **YES** — named in AD-026 §12.1 | No | Yes | Yes | No | No |
| Restart recovery | Postgres crash recovery | Own WAL | Lucene | Lucene | Object store | n/a |
| Backup/restore | **`pg_dump` / PITR — one story for records and vectors** | Snapshot API | Snapshot | Snapshot | Backup tool | Vendor |
| Residency | Wherever Postgres runs | Self-host | Self-host | Self-host | Self-host | **Vendor regions — fails C1** |
| Max indexed dims | **`vector` ≤ 2,000; `halfvec` ≤ 4,000** | No practical limit at our sizes | 16k | 4k+ | 32k | 20k |
| Java integration | JDBC — already present | REST/gRPC client | REST client | REST client | SDK | SDK |
| Scale ceiling | Millions, single node | Tens of millions+ | Large | Large | Very large | Very large |

### 7.2 Decision — PostgreSQL + pgvector

**Decision.** The first durable `VectorIndexPort` adapter is **PostgreSQL with the pgvector extension**, in its own module (`gateway-dp-memory-vector-pgvector`), outside C17.

**Why.**

1. **It is named in the frozen ADR.** AD-026 §12.1 lists "Postgres with pgvector" among the technologies satisfying `VectorIndexPort`, and specifically as one that can serve both ports. This is not a new architectural direction; it is the one already written down.
2. **Zero new operational surface.** C1 already puts Postgres on the VPS. Every alternative adds a daemon, a backup story, a restore drill and a failure mode. On a single box that is the dominant cost, and it is paid every day, not once.
3. **`visibleTo` is a SQL predicate.** Our isolation model is not a flat tenant tag — it is org, tenant, workspace, project, owner-privacy and partition, with asymmetric rules (a user-private record is invisible to a caller with no owner). Expressing that as a `WHERE` clause is exact and reviewable. Expressing it as vector-store payload filters is a re-implementation of a security predicate in a second language, and §10 explains why that is the wrong place for it.
4. **One backup covers records and vectors.** With a separate vector store, a restore can leave the index and the record set at different points in time — silently, because a missing vector looks like "not yet indexed" and a stale one looks like a valid hit.
5. **It makes Decision 4 tractable.** See §7.5.

**Consequences.**
- *Positive:* one durability story, one backup, one restore, transactional deletes, and `indexPending` clearable in the same transaction as the vector write.
- *Negative:* Postgres becomes a shared failure domain for records and semantics. Mitigated by A4 — index unavailability is reported, and AD-026 §11 already degrades hybrid queries to keyword.
- *Negative:* pgvector is not the fastest filtered-ANN engine. Accepted under driver 3.

**Two concrete constraints this creates, stated now rather than discovered later:**

- **`text-large-3072` cannot use a `vector` HNSW index.** pgvector caps HNSW at **2,000 dimensions** for `vector` and **4,000** for `halfvec`. Our shipped `text-large-3072` is 3,072. It must therefore be stored as `halfvec` (fp16, lossy) to be indexed, or left unindexed. **This is a real capability limit tied to a model we already ship**, and the implementing milestone must decide per-model storage type. Recorded as **B70**.
- **Iterative scan must be enabled.** pgvector applies filtering *after* the index scan by default. With `hnsw.ef_search = 40` and a filter matching 10% of rows, a request for 10 neighbours returns ~4. `hnsw.iterative_scan = strict_order | relaxed_order` (0.8.0+) is not optional for us — see §7.4.

**Escape hatch.** **Qdrant** is the documented second implementation if measurement shows pgvector cannot meet latency at the corpus size reached. It is Apache 2.0, has no external dependencies, runs in ~1.2 GB for 1M vectors, and solves filtered recall structurally with filterable HNSW. Because both sit behind the same frozen port, that migration is an adapter swap, not a redesign.

### 7.3 Rejected, with reasons

| Rejected | Reason |
|---|---|
| **Qdrant now** | Better filtered-ANN engineering, but a second daemon, a second backup story, a second failure domain, and it cannot serve `MemoryStorePort` — which forfeits the transactional reconciliation in §7.5. Chosen *later* if measured need appears, not on preference. |
| **Elasticsearch** | Strongest filtered-vector performance of the two Lucene engines, but **AGPLv3/ELv2/SSPL**. AGPL copyleft in a product that ships as a gateway is a licensing question for counsel, not an engineering default. Rejected on licence risk, not capability. |
| **OpenSearch** | Apache 2.0 and technically viable; can serve both ports. Rejected only against pgvector on operational surface — a JVM cluster beside the gateway JVM on one VPS. **The strongest runner-up if the deployment ever needs keyword and vector in one engine** (it would also address B42). |
| **Milvus** | Heaviest footprint; historically etcd + MinIO, optionally Pulsar/Kafka. Built for a scale C1 explicitly defers. |
| **Pinecone** | SaaS-only. Fails C1 (self-hosted), fails residency, and puts tenant vectors — a derivative of protected content (§11) — in a third party. Rejected outright. |
| **Journal-backed brute force** (extend AD-027) | Tempting: reuses a proven segmented log. But it is `InMemoryVectorIndex` with a disk file — a linear scan, not an index. AD-026 §12.1's list implies a real ANN technology, and Phase 5's gate is *semantic recall*, which a brute-force scan meets trivially and misleadingly. Rejected as a category error. |

### 7.4 Decision 1b — B69, the type filter applied after `limit`

**The finding, restated precisely.** `similar(scope, types, embedding, limit)` accepts a type filter that **no adapter can implement**, because `index(scope, id, embedding)` is never told the record's type. `InMemoryVectorIndex` therefore ignores it, and `MemoryReadPipeline:277` re-filters afterwards — *after* `limit` has already truncated. A semantic query can return fewer results than requested while matching records exist. It is a **recall defect, not an isolation defect**: scope filtering is applied correctly inside the index; only *type* leaks past.

**The important context:** this is not a WULG bug. pgvector's own documentation describes exactly this behaviour as its default, and iterative index scans exist precisely to fix it. Post-filtering after ANN truncation is the industry-default failure mode.

**Decision: (1) the adapter must over-fetch. The port contract stays frozen.**

**Why not the alternatives:**

| Option | Verdict |
|---|---|
| (2) Change the port so `similar` returns types | Rejected — it would move record semantics into the index and oblige every adapter to store `MemoryType`, i.e. a schema change to fix a recall bug. |
| (3) Move filtering into the vector store | Rejected **now**, correct **later**. It needs `MemoryType` at index time, which is a frozen-contract change to `VectorIndexPort.index`. That is a separate ADR (§19.2) and must not be smuggled in here. |
| (4) Accept current semantics | Rejected. Silently returning 4 of 10 requested results is a correctness failure the caller cannot detect. |

**Exact semantics the implementation must honour:**
- The adapter over-fetches by a bounded factor and re-queries with a widened search parameter until either `limit` scope-visible candidates are returned or a **bounded** number of iterations is exhausted.
- Over-fetch is **bounded**, always. An unbounded widen under a highly selective filter degenerates into a full scan and turns a slow query into an outage.
- When the bound is hit with fewer than `limit` results, the adapter returns what it has. It **must not** signal unavailability — A4 is about the index being unreachable, not about a selective filter.
- Type filtering remains the pipeline's job at `:277`. Over-fetching only ensures enough candidates survive it.

**What remains blocked:** true pre-filtered ANN on type. That needs the §19.2 amendment.

### 7.5 Why Decisions 1 and 4 are coupled

`MemoryStorePort.put` and `VectorIndexPort.index` are, in the general case, two systems with no shared transaction — so clearing `indexPending` after a successful index write is a second, separately-failable operation, and the guarantee is at-least-once.

**If Postgres serves both ports, they are one system.** The vector insert and the `indexPending = false` update become one transaction: either both happen or neither does. The retry loop remains for the *embedding provider* call (which is genuinely external), but the store↔index gap closes entirely.

This is the single strongest argument for pgvector, and it is worth more than any benchmark in §7.1.

---

## 9. Decision 2 — B65, embedding scope

### 9.1 Options

| | **A. `embed(MemoryScope, String)`** | **B. One `MemoryRuntime` per tenant** | **C. Scoped context object** |
|---|---|---|---|
| Tenant isolation | Explicit, per call | Structural, by instance | Explicit, per call |
| Workspace/project/owner isolation | **Full — `MemoryScope` carries all of it** | Only if an instance per workspace, which is absurd | Full |
| Cache isolation | Complete (AD-030's key already accepts the fields) | Complete, but one cache per tenant | Complete |
| Provider batching | **Preserved** — one batcher, many scopes | **Destroyed** — batching cannot cross instances, so a 200-tenant deployment sends batches of one | Preserved |
| Concurrency / thread safety | Unchanged; scope is a parameter | Unchanged | Unchanged |
| Cost accounting | Per scope, exact | Per instance | Per scope |
| Ergonomics | One extra parameter | Composition root owns tenant lifecycle | A new type in C17's api |
| Backward compatibility | **Breaking** — one call site (`MemoryEmbedder`) | Non-breaking | **Breaking** |
| Hidden-context risk | **None** | None | None |
| MEM-1 / MEM-2 / MEM-3 | **Compliant** — `MemoryScope` is C17's own type, names no provider, no model, no vector arithmetic | Compliant | Compliant |

### 9.2 Decision — Option A, and **yes, the contract must change**

**Decision.** `EmbeddingPort` should become:

```java
float[] embed(MemoryScope scope, String text);
```

**This is a frozen-contract change and this ADR does not make it.** §19.1 states the amendment; the Council must ratify it before any code moves.

**Why A over C.** A scoped context object would carry exactly the information `MemoryScope` already carries, and would add a type to C17's `api` whose only purpose is to wrap another type in the same package. If a later need arises for something genuinely *not* in `MemoryScope` — a purpose, a deadline — C is the natural evolution. Until then it is ceremony.

**Why not B, emphatically.** Instantiating one runtime per tenant looks like the conservative choice because it changes no interface. It is the expensive one. `MemoryRuntime.write` takes the scope *per call* precisely because it is designed as a multi-tenant singleton; per-tenant instances would multiply the cache, the batcher, the registry and the provider connection pool by the tenant count, and AD-030 §11.5 measured what that costs — the coalescing batcher's mean batch size **equals the number of concurrent callers**, so per-tenant instances would reduce every batch to one and forfeit the entire economic argument for batching.

**Why the change is MEM-safe.** MEM-1 forbids naming a *provider*; MEM-2 forbids a *model* and vector arithmetic; MEM-3 requires embeddings stay opaque. `MemoryScope` is none of those — it is C17's own identity type, already threaded through every other port in the module. The port gains identity, not technology.

**Consequences.**
- *Security:* closes B65 and B64 together — owner reaches the cache key, so two users in one workspace stop sharing entries.
- *Operational:* one call site changes (`MemoryEmbedder.embed`), plus `PipelineEmbeddingPort`, which stops fixing a tenant at construction.
- *What becomes possible:* a single shared `MemoryRuntime` with a tenant-safe embedding cache; per-scope cost attribution; future per-tenant provider routing.
- *What becomes impossible:* nothing.
- *What remains blocked:* everything, until ratification. **B64 and B65 stay open.**

---

## 10 & 11. Decision 3 — B68, encrypted memory and semantic indexing

### 10.1 Challenging the proposed default

The brief's leaning is *"DEFAULT DENY semantic indexing when `encryptionRequired = true`"*. **I accept the default and reject the reasoning**, because the stated rule bakes in a permanent block on the correct future solution.

**The analysis.**

1. **Is an embedding a searchable derivative of protected content?** Yes. Embedding-inversion is a documented attack class; a 1536-float vector is a lossy but substantial encoding of its source. Treating it as opaque metadata is wrong.
2. **Do embeddings themselves need protection?** Yes, and **they do not have it today**. B35 (severity High) states plainly: *"Metadata, embeddings, digests and unsealed records are plaintext on disk."* So a sealed record's vector currently sits unprotected beside the ciphertext.
3. **Should `encryptionRequired` therefore *imply* denial?** **No — and this is the correction.** The hazard is not that the record is encrypted; it is that **the index offers weaker protection than the record's policy demands**. Writing "encrypted ⇒ no semantic indexing" into policy means that when B35 is fixed and the index *can* protect vectors, the rule still forbids the now-safe operation, and someone will weaken the encryption policy to get search back. That is the failure mode to design out.
4. **Can similarity leak information even with a protected index?** Yes. An attacker with query access can probe a corpus by similarity without ever reading a record. Protection of the vector at rest does not remove that channel — which is why `SECRET` must be excluded regardless of index capability.
5. **Does redaction precede embedding?** **Yes — verified.** `MemoryWritePipeline:197` replaces the body with `classified.redactedBody()` under `PiiAction.REDACT`, and `:203` captures `plaintext` afterwards. Redacted content is what gets embedded. This is a genuine existing protection and should be recorded as such rather than rediscovered.
6. **Rule of Two / lethal trifecta (AD-024 §35.2).** A semantic index over protected content is a *private-data* limb that an agent could reach without reading a single record. If Phase 6 ever lands, the memory capability must count as private-data access for Rule-of-Two purposes even when it returns only similarity scores. Recorded for the C13 decision; **not decided here**.
7. **B42 interaction.** Sealed content is already invisible to keyword retrieval. Denying semantic indexing too means a sealed record is findable only by scope and metadata. That is a real functional loss and must be stated to operators at policy-authoring time, not discovered in production.

### 10.2 Decision — capability-matched, default deny

**Decision.** Semantic indexing is permitted only when the index provides protection **equivalent to** what the record's policy demands. Expressed as a policy field, not as a derived rule:

```
semanticIndexing ∈ { FORBIDDEN, ALLOW_PLAINTEXT_INDEX, REQUIRE_PROTECTED_INDEX }
```

**Exact semantics for the future implementer:**

| Condition | Effective value |
|---|---|
| Policy unresolved / `UNENFORCEABLE` | `FORBIDDEN` — fail closed, consistent with MEM-21 |
| `classification == SECRET` | **`FORBIDDEN`, unconditionally and non-overridably.** Not even an explicit opt-in. Similarity probing (§10.1 point 4) is not removable by encrypting the vector |
| `encryptionRequired == true`, field unset | **`REQUIRE_PROTECTED_INDEX`** → deny while B35 is open, permit automatically once the index can protect vectors at rest |
| `encryptionRequired == true`, operator sets `ALLOW_PLAINTEXT_INDEX` | Permitted — an explicit, auditable, per-scope decision. The operator is accepting a stated risk, which is different from the system accepting it silently |
| `encryptionRequired == false` | `ALLOW_PLAINTEXT_INDEX` |

**Refusal must be a first-class outcome, not an error.** A record whose policy forbids semantic indexing is **not** `indexPending`. It is *not to be indexed*, permanently, and it must be distinguishable in the record state — otherwise Decision 4's reconciler will retry it for ever. This is the single most important interface consequence of Decision 3, and it is why B67 and B68 must be implemented together or not at all.

**Consequences.**
- *Security:* closes the derived-plaintext channel by default; keeps the door open for a protected index.
- *Functional:* a sealed record becomes findable only by scope and metadata (B42 + this). Must be surfaced in policy tooling.
- *What becomes possible:* an auditable per-scope decision to trade search for protection.
- *What becomes impossible:* silently embedding `SECRET` content.
- *What remains blocked:* **B68 stays open.** This decides semantics; `EffectiveMemoryPolicy` is unchanged and untouched.

---

## 12. Decision 4 — B67, index reconciliation

### 12.1 Ownership

**Decision: a Memory Runtime worker**, sibling to `MemoryLifecycleSweeper`.

| Candidate | Verdict |
|---|---|
| **Memory Runtime worker** | **Chosen.** `indexPending` is C17's state, in C17's records, cleared through C17's ports. AD-026 §15.2 Phase 7 already contemplates a scheduled sweeper. |
| Agent Runtime | Rejected — would make memory consistency depend on an unwired runtime, and AD-025 owns runs, not storage invariants. |
| Plugin Runtime | Rejected — PRT-D1 (additive-only); a plugin repairing a storage invariant is not additive. Also C6. |
| External durable execution system | Rejected under C1 — a new daemon for a retry loop over durable state we already have. |

### 12.2 Delivery guarantee

| Guarantee | Achievable? |
|---|---|
| **At-most-once** | Achievable and **wrong** — it is today's behaviour, which loses semantic findability permanently. |
| **At-least-once** | **Achievable, and the decision.** `index(scope,id,vec)` is put-semantics, hence idempotent: a duplicate re-index costs an embedding call, not correctness. |
| **Exactly-once** | **Not achievable across the store↔provider boundary, and not claimed.** The embedding provider is a remote non-transactional system; a crash after its response and before the index write is indistinguishable from a failed call. |
| *Effectively-once (store↔index only)* | **Achievable if and only if one technology serves both ports** — see §7.5. With Postgres, the vector write and `indexPending = false` are one transaction. The provider call remains at-least-once. |

**The honest formulation: at-least-once against the provider; transactional between store and index when the adapter pairing allows it.**

### 12.3 Required semantics

| Concern | Decision |
|---|---|
| Retry model | Bounded attempts, exponential backoff with **full jitter** — consistent with AD-030 §7.2, and for the same reason: a sweeper reconciling thousands of records must not synchronise its retries into a thundering herd against the provider. |
| Permanent failure | After the bound, the record enters an explicit **dead-letter** state, distinct from `indexPending`. It must never be silently retried for ever. |
| Policy refusal | Distinct from both (see §10.2) — never retried, never dead-lettered, because it is not a failure. |
| Idempotency | Keyed on `(scope, recordId)`; `index()` overwrites. |
| Deletion race | Re-read the record before indexing; if absent or deleted, `remove()` (idempotent) and stop. An index entry outliving its record is worse than a missing one (AD-026 §11.2). |
| Update race | Re-read before indexing and embed the *current* body. Embedding a stale body would index content the store no longer holds. |
| Provider outage | Backoff; do not drain the budget. Reuse AD-030's health signal rather than a second, differently-behaved one. |
| Vector-store outage | Unavailability, never empty (A4). Stop the sweep; do not dead-letter. |
| Crash / restart | State is durable (`idxp`); the sweeper resumes by scanning for it. No in-memory queue — that would be the "unreliable pseudo-queue" the brief forbids. |
| Tenant isolation | Reconciliation is per scope; one tenant's backlog must not starve another's. Round-robin across tenants, not oldest-first globally. |
| Cost ceiling | **Mandatory.** Reconciliation spends provider money without a user request. It must pass `EmbeddingGovernancePort.admitSpend` like any other embedding, and needs a per-tenant reconciliation budget — otherwise a large backlog after an outage becomes an unbounded, unattended bill. |
| Observability | Backlog depth by tenant, reconciliation attempts, successes, dead-letters, age of oldest pending record. Backlog depth is the number that goes from fine to catastrophic without passing through concerning. |
| Audit | Dead-lettering is an auditable event: a record permanently not semantically findable is a durable change in what the system can answer. |

**What remains blocked: B67 stays open.** No worker is built.

---

## 13–16. Cost, performance, residency, operations

**Cost.** pgvector adds no licence and no host. The dominant cost is the embedding provider, governed by AD-030's pre-call admission — *except* for reconciliation, which is why §12.3 makes a budget ceiling mandatory. Pinecone was rejected partly on the compounding cost of a per-vector SaaS at corpus scale.

**Performance. Not measured — no adapter exists.** Prior measured figures stand: cache hit ≈ 2.0 µs, miss ≈ 19.1 µs, memory write without embedding ≈ 142 µs, with ≈ 6.9 ms (batch-timeout dominated, AD-030 §11.5). The implementing milestone must measure vector write, lookup, delete and reconciliation throughput, and must **not** infer them from this document.

**Residency.** pgvector inherits Postgres's location, which already satisfies `EffectiveMemoryPolicy.permittedRegions` for records — one residency story, not two. Pinecone was rejected on this alone.

**Operational model.** One Postgres instance, one backup, one restore drill, one PITR timeline. The vector index becomes part of the existing database's operational envelope rather than a new one.

---

## 17. Migration strategy

1. Build the pgvector adapter behind the unchanged `VectorIndexPort`; `InMemoryVectorIndex` remains for tests.
2. Run the **adapter conformance suite** (AD-026 §15.3) unmodified against both. An adapter is admitted when it passes the suite written against the *port*, not against the reference.
3. If measurement later shows pgvector cannot meet latency, add a Qdrant adapter behind the same port and switch by composition. Because the port is frozen and records are portable (MEM-4), that is an adapter swap.

---

## 19. Exact future contract changes required

### 19.1 Required before Decision 2 — needs Council ratification

```java
// memory/api/EmbeddingPort.java
- float[] embed(String text);
+ float[] embed(MemoryScope scope, String text);
```

Amends AD-026 §12. Compatible with MEM-1/2/3 (§9.2). Call sites: `MemoryEmbedder`, `PipelineEmbeddingPort`. **Not performed in this ADR.**

### 19.2 Required only for full pre-filtered ANN on type — a separate ADR

```java
// memory/api/VectorIndexPort.java
- void index(MemoryScope scope, MemoryRecordId id, float[] embedding);
+ void index(MemoryScope scope, MemoryRecordId id, MemoryType type, float[] embedding);
```

**Deliberately not proposed for ratification here.** Decision 1b's over-fetch removes the recall defect without it. This becomes worth doing only if measurement shows over-fetch is too expensive under selective type filters.

### 19.3 Required for Decision 3 — a policy field

A `semanticIndexing` field on `EffectiveMemoryPolicy`, plus the compiler and snapshot changes, plus a record state distinguishing *policy-refused* from *index-pending*. **Not performed.**

---

## 20 & 21. Dependency graph and phase mapping

```
AD-031 ratified (this document)
   ├── Decision 1  → build pgvector adapter ─────────────┐
   ├── Decision 2  → §19.1 amendment ratified → scope-aware EmbeddingPort
   ├── Decision 3  → §19.3 policy field + refusal state ─┤
   └── Decision 4  → reconciler (needs Decisions 1 + 3) ─┘
                                                          ▼
                                    AD-026 §15.2 Phase 5 COMPLETE
                                    (gate: semantic recall measured on a labelled set)
                                                          ▼
                                    Memory Runtime safe and semantically usable
                                                          ▼
                                    production wiring decision  (B62)
                                                          ▼
                        tool-execution constitutional decision (EPC-3/EPC-6/GV-D10/PRT-D1)
                                                          ▼
                                          Phase 6 / B27   ← STILL BLOCKED
```

**AD-031 creates no path to B27.** It does not amend EPC-3, EPC-6, GV-D10 or PRT-D1; it does not bind `ToolExecutionPort`; it does not construct `MemoryRuntime` or `PluginRuntimeToolAdapter`. Every one of those remains exactly as it was. Completing all four decisions advances Phase 5 only — and Phase 5's gate, *semantic recall measured on a labelled set*, still requires a labelled set that does not exist.

---

## 22. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Postgres becomes a shared failure domain for records and semantics | A4 reporting; AD-026 §11 keyword degradation |
| R2 | `halfvec` precision loss for 3072-dim models silently degrades recall (**B70**) | Per-model storage decision; recall measured before adoption |
| R3 | Over-fetch (1b) degenerates toward a full scan under selective filters | Hard bound on iterations; measure |
| R4 | Reconciliation backlog after an outage becomes an unattended bill | Per-tenant budget ceiling (§12.3) — mandatory, not optional |
| R5 | Decision 3's refusal state confused with `indexPending`, causing infinite retry | Explicitly distinct states; B67 and B68 implemented together |
| R6 | §19.1 ratified but §19.3 deferred, leaving a tenant-safe cache over unprotected vectors | Sequence Decision 3 before or with Decision 2 |

---

## 23. Open questions

1. What constitutes a **labelled recall set** for Phase 5's gate, and who produces it? Without an answer, Phase 5 cannot be declared complete however much is built.
2. Should Postgres also replace `JournalMemoryStore` for `MemoryStorePort`, to realise §7.5's transactional reconciliation? AD-027 chose the journal for good reasons; this ADR does not reopen it.
3. Does a similarity-only hit count as private-data access for the Rule of Two? Needed for C13, not for Phase 5.
4. What protection would make an index "protected" for §10.2 — encrypted at rest, or something stronger?

---

## 24 & 25. Prerequisites and verification requirements

**Prerequisites:** §19.1 ratified (Decision 2 only); B35 assessed (Decision 3's `REQUIRE_PROTECTED_INDEX` is inert while embeddings are plaintext on disk); a labelled recall set (Phase 5 gate).

**Verification the implementing milestone must produce** — the standard AD-030 and docs/39 set: adapter conformance suite unmodified; cross-tenant, cross-workspace, cross-project and cross-owner isolation; deletion, replacement, duplicate indexing; provider outage, index outage, restart recovery, reconciliation recovery; malformed vectors; unauthorized retrieval; encrypted-memory policy refusal; provider neutrality; **sabotage verification against a green baseline with byte-identical restore**; and measured — not inferred — latency for vector write, lookup, delete and reconciliation.

---

## 26. Explicit non-goals

Not decided here: whether to wire `MemoryRuntime` into `gateway-dp-app`; anything about tool execution or B27; replacing `JournalMemoryStore`; ANN parameter tuning; a second embedding provider; changing `RequestPipeline`; amending EPC-3/EPC-6/GV-D10/PRT-D1; and **any implementation whatsoever**.

---

## 27. Council decisions required

| # | Decision | Blocking |
|---|---|---|
| **CD-1** | Ratify pgvector as the first durable `VectorIndexPort` | Decision 1 |
| **CD-2** | **Ratify the §19.1 `EmbeddingPort` amendment** | Decision 2 — nothing may be built until this is answered |
| **CD-3** | Ratify the §10.2 policy semantics, including that `SECRET` is non-overridable | Decision 3 |
| **CD-4** | Approve a per-tenant reconciliation budget ceiling | Decision 4 |
| **CD-5** | Name the owner of the labelled recall set | Phase 5 exit |

---

## 28. Gap register implications

**No gap is closed by this document.**

| Gap | Status after AD-031 |
|---|---|
| B64, B65 | **OPEN.** Direction decided (Decision 2); requires CD-2 then implementation |
| B67 | **OPEN.** Ownership and guarantee decided; no worker exists |
| B68 | **OPEN.** Semantics decided; no policy field exists |
| B69 | **OPEN.** Resolution decided (over-fetch); no adapter exists |
| **B70** | **NEW.** pgvector HNSW indexes `vector` to 2,000 dimensions and `halfvec` to 4,000; the shipped `text-large-3072` therefore requires `halfvec` (lossy fp16) to be indexed, or must remain unindexed |
| B35, B36, B42 | Unchanged; B35 is a prerequisite for Decision 3's protected-index branch |
| B57, B58, B62, B26, B27 | Unchanged |

**Next free gap number: B71.**

---

## Sources

Researched August 2026; primary documentation preferred.

- [pgvector — GitHub](https://github.com/pgvector/pgvector) — v0.8.6; HNSW `vector` ≤2,000 dims, `halfvec` ≤4,000; "filtering is applied *after* the index is scanned"; `hnsw.iterative_scan`
- [pgvector 0.8.0 release notes — Nile](https://www.thenile.dev/blog/pgvector-080) — iterative index scans
- [Filtering — pgEdge pgvector documentation](https://docs.pgedge.com/pgvector/development/filtering/)
- [A Complete Guide to Filtering in Vector Search — Qdrant](https://qdrant.tech/articles/vector-search-filtering/) — filterable HNSW, cardinality-based strategy
- [Multitenancy — Qdrant](https://qdrant.tech/documentation/guides/multiple-partitions/) — payload partitioning recommended; ≤1000 collections
- [Qdrant 1.16 — Tiered Multitenancy](https://qdrant.tech/blog/qdrant-1.16.x/)
- [Minimal RAM to Serve 1M Vectors — Qdrant](https://qdrant.tech/articles/memory-consumption/) — ~1.2 GB per 1M vectors
- [qdrant/LICENSE](https://github.com/qdrant/qdrant/blob/master/LICENSE) — Apache 2.0
- [Elastic announces AGPL option](https://alternativeto.net/news/2024/8/elastic-reverts-elasticsearch-and-kibana-to-open-source-with-new-agpl-license-option) — triple licensing since August 2024
- [OpenSearch vs Elasticsearch 2026 — licensing and filtered-vector performance](https://bigdataboutique.com/blog/opensearch-vs-elasticsearch-compared)
- [Requirements for Installing Milvus Standalone](https://milvus.io/docs/prerequisite-docker.md) and [Scale Dependencies](https://milvus.io/docs/scale-dependencies.md) — etcd / MinIO / Pulsar footprint

---

*End of document — adr/ADR-031-Memory-Runtime-Architectural-Decisions.md*
