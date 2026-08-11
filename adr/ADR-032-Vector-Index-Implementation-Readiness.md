# AD-032 — Vector Index Implementation Readiness

**Status:** Proposed — **readiness document. No implementation, no dependency, no schema created.**
**Date:** 2026-08-08
**Supersedes:** nothing. **Superseded by:** nothing.
**Extends:** AD-031 (which selected PostgreSQL + pgvector but did not say how a schema arrives).
**Closes:** nothing. **Decides:** B71. **Analyses:** B69, B70. **Allocates:** B71–B73 — B71 was first identified in the readiness review that preceded this document and is formally recorded here; B72 and B73 are new.
**Depends on:** AD-026 (C17), AD-027 (journal store precedent), AD-030 (embedding provider), AD-031.

---

## 0. Status key

Every statement in this document carries one of four labels. Nothing here is implemented.

| Label | Meaning |
|---|---|
| **DECIDED** | Settled by this document; an implementer may proceed on it without further approval |
| **PROPOSED** | A recommendation with reasoning; may be built once the reader accepts it |
| **BLOCKED** | Cannot proceed; the blocker is named |
| **NEEDS COUNCIL DECISION** | Requires explicit ratification because it changes policy, cost or a contract |

---

## 1. Executive summary

AD-031 chose PostgreSQL + pgvector and did not say how its schema is created, versioned or upgraded. That omission is **B71**, and it is mine. This document closes the design gap so the adapter milestone can be *implemented and genuinely verified* rather than simulated.

| # | Question | Outcome |
|---|---|---|
| 1 | B71 — migration mechanism | **DECIDED: application-owned versioned bootstrap**, following AD-027's existing `FORMAT_VERSION` precedent. No Flyway, no Liquibase |
| 2 | pgvector schema | **PROPOSED** — §4, derived strictly from what `VectorIndexPort` can legally supply |
| 3 | SQL security model | **PROPOSED** — §5, with `visibleTo` translated predicate-by-predicate and an 11-row sabotage table |
| 4 | pgvector acquisition | **PROPOSED: Docker image for laptop and CI; native extension on the production VPS**. **BLOCKED here** — the Docker daemon is down |
| 5 | JDBC driver | **BLOCKED — NEEDS COUNCIL DECISION.** Obtainable by direct HTTPS, not by Maven |
| 6 | Phase 5 recall gate | **PROPOSED** — §8 defines the set, the metric, the threshold and the anti-fraud control. **No dataset manufactured** |
| 7 | B70 — 3072 dimensions | **Analysed and now measured.** `halfvec` is required; the storage-format cost is **4.2×10⁻⁶ mean cosine error, recall@10 0.999, top-1 unchanged** — safe. The earlier ~5×10⁻⁴ estimate in this document was 100× too pessimistic and is corrected in §9.1. **B72** narrowed to pgvector-specific confirmation |

**The single most useful finding:** the repository already has a schema-evolution idiom, and it is not a migration framework. `JournalCodec.FORMAT_VERSION = 1` is written into every segment header, with the rule *"a change needs a reader for both, or old journals become unreadable."* B71 should follow that, not import a framework into a deliberately zero-framework codebase.

---

## 2. Verified repository facts

Read from source for this document.

| Fact | Evidence |
|---|---|
| No Flyway, Liquibase, JDBC or datasource dependency exists | `grep -rn -i "postgres\|jdbc\|flyway\|liquibase\|hikari\|datasource" backend/ --include=pom.xml` → empty |
| No `.sql` file exists anywhere | `find . -name "*.sql"` → empty |
| The journal store versions its on-disk format explicitly | `JournalCodec.FORMAT_VERSION = 1`; `SegmentLog:398` writes `HEADER_PREFIX + FORMAT_VERSION` |
| `java.sql..` is forbidden **per module**, not globally | each module's own ArchUnit test scopes the rule to `resideInAPackage(ROOT + "..")` — a new adapter module is exempt, as `gateway-dp-memory-store-journal` is |
| `VectorIndexPort.index` receives **only** `(MemoryScope, MemoryRecordId, float[])` | `api/VectorIndexPort.java` |
| `MemoryRecordId` is `record MemoryRecordId(String value)` | `api/MemoryRecordId.java` |
| `MemoryScope.partitioned()` is `!partition.isEmpty()` | `api/MemoryScope.java` |
| `sameTenantAs` compares **org and tenant only** | `api/MemoryScope.java:123-127` |
| `dimensionVisible(callerValue, recordValue)` is `callerValue == null \|\| callerValue.equals(recordValue)` | same file |
| PostgreSQL **15.15** is installed and listening on 127.0.0.1:5432, trust auth | verified by connection |
| pgvector is **not** installed and cannot be created | `ERROR: extension "vector" is not available` — no `vector.control` |
| Docker CLI 29.6.2 present, **daemon not running** | `dockerDesktopLinuxEngine` pipe absent; WSL `docker-desktop` **Stopped** |
| Maven Central **is** reachable by direct HTTPS | `postgresql-42.7.4.jar` fetched, HTTP 200, 1,086,687 bytes (probe deleted) |
| Testcontainers has **zero jars** — BOM `.pom` files only | `find .../org/testcontainers -name "*.jar"` → 0 |

---

## 3. Decision 1 — B71, the migration mechanism

### 3.1 Options

| | **Flyway** | **Liquibase** | **Application-owned versioned bootstrap** |
|---|---|---|---|
| Startup behaviour | Scans classpath, applies pending migrations, locks | Same, via changelog | Adapter runs its own ordered DDL under an advisory lock |
| Schema versioning | `flyway_schema_history` table | `DATABASECHANGELOG` | One `schema_version` row in a table the adapter owns |
| Upgrades | Add a numbered file | Add a changeset | Add a step to an ordered list, bump the version constant |
| Rollback | Paid feature (Teams) | Supported, XML/YAML | **None** — forward-only, stated as such |
| Concurrent startup | Table lock | Table lock | **Postgres advisory lock** (`pg_advisory_lock`), which is exactly this problem's tool |
| Failure behaviour | Fails startup, leaves a failed row | Fails startup | **Throws; the adapter refuses to serve** — matches A1 "durable before it returns, or it throws" |
| VPS deployment | Fine | Fine | Fine, and one less thing in the image |
| CI/testing | Needs the dependency | Needs the dependency | Runs in-process; nothing extra to install |
| **Zero-framework philosophy** | **A framework** — the repo has 0 Spring imports and a plain-Java composition root | **A framework**, plus XML/YAML | **Consistent** — mirrors `JournalCodec.FORMAT_VERSION` |
| Operational complexity | New dependency, new table, new failure mode, new CLI | Higher | Lowest |
| Dependency cost | New artifact, and **Maven cannot resolve** (§7) | Same | **None** |

### 3.2 DECIDED — application-owned versioned bootstrap

**Decision.** The pgvector adapter owns its schema. On construction it acquires a Postgres **advisory lock**, reads a single-row `wulg_vector_schema_version` table, applies any ordered steps whose version exceeds it, writes the new version, and releases the lock — all inside one transaction. If any step fails it throws, and the adapter does not serve.

**Why.**

1. **The repository already does this.** AD-027 embeds `FORMAT_VERSION` in every segment header and states the rule plainly: *"a change needs a reader for both, or old journals become unreadable."* B71 is the same problem in a different store, and answering it differently would give the codebase two schema-evolution idioms for no gain.
2. **Zero-framework is a real constraint here, not an aesthetic.** The repo has no Spring, a plain-Java composition root, and — critically — **Maven cannot resolve new dependencies** (§7). Flyway would add a blocker on top of a blocker.
3. **The advisory lock is the correct primitive**, not a workaround. Flyway and Liquibase both solve concurrent startup with a lock table; Postgres gives us a better one for free.
4. **Forward-only is honest.** Neither Flyway's free tier nor our operational model supports automated rollback of a vector table. Saying "forward-only, restore from backup" is truthful; shipping a rollback path nobody tests is not.

**Consequences.**
- *Positive:* no dependency, no framework, one idiom, testable in-process.
- *Negative:* we own the lock/version/ordering logic — perhaps 80 lines. It must be tested for concurrent startup specifically.
- *Negative:* no `migrate info` CLI. An operator inspects one table.
- *What becomes possible:* a SQL-backed adapter with no new build dependency.
- *What becomes impossible:* nothing.
- *What remains blocked:* the adapter itself, on §6 and §7.

**Not chosen but not wrong:** if the project ever adopts Spring Boot (Doc 09 lists it, though the codebase deliberately has zero Spring imports), Flyway becomes the conventional answer and this decision should be revisited. Recorded rather than pretended away.

---

## 4. Decision 2 — schema design (PROPOSED)

### 4.1 What the port can legally supply

This is the discipline that shapes everything below. `index(MemoryScope, MemoryRecordId, float[])` supplies **exactly**: org, tenant, workspace, project, owner, partition, record id, and the vector. It does **not** supply `MemoryType`, the model id, the provider id, classification, `indexPending`, or timestamps.

**Therefore the schema must not contain columns for them.** Inventing a `memory_type` column would require a value the adapter is never given, and filling it by querying `MemoryStorePort` from inside the vector adapter is the "undocumented contract" the brief forbids.

Two columns are the exception, and both are derived locally rather than received:
- **`dim`** — `embedding.length`, known from the argument itself.
- **`updated_at`** — the adapter's own clock, for operability. It is not read by any query and carries no semantics the port promises.

**Explicitly NOT in the schema:** `memory_type` (B69 — see §5.6), `model_id` / `provider_id` (the port supplies neither; AD-030 §6.1 already binds them into the *embedding cache* key, which is where they belong), `index_pending` (C17 state, and B67 is out of scope), classification or key-version (crypto state the port never sees).

### 4.2 Proposed DDL

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE wulg_vector_entry (
    org          text        NOT NULL,
    tenant       text        NOT NULL,
    workspace    text        NULL,          -- NULL is a real value: "no workspace"
    project      text        NULL,
    owner        text        NULL,          -- NULL means shared; non-NULL means user-private
    partition    text        NOT NULL DEFAULT '',   -- '' means unpartitioned (MemoryScope.NO_PARTITION)
    record_id    text        NOT NULL,
    dim          integer     NOT NULL,
    embedding    vector      NULL,          -- populated when dim <= 2000
    embedding_h  halfvec     NULL,          -- populated when dim  > 2000   (B70)
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT wulg_vector_entry_pk
        PRIMARY KEY (org, tenant, workspace, project, owner, partition, record_id),
    CONSTRAINT wulg_vector_entry_one_representation
        CHECK ((embedding IS NULL) <> (embedding_h IS NULL)),
    CONSTRAINT wulg_vector_entry_dim_positive
        CHECK (dim > 0)
);
```

**Identity.** The primary key is the full scope plus the record id — the SQL equivalent of `InMemoryVectorIndex`'s `key(scope, id) = scope.key() + "|" + id.value()`. This is what makes `(scopeA, id)` and `(scopeB, id)` independent rows and makes `INSERT … ON CONFLICT DO UPDATE` correct put-semantics.

> **Caveat, stated because it is load-bearing.** PostgreSQL primary keys cannot contain NULL, but `workspace`, `project` and `owner` are legitimately NULL. The implementation must therefore either declare them `NOT NULL DEFAULT ''` with `''` meaning absent, or use a unique index over `COALESCE(...)` expressions, or a partial-unique-index set. **The empty-string sentinel is the wrong choice** — §5.2 shows the workspace predicate depends on distinguishing NULL from a value, and collapsing them would silently merge "no workspace" with "a workspace literally named empty". **PROPOSED: a `UNIQUE` index over `COALESCE`-normalised expressions plus `NOT NULL` on the identity used for conflict resolution.** This is the one place the design is not yet exact, and the implementer must resolve it against a live database rather than on paper. Allocated as **B73**.

**Indexes.**

```sql
-- Scope selectivity first: every query is scope-bounded before it is similarity-ordered.
CREATE INDEX wulg_vector_entry_scope
    ON wulg_vector_entry (org, tenant, workspace, project, owner, partition);

-- ANN, cosine, one per representation. Built only after the table is populated in bulk loads.
CREATE INDEX wulg_vector_entry_ann
    ON wulg_vector_entry USING hnsw (embedding vector_cosine_ops);

CREATE INDEX wulg_vector_entry_ann_h
    ON wulg_vector_entry USING hnsw (embedding_h halfvec_cosine_ops);
```

**Cosine, not L2.** AD-030 normalises every vector to unit length before it leaves the pipeline, so cosine and inner product are monotonically related — but `vector_cosine_ops` states the intent and stays correct if normalisation is ever relaxed. `InMemoryVectorIndex` computes cosine rescaled into `[0,1]`; the pgvector adapter must return the **same scale**, because `MemoryRanker` consumes the score. pgvector's `<=>` yields cosine *distance* in `[0,2]`, so the adapter must return `1 - (embedding <=> :q) / 2` to match. **A conformance test must assert both adapters agree on ordering and scale.**

---

## 5. Decision 3 — SQL security model (PROPOSED)

### 5.1 The translation rule

`visibleTo` is six predicates. Each maps to one SQL clause, and **all six must be in the `WHERE` clause of the similarity query, evaluated before `LIMIT`.**

| # | Java (`MemoryScope.visibleTo`) | SQL predicate |
|---|---|---|
| 1 | `sameTenantAs` — org **and** tenant equal | `org = :callerOrg AND tenant = :callerTenant` |
| 2 | `dimensionVisible(caller.workspace, record.workspace)` | `(:callerWorkspace IS NULL OR workspace = :callerWorkspace)` |
| 3 | `dimensionVisible(caller.project, record.project)` | `(:callerProject IS NULL OR project = :callerProject)` |
| 4+5 | owner rules, combined | `(owner IS NULL OR owner = :callerOwner)` |
| 6 | partition | `(:callerPartition = '' OR partition = '' OR partition = :callerPartition)` |

### 5.2 Why each one is subtle

**Workspace and project invert the usual direction.** A *caller* with no workspace is a wildcard that sees every workspace; a *record* with no workspace is **not** visible to a caller who has one. `(:callerWorkspace IS NULL OR workspace = :callerWorkspace)` gets both: when the parameter is NULL the clause is true for all rows; when it is set, `workspace = 'a'` against a NULL row yields NULL, which is not true, so the row is excluded. That is exactly `dimensionVisible`.

**The owner rule is the opposite direction, and SQL three-valued logic gets it right for free.** Rules 4 and 5 together mean: a record with no owner is visible to anyone; a record *with* an owner is visible only to that same owner, and **never** to a caller with no owner. `(owner IS NULL OR owner = :callerOwner)` with `:callerOwner` bound to NULL evaluates to `(false OR NULL)` = NULL for every owned row — excluded. **This is correct, and it is correct for a reason that is easy to break:** anyone who "fixes" it to `COALESCE(owner, '') = COALESCE(:callerOwner, '')` changes the semantics and makes owned records visible to unowned callers. The sabotage table below covers it.

**Partition is deliberately not a privacy boundary.** `MemoryScope` says so in a comment. An unpartitioned caller sees every partition it is otherwise entitled to. The predicate must therefore be permissive in both directions, unlike owner.

### 5.3 INSERT (put semantics)

```sql
INSERT INTO wulg_vector_entry
      (org, tenant, workspace, project, owner, partition, record_id, dim, embedding, embedding_h)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?::halfvec)
ON CONFLICT ON CONSTRAINT wulg_vector_entry_pk
DO UPDATE SET embedding   = EXCLUDED.embedding,
              embedding_h = EXCLUDED.embedding_h,
              dim         = EXCLUDED.dim,
              updated_at  = now();
```

Idempotent by construction; a repeated index of the same `(scope, id)` replaces. Cross-scope overwrite is impossible because scope is in the key.

### 5.4 DELETE

```sql
DELETE FROM wulg_vector_entry
 WHERE org = ? AND tenant = ? AND workspace IS NOT DISTINCT FROM ?
   AND project IS NOT DISTINCT FROM ? AND owner IS NOT DISTINCT FROM ?
   AND partition = ? AND record_id = ?;
```

**`IS NOT DISTINCT FROM`, not `=`** — the nullable scope columns must match NULL to NULL. `remove` targets one exact row, not a visibility set, so this uses *equality of the stored scope*, not `visibleTo`. Deleting zero rows is success (the port requires idempotence).

### 5.5 Similarity search

```sql
SET LOCAL hnsw.ef_search = :efSearch;              -- widened per over-fetch iteration
SET LOCAL hnsw.iterative_scan = 'relaxed_order';   -- pgvector >= 0.8.0

SELECT record_id, 1 - (embedding <=> :queryVector) / 2 AS score
  FROM wulg_vector_entry
 WHERE org = :callerOrg
   AND tenant = :callerTenant
   AND (:callerWorkspace IS NULL OR workspace = :callerWorkspace)
   AND (:callerProject   IS NULL OR project   = :callerProject)
   AND (owner IS NULL OR owner = :callerOwner)
   AND (:callerPartition = '' OR partition = '' OR partition = :callerPartition)
   AND dim = :queryDim
 ORDER BY embedding <=> :queryVector, record_id
 LIMIT :fetchLimit;
```

**`ORDER BY … , record_id`** — the secondary key makes ties deterministic, matching `InMemoryVectorIndex`, which sorts by score then `id.value()`.

**`dim = :queryDim`** — comparing vectors of different widths is meaningless. `InMemoryVectorIndex` returns `0.0` for a width mismatch rather than throwing; the SQL adapter excludes them instead. **These differ**, and the conformance suite must decide which is the contract. **PROPOSED: exclude.** A zero-scored row consumes a `LIMIT` slot for a row that can never be relevant.

### 5.6 B69 — over-fetch, and what it does and does not fix

The type filter is **absent from the SQL above, deliberately.** `index()` is never given `MemoryType`, so no legal schema can hold it, and `MemoryReadPipeline:277` re-filters afterwards. AD-031 decided over-fetch; concretely:

- The adapter issues the query with `fetchLimit = limit × overFetchFactor`, bounded.
- If fewer than `limit` rows return **and** the index reports it was truncated, widen `ef_search` and retry, up to a **bounded** iteration count.
- On exhaustion, return what there is. **Never** signal unavailability — A4 is about the index being unreachable, not about a selective filter.

**Over-fetch does not close B69.** It raises the probability that enough rows survive the pipeline's type filter; it cannot guarantee it, because the adapter cannot know how many candidates are of the wanted type. **B69 stays open**, and the frozen contract is why.

### 5.7 Security invariant table

Eleven invariants, each with its predicate, its sabotage mutation, and the test that must fail. **None of these has been executed** — see §6.

| # | Invariant | SQL predicate | Sabotage mutation | Expected failure |
|---|---|---|---|---|
| 1 | Tenant isolation | `tenant = :callerTenant` | delete the clause | Tenant A retrieves Tenant B's vector |
| 2 | Organization isolation | `org = :callerOrg` | delete the clause | Org A retrieves Org B's vector |
| 3 | Workspace isolation | `(:callerWorkspace IS NULL OR workspace = :callerWorkspace)` | delete the clause | Workspace A retrieves Workspace B's vector |
| 4 | Project isolation | `(:callerProject IS NULL OR project = :callerProject)` | delete the clause | Project A retrieves Project B's vector |
| 5 | Owner privacy | `(owner IS NULL OR owner = :callerOwner)` | replace with `COALESCE(owner,'') = COALESCE(:callerOwner,'')` | User B retrieves User A's private record |
| 6 | Partition isolation | `(:callerPartition = '' OR partition = '' OR partition = :callerPartition)` | delete the clause | A partitioned caller sees another partition's record |
| 7 | Delete scope safety | full scope in the `DELETE` `WHERE` | drop scope, keep `record_id` | Deleting `(scopeA, id)` removes `(scopeB, id)` |
| 8 | Parameterisation | every value bound, never concatenated | build the `WHERE` by string concat | An injection-shaped tenant id alters the result set |
| 9 | Dimension validation | pre-flight check + `dim = :queryDim` | remove the check | A wrong-width or NaN vector is persisted |
| 10 | Similarity ordering | `ORDER BY embedding <=> :q, record_id` | drop the `ORDER BY` | Results are not similarity-ordered; ties non-deterministic |
| 11 | **Visibility before `LIMIT`** | all six predicates in `WHERE`, not applied after | move filtering to Java after the query | A high-similarity **unauthorized** record displaces an authorized one |

**Invariant 11 is the one that matters most**, and it is the one a plausible "optimisation" breaks: fetching by similarity and filtering in Java looks faster and is a cross-tenant leak. It is also the mutation most likely to pass a careless test suite, because the *final* returned set may still look correct while an authorized record has been silently displaced. The test must assert that an authorized record with a **worse** score is still returned when an unauthorized record has a better one.

---

## 6. Decision 4 — pgvector environment strategy (PROPOSED / BLOCKED)

| Context | Recommendation | Why |
|---|---|---|
| **A. Developer laptop** | **Docker image** `pgvector/pgvector:pg17` | Reproducible, disposable, version-pinned, no admin write to `C:\Program Files\`. The native PG 15.15 here is a *shared, stateful* machine resource — tests that create and drop tables in it are a footgun |
| **B. CI** | **Same Docker image**, started by the workflow | Identical to the laptop, which is the point. Testcontainers would be ideal but has **zero jars** locally, so the workflow starts the container as a service instead |
| **C. Production VPS** | **Native extension in the existing PostgreSQL**, installed from the distribution package | C1 already puts Postgres on the VPS. Running the database in Docker beside it would add a second data lifecycle to back up. `apt install postgresql-17-pgvector` is a packaged, signed artifact |

**Reproducibility and security, not convenience.** The image must be **pinned by digest**, not by tag, in CI — a floating `:pg17` tag is a supply-chain hole in a project that already runs `SupplyChainTest` on plugins. On the VPS the distribution package is preferable to `make install` from source for the same reason: signed, versioned, upgradeable.

**BLOCKED here.** The Docker daemon is not running (`dockerDesktopLinuxEngine` absent; WSL `docker-desktop` Stopped). I did not start it, install anything, or modify Docker configuration — the brief forbids all three. **The unblock is one action: start Docker Desktop.** Alternatively, install pgvector into the existing PG 15.15, which needs admin rights and a build toolchain, and which I judged too invasive to do unilaterally.

---

## 7. Decision 5 — JDBC dependency strategy (BLOCKED — NEEDS COUNCIL DECISION)

**Root cause — diagnosed, not guessed.** A TLS probe against Maven Central shows the certificate Java is
presented is **not** Maven Central's:

```
repo.maven.apache.org  →  subject: CN=repo.maven.apache.org
                          issuer : CN=Avast Web/Mail Shield Root,
                                   OU=generated by Avast Antivirus for SSL/TLS scanning
```

**Avast Antivirus is performing HTTPS interception.** It terminates TLS and re-signs with its own root,
which is installed in the **Windows** certificate store (thumbprint `3D136D8FEEE6C1F15CFEADAC93FCDD32F2A62557`,
valid to 2040) but not in the **JDK** trust store (`jdk-21/lib/security/cacerts`, 109 anchors).

That asymmetry explains the whole symptom: `curl` on Windows validates against the Windows store and
succeeds; Java validates against `cacerts` and fails with `SunCertPathBuilderException`. **It is neither
a Maven configuration problem, nor a proxy setting, nor a repository problem — it is a JDK trust-store
gap created by antivirus interception.**

**The fix is verified, not theorised.** Importing that root into a *copy* of `cacerts` and pointing Java
at the copy made default validation succeed, and made Maven resolve the driver normally:

```
default JDK trust: ACCEPTED (HTTP 200)      — both Maven Central hosts
mvn dependency:get org.postgresql:postgresql:42.7.4  → exit 0
~/.m2/.../postgresql-42.7.4.jar  1,086,687 bytes     — resolved, not vendored
```

TLS verification stayed **fully enabled** throughout; no insecure flag was used and the system trust
store was not modified. This confirms the diagnosis and the remedy in one step.

**Resolution paths, in order of preference:**

| Path | Assessment |
|---|---|
| **1. Fix Maven's trust store** — import the intercepting CA into the JDK truststore or point Maven at it | **PROPOSED.** Correct fix. Repairs *every* future dependency, not just this one, and would also restore Checkstyle, SpotBugs, ErrorProne, JaCoCo, PITest and ArchUnit — all currently unverifiable |
| 2. Internal Maven mirror / repository manager | Reasonable at team scale; heavier than needed for one machine |
| 3. Pre-seed `~/.m2` by direct download | Works, but a hand-placed jar is unreproducible and invisible to review |
| 4. **Vendor the jar into the repo** | **Rejected**, and the brief forbids it. A binary in version control is a supply-chain artifact nobody diffs |

**Why this needs a decision rather than an action.** Adding `org.postgresql:postgresql` is the first third-party *runtime* dependency in a module tree that currently has none — the codebase is deliberately zero-framework with a plain-Java composition root. AD-031 chose pgvector, which implies a driver, but nobody has ratified *"this project now ships a third-party runtime dependency."* That is a policy question about the dependency budget, and it is the Council's, not mine.

---

## 8. Decision 6 — Phase 5 semantic recall gate (PROPOSED)

AD-026 §15.2 gates Phase 5 on *"semantic recall measured on a labelled set."* **No such set exists, none is manufactured here, and Phase 5 cannot close.**

### 8.1 What the set must contain

| Property | Requirement |
|---|---|
| Size | **≥ 300 records and ≥ 50 queries** minimally useful. Below ~50 queries a single result moves recall@10 by 2 percentage points and the metric cannot distinguish a regression from noise |
| Relevance labels | Per query, a judged-relevant set. Graded (0–3) preferred; binary acceptable |
| **Semantic cases** | Queries that share **no** significant lexical overlap with their relevant records — paraphrase, synonym, abstraction. **This is the only class that proves semantics rather than keyword matching** |
| **Exact-match cases** | Queries that are near-verbatim. A system failing these is broken in an obvious way and the set should catch it |
| **Negative cases** | Queries with **no** relevant record, to detect a system that always returns something |
| **Isolation cases** | Records placed in different orgs, tenants, workspaces, projects, owners and partitions, where the *lexically best* match is in a scope the querying caller may not see. **Recall must be measured against the authorized set only, and any unauthorized hit is a hard failure, not a scored miss** |
| Multilingual | Non-Latin script records, given B58's known 4× token under-count |
| Provenance | Which model and provider produced the embeddings, since a vector space is not portable across providers (AD-030 §4.2) |

### 8.2 Metric and threshold

**Primary: recall@10 on the authorized set.** Secondary: nDCG@10 for ranking quality; **isolation violations, which must be exactly zero** and are not tradeable against recall.

**Threshold: PROPOSED, not decided.** Setting a number before any measurement exists would be inventing a result. What *can* be decided now: the threshold must be set against a **stated baseline** — recall@10 of the existing keyword path on the same set — and semantic retrieval must beat it materially on the semantic-case subset, or it is not earning its cost. That comparison is the honest gate; an absolute number chosen in advance is not.

### 8.3 The anti-fraud control — the important part

`DeterministicEmbeddingProvider` produces hash-derived vectors that carry, in its own words, *"no semantic information whatsoever."* A recall harness run against it would produce a number, and that number would be meaningless.

**Control: the evaluation must fail closed against a semantically-null provider.** Concretely — the harness runs the same labelled set twice, once through the configured provider and once through `DeterministicEmbeddingProvider`, and **refuses to report a pass unless the real provider beats the deterministic one by a stated margin on the semantic-case subset.** Hash vectors score at chance; a real provider does not. Any run where the two are indistinguishable is a failed evaluation regardless of the absolute recall figure.

This single control is what prevents Phase 5 being closed by a green harness running on meaningless vectors — which, given that the deterministic provider is the only one reachable in this environment, is exactly how it would otherwise happen.

### 8.4 Ownership — NEEDS COUNCIL DECISION

Engineering cannot own relevance judgements; a labelled set built by the team that wrote the retriever measures the team's assumptions. **PROPOSED: the set is owned by whoever owns the product's retrieval quality, with engineering owning the harness only.** This is CD-5 from AD-031, still unanswered.

---

## 9. Decision 7 — B70 analysis, 3072 dimensions

**The constraint.** pgvector indexes `vector` to **2,000** dimensions and `halfvec` to **4,000**. `text-large-3072` is 3,072, so it is `halfvec` (fp16) or unindexed.

### 9.1 Measured — and the earlier estimate in this document was wrong

> **Correction.** The first revision of this section estimated the cosine error analytically at
> **≈5×10⁻⁴**. That estimate was **~100× too pessimistic** and is superseded by the measurement below.
> The error was double-counting the component magnitude: it multiplied a *component absolute* error by
> the mean component magnitude a second time before accumulating. The original figure is left visible
> here rather than deleted, because a wrong number that shaped a recommendation should be seen to have
> been corrected.

Measured with IEEE 754 binary16 round-tripping (`Float.floatToFloat16` / `float16ToFloat`) — the same
representation `halfvec` uses — over 2,000 unit-normalised 3072-dim vectors, 200 queries, fixed seed
`20260808`, reproducible:

| Measurement | Value |
|---|---|
| mean \|component error\| | 2.54×10⁻⁶ |
| max \|component error\| | 3.05×10⁻⁵ |
| **mean \|cosine error\|** | **4.23×10⁻⁶** |
| p99 \|cosine error\| | 1.33×10⁻⁵ |
| max \|cosine error\| | 2.19×10⁻⁵ |
| *(superseded analytical estimate)* | *5×10⁻⁴* |

| Ranking impact | Value |
|---|---|
| recall@10 against the fp32 reference | **0.9990** |
| identical top-10 set | 198/200 |
| identical top-10 order | 193/200 |
| **top-1 changed** | **0/200** |
| near-duplicate pairs reordered (500 pairs at a ~10⁻⁴ cosine gap) | **1/500 (0.2%)** |

**Interpretation.** At a measured mean cosine error of ~4×10⁻⁶, `halfvec` is **safe for this use**.
Recall@10 is 0.999 against the exact-precision reference and the top result never changed. Even the
case the estimate flagged as risky — near-duplicate ordering where candidates differ in the fourth
decimal — flips only 0.2% of pairs. The earlier caution about dedup ranking was based on the inflated
figure and does not survive measurement.

**What this does NOT establish.** The experiment measures the *storage format*, not pgvector's HNSW
index over that format. Two things remain unconfirmed against the real extension: that `halfvec` is
IEEE binary16 as assumed, and that ANN recall over a `halfvec` HNSW index behaves like the exact scan
measured here. **B72 is narrowed, not closed.**

**Should original float32 be retained?** **PROPOSED: yes, but not by this adapter.** The authoritative record lives behind `MemoryStorePort`; the vector index is a derived structure that can be rebuilt by re-embedding. Storing a second float32 copy inside the index doubles its size to protect against a loss the source can already repair. **What must exist instead is the ability to rebuild the index** — which is B67's reconciliation, and is out of scope here.

**Does this warrant a new gap?** No. B70 already records the constraint and this section is its analysis. **A different gap does surface**, below.

---

## 10. New gaps

| Gap | Description |
|---|---|
| **B72** | **`halfvec` behaviour over a real pgvector HNSW index is unconfirmed.** §9.1 now *measures* the storage-format cost (mean cosine error 4.2×10⁻⁶, recall@10 0.999, top-1 never changed) and finds it safe. What remains unverified is that `halfvec` is IEEE binary16 as assumed, and that ANN recall over a `halfvec` HNSW index matches the exact scan measured here. Narrowed, not closed |
| **B73** | **Nullable scope columns cannot form a PostgreSQL primary key.** `workspace`, `project` and `owner` are legitimately NULL, but a PK cannot contain NULL, and the empty-string sentinel is unsafe because §5.2's workspace predicate depends on distinguishing NULL from a value. The uniqueness/conflict-resolution mechanism must be resolved against a live database, not on paper |

**Next free gap number: B74.**

---

## 11. Dependency graph

```
AD-032 (this document)
   ├── B71 DECIDED ────────────────────────┐
   ├── schema + SQL design PROPOSED ───────┤
   ├── environment  BLOCKED (Docker down) ─┤
   └── JDBC driver  BLOCKED (CD-6) ────────┤
                                            ▼
                         pgvector adapter implementable and VERIFIABLE
                                            ▼
                    B72 measured · B73 resolved · B69 documented (stays open)
                                            ▼
                    §8 labelled set exists (CD-5)  →  Phase 5 recall gate runnable
                                            ▼
                              AD-026 §15.2 Phase 5 COMPLETE
                                            ▼
                    production wiring decision (B62) → tool-execution decision → Phase 6/B27
```

**AD-032 creates no path to B27.** It amends no frozen contract, binds no port, and constructs nothing.

---

## 12. Council decisions required

| # | Decision | Blocks |
|---|---|---|
| **CD-6** | **Ratify that this project may take a third-party runtime dependency** (`org.postgresql:postgresql`), and fix Maven's trust store rather than vendoring | The adapter entirely |
| **CD-7** | Approve the Docker-for-dev/CI, native-package-for-VPS split, with digest-pinned images | Verification |
| **CD-5** *(carried from AD-031)* | Name the owner of the labelled recall set | Phase 5 exit |
| **CD-2** *(carried)* | `EmbeddingPort` widening | B65/B64 — **not this milestone** |

---

## 13. Non-goals

Not done here: the adapter; any DDL executed; any dependency added; any Docker or database configuration changed; any dataset manufactured; any threshold declared; B65, B67, B68, B27, production wiring, or any change to `EmbeddingPort`, `VectorIndexPort`, `MemoryStorePort`, `RequestPipeline`, `ToolExecutionPort`, the Plugin Runtime or `gateway-dp-app`.

---

## 14. Gap register implications

| Gap | Status after AD-032 |
|---|---|
| **B71** | **DECIDED** (§3) — application-owned versioned bootstrap. Not implemented, so **not closed** |
| B69 | **OPEN.** §5.6 documents the over-fetch behaviour and why it cannot close under the frozen port |
| B70 | **OPEN.** Analysed (§9); `halfvec` required |
| **B72, B73** | **NEW** (§10) |
| B64, B65, B67, B68 | Unchanged |
| B57, B58, B62, B26, B27, B35, B36, B42 | Unchanged |

---

*End of document — adr/ADR-032-Vector-Index-Implementation-Readiness.md*
