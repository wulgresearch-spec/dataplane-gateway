# 000 — Architecture Decision Record (ADR) Index

**Project:** Reliability-First AI Gateway
**Location:** `adr/`
**Status:** Living index — individual ADRs are immutable once Accepted, changed only by superseding
**Audience:** Architects, Engineering Leadership, Enterprise Customers, Auditors, Future Engineering Teams

> **Purpose.** This index lists every Architecture Decision Record. Each ADR is an independent document in this folder. ADRs capture **why** each major architectural decision was made. An Accepted ADR is immutable and is changed only by a new, superseding ADR.
>
> **Referencing convention.** Throughout the documentation, ADRs are referenced by **ADR ID** (`AD-001` … `AD-032`), never by filename. Each ID maps to exactly one file below.

---

## Index

| ADR ID | Title | File | Status | Date | Short Description | Related ADRs |
|---|---|---|---|---|---|---|
| AD-001 | AI Gateway as Enterprise Control Plane | `ADR-001-Control-Plane.md` | Accepted | 2026-07-20 | Build the product as an enforced control plane on the request path, not a library or observe-only proxy. | AD-017, AD-016, AD-018 |
| AD-002 | Hexagonal (Ports & Adapters) Architecture | `ADR-002-Hexagonal-Architecture.md` | Accepted | 2026-07-20 | Provider-agnostic core exposes ports; all externals are adapters. Basis of provider independence. | AD-007, AD-003, AD-010 |
| AD-003 | Domain-Driven Design & Bounded Contexts | `ADR-003-Domain-Driven-Design.md` | Accepted | 2026-07-20 | Decompose the platform into bounded contexts with explicit context mapping. | AD-002, AD-005 |
| AD-004 | Plugin Architecture (Sandboxed, Additive-Only) | `ADR-004-Plugin-Architecture.md` | Accepted | 2026-07-20 | Extensibility at defined, additive-only, sandboxed extension points that cannot weaken guarantees. | AD-018, AD-002 |
| AD-005 | Event-Driven Communication (Off Hot Path) | `ADR-005-Event-Driven-Architecture.md` | Accepted | 2026-07-20 | Telemetry/audit/accounting emitted asynchronously as events; no synchronous latency; zero-loss where it matters. | AD-009, AD-011, AD-018 |
| AD-006 | Stateless Data-Plane Services | `ADR-006-Stateless-Services.md` | Accepted | 2026-07-20 | Stateless hot path; ephemeral state in the in-memory tier; durable state in control-plane stores. | AD-017, AD-008, AD-020 |
| AD-007 | Multi-Provider Abstraction Layer | `ADR-007-Multi-Provider-Abstraction.md` | Accepted | 2026-07-20 | Adapters absorb provider surface *and semantics*, presenting a neutral, stable core model. | AD-002, AD-014, AD-018 |
| AD-008 | Distributed In-Memory Tier: Redis-class | `ADR-008-Redis-Caching.md` | Accepted | 2026-07-20 | Select a Redis-class in-memory store for counters, health, cache, short-lived caches, behind a port. | AD-006, AD-002, AD-021 |
| AD-009 | Event Streaming Backbone: Kafka-class | `ADR-009-Kafka-Event-Streaming.md` | Accepted | 2026-07-20 | Select a Kafka-class durable log backbone for events, enabling zero-loss audit/accounting, behind a port. | AD-005, AD-011, AD-010 |
| AD-010 | Polyglot Persistence: PostgreSQL + MongoDB (+ specialized) | `ADR-010-Polyglot-Persistence.md` | Accepted | 2026-07-20 | PostgreSQL (relational) + MongoDB (document) as primary DBs, complemented by specialized stores per data class. | AD-002, AD-005, AD-009, AD-008 |
| AD-011 | Observability Instrumentation: OpenTelemetry | `ADR-011-OpenTelemetry.md` | Accepted | 2026-07-20 | Adopt OpenTelemetry as the vendor-neutral instrumentation/export standard for metrics, traces, logs. | AD-005, AD-009 |
| AD-012 | Zero-Trust Security Model | `ADR-012-Zero-Trust.md` | Accepted | 2026-07-20 | Every request and inter-component call authenticated, authorized (deny-by-default), and encrypted. | AD-019, AD-021, AD-013 |
| AD-013 | Configuration Management Strategy | `ADR-013-Configuration-Management.md` | Accepted | 2026-07-20 | Declarative, validated, immutable, versioned configuration with secure defaults and reversible rollback. | AD-022, AD-015, AD-019 |
| AD-014 | Multi-Region Deployment Strategy | `ADR-014-Multi-Region.md` | Accepted | 2026-07-20 | Regionally-isolated operation with globally-consistent policy; residency confined including on failover. | AD-007, AD-017, AD-010 |
| AD-015 | Versioning & Backward Compatibility | `ADR-015-Versioning.md` | Accepted | 2026-07-20 | Explicit versioning, strict backward compatibility within a major version, deprecation windows. | AD-013, AD-007, AD-002 |
| AD-016 | Reliability-First Design Principle (meta-ADR) | `ADR-016-Reliability-First.md` | Accepted | 2026-07-20 | Reliability and correctness take precedence on the critical path; the tie-breaker governing all ADRs. | Governs all |
| AD-017 | Data Plane / Control Plane Separation | `ADR-017-Data-Control-Plane-Separation.md` | Accepted | 2026-07-20 | Separate a stateless Data Plane from a stateful Control Plane; control-plane outage does not stop the data plane. | AD-001, AD-006, AD-022, AD-014 |
| AD-018 | Non-Bypassable Correctness & Governance Pipeline | `ADR-018-Non-Bypassable-Pipeline.md` | Accepted | 2026-07-20 | Correctness and governance are mandatory, non-bypassable pipeline stages for every protected request. | AD-001, AD-016, AD-004, AD-019 |
| AD-019 | PEP/PDP Authorization Split with Cached Policy | `ADR-019-PEP-PDP-Authorization.md` | Accepted | 2026-07-20 | Local Policy Enforcement Point + centrally-distributed versioned policy snapshots; deny-by-default. | AD-012, AD-022, AD-018 |
| AD-020 | Co-located Request Pipeline vs Microservice Hot Path | `ADR-020-Colocated-Pipeline.md` | Accepted | 2026-07-20 | Hot-path stages co-located in one scalable unit to meet the latency budget; control plane decomposed. | AD-006, AD-002, AD-018 |
| AD-021 | Multi-Tenancy Isolation Model | `ADR-021-Multi-Tenancy-Isolation.md` | Accepted | 2026-07-20 | Logical multi-tenancy with end-to-end enforcement + detectors; optional physical isolation at top tier. | AD-012, AD-006 |
| AD-022 | Cached Versioned Configuration Snapshots | `ADR-022-Config-Snapshots.md` | Accepted | 2026-07-20 | Data plane pulls immutable versioned config snapshots (last-known-good); high-priority path for urgent changes. | AD-017, AD-006, AD-013, AD-019 |
| AD-023 | Runtime Strategy (Java 21 + Spring Boot) | `ADR-023-Runtime-Strategy.md` | Accepted | 2026-07-20 | Java 21 + Spring Boot primary runtime; Virtual Threads default concurrency; GraalVM Native Image optional; Rust profiling-gated hot-path only; Go not adopted. | AD-016, AD-006, AD-020, AD-002, AD-013 |
| AD-024 | Enterprise Tool Execution Architecture | `ADR-024-Enterprise-Tool-Execution-Architecture.md` | Proposed | 2026-07-31 | Tool execution belongs in a new Tool Session Orchestrator (C13) **above** the request pipeline, calling it once per model turn; every turn fully governed, every tool call individually authorized. Adds no pipeline extension point. | AD-004, AD-018, AD-019, AD-007, AD-020, AD-021 |
| AD-025 | Agent Runtime V1 Architecture | `ADR-025-Agent-Runtime-Architecture.md` | Proposed | 2026-07-31 | Agent Runtime (C14) sits **above** C13: a durable, plan-driven, supervised orchestrator split into a control-plane Run Store and stateless data-plane Step Executors. Plan is versioned data; one C13 session per step; deterministic replay for crash recovery without re-inference; OTP-style supervision with restart intensity; five mandatory bounds with a termination proof; run-level taint accumulation. Adds no pipeline or plugin extension point. | AD-024, AD-006, AD-017, AD-020, AD-021 |
| AD-026 | Memory Runtime | `ADR-026-Memory-Runtime.md` | Proposed | 2026-08-03 | Memory Runtime (**C17**) is a provider-neutral, policy-enforcing memory plane between the Plugin Runtime and the Agent Runtime. Seven memory types; write and read pipelines with governance, memory policy and audit; ten policies compiled once and swapped atomically; six retrieval modes and six ranking signals; four isolation levels enforced by narrow-before-query; delete proof. Owns no database, vector index, embedding model or cryptography — adapters only. **Also records that AD-024's C13 and AD-025's C14 collide with existing components; see §14.2.** | AD-004, AD-006, AD-012, AD-017, AD-021, AD-024, AD-025 |
| AD-027 | Durable Memory Store: Journal Adapter | `ADR-027-Memory-Store-Journal-Adapter.md` | Proposed | 2026-08-05 | One production `MemoryStorePort` adapter, closing **B22**: a segmented append-only journal per tenant with fsync-before-acknowledge durability, CRC framing, torn-write recovery, compaction, snapshots and optimistic concurrency. Memory Runtime unchanged; vendor-specific code confined to the adapter module. Conformance suite runs the same contract against both stores. | AD-026, AD-006, AD-021 |
| AD-028 | Memory Content Cryptography | `ADR-028-Memory-Cryptography.md` | Proposed | 2026-08-05 | Production `MemoryCryptoPort`, closing **B24**: AES-256-GCM envelope encryption with a per-record data key, versioned master keys, rotation and rewrap, and additional authenticated data binding every ciphertext to its `MemoryScope`. Memory Runtime unchanged. Records two structural gaps the port signature cannot close (**B35** metadata in the clear, **B36** no record-identity binding). | AD-026, AD-027, AD-021 |
| AD-029 | Memory PII Detection | `ADR-029-Memory-PII-Detection.md` | Proposed | 2026-08-06 | Production `PiiClassifierPort`, closing **B23**: a deterministic rule engine with checksum validators, context scoring, exemption rules, layered built-in/organization/tenant rule sets, and a step budget bounding tenant-supplied patterns. No model, no network. Memory Runtime unchanged. Records that the port signature carries no tenant, so tenant rules are unreachable through it (**B45**). | AD-026, AD-028, AD-021 |
| AD-030 | Production Embedding Provider | `ADR-030-Embedding-Provider.md` | Proposed | 2026-08-07 | Provider-neutral embedding **generation**, closing **B25**: `EmbeddingProviderPort` with a canonical embedding, a declared-capability registry, a cache→budget→split→retry→normalize pipeline, deterministic SHA-256 tenant-scoped caching with a negative cache, splitting plus concurrent coalescing, full-jitter retry on transient failures only, pre-call budget admission, observed health, and per-input partial results. One adapter (OpenAI), living in `dataplane/providers` so the module names no vendor at all. Memory Runtime unchanged. Builds **no** index, ANN, similarity or retrieval — those are the vector-adapter half of AD-026 §15.2 Phase 5. Records that nothing wires it at startup yet (**B62**), that cost is a four-chars-per-token estimate (**B58**), and that the adapter is unverified against the live API (**B57**). | AD-026, AD-025A, AD-007, AD-021 |
| AD-031 | Memory Runtime: Four Architectural Decisions | `ADR-031-Memory-Runtime-Architectural-Decisions.md` | Proposed | 2026-08-08 | **Decision document only — no implementation, and closes no gap.** Resolves the four questions blocking AD-026 §15.2 Phase 5. **(1)** PostgreSQL + pgvector as the first durable `VectorIndexPort`, Qdrant as the documented escape hatch; **(1b)** **B69** resolved by adapter over-fetch, `VectorIndexPort` unchanged; **(2)** **B65** resolved by widening `EmbeddingPort` to `embed(MemoryScope, String)` — **a frozen-contract amendment requiring Council ratification (CD-2), not made here**; **(3)** **B68** resolved as capability-matched default-deny — denial follows the index's protection, not the fact of encryption, with `SECRET` non-overridable; **(4)** **B67** owned by a Memory Runtime worker at **at-least-once**, becoming transactional between store and index only where one technology serves both ports. Records that Decisions 1 and 4 are coupled for exactly that reason. Creates **no** path to Phase 6/B27 and amends no frozen contract. Allocates **B70** (pgvector HNSW caps `vector` at 2,000 dimensions, so the shipped 3072-dim model needs lossy `halfvec`). | AD-026, AD-027, AD-028, AD-029, AD-030 |
| AD-032 | Vector Index Implementation Readiness | `ADR-032-Vector-Index-Implementation-Readiness.md` | Proposed | 2026-08-08 | **Readiness document — no implementation, no dependency, no schema created.** Extends AD-031, which chose pgvector without saying how a schema arrives. **Decides B71**: schema evolution is an **application-owned versioned bootstrap** under a Postgres advisory lock, following AD-027's existing `JournalCodec.FORMAT_VERSION` idiom rather than importing Flyway or Liquibase into a zero-framework codebase. Designs the pgvector schema strictly from what `VectorIndexPort` can legally supply — **no `memory_type`, `model_id` or `index_pending` column, because the port never provides them**. Translates `MemoryScope.visibleTo()` into six SQL predicates and gives an 11-row invariant/sabotage table, with *visibility-before-`LIMIT`* named as the invariant most likely to be broken by a plausible optimisation. **BLOCKED** on the Docker daemon (environment) and on Maven trust-store repair for the JDBC driver (**CD-6**). Proposes the Phase 5 recall gate including an anti-fraud control that fails closed against a semantically-null provider. Allocates **B72** (halfvec precision estimated, not measured) and **B73** (nullable scope columns cannot form a PostgreSQL primary key). Creates no path to B27. | AD-031, AD-026, AD-027, AD-030 |

---

## Grouping (by theme)

**Foundational / structural:** AD-001, AD-017, AD-006, AD-020, AD-018
**Design method / structure:** AD-002, AD-003, AD-007, AD-004, AD-005
**Security / governance:** AD-012, AD-019, AD-021, AD-013, AD-022
**Technology selections (implementations of `04` role-defined ports):** AD-008, AD-009, AD-010, AD-011
**Runtime:** AD-023 (Java 21 + Spring Boot)
**Operations / lifecycle:** AD-014, AD-015, AD-016 (meta)

---

## `04 §55` summary-label → canonical ADR mapping

The architecture summary in `04-System-Architecture.md §55` used short labels `AD-1 … AD-12`. This index is canonical (three-digit `AD-0NN` IDs). Mapping:

| `04 §55` label | Decision | Canonical ADR |
|---|---|---|
| AD-1 | Data-plane / control-plane split | AD-017 |
| AD-2 | Stateless hot path | AD-006 |
| AD-3 | Hexagonal ports/adapters | AD-002 |
| AD-4 | Co-located pipeline (not per-stage microservices) | AD-020 |
| AD-5 | Event-driven off-hot-path | AD-005 |
| AD-6 | Cached versioned config snapshots | AD-022 |
| AD-7 | PEP/PDP authorization split | AD-019 |
| AD-8 | Non-bypassable correctness/governance stages | AD-018 |
| AD-9 | Polyglot persistence | AD-010 |
| AD-10 | Sandboxed additive-only plugins | AD-004 |
| AD-11 | Logical multi-tenancy + optional physical isolation | AD-021 |
| AD-12 | Regionally-isolated, globally-consistent policy | AD-014 |

---

## Governance

- **Field template.** Each ADR records: ID · Title · Status · Date · Decision Makers · Context · Problem Statement · Decision · Decision Drivers · Alternatives Considered · Pros · Cons · Trade-offs · Consequences · Business/Engineering/Security/Performance/Operational Impact · Risks · Mitigations · Affected Components · Related BR/NFR/PRB · Related ADRs · Review Criteria · Future Revisions.
- **Decision Makers** throughout: *Architecture Council* — Chief Software Architect, Distinguished Engineer, Principal SRE, Security Lead, Compliance Lead.
- **Lifecycle.** Proposed → Accepted; an Accepted ADR is immutable and changed only by a superseding ADR (old status → Superseded, with a reference). Deprecated marks a decision no longer recommended but not yet replaced.
- **Technology-selection principle.** Technology ADRs (AD-008/009/010/011) select implementations for the role-defined ports established in `04` (hexagonal, AD-002); they name representative technology classes and remain swappable behind their ports without a new architectural ADR, provided the port contract and all invariants hold.
- **Invariant-bearing ADRs** (AD-012, AD-018, AD-021) and the meta-ADR (AD-016) may be strengthened but never weakened without revisiting the frozen foundational documents.
- **Gap numbering (`Bnn`).** `ADR-026 §14.1` is the origin of the register; every later document extends it. A `Bnn` denotes an **honest gap**, never a milestone or a plan. Each document that records gaps allocates the **next free contiguous block** and never reuses a number: 28A → B11–B14, AD-026 → B21–B27, AD-027 → B28–B34, AD-028 → B35–B44, AD-029 → B45–B54, AD-030 → B55–B63, `docs/39` → B64–B68, AD-031 → B70, AD-032 → B71–B73. **Next free: B74.** A document closing a gap says *"Closes Bnn"* in its header. Two consequences follow, and both were violated once before this rule was written down: **do not name a milestone after a `Bnn`** — the milestone is named by its ADR, and delivered work is not a gap; and **do not forward-reference unbuilt work by a `Bnn`** unless that number is already allocated to it — cite the phase or the ADR section instead. `docs/39 §0.1` records the incident.

---

*End of document — adr/000-ADR-Index.md*
