# ADR-026 — Memory Runtime

**ADR ID:** AD-026 · **Also designated:** Memory Runtime Constitution
**Status:** Proposed · **Date:** 2026-08-03
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Introduces:** **C17 — Memory Runtime** (data plane)
**Builds on:** `AD-004` (plugins), `AD-006` (stateless services), `AD-012` (zero trust), `AD-017` (DP/CP
separation), `AD-021` (multi-tenancy), `AD-024` (tool execution), `AD-025` (agent runtime)
**Amends:** `06-Service-Boundaries.md` (adds C17), `05-Domain-Model.md` (adds the Memory context)
**Does NOT amend:** `21-GovernanceEngine.md`, `28-PluginRuntime.md`, `AD-025`, `RequestPipeline` ordering

---

## §0 — Two corrections before the design

A constitutional document that inherits an error propagates it. Two need stating first.

### §0.1 — The component register is full, and I already collided with it twice

`05-Domain-Model.md` and `06-Service-Boundaries.md` allocate **C1 through C16 with no gaps**:

| # | Allocated to | # | Allocated to |
|---|---|---|---|
| C1 | Provider Registry / routing | C9 | Observability |
| C2 | Reliability | C10 | Audit |
| C3 | Identity (authn) | C11 | Prompt Asset |
| C4 | Policy & Governance | C12 | Extensibility / Plugin Registry |
| C5 | Metering & Cost | **C13** | **Administration & Success** |
| C6 | Identity & Tenancy | **C14** | **Secret & Credential** |
| C7 | Tenancy | **C15** | **Configuration & Policy** |
| C8 | Billing & Commerce | C16 | Notification |

`AD-024` states "adds C13" for the Tool Session Orchestrator. `AD-025` states "adds C14" for the Agent
Runtime. **Both numbers were already taken**, and both statements are wrong. I wrote them.

This milestone was briefed as "C15 — Memory Runtime". C15 is Configuration & Policy. Taking it would
have been a third collision, and the one most likely to cause real confusion, because Configuration &
Policy is the service the Memory Runtime *reads policy from*.

**Resolution adopted here:**

- **Memory Runtime is C17** — the next genuinely free slot at time of allocation.
- The Tool Session Orchestrator and the Agent Runtime are, as of today, **unallocated**. Their claimed
  numbers belong to other services. §14.2 recommends C18 and C19 and records the correction as a
  blocker for Council ratification. This document does not edit `AD-024` or `AD-025`.

`24A-Architecture-Reconciliation.md` previously rejected a proposal for "inventing C17". That rejection
does not apply here and it is worth saying why rather than quietly diverging: it rejected inventing a
*bounded context that was not needed* for the Token Optimization Engine, whose behaviour belonged inside
C1/C12. The Memory Runtime is the opposite case — it has its own domain model, its own store, its own
lifecycle and its own retention obligations, which is the textbook definition of a bounded context.
Extending a full register for a genuinely new context is register hygiene, not invention.

### §0.2 — The layering diagram is a stack, not a call graph

The brief gives:

```
Plugin Runtime  →  Memory Runtime  →  Agent Runtime
```

Read as a call graph this is unimplementable and unsafe: it has the Memory Runtime calling the Agent
Runtime, which would let a store drive an orchestrator, and it inverts `AD-025`.

Read as a **layer stack** — Plugin Runtime beneath, Memory Runtime above it, Agent Runtime above that —
it is exactly right, and exactly what `AD-025` §42 already requires. The call path is:

```
Agent Runtime (C18*)                 ── declares a memory capability on a step
      │  AgentToolPort
      ▼
Plugin Runtime (C12)                 ── resolves the capability, sandboxes, meters
      │  MemoryAccessPort
      ▼
Memory Runtime (C17)                 ── governance → policy → adapter → audit
      │  MemoryStorePort / VectorIndexPort / EmbeddingPort
      ▼
storage adapters                     ── outside this module, one per technology
```

**This preserves the two invariants that matter.** `AD-025` AGT-5: the agent does not own memory.
`AD-025` AGT-9: memory content reaches a model only through a new, fully governed `RequestPipeline`
execution — because it arrives as a tool artifact that a later model step names as an input, never as a
side channel into a prompt.

> **Consequence, stated plainly.** The Memory Runtime has **no dependency on and no knowledge of** the
> Agent Runtime. Nothing in C17 imports a C18 type. An agent is simply one caller among several; the
> runtime cannot tell an agent from a plugin from an operator tool.

\* pending §14.2 renumbering.

---

## §1 — Decision

**The Memory Runtime (C17) is a provider-neutral, policy-enforcing, adapter-backed memory plane. It owns
the memory domain model, the write and read pipelines, the memory policy engine and the audit contract.
It owns no database, no vector index, no embedding model and no cryptography implementation — every one
of those is a port.**

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        C17  MEMORY RUNTIME                                   │
│                                                                              │
│   WRITE PATH                              READ PATH                          │
│   ─────────                               ────────                           │
│   admit                                   admit                              │
│     ↓ MemoryGovernancePort                  ↓ MemoryGovernancePort           │
│   governance                              governance                         │
│     ↓                                       ↓                                │
│   memory policy      ◄── PolicySnapshot ──► memory policy                    │
│     ↓  (TTL, PII, residency, encryption,    ↓  (legal hold, residency,       │
│     ↓   legal hold, versioning)             ↓   redaction)                   │
│   storage adapter                         retrieval  ── keyword | semantic   │
│     ↓ MemoryStorePort                       ↓           | hybrid + filters   │
│     ↓ VectorIndexPort                     ranking    ── 6 signals            │
│     ↓                                       ↓                                │
│   audit                                   audit                              │
│     ↓ MemoryAuditPort                       ↓ MemoryAuditPort                │
│                                                                              │
│   LIFECYCLE (out of band): TTL sweep · retention · archive · delete proof     │
└──────────────────────────────────────────────────────────────────────────────┘
        │                    │                    │                  │
   MemoryStorePort    VectorIndexPort      EmbeddingPort      MemoryCryptoPort
        │                    │                    │                  │
   ┌────┴─────┬──────┬───────┴──┬─────────┬───────┴──┐         ┌─────┴─────┐
 Postgres  MongoDB  Redis  Elasticsearch  pgvector          KMS / AEAD adapter
                           OpenSearch   Milvus Pinecone Qdrant
                    ── all outside this module, one adapter module each ──
```

## §2 — What C17 owns, and what it must never own

| Owns | Does not own | Whose |
|---|---|---|
| The memory domain model (7 types) | The database | Adapter |
| Write and read pipelines | The vector index | Adapter |
| Memory policy: compile, merge, enforce | Embeddings | Adapter |
| Retrieval planning and ranking | Encryption primitives / key custody | C14 Secrets + crypto adapter |
| Scope and isolation enforcement | Authorization decisions | C4 Governance |
| Audit contract and delete proof | The audit sink | C10 Audit |
| Lifecycle: TTL, retention, archive | Tool sandboxing and quotas | C12 Plugin Runtime |
| Snapshot compilation and atomic swap | Orchestration, planning, run state | Agent Runtime |
| | Billing and pricing | C5 / C8 |

**The single most important row is the last-but-one.** The Memory Runtime never orchestrates. It answers
a write or a read and returns. It holds no loop, starts no thread on the hot path, and has no concept of
a conversation that must be advanced.

---

## §3 — Invariants (MEM-1 … MEM-28)

These bind every future Memory Runtime implementation.

### Neutrality

| # | Invariant |
|---|---|
| **MEM-1** | The runtime **MUST NOT** name a model provider, an embedding provider, or a database technology, in code, in configuration keys, or in a memory type. |
| **MEM-2** | The runtime **MUST NOT** contain database logic, query-language construction, vector arithmetic, or an embedding model. |
| **MEM-3** | Embeddings are opaque `float[]` produced behind `EmbeddingPort`. The runtime never computes one and never interprets one beyond similarity reported by the index. |
| **MEM-4** | A memory record is portable: any conforming adapter can store and return any record without loss. |

### Boundary

| # | Invariant |
|---|---|
| **MEM-5** | The runtime **MUST NOT** import an Agent Runtime, Plugin Runtime, Governance Engine or `RequestPipeline` type. Every outbound edge is a port. |
| **MEM-6** | Memory content reaches a model **only** as a tool artifact consumed by a later governed turn (`AD-025` AGT-9). The runtime offers no path that injects content into a prompt. |
| **MEM-7** | The runtime never authorizes. It **asks** `MemoryGovernancePort` and obeys. |
| **MEM-8** | The runtime holds no key material. Encryption is delegated; only ciphertext and a key reference cross the storage boundary. |

### Pipeline

| # | Invariant |
|---|---|
| **MEM-9** | Every write passes governance → memory policy → adapter → audit, in that order, with no stage skippable. |
| **MEM-10** | Every read passes governance → retrieval → ranking → audit, in that order. |
| **MEM-11** | A refusal at any stage is **fail-closed**: nothing is stored, nothing is returned, and the refusal is audited. |
| **MEM-12** | Audit is emitted for **every** admitted write and read, including those returning nothing. |
| **MEM-13** | No stage may be reordered, and no caller may enter the pipeline mid-way. |

### Isolation

| # | Invariant |
|---|---|
| **MEM-14** | Every record carries a complete `MemoryScope`. A record without one is unrepresentable. |
| **MEM-15** | A query is **narrowed** to the caller's scope before it reaches an adapter, never merely filtered afterwards. |
| **MEM-16** | Cross-tenant reachability is impossible: an adapter never receives a query that could match another tenant's record. |
| **MEM-17** | Isolation is enforced at four levels — tenant, workspace, user, role — and the strictest applicable level wins. |

### Policy

| # | Invariant |
|---|---|
| **MEM-18** | Memory policy is **compiled once** into an immutable snapshot and swapped atomically. The hot path parses nothing. |
| **MEM-19** | Policy merge is monotonically non-loosening: combining scopes never widens what is permitted or lengthens what is retained. |
| **MEM-20** | A record under legal hold is **never** deleted, expired, archived or overwritten, whatever any other policy says. |
| **MEM-21** | Absent or unreadable policy is **not** permissive. It is unenforceable, and unenforceable fails closed. |
| **MEM-22** | Residency is checked before the adapter is chosen, never after the write. |

### Integrity and proof

| # | Invariant |
|---|---|
| **MEM-23** | Audit events are append-only and immutable. Nothing in the runtime can modify or delete one. |
| **MEM-24** | Every record carries a content digest. A record whose digest does not match its content is refused on read. |
| **MEM-25** | A delete produces a **delete proof** — a durable, verifiable record that the deletion happened, surviving the deleted content. |
| **MEM-26** | Audit events are `ContentFree`: they carry digests, scopes and classifications, never tenant content. |

### Performance

| # | Invariant |
|---|---|
| **MEM-27** | Reads take no lock. Snapshot access is a single volatile read of an immutable structure. |
| **MEM-28** | No reflection, no runtime code generation, no dynamic proxies, no classpath scanning. |

---

## §4 — The seven memory types

The brief names seven. Each earns its place by differing in **lifetime**, **scope**, **write pattern**
and **default retrieval mode** — not merely in what a caller chooses to put in it. A type that differed
in none of those would be a metadata tag, and modelling it as a type would be a lie about the domain.

| Type | Lifetime | Default scope | Write pattern | Default retrieval | Survives restart |
|---|---|---|---|---|---|
| **WORKING** | Seconds–minutes | Session | High churn, overwritten | Most-recent-first | No (may be volatile) |
| **SESSION** | Hours | Session | Append | Recency | Yes |
| **LONG_TERM** | Indefinite, retention-bound | User or workspace | Append, curated | Hybrid | Yes |
| **SEMANTIC** | Indefinite | Workspace or tenant | Append, embedded | Semantic | Yes |
| **EPISODIC** | Retention-bound | User | Append, time-anchored | Time-filtered + recency | Yes |
| **TOOL** | Retention-bound | Workspace | Append, keyed by capability | Keyword + metadata | Yes |
| **TASK** | Task lifetime + grace | Task | Append, state-like | Metadata-filtered | Yes |

### §4.1 — What each type is for, and what it is not

**WORKING** — the scratchpad for one active exchange. Deliberately the only type an adapter may hold in
a volatile tier, because losing it costs a retry rather than a fact. Not a cache of long-term memory:
its records are authored, not copied.

**SESSION** — the transcript-shaped record of one bounded interaction. Retrieved by recency because a
session's meaning is sequential. Not a conversation store the runtime interprets; it stores opaque
entries and never parses roles or turns.

**LONG_TERM** — durable facts about a user or workspace that outlive any session. The type most exposed
to retention and residency policy, and the one where a delete proof matters most.

**SEMANTIC** — meaning-addressed knowledge. The only type for which an embedding is mandatory rather
than optional, because a semantic memory with no vector is a keyword memory wearing a costume.

**EPISODIC** — what happened, and when. Distinguished from SESSION by being time-anchored rather than
session-anchored: an episode may span sessions, and is retrieved by time window.

**TOOL** — what a capability returned, keyed by capability and arguments. Its purpose is to avoid
re-invoking an expensive tool. **Tainted by default without exception** (§10.4): tool output is
untrusted content, and a tool memory is untrusted content that has been made durable, which is strictly
worse.

**TASK** — the state of a unit of work. Scoped to a task identifier supplied by the caller. The runtime
does not know what a task is, does not create one and does not end one; it stores records against an
opaque key.

### §4.2 — What the type does *not* control

A type is **not** an authorization boundary. Two records of different types in the same scope are
equally reachable to a caller authorized for that scope. Authorization is scope plus governance;
treating a type as a permission would produce a second, weaker access-control model beside C4's.

---

## §5 — The write pipeline

```
caller
  │  MemoryWriteRequest { scope, type, content, metadata, ttl?, importance? }
  ▼
┌─ ADMIT ────────────────────────────────────────────────────────────────┐
│  scope complete? type valid? content within bound? caller matches scope?│  refuse → audit → return
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ GOVERNANCE ───────────────────────────────────────────────────────────┐
│  MemoryGovernancePort.admitWrite(scope, type, classification, size)     │  DENY → audit → return
│  the C4 engine decides; this runtime interprets ALLOW/DENY only         │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ MEMORY POLICY ────────────────────────────────────────────────────────┐
│  resolve snapshot for scope chain  (compiled, lock-free)                │
│  ├ PII        classify → redact | refuse | store-encrypted              │
│  ├ RESIDENCY  choose the permitted region; no permitted region → refuse │
│  ├ ENCRYPTION at-rest requirement → MemoryCryptoPort.seal               │
│  ├ TTL        compute expiry from policy, not from the caller's wish    │
│  ├ VERSIONING supersede or append a new version                         │
│  └ LEGAL HOLD an existing held record may not be superseded             │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ STORAGE ADAPTER ──────────────────────────────────────────────────────┐
│  MemoryStorePort.put(record)                                            │
│  VectorIndexPort.index(id, embedding)   ── only when the type needs it  │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ AUDIT ────────────────────────────────────────────────────────────────┐
│  MemoryAuditPort.written(scope, id, type, digest, classification, ...)  │
│  ContentFree. Emitted after durability, so no audit claims a lost write │
└────────────────────────────────────────────────────────────────────────┘
```

### §5.1 — Ordering rules, and why each is where it is

**Governance before policy.** C4 decides *whether* this principal may write memory of this kind at all.
Memory policy decides *how* an admitted write is stored. Running policy first would mean computing
residency, sealing content and deriving a TTL for a write that governance was going to refuse — work,
spend and a crypto call for nothing.

**PII before encryption.** Classification determines whether encryption is *required*. Sealing first
would encrypt content that policy would have refused outright, and would put a decision behind a
ciphertext the classifier cannot read.

**Residency before the adapter.** MEM-22. Choosing a store and then discovering the region is forbidden
means either an illegal write or a compensating delete — and a compensating delete of data that should
never have crossed a border is not a remedy, it is an incident with extra steps.

**TTL from policy, not from the caller.** A caller may *request* a TTL. Policy decides the actual one,
and may only shorten. A caller-chosen TTL that policy honoured would let any caller opt out of retention
limits by asking for a hundred years.

**Audit after durability.** MEM-12 requires an audit for every admitted write; emitting before the
adapter acknowledges would produce audit records for writes a crash then unmakes.

### §5.2 — Idempotence

A write carries a caller-supplied `writeKey`. Two writes with the same key in the same scope are the
same write: the second returns the first's outcome and stores nothing. Without this, a retried tool
invocation after a lost acknowledgement duplicates a memory, and duplicated memories are worse than
missing ones — they distort every ranking signal that counts frequency.

---

## §6 — The read pipeline

```
caller
  │  MemoryQuery { scope, types, mode, text?, embedding?, filters, timeWindow?, limit }
  ▼
┌─ ADMIT ── query well-formed? limit within bound? mode satisfiable? ─────┐  refuse → audit
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ GOVERNANCE ── MemoryGovernancePort.admitRead(scope, types, mode) ──────┐  DENY → audit
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ NARROW ───────────────────────────────────────────────────────────────┐
│  intersect the requested scope with the caller's authorized scope       │
│  MEM-15: the adapter is never handed a query that could match another   │
│  tenant. Filtering after the fact would mean the data was already read. │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ RETRIEVAL ────────────────────────────────────────────────────────────┐
│  KEYWORD   → MemoryStorePort.search(narrowed)                           │
│  SEMANTIC  → EmbeddingPort.embed(text) → VectorIndexPort.similar(...)   │
│  HYBRID    → both, fused by rank (§7.3)                                 │
│  + metadata filter, time filter, tenant filter — all pushed to adapter  │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ POLICY FILTER ────────────────────────────────────────────────────────┐
│  drop expired-but-not-yet-swept · drop archived-unless-requested        │
│  redact per PII policy · decrypt via MemoryCryptoPort where permitted   │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ RANKING ── six signals, weighted, deterministic (§7) ──────────────────┐
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌─ AUDIT ── MemoryAuditPort.read(...) — every read, including empty ones ─┐
└────────────────────────────────────────────────────────────────────────┘
```

### §6.1 — Why a policy filter exists after retrieval

An adapter is not trusted to enforce policy. It is trusted to answer a query. A record may be returned
that has expired but not yet been swept, or that has been archived, or whose PII policy has tightened
since it was written. Enforcing only at write time would mean a policy change never reaches data already
stored — which is precisely the case that matters when a policy tightens after an incident.

**This is defence in depth, not redundancy.** The adapter is asked to exclude these records; the runtime
verifies that it did.

### §6.2 — An empty result is still a read

MEM-12. A query that matched nothing is an access attempt, and an access log with holes where the
misses were is not an access log. It is also the signal that matters most when someone is probing for
the existence of records they cannot see.

---

## §7 — Retrieval and ranking

### §7.1 — Six retrieval modes

| Mode | Needs | Pushed to |
|---|---|---|
| `KEYWORD` | query text | `MemoryStorePort.search` |
| `SEMANTIC` | text (embedded) or a caller-supplied vector | `VectorIndexPort.similar` |
| `HYBRID` | both | both, fused by §7.3 |
| `METADATA` | key/value predicates | `MemoryStorePort.search` |
| `TIME` | a half-open instant window | `MemoryStorePort.search` |
| `SCOPE` | scope alone — "everything I may see of this type" | `MemoryStorePort.search` |

Metadata, time and tenant filters are **not** modes in the sense of being alternatives — they compose
with any mode. `METADATA`, `TIME` and `SCOPE` exist as modes for the case where a filter is the *whole*
query. Every filter is pushed down to the adapter; the runtime never retrieves broadly and narrows in
memory, because a broad retrieval has already read the data it was supposed to exclude.

### §7.2 — Six ranking signals

Ranking is a weighted sum of six normalised signals, each in `[0,1]`:

| Signal | Meaning | Computed from |
|---|---|---|
| `SIMILARITY` | Semantic closeness | Index score, normalised |
| `KEYWORD_MATCH` | Lexical overlap | Adapter score, normalised |
| `FRESHNESS` | How recently written | Exponential decay over age |
| `RECENCY_OF_USE` | How recently read | Exponential decay over last-access |
| `IMPORTANCE` | Declared significance | Caller-declared, policy-clamped |
| `USAGE_FREQUENCY` | How often read | Damped log of access count |

**Deterministic and total.** The same inputs give the same order, always. Ties break on a stable
key — record id — so pagination cannot loop or skip. A ranker whose order varied between two identical
calls would make every cursor unsound.

**Importance is clamped by policy, not trusted.** A caller declaring maximum importance on everything
would otherwise pin its own records to the top of every result set forever, which is a denial-of-quality
attack that costs the attacker nothing.

### §7.3 — Hybrid fusion

Hybrid uses **reciprocal rank fusion** over the two result lists rather than a weighted sum of raw
scores. Raw scores from a keyword engine and a vector index are not commensurable — one is a BM25-family
figure with no upper bound, the other a bounded cosine — and averaging them means whichever adapter
happens to produce larger numbers wins. Fusing on **rank** is scale-free, so swapping an adapter cannot
silently change relevance.

### §7.4 — What ranking does not do

It does not re-order across scopes to hide an isolation failure, does not fetch more than the adapter
returned, and does not learn. A ranker with state would be a model, and a model in the memory plane
would need its own governance, versioning and audit — a second inference surface behind a store.

---

## §8 — Memory policy

### §8.1 — Ten policy types

| Policy | Question it answers | Merge rule |
|---|---|---|
| `TTL` | How long may this live? | **min** |
| `RETENTION` | How long *must* this live? | **max** |
| `PII` | What if it contains personal data? | strictest action |
| `RESIDENCY` | Where may bytes live? | **intersection** |
| `ENCRYPTION` | Must it be sealed at rest? | required wins |
| `LEGAL_HOLD` | Is deletion suspended? | any hold wins |
| `DELETE` | Who may delete, and how? | strictest |
| `ARCHIVE` | When does it move to cold storage? | **min** age |
| `VERSIONING` | Supersede or append? | explicit > default |
| `SNAPSHOT` | Point-in-time capture cadence | **min** interval |

### §8.2 — Merge is a semilattice meet

Policies resolve along a scope chain — tenant → workspace → user → session/task — and merging is
**idempotent, commutative, associative and monotonically non-loosening** (MEM-19). This is the same
algebra `21A` established for governance, and reusing it is deliberate: an operator who has learned that
"more specific scopes can only tighten" should not have to learn a second, different rule here.

**The one apparent contradiction, resolved.** `TTL` merges by minimum and `RETENTION` by maximum, which
looks like one of them must be loosening. It is not: TTL is a ceiling on life and retention a floor, so
tightening TTL shortens and tightening retention lengthens. When they cross — a retention floor above a
TTL ceiling — the record is **retained and flagged unexpirable**, and the conflict is surfaced rather
than silently resolved. Deleting data a retention rule requires is the worse error, and a rule conflict
that nobody sees is worse than both.

### §8.3 — Legal hold overrides everything

MEM-20 is absolute. A held record is not deleted by TTL expiry, not archived, not superseded by a new
version, not removed by an explicit delete, and not purged by a tenant offboarding. The only thing that
removes a hold is releasing the hold, which is a separate, audited control-plane act.

**Every deletion path in the implementation checks the hold, and the check is unconditional.** A hold
honoured on three of four paths is not a hold.

### §8.4 — Compile once, swap atomically

Policy is compiled from its source into an immutable `MemoryPolicySnapshot` and published through a
single `AtomicReference`. The hot path performs one volatile read and walks pre-resolved structures. It
parses nothing, allocates nothing per policy, takes no lock, and cannot observe a half-installed
snapshot. This mirrors `PolicyStore` in the governance engine, which is the pattern this codebase
already uses and tests.

### §8.5 — Unenforceable is not permissive

MEM-21. If the snapshot is absent, if a scope resolves to nothing, or if a required classification could
not be produced, the outcome is **not** "no policy, therefore allow". It is `UNENFORCEABLE`, and
unenforceable refuses. This is the same decision made for the governance admission path in Milestone 2,
and for the same reason: the alternative silently converts a broken dependency into an open door.

---

## §9 — Isolation

### §9.1 — Four levels

```
TenantScope { org, tenant, workspace, project }  +  PrincipalId  +  roles
                    │
   ┌────────────────┼────────────────┬─────────────────┐
   ▼                ▼                ▼                 ▼
 TENANT          WORKSPACE          USER              ROLE
 hard partition  sub-partition   owner-private   capability-gated
```

`MemoryScope` is the composite. It is **mandatory and complete** on every record (MEM-14) — there is no
constructor that produces a partial scope, so "forgot to set the tenant" is not a reachable state.

### §9.2 — Narrowing, not filtering

MEM-15 is the load-bearing rule. A query's scope is intersected with the caller's authorized scope
*before* the adapter sees it. The distinction matters because:

- **Filtering after** means the adapter read another tenant's records into this process. Even if they
  are discarded, they were read, they may be logged, and they may appear in a heap dump.
- **Narrowing before** means the query cannot match them. The adapter is never asked.

### §9.3 — Role isolation is a capability check, not a row filter

Roles do not appear in the storage predicate. A role determines *which memory types and which
operations* a caller may request; once admitted, retrieval is by scope. Encoding roles into every query
would push an authorization model into nine adapters and guarantee the nine disagree.

---

## §10 — Security and threat model

### §10.1 — Assets

Tenant memory content · embeddings (which leak content) · metadata (which leaks structure) · the audit
trail · key references · the policy snapshot.

**Embeddings are treated as content, not as metadata.** A vector is invertible enough to matter; storing
one where the plaintext is forbidden would be a residency and PII bypass with a mathematical fig leaf.

### §10.2 — Threats and mitigations

| # | Threat | Mitigation | Invariant |
|---|---|---|---|
| T1 | Cross-tenant read | Narrow-before-query; scope mandatory | MEM-15, MEM-16 |
| T2 | Cross-tenant write (poisoning) | Scope taken from the authenticated caller, never from the payload | MEM-14 |
| T3 | Memory poisoning via tool output | TOOL memories tainted by default; taint carried on read | §10.4 |
| T4 | Injection persisted then replayed | Content reaches a model only via a governed turn | MEM-6 |
| T5 | Residency violation | Region resolved before adapter selection | MEM-22 |
| T6 | PII stored unclassified | Classification mandatory; unclassifiable → refuse | MEM-21 |
| T7 | Deletion evasion | Delete proof; audit immutable | MEM-25, MEM-23 |
| T8 | Deletion of held data | Unconditional hold check on every path | MEM-20 |
| T9 | Tampered record | Content digest verified on read | MEM-24 |
| T10 | Key exposure | No key material in the runtime; ciphertext + key ref only | MEM-8 |
| T11 | Audit forgery / gap | Append-only, `ContentFree`, emitted post-durability | MEM-23, MEM-26 |
| T12 | Policy bypass via missing policy | Unenforceable fails closed | MEM-21 |
| T13 | Enumeration by probing | Empty reads audited; refusals do not distinguish "absent" from "forbidden" | §10.3 |
| T14 | Ranking manipulation | Importance clamped by policy | §7.2 |
| T15 | Unbounded growth / DoS | Content, query and result bounds; TTL mandatory per type | §12.3 |
| T16 | Adapter compromise | Runtime re-verifies scope, expiry and digest on every returned record | §6.1 |

### §10.3 — Absent and forbidden are indistinguishable to a caller

A read for a record the caller may not see returns the same shape as a read for a record that does not
exist. Distinguishing them would turn the read API into an oracle for the existence of other tenants'
records — the classic enumeration leak. The audit trail records the difference; the caller does not see
it.

### §10.4 — Taint

A `TOOL` memory is tainted at write, and taint is returned on read. This is the durable analogue of
`AD-024` §35 and `AD-025` §60.2: untrusted content that has been persisted is *more* dangerous than
untrusted content in flight, because it will be retrieved later by a caller who has forgotten where it
came from. The runtime does not decide what to do about taint — that is the agent's Rule-of-Two
accounting — but it must never lose the flag.

### §10.5 — Delete proof

A delete removes content and leaves a `DeleteProof`: record id, scope, content digest, deleting
principal, reason, instant, and a proof digest binding them. It is durable, immutable and outlives the
content.

Without it, "we deleted it" is an assertion. With it, it is evidence — which is what a regulator, an
auditor and a customer exercising erasure rights each actually require. Note what the proof deliberately
does **not** contain: the content. A proof that quoted what was deleted would be a copy of the thing
that was supposed to be gone.

---

## §11 — Failure recovery

| Failure | Behaviour | Rationale |
|---|---|---|
| Store unreachable on **write** | Refuse, audit `WRITE_UNAVAILABLE`; caller retries with the same `writeKey` | Never claim durability that does not exist |
| Store unreachable on **read** | Refuse; **never** return a partial result silently | A partial memory result is a wrong answer that looks right |
| Vector index down, mode `SEMANTIC` | Refuse | Degrading to keyword would answer a different question than asked |
| Vector index down, mode `HYBRID` | **Degrade to keyword, flagged in the result** | Hybrid already promises fusion of what is available; the flag keeps it honest |
| Embedding provider down | Refuse semantic; keyword unaffected | |
| Crypto port down, encryption required | Refuse the write | Storing plaintext because sealing failed is the whole breach |
| Crypto port down on read | Return the record marked undecryptable | The ciphertext is not a leak; failing the read loses availability for nothing |
| Audit sink down | **Write/read still fails closed** for writes; reads proceed and the gap is counted | See §11.1 |
| Policy snapshot missing | Unenforceable → refuse | MEM-21 |
| Partial write (store ok, index failed) | Record marked `INDEX_PENDING`; reconciled by sweeper; keyword-visible meanwhile | Better than an orphaned index entry pointing at nothing |
| Partial write (index ok, store failed) | Index entry orphaned → swept | An index hit that dereferences to nothing must never reach a caller |
| Sweeper crash mid-pass | Idempotent; next pass resumes | Deletion is idempotent by construction |
| Clock skew | All time from `ClockPort`; TTL evaluated at read as well as by sweep | A record must not be visible past its expiry because a sweeper is late |

### §11.1 — Why audit failure is asymmetric

A **write** that cannot be audited is refused: an unaudited write creates data nobody can account for,
and that is exactly the state a compliance regime exists to prevent. A **read** that cannot be audited
proceeds, and the gap is counted and alarmed — because refusing reads when the audit sink hiccups turns
an observability outage into a total memory outage, and the data was already lawfully readable.

This asymmetry is deliberate and is the one place where availability is chosen over completeness of the
trail. It is called out here rather than buried so a reviewer can disagree with it.

### §11.2 — Ordering under partial failure

The write path is **store-then-index**. If the index write fails the record still exists and is
keyword-visible; if the order were reversed a crash between the two would leave an index entry pointing
at nothing, and a semantic hit that dereferences to a missing record is worse than a record that is
temporarily not semantically findable.

---

## §12 — Adapters

### §12.1 — Three ports, nine technologies, zero vendor code in C17

| Port | Contract | Satisfied by |
|---|---|---|
| `MemoryStorePort` | Durable record store: put, get, search (keyword + metadata + time + scope), delete, list-expired | PostgreSQL, MongoDB, Redis, Elasticsearch, OpenSearch |
| `VectorIndexPort` | Similarity index: index, similar, remove | pgvector, Milvus, Pinecone, Qdrant, Elasticsearch, OpenSearch |
| `EmbeddingPort` | text → `float[]` | any embedding provider |

Nine technologies, three ports, **one adapter module each, all outside C17**. The runtime names none of
them (MEM-1). A technology serving both roles — Elasticsearch, OpenSearch, Postgres with pgvector —
implements both ports; the runtime cannot tell and must not care.

### §12.2 — What the ports deliberately do not expose

No query language, no index tuning, no consistency level, no connection or transaction handle, no
paging cursor format. Each would leak a technology's model into the runtime and make a portable record
(MEM-4) impossible. A cursor is an opaque token the runtime passes back unread.

### §12.3 — What an adapter must guarantee

| # | Guarantee |
|---|---|
| A1 | `put` is durable before it returns, or it throws. No "queued". |
| A2 | `put` is idempotent on `(scope, writeKey)`. |
| A3 | `search` returns **only** records matching the narrowed scope. The runtime re-verifies, but an adapter that needs re-verification to be correct is a broken adapter. |
| A4 | Unavailability is reported as unavailability, never as an empty result. |
| A5 | Bounds are honoured: never return more than `limit`. |
| A6 | Stored bytes are returned byte-identical. |
| A7 | `delete` is idempotent and reports whether anything was removed. |

**A4 is the one most easily got wrong and most damaging.** An adapter that returns empty on failure
turns a store outage into "the user has no memories" — which reads as data loss to the caller and, in a
write-if-absent flow, causes actual data loss.

### §12.4 — Why no vendor adapter ships in this milestone

None of the nine drivers is resolvable in this environment (blocker B1: Maven is unusable), and a vendor
adapter compiled against no driver is a stub asserting nothing. Shipping nine such stubs would inflate
the file count while proving less than the one reference adapter that is actually exercised by 100+
tests. §14.1 records this as the honest gap it is.

---

## §13 — Performance

| Requirement | Mechanism |
|---|---|
| Hot path | Read path allocates only the result; no policy parsing, no map building |
| Snapshot compilation | Policy compiled once at install; hot path walks resolved structures |
| Immutable structures | Every domain type is a record with defensively-copied collections |
| Lock-free reads | One `AtomicReference` volatile read; no lock anywhere on read |
| Atomic swaps | Snapshot install is a single CAS; readers see old or new, never partial |
| No reflection | Explicit construction throughout; verified by scan |
| No runtime codegen | No proxies, no bytecode generation, no dynamic classes |

**Bounds are part of performance, not only of security.** Content size, metadata cardinality, query
`limit` and result-set size are all bounded, because an unbounded memory read is an unbounded
allocation, and an unbounded allocation on a shared data plane is a tenant-visible outage.

---

## §14 — Blockers and honest gaps

### §14.1 — Recorded with this milestone

| # | Gap |
|---|---|
| **B21** | **C-number collision.** `AD-024` claims C13 (Administration & Success) and `AD-025` claims C14 (Secret & Credential). Both are wrong and both are mine. §14.2 recommends the fix; it needs Council ratification. |
| **B22** | No vendor storage adapter ships. One reference in-memory adapter only (§12.4). |
| **B23** | No real PII classifier. The port exists; the reference implementation is deliberately conservative and marks uncertain content as PII, which fails closed but over-classifies. |
| **B24** | No real crypto adapter. `MemoryCryptoPort` exists; the reference implementation is **not** cryptography and is named so it cannot be mistaken for it. |
| **B25** | No real embedding provider. Port only. |
| **B26** | Snapshot/versioning policies are modelled and enforced at the record level; a point-in-time snapshot *of a whole scope* needs an adapter capability that no port yet exposes. |
| **B27** | The memory capability is not yet registered with the Plugin Runtime, so no agent can reach memory end-to-end until a capability binding is added. Deliberate: registering it is a C12 change, and this milestone must not modify the Plugin Runtime. |

### §14.2 — Recommended register correction (needs ratification)

| Component | Claimed | Recommended |
|---|---|---|
| Tool Session Orchestrator (`AD-024`) | C13 ❌ *(Administration & Success)* | **C18** |
| Agent Runtime (`AD-025`) | C14 ❌ *(Secret & Credential)* | **C19** |
| Memory Runtime (this document) | — | **C17** ✅ *(allocated here)* |

Memory Runtime takes C17 because it is the free slot at the time of allocation. The other two are
renumbered in introduction order. This document does not edit `AD-024` or `AD-025`.

---

## §15 — Migration guide

### §15.1 — Nothing to migrate yet, and that is the point

C17 is **purely additive**. No existing module is modified. Removing the module restores today's
behaviour exactly; nothing in the request pipeline, the plugin runtime, governance or the agent runtime
depends on it.

### §15.2 — Phases

| Phase | Content | Gate |
|---|---|---|
| **0** | Council ratifies C17 and the §14.2 correction | Approval |
| **1** | Runtime + reference adapter + policy engine *(this milestone)* | Suite green; sabotage verified |
| **2** | First durable adapter (one technology, chosen by deployment) | Adapter conformance suite passes unmodified |
| **3** | Real crypto adapter via C14 Secrets | Sealed records unreadable without the key reference |
| **4** | Real PII classifier | Over-classification rate acceptable to compliance |
| **5** | Real embedding provider + vector adapter | Semantic recall measured on a labelled set |
| **6** | Register the memory capability with C12 | An agent reaches memory through a governed turn, end to end |
| **7** | Lifecycle sweeper on a schedule | TTL, retention, archive and delete proof observed in production |

**Phase 6 is the one that must not be brought forward.** Until phases 3–5 are real, an agent reaching
memory would be reaching a store with no encryption, no classification and no semantics. Better that it
cannot reach it at all.

### §15.3 — Adapter conformance

An adapter is admitted when it passes the shared conformance suite unmodified. The suite is written
against the port, not against the reference adapter, and the reference adapter is simply its first
subject — so "passes for the in-memory one" and "passes for Postgres" mean the same thing.

### §15.4 — Data migration between adapters

Because a record is portable (MEM-4), migration is read-all-write-all through the ports, with three
rules: legal holds transfer first and are verified before any source deletion; residency is re-evaluated
against the target, and a record whose residency the target cannot satisfy is not migrated; and the
source is not deleted until the target's digests verify.

---

## §16 — Rejected alternatives

| Rejected | Why |
|---|---|
| Memory inside the Agent Runtime | Violates `AD-025` AGT-5; puts durable state in a component whose executor must stay stateless |
| Memory as a pipeline stage | Not every request has memory; `RequestPipeline` ordering is frozen and a conditional stage is not a stage |
| One store port per technology | Nine ports, nine semantics, and the runtime learns all nine — the opposite of an abstraction |
| Embeddings computed in-runtime | Violates MEM-2 and drags a model into the memory plane |
| Type as an authorization boundary | A second, weaker access-control model beside C4's (§4.2) |
| Weighted-sum hybrid fusion | Incommensurable scales; adapter swap silently changes relevance (§7.3) |
| Adapter-enforced policy | Policy would live in nine places and disagree in nine ways |
| Caller-chosen TTL | Lets any caller opt out of retention by asking (§5.1) |
| Learned ranking | An unaudited inference surface behind a store (§7.4) |
| Soft delete only | "Deleted" that is still readable is not deleted; erasure rights require removal + proof |

---

*End of ADR-026 — Memory Runtime Constitution.*
