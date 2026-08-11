# 08 — Data Architecture

**Document:** Data Architecture Specification
**Project:** Reliability-First AI Gateway
**Status:** **Approved & Frozen (v1.2, 2026-07-20)** — **binding data-architecture standard**
**v1.1 changes:** applied the full Principal-Engineer hardening pass (6 Critical + 8 High + Mediums/Nice-to-haves). See **§24 Hardening Resolutions** for the per-finding record and change log. Inline corrections in §10, §14, §15, §16; new additive specs in §24. Triggered additive patch `06 → v1.3` (`RequestFinalized` terminal event, §23.11).
**v1.2 changes (editorial only, r1–r5 — no architectural/ownership/consistency/compliance change):** §10 manifest-absence timeout + new-topic mechanics note; §16 restore-window boundary; §24.5 node-local lease checks; §14 DEK-unwrap caching. Frozen after second review passed all six verification criteria.
**Audience:** Architecture, Platform & Data Teams, SRE, Security, Compliance, Auditors
**Classification:** Internal — Architecture Standard
**Builds on (approved, frozen):** `00`–`07` (esp. **`06 §24` Data Ownership Matrix**, **`06 §23` Event Ownership Matrix**, and **`07` Event Architecture** — outbox, WAL/EV-D3, compacted snapshots/EV-D1, retention, crypto-shredding), and the ADR register in `adr/` (referenced by ID; see `adr/000-ADR-Index.md`)

> **Authority & scope.** This document defines **how data is stored, owned, made consistent, secured, retained, replicated, and recovered** across the platform. **Store ownership** (which service owns which store) is **already frozen in `06 §24`** — this document does not re-own data; it defines the **design of each store class and each service's data**, the consistency model, residency, encryption/key-management, retention/erasure, backup/DR, migrations, and data observability. It stays at architecture level — no schemas, no DDL, no code; data is described in business/architecture terms. Where this document and `06 §24` differ on ownership, `06 §24` governs; this document governs data *design and mechanics*.

---

## Table of Contents
1. Executive Summary
2. Purpose & Scope
3. Constraints from the Frozen Documents
4. Data Ownership & Principles
5. Data Classification Model
6. Polyglot Store-Class Catalog (the seven classes)
7. Per-Service Data Design
8. Consistency & Transaction Model
9. Transactional Outbox & Idempotent Consumption
10. Audit Data Architecture (WORM, sealing, crypto-shred)
11. Metering & Cost Data Architecture
12. Snapshot, Cache & Ephemeral Data
13. Data Residency & Multi-Region
14. Encryption & Key Management
15. Data Retention, Lifecycle & Erasure
16. Backup & Disaster Recovery
17. Schema Evolution & Migrations
18. Data Observability & Quality
19. Data Security & Access Governance
20. Anti-Patterns
21. Risks & Open Questions
22. Traceability Matrix
23. Appendix

---

## 1. Executive Summary

The platform is **polyglot-persistent by data class** (AD-010): each kind of data is stored in the technology whose access pattern, consistency, and durability match it, and each dataset has exactly **one owning service** (store-per-service, `06 §24`). This document formalizes **seven store classes** — PostgreSQL (relational systems-of-record), MongoDB (flexible documents/records), Redis (ephemeral in-memory), the Kafka-class log (event backbone, designed in `07`), object storage (large objects + **WORM** for immutable audit), a **time-series store (TSD)** for metrics, and node-local **durable buffers** (the EV-D3 WAL and consumer dedup stores) — and specifies how each is designed, secured, and operated.

Three properties dominate the design, inherited from the frozen documents:

1. **Correctness and compliance outrank convenience.** Strong consistency is used where correctness demands it (config, tenancy, policy, accounting reconciliation, billing); eventual consistency is used only where it is safe, and always via events (`07`), never via shared databases. Audit is a **tamper-evident, WORM, zero-loss (RPO=0)** system of record.
2. **Data is governed at rest exactly as it is in motion.** The `05`/`03` invariants — tenant isolation (AD-021), residency confinement (AD-014), non-exposure of secrets, and classification-driven handling — are enforced at the storage layer with per-tenant encryption keys, residency-bound replication, least-privilege access, and complete access audit. **Right-to-erasure against immutable audit is solved by crypto-shredding** (per-tenant/subject keys), reconciling immutability with deletion obligations.
3. **No durable store sits synchronously on the hot path.** The Data Plane is stateless (AD-006); it owns no durable store, only ephemeral Redis namespaces, materialized snapshot caches (compacted topics, EV-D1), and the durable emit WAL (EV-D3). All durable writes are off the hot path, event-driven (AD-005), so persistence is never a request-path bottleneck (`NFR-DS-001`).

This document resolves the data-layer questions deferred from `07`: the **transactional-outbox relay mechanism** (change-data-capture or transactional polling publisher), **WORM/tiered retention** for audit, **dedup-store** design, **WAL** characteristics, **per-topic/partition and retention defaults** (as calibration targets), and **cross-region topology** per residency regime. It defines the consistency model, the encryption/key hierarchy, retention/erasure, and backup/DR to the tiered RPO/RTO of `03 §39`. It is the source of truth for every store the `09` technology-decisions and module documents will implement.

---

## 2. Purpose & Scope

**Purpose.** Give every team an authoritative answer to: *where does my data live, in which store class, with what consistency, encryption, residency, retention, backup, and access controls — and how do I evolve it safely?*

**In scope.** Store-class design; per-service data design; consistency/transaction model; outbox & idempotent consumption; audit/metering data; snapshot/cache/ephemeral data; residency & multi-region data topology; encryption & key management; retention/lifecycle/erasure; backup & DR; schema evolution/migration; data observability/quality; data security & access governance.

**Out of scope (deferred).** Concrete schemas/DDL and code (→ module docs); specific product/version selections and tuning (→ `09-Technology-Decisions.md`); infrastructure provisioning (→ `16-Deployment-Standards.md`); event mechanics (owned by `07`). Store *ownership* is fixed in `06 §24`.

---

## 3. Constraints from the Frozen Documents

- **AD-010** — polyglot persistence by data class; PostgreSQL + MongoDB primary DBs + specialized stores; reached via persistence ports (AD-002).
- **AD-006** — Data Plane is stateless; owns no durable store.
- **AD-005 / `07`** — durable writes are event-driven and off the hot path; audit/accounting zero-loss; outbox for stateful producers; WAL (EV-D3) for ZL hot-path emission; compacted snapshots (EV-D1).
- **AD-021 / `03` NFR-REL-002** — tenant isolation is absolute, enforced at the data layer.
- **AD-014 / `03` NFR-MR-001** — residency confinement, including replication and backup.
- **AD-012 / `03` NFR-ENC/SEC-SM/PRIV** — encryption at rest (AES-256), secret non-exposure, per-tenant keys, customer-managed keys, crypto-shredding.
- **`03` NFR-AUD-001** — audit RPO=0, tamper-evident, complete, retention-governed.
- **`03` NFR-COST-001** — authoritative, reconcilable accounting.
- **`03` NFR-DR-001 / NFR-BAK-001** — tiered RPO/RTO; encrypted, integrity-verified, quarterly-tested backups; audit RPO=0.
- **`03` NFR-DS-001** — persistence never a hot-path bottleneck.
- **`06 §24`** — the frozen Data Ownership Matrix (this document conforms).
- **AD-015 / `03` NFR-VER-001** — backward-compatible, zero-downtime schema/data evolution.

---

## 4. Data Ownership & Principles

Ownership is **frozen in `06 §24`**. Restated principles:

- **P-1 Store-per-service.** Each dataset has one owning service = its sole writer / system-of-record. No service reads or writes another's store directly; cross-service data flows via events / published language (`07`).
- **P-2 Right store per data class.** Technology is chosen per access pattern and durability need (Section 6), not one-size-fits-all.
- **P-3 No durable store on the hot path.** The Data Plane owns no durable store (AD-006); durable writes are event-driven and asynchronous.
- **P-4 Physical sharing ≠ ownership.** Clusters may be physically shared, but datasets are service-owned partitions; ownership, not co-location, is the boundary (no shared-database ownership).
- **P-5 Govern data at rest as in motion.** Classification, tenant isolation, residency, encryption, retention, and access audit apply to every store.
- **P-6 Consistency by need.** Strong consistency where correctness demands it; eventual (via events) where safe; never cross-service distributed transactions.
- **P-7 Evolve without breaking.** Backward-compatible, expand-then-contract migrations; zero-downtime (AD-015, `NFR-UPG-001`).

---

## 5. Data Classification Model

Classification drives encryption, residency, retention, access, and event-payload rules (`07 §17`). Every stored datum carries a class:

| Class | Examples | Encryption | Key scope | Residency | In events? |
|---|---|---|---|---|---|
| **Public** | published capability metadata, docs | at rest (baseline) | platform key | unconstrained | yes |
| **Internal** | config, provider profiles, SLIs, plugin registry | at rest | platform/service key | region-aware | yes (non-sensitive) |
| **Confidential** | tenant metadata, subscriptions, invoices, policy | at rest | **per-tenant key** | residency-bound | metadata only |
| **Regulated-PII** | personal data in requests/records/audit | at rest | **per-tenant / per-subject key** | **strictly residency-bound** | **audit-only, governed** (`07 §17`) |
| **Regulated-PHI** | protected health information | at rest | **per-tenant / per-subject key** | **strictly residency-bound** | **audit-only, governed** |
| **Secret** | provider credentials, keys | at rest (KMS/HSM) | **KMS / customer-managed key** | residency-bound | **never** (`07 §17`) |

**Rules:** classification is assigned at write; it determines the encryption key (Section 14), the store's residency handling (Section 13), the retention/erasure path (Section 15), and whether/where the datum may appear in events (`07 §17`). Regulated classes are **crypto-shred-eligible** (per-subject keys) for erasure (Section 15).

---

## 6. Polyglot Store-Class Catalog (the seven classes)

> Each store class is reached through a **persistence port** (AD-002), so the concrete product (`09`) is swappable. "Owner" is always a service (`06 §24`); the class is the technology role.

### 6.1 Relational — PostgreSQL-class (systems-of-record)
- **Role:** strongly-consistent, transactional systems-of-record with relational integrity and versioning.
- **Data:** config & snapshots metadata, feature flags (Configuration); policy & versions (Policy & Governance); principals/sessions/roles + org→tenant→workspace→project hierarchy (Identity & Tenancy); provider/capability/health (Provider Registry); usage ledger + reconciliation (Metering); subscriptions/invoices/entitlements/licenses (Billing); secret metadata (Secrets); registries (Extensibility); admin/support/success (Administration); prompt-asset metadata/versions/approvals (Prompt).
- **Consistency:** strong, ACID; the transactional home of the **outbox** (Section 9).
- **Access:** moderate volume, read-heavy for lookups; **not on the hot path** (Data Plane reads cached snapshots, not the DB).
- **Why:** relational integrity + transactions are required for config, financial, identity, and accounting correctness (AD-010).

### 6.2 Document — MongoDB-class (flexible records)
- **Role:** high-volume, flexible, semi-structured records where schema varies.
- **Data:** raw usage records (Metering); queryable audit records (Audit); prompt content documents (Prompt); plugin manifests (Extensibility); logs (Observability); support-case documents (Administration).
- **Consistency:** tunable; typically read-your-writes per document; eventual across.
- **Access:** high write throughput (usage/audit ingest), flexible query.
- **Why:** schema flexibility + write scale for records that don't fit relational rigidity (AD-010).

### 6.3 In-Memory — Redis-class (ephemeral substrate, AD-008)
- **Role:** ultra-low-latency ephemeral state; **not a system of record**.
- **Data (namespaced, tenant-scoped):** rate-limit/quota counters, circuit/health state, response cache, materialized config/secret snapshot caches, session/auth-key caches, webhook/notification delivery state, dedup caches.
- **Consistency:** best-effort; **fail-safe on loss** (rate limits conservative, cache miss → origin) — loss is tolerable by design (`07`, AD-008).
- **Access:** hot path (the only store class the Data Plane touches at request time — and only ephemerally).
- **Why:** sub-ms latency for the stateless hot path (AD-006/008); durability lives elsewhere.

### 6.4 Log — Kafka-class (event backbone)
- **Role:** the durable event log — designed in **`07`**; referenced here as a store class for completeness.
- **Data:** all domain events (`06 §23`); compacted snapshot topics (EV-D1); DLQ/retry topics.
- **Retention:** **operational/bounded** (replay/recovery) — **not** compliance retention (`07 §7`; compliance copy is the audit WORM store).
- **Why:** durable, ordered, replayable decoupling (AD-009).

### 6.5 Object Storage — incl. WORM (large objects & immutable audit)
- **Role:** large/immutable objects and long-term retention.
- **Data:** immutable **sealed audit records (WORM)** + long-term audit retention (Audit); encrypted secret backups/key material (Secrets, KMS-backed); large payload/exports (Metering, Billing, Prompt); trace/log archives (Observability); plugin packages (Extensibility); backups (all).
- **Consistency:** write-once (WORM) for audit; standard for others; durability is highest.
- **Why:** cheap, highly durable, WORM-capable — the compliance-grade home for immutable audit and backups (AD-010, `NFR-AUD/BAK`).

### 6.6 Time-Series — TSD (the seventh class, formalized)
- **Role:** high-ingest, high-cardinality, time-range metrics and SLIs. **`07`/`06 §24` flagged this as a class beyond the six columns; this document formalizes it.**
- **Data:** metrics and SLI series (Observability owner).
- **Consistency:** eventual; downsampled/rolled up over time.
- **Retention:** tiered (high-resolution short; rolled-up long); operational, not compliance.
- **Why:** relational/document stores are unfit for metric cardinality and time-range queries (AD-011 telemetry).

### 6.7 Node-local Durable Buffers (WAL & dedup — from `07`)
- **Role:** two node-local durable mechanisms defined by `07`.
  - **Emit WAL (EV-D3):** replicated node-local durable buffer for ZL hot-path events; fast local ack, async `acks=all` drain to the log; fail-safe on saturation. **Outbound-durability infra, not request-routing state** (AD-006 preserved).
  - **Consumer dedup store:** bounded processed-key store for idempotent consumption (`07 §13`), sized to the retry window; **not** relied on for replay correctness (replay uses natural/version-guarded idempotency).
- **Why:** reconcile zero-loss with a stateless, low-latency hot path (`07` EV-D3).

**Store-class summary:**

| Class | Systems-of-record? | Hot path? | Owner examples | Primary NFR |
|---|---|---|---|---|
| PostgreSQL | Yes | No | Config, Identity, Policy, Metering(ledger), Billing | NFR-DS/CFG/COST |
| MongoDB | Yes (records) | No | Metering(raw), Audit(query), Prompt | NFR-DS/AUD |
| Redis | No | Yes (ephemeral) | Data Plane, all (namespaced) | NFR-PERF/CACHE |
| Kafka log | Durable transport | Emit only | all (`07`) | NFR-Q/AUD |
| Object/WORM | Yes (immutable) | No | Audit(WORM), Secrets(backup), backups | NFR-AUD/BAK |
| TSD | Yes (metrics) | No | Observability | NFR-MET/OBS |
| WAL/dedup | Durable buffer | Emit/consume | Data Plane(WAL), consumers(dedup) | NFR-AUD/RTY |

---

## 7. Per-Service Data Design

Elaborating `06 §24` — each service's data by class and store, with consistency and retention posture:

| Service | Relational (SoR) | Document | Object/WORM | TSD | Consistency | Retention posture |
|---|---|---|---|---|---|---|
| **Gateway Data Plane** | — | — | — | — | stateless; ephemeral Redis + WAL only | none durable |
| **Provider Registry** | provider/capability/health | — | — | — | strong | current + change history |
| **Policy & Governance** | policy/versions/rules | — | — | — | strong, versioned | version history retained |
| **Configuration** | snapshot metadata/flags | — | large config artifacts | — | strong, versioned | version history (rollback source, EV-D1) |
| **Identity & Tenancy** | principals/sessions/roles/hierarchy | — | — | — | strong | lifecycle + audit-linked |
| **Secrets** | secret metadata/rotation | — | encrypted material/backups (KMS/WORM) | — | strong | rotation history; crypto-shred on revoke |
| **Metering & Cost** | usage ledger + reconciliation | raw usage records | usage exports | — | strong (ledger), eventual (raw) | ledger long; raw tiered |
| **Billing & Commerce** | subscriptions/invoices/entitlements | — | invoice documents | — | strong (financial) | financial/legal retention |
| **Audit** | audit index/report metadata | queryable audit records | **sealed WORM records + long-term** | — | strong index; immutable WORM | compliance retention + crypto-shred |
| **Observability** | dashboards/alert config | logs | trace/log archives | **metrics/SLIs** | eventual | operational (short-medium) |
| **Prompt Asset** | asset metadata/versions/approvals | prompt content docs | large artifacts | — | strong (approvals) | version history |
| **Extensibility** | plugin/SDK/webhook registry | plugin manifests | plugin packages | — | strong | version history |
| **Administration & Success** | admin/support/success | support docs | attachments | — | strong | operational + audit-linked |
| **Notification** | config/templates/channels | — | — | — | strong | operational |

**Rule:** a service may own data in **multiple** classes (e.g., Metering owns a relational ledger *and* document raw records), but each dataset has one owner and one authoritative store; cross-class within a service is coordinated internally (not via another service's store).

---

## 8. Consistency & Transaction Model

- **Within an aggregate: strong/ACID.** An aggregate (`05`) is the transactional consistency boundary; its invariants are enforced in a single store transaction in the owning service (small-aggregate rule).
- **Across aggregates/services: eventual, via events.** No cross-service distributed transactions and no two-phase commit. Cross-service consistency is achieved by **events + idempotent consumers** (`07`), i.e., **effectively-once processing**.
- **Process managers / sagas where a multi-step business process must converge.** Long-running cross-service flows (e.g., usage → rating → invoicing; subscription change → entitlement distribution) are modeled as **event-driven process managers** with **compensating actions** rather than distributed transactions. Each step is idempotent and independently recoverable.
- **Published language for cross-context truth.** Only defined published-language contracts cross contexts (e.g., **UsageFact** Metering→Billing, `06 §23`); consumers derive their own state — they never read the producer's store.
- **Read models / materialized views.** Services build **local read models** from consumed events for their own queries (CQRS-style where useful), keeping ownership clean and avoiding cross-store joins.
- **Strong-consistency islands:** config, tenancy, policy, identity, accounting-ledger, billing — these must be internally strongly consistent for correctness; their *propagation* to others is eventual (snapshots/events).

**Why not distributed transactions:** they couple availability, add latency, and are fragile at scale (AD-016 prefers proven, decoupled patterns). Eventual consistency + idempotency + sagas is the robust, scalable choice, and the invariants that must be atomic are always *within* a single aggregate/store.

---

## 9. Transactional Outbox & Idempotent Consumption — resolving EOQ-3

`07` mandated the outbox for stateful producers; this document fixes the **mechanism**:

**Decision DA-D1 — Outbox via transactional write + a relay publisher (CDC or transactional polling).**
- **Write side:** the state change and its outbound event are written in the **same store transaction** (an outbox record in the owning relational store, `06 §24`). This makes "state changed ⇔ event exists" atomic — eliminating dual-write inconsistency.
- **Relay side:** a **relay publisher** moves outbox records to the Kafka-class log, using either **change-data-capture (CDC)** on the outbox table or a **transactional polling publisher** — chosen per service in `09` based on the store and scale. The relay marks records published; it is **at-least-once** (a crash may re-publish) → consumers are idempotent.
- **Ordering:** outbox relay preserves per-aggregate order (publish in commit order per key), aligning with `07 §12` per-key ordering.
- **Observability:** **outbox relay lag** is a first-class metric (`07 §16`); a stalled relay delays but never loses events (outbox is durable).
- **Data Plane exception:** the stateless Data Plane has no store, so it uses the **emit WAL (EV-D3)** instead of an outbox for ZL events — same guarantee (durable capture before completion), different mechanism.

**Idempotent consumption:** every durable-topic consumer applies events idempotently keyed by the `06 §23` idempotency key; **replay-critical consumers use natural/version-guarded idempotency** (upsert/monotonic), not TTL'd dedup (`07 §13`). Dedup stores (Redis-class or embedded) are a bounded optimization sized to the retry window.

---

## 10. Audit Data Architecture (WORM, sealing, crypto-shred) — resolving EOQ-4

Audit is the compliance system of record — designed to `NFR-AUD-001` (RPO=0, complete, tamper-evident, retention-governed) and `07 §17`:

- **Capture:** the Audit Service consumes ZL audit-relevant events (`06 §23`) — durable, at-least-once, idempotent → **zero loss**.
- **Two-tier storage:**
  - **Query tier (MongoDB-class):** queryable audit records for investigation/reporting (indexed, access-governed).
  - **WORM tier (object storage):** **sealed, immutable, write-once** records for compliance — the tamper-evident source of truth. Sealing computes an **integrity proof** (hash chain / per-record digest) so tampering is detectable.
- **Tamper-evidence — per-record signature + periodic Merkle checkpoints (finding H7).** A **global hash chain** on a high-ingest, multi-topic, out-of-order audit stream would be a serialization bottleneck, so it is **not** used. Instead: each sealed record carries a **cryptographic signature/digest**, and the Audit Service periodically computes a **Merkle-tree checkpoint per partition (per tenant/time bucket)** over sealed records; checkpoints are anchored (WORM + an integrity log). Tampering with any record breaks its signature and the enclosing Merkle checkpoint. Tamper-evident **and** horizontally scalable (checkpoints parallelize per partition). Records are ordered by `correlationId` + Occurred-At, not by a global sequence.
- **Tiered retention:** hot (query tier, recent) → warm/cold (WORM, long-term) per regulatory requirement; **compliance retention lives here, not on Kafka** (`07 §7`).
- **Erasure via crypto-shredding (`07 §17`, Sections 14/15):** regulated payloads within audit are encrypted under **per-subject DEKs** (Section 14); erasure destroys the subject's DEK → data unrecoverable **without mutating the immutable record**. **Legal hold** overrides erasure (blocks DEK destruction); retention and hold are governed by Audit/Governance. This reconciles WORM immutability with right-to-erasure — the central audit compliance tension.
- **Completeness assurance — terminal manifest, not just a global loss detector (finding C1).** The prior design could detect *global* loss but not *per-record* completeness. The Data Plane now emits a terminal **`RequestFinalized`** event (`06 §23.11`, ZL) carrying the **manifest** of audit-relevant event types/counts expected for the `correlationId`. The Audit Service marks a record **complete only when every manifested event has been received**; a record still missing manifested events past a bounded assembly window raises a **completeness alarm** (Sev, zero error budget) — distinguishing "still assembling" from "permanently missing." The global loss detector (produced vs consumed vs sealed, `07 §16`) remains as a second, independent check. **No-manifest case (r1):** since a request could crash before emitting `RequestFinalized`, the Audit Service also **anchors on the first event seen for a `correlationId`** and applies an **assembling-record timeout** — a record that never receives its terminal manifest within the timeout raises the completeness alarm, so a *missing* manifest is itself detected, not silently ignored. **Topic mechanics (r5):** `rfaig.request.lifecycle.v1` is a new ZL topic governed by `07`'s general ZL mechanics; per `07 §5` ("the canonical catalog is `06 §23`; new topics are added there first"), no change to frozen `07` is required.
- **Access:** audit data (may contain regulated data) is itself access-governed and access-audited (`03` OBS-5).

---

## 11. Metering & Cost Data Architecture

Designed to `NFR-COST-001` (authoritative, reconcilable) and `07`:

- **Raw records (MongoDB-class):** one raw usage record per request (high-volume, flexible), captured from `UsageMeasured` (ZL). Tiered retention (recent hot, older rolled up/archived to object storage).
- **Authoritative ledger (PostgreSQL-class):** attributed, aggregated usage — the **system of record for accounting**, strongly consistent for reconciliation and chargeback/billing. Publishes **UsageFact** (`06 §23`) to Billing.
- **Reconciliation:** periodic reconciliation runs compare ledger vs provider ground truth within tolerance (`NFR-COST-001`); discrepancies raise events (`ReconciliationDiscrepancyDetected`).
- **Idempotency:** ledger updates are keyed by `requestId` (natural idempotency) so replay/duplicates never double-count — critical for financial correctness.
- **Attribution:** every record/ledger entry carries the tenant/workspace/project/principal attribution scope (`05` C5) — 100% attribution (`NFR-COST-001`).
- **Exports:** cost/usage exports to object storage for customer/finance consumption.

---

## 12. Snapshot, Cache & Ephemeral Data

- **Materialized snapshots (EV-D1):** the Data Plane materializes compacted snapshot topics (config, policy, provider, identity keys, secrets) into **local last-known-good caches** (in-memory + optional local durable copy for fast cold-start). Source of truth is the owning service's relational store; the compacted topic distributes latest; **rollback comes from the owning store** (EV-D1), never the compacted log.
- **Redis namespaces (AD-008):** rate-limit counters, circuit/health, response cache, session/key caches, dedup, delivery state — all **tenant-scoped, namespaced, ephemeral, fail-safe** (Section 6.3).
- **Response cache correctness:** caching honors `NFR-CACHE-001` — tenant/policy-scoped, sensitive-not-cached-by-default, freshness-enforced; a cache miss always falls through to origin.
- **No system-of-record in ephemeral stores:** loss of Redis/snapshot caches is recoverable (rematerialize/recompute); correctness never depends on them.
- **Dedup stores:** bounded, sized to retry window; not authoritative (Section 9).

---

## 13. Data Residency & Multi-Region — resolving EOQ-5

To `NFR-MR-001` / AD-014 (regionally-isolated, globally-consistent policy; residency confined including failover):

- **Residency is a data-placement invariant.** Regulated data (PII/PHI/Confidential/Secret) is stored, replicated, and backed up **only within permitted regions** — enforced at write, replication, and backup (Section 16). Classification (Section 5) drives placement.
- **Per-class regional topology:**
  - *Strongly-consistent SoR (config/tenancy/policy/ledger/billing):* regional primary with in-region replicas; **globally-consistent policy** is distributed via events/snapshots (`07`), not global shared state.
  - *Audit/accounting (RPO=0):* synchronously/quorum-durable **within permitted regions**; cross-region replication only to other permitted regions.
  - *Metrics/logs (TSD/object):* regional; aggregation respects residency.
  - *Event log:* regionally partitioned; residency-constrained events never cross to non-permitted regions (`07 §7`).
  - *Redis/ephemeral:* regional; never a residency concern (ephemeral, no SoR).
- **Cross-region movement is governed and classified.** Only non-residency-constrained data (public/internal) may move freely; regulated data crossing regions is prohibited unless the target region is permitted.
- **DR within residency (Section 16):** failover/restore targets are residency-valid — no DR event violates residency (AD-014 invariant).

**Decision DA-D2 — Residency topology is per-tenant/per-regime, driven by classification + tenant residency policy; the default is single-region-of-record with in-region redundancy, extended to multi-permitted-region for continuity where the tenant's regime allows.** Exact topologies per regulated regime are finalized with customers (ties to `04` AOQ-6).

---

## 14. Encryption & Key Management

To `NFR-ENC-001` / `NFR-SEC-SM-001` / AD-012:

- **Encryption at rest everywhere:** AES-256 (or equivalent) on all durable stores (relational, document, object/WORM, TSD, backups) and in transit (TLS 1.2+/1.3).
- **Encryption granularity — MANDATORY field/row/tenant-partition level for Confidential & Regulated (finding C3).** Per-tenant crypto-isolation is only real if the encryption is applied at **field / row / tenant-partition** granularity, **not** database- or cluster-level. Confidential and Regulated data are encrypted at this granularity so that a cross-tenant query returns **ciphertext the querying context cannot decrypt** (defense-in-depth for AD-021). Database-level single-key encryption is prohibited for these classes.
- **Key hierarchy (enables crypto-shred, customer control, and subject-level erasure):**
  - *Platform keys* — public/internal data.
  - *Per-tenant keys (KEK)* — Confidential/Regulated data; enable **tenant-level crypto-shred** and blast-radius isolation (a key compromise is tenant-scoped).
  - *Per-subject Data-Encryption-Keys (DEK), wrapped by the tenant KEK (finding C6)* — each data subject's Regulated-PII/PHI is encrypted under a **per-subject DEK**; the DEK is itself encrypted (wrapped) by the tenant KEK and stored in a compact **subject→wrapped-DEK map**. **Subject-level erasure = destroy that subject's DEK** (or its wrapped entry) → that subject's data is unrecoverable **without touching any other subject's data and without mutating immutable records**. This bounds key sprawl (one small wrapped-DEK entry per subject, not a full key in a KMS) while giving true subject-granular erasure — resolving the per-tenant-vs-per-subject tension (review DR-2).
  - *Regulated event payloads use the same per-subject DEKs (finding C2)* — any Regulated payload that appears in an (audit-only, governed) event on the log is encrypted under the subject's DEK, so **crypto-shredding erases it from the event log too** (you cannot delete from an append-only log; destroying the DEK is the mechanism). This makes event-log erasure actually achievable and consistent end-to-end.
  - *Customer-managed keys (CMK)* — offered at Enterprise/Regulated tiers (`03 §64`); the customer holds/rotates the key (as a top-level KEK). **CMK-revocation safeguard (finding M6):** because CMK revocation is a data-loss kill-switch, revocation requires a confirmed, time-delayed (grace-period) workflow with alerting and a break-glass hold, to prevent accidental irreversible loss.
  - *Secret material* — in KMS/HSM-backed storage; **never** in application stores or events (`07 §17`).
- **Key-version retention for backups (finding M4).** Because crypto-shred destroys keys but backups are encrypted under the key version at backup time, **key versions are retained for at least the backup-retention window** (and released together with the backups). Restoring an old backup uses its key version; a shredded subject remains shredded in restores (the DEK is gone), so erasure survives restore — an important property.
- **DEK-unwrap performance (r4).** Per-subject decrypt is two-step (unwrap the DEK with the tenant KEK, then decrypt the data). To avoid a KMS call per record, **unwrapped DEKs are cached in memory** (never persisted), scoped and TTL-bounded, so bulk decrypts (e.g., audit queries over many records) amortize the unwrap. This is a **performance optimization only** — it changes no isolation, erasure, or compliance guarantee.
- **Rotation & revocation:** keys rotate on policy; revocation is effective quickly (secrets ≤ 5 min, `06 §9.6`); rotation is non-disruptive (re-wrap, not re-encrypt-all where envelope encryption is used).
- **Envelope encryption:** data encrypted with data keys; data keys wrapped by tenant/CMK keys — so rotation/shred operates on wrapping keys efficiently.
- **Access to keys is least-privilege and audited** (AD-012); no service can decrypt another tenant's data (isolation invariant at the crypto layer — defense in depth for AD-021).

---

## 15. Data Retention, Lifecycle & Erasure

- **Retention is per-class and per-regime (Section 5), configurable per tenant/regulation:**
  - *Audit:* compliance-driven long retention (WORM), with legal-hold override.
  - *Accounting ledger:* financial/legal retention.
  - *Raw usage/metrics/logs:* operational tiered (hot→rolled-up/archived→expire).
  - *Event log:* bounded operational (replay window) — not compliance retention (`07 §7`).
  - *Ephemeral (Redis/cache):* TTL-based, no retention obligation.
- **Lifecycle automation:** tiering (hot→warm→cold→expire) is automated per policy; movement respects residency and classification.
- **Erasure (right-to-erasure / data-subject deletion) — via per-subject DEK crypto-shred (findings C2, C6):**
  - *Mechanism:* destroy the subject's **per-subject DEK** (Section 14). Because all of that subject's Regulated data — in relational, document, object/WORM stores **and** in regulated event-log payloads — is encrypted under that one DEK, a single DEK destruction renders **all** of it unrecoverable at once, **without mutating any immutable record and without deleting from the append-only log**. This is the concrete resolution to "erasure across immutable stores + the event log."
  - *Mutable stores:* additionally delete/anonymize plaintext-indexed fields where present (defense in depth).
  - *Tri-state interaction (finding M8):* **WORM-retention-lock** prevents deletion of the *object* but **does not** prevent DEK destruction (crypto-shred works under retention lock — that is its purpose). **Legal hold** takes precedence over erasure: while a hold is active on a subject, its DEK **must be retained** (destruction blocked) until the hold is released; then erasure proceeds. Retention-expiry, legal-hold, and erasure are evaluated as an explicit precedence: legal-hold > retention-lock > erasure-request.
  - *Cascade as a reliable saga (finding H1):* the erasure cascade is a **compensating, idempotent, verified process manager** (Section 24.4) — DEK destruction is the single atomic step for encrypted data; plaintext-index cleanup steps are retried-until-verified, and the erasure is not marked complete until **verification confirms** no recoverable copy remains in any owning store.
- **Verification:** erasure completeness is verifiable and audited (an erasure is itself a governed, recorded action, with a verification step, Section 24.4).

---

## 16. Backup & Disaster Recovery

To `NFR-DR-001` / `NFR-BAK-001` (tiered RPO/RTO; audit RPO=0; encrypted, integrity-verified, quarterly-tested backups):

- **RPO by class:** **audit & accounting = 0** (synchronous/quorum durability within permitted regions); config/tenancy/policy ≤ 1 min; raw/metrics/logs best-effort/tiered; ephemeral = not backed up (recomputable).
- **RTO by tier:** regional failover ≤ 5 min (Regulated) / ≤ 15 min (Enterprise) / ≤ 1 h (Business) / ≤ 4 h (Developer) — per `03 §64`.
- **RPO=0 is conditional under single-permitted-region residency (finding C5).** Audit/accounting RPO=0 is achieved by **synchronous multi-AZ quorum durability within the permitted region(s)** plus WORM. For a tenant whose regime permits **only one region**, residency (Section 13, AD-014) **forbids** the cross-region replica that would protect against a **full-region loss** — so a total-region disaster risks **RPO > 0** for that tenant. This is disclosed, not hidden: single-permitted-region tenants receive **RPO=0 against AZ/node/instance failure** (the overwhelmingly common case) but **reduced disaster resilience against whole-region loss** (finding M5). Mitigations: strong multi-AZ synchronous durability, WORM immutability, and — where the regime permits **any** second region — cross-permitted-region replication. The residency-vs-continuity trade-off is stated in the customer's service description.
- **Coordinated recovery point & restore order (finding H4).** Services are **not** restored independently to arbitrary points. Recovery uses a **coordinated recovery point**: restore each owning store to its backup, then **replay the durable event log forward to a common target** and let idempotent consumers/read-models reconcile to a consistent cross-service state (Sections 8–9). **Restore order** respects dependencies: **Identity & Tenancy → Configuration → Policy & Governance → Provider Registry → remaining services**, so scopes/keys/policy exist before dependents restore. Post-restore, **reconciliation checks** (audit completeness, accounting reconciliation, isolation detectors) must pass before traffic is admitted. **Restore-window boundary (r2):** forward-replay reconciliation is possible only within the **event-log operational retention window**; a backup older than that window cannot fully reconcile operational read-models by replay (the audit WORM compliance record survives independently regardless). Such deep restores rebuild read models from their own backups and accept a **documented reconciliation gap** for aged-out operational events — the compliance system of record is never affected.
- **Read-model / CQRS rebuild-on-restore (finding N5):** services holding event-derived read models rebuild them by **replaying the event log** from the coordinated recovery point (idempotent, `07`/Section 9) rather than restoring a possibly-stale derived copy — guaranteeing read models converge to the recovery point.
- **Backups:** encrypted (Section 14), integrity-verified, **immutable/WORM for audit**, residency-confined; **quarterly test-restores** with verified integrity (`NFR-BAK-001`).
- **DR mechanisms per class:** relational — regional replicas + PITR; document — replica sets + snapshots; object/WORM — cross-(permitted-)region durable replication; TSD — regional + rollups; event log — replicated (`07`); Redis/ephemeral — not restored (rematerialize/recompute).
- **Fail-safe DR:** in any disaster the system fails safe — it does not deliver incorrect results, bypass governance, or violate residency, even if it must reject requests (AD-016/EV-D3, `03 §36`).
- **Backbone/WAL in DR:** the emit WAL (EV-D3) and durable log guarantee no ZL loss across a regional failover within permitted regions.

---

## 17. Schema Evolution & Migrations

To AD-015 / `NFR-VER-001` (backward-compatible, zero-downtime):

- **Expand-then-contract (parallel-change) migrations:** add new fields/structures (expand) → dual-write/backfill → switch reads → remove old (contract) — never a breaking in-place change; zero-downtime.
- **Backward-compatible by default:** additive changes within a major; consumers tolerate unknown fields (aligns with `07 §9` event schema evolution).
- **Event schema and data schema evolve together** but independently versioned; the schema registry governs event contracts (`07 §9`), migration tooling governs store schemas.
- **Online migrations:** large backfills run online, throttled, tenant-aware, without hot-path impact (persistence is off the hot path, `NFR-DS-001`).
- **Rollback:** migrations are reversible (expand/contract stages are individually revertible); config/snapshot rollback via owning-store version history (EV-D1).
- **Data contracts:** cross-service data contracts (published language) are versioned and compatibility-checked (AD-015).

---

## 18. Data Observability & Quality

- **Freshness & lag:** outbox relay lag (DA-D1), consumer lag (`07`), snapshot propagation lag (EV-D1), WAL drain lag (EV-D3), replication lag (per store) — all monitored with SLOs (`NFR-MET/ALRT`).
- **Integrity & reconciliation:** audit loss detector (produced/consumed/sealed = 0); accounting reconciliation vs provider ground truth (`NFR-COST`); backup integrity verification; audit tamper-evidence checks.
- **Data quality:** attribution completeness (100%, Metering); classification coverage (100%); residency-conformance (100%); encryption coverage (100%) — each a monitored invariant.
- **Drift:** config/data drift detection (`NFR-CFG`); schema-registry compatibility telemetry (`07 §9`).
- **Capacity:** store growth, partition/shard balance, TSD cardinality, WORM/object growth — feeding `32-Capacity-Planning.md`.

---

## 19. Data Security & Access Governance

- **Least-privilege data access:** each service accesses only its own store (P-1); credentials are least-privilege, per-service, rotated (Secrets); no cross-service DB access.
- **Tenant isolation at the data layer (defense in depth for AD-021):** tenant scoping on every dataset + per-tenant encryption keys (Section 14) → even a query bug can't decrypt another tenant's data; isolation-violation detectors run at the data layer.
- **Access audit:** all access to Confidential/Regulated data (esp. audit data) is itself audited and governed (`03` OBS-5, `07 §17`).
- **Secret handling:** secrets in KMS/HSM only; never in application stores/logs/events; access attributed and audited (`06 §9.6`).
- **Residency & classification enforcement** at write/replicate/backup (Sections 5, 13, 16).
- **Encryption in transit** for all inter-store and replication traffic (Section 14).

---

## 20. Anti-Patterns (MUST NOT)

- **Shared database across services** — hidden coupling, blast-radius spread (P-1, P-4).
- **Distributed transactions / 2PC across services** — fragile, coupling; use events + sagas + idempotency (Section 8).
- **Durable store synchronously on the hot path** — violates AD-006/`NFR-DS-001`; hot path uses ephemeral Redis + WAL only.
- **One store forced to do everything** — use the right class per data (AD-010).
- **Regulated data with a single platform key** — no per-tenant/subject keys means no crypto-shred and a huge blast radius (Section 14).
- **Compliance retention on the event log** — Kafka retention is operational; compliance copy is audit WORM (`07 §7`, Section 10).
- **Mutating immutable audit for erasure** — use crypto-shredding (Section 15).
- **Reading another service's store** — integrate via events/published language (P-1).
- **Cross-region replication of residency-constrained data to non-permitted regions** — residency invariant (Section 13, AD-014).
- **Breaking in-place schema change** — use expand-then-contract (Section 17).

---

## 21. Risks & Open Questions

**Risks.**
- **DR-1 Polyglot operational burden** — many store classes to run/secure/back up. *Mitigation:* store-per-service ownership, per-class runbooks, platform-operated clusters (`06 §16`).
- **DR-2 Per-subject key sprawl** — subject-granular keys at scale are complex. *Mitigation:* per-tenant keys as the default; per-subject only where erasure granularity demands; envelope encryption to bound key count.
- **DR-3 Reconciliation gaps (accounting/audit)** — silent drift. *Mitigation:* loss detectors + reconciliation SLOs (Sections 10–11, 18).
- **DR-4 Migration risk at scale** — large backfills. *Mitigation:* expand-then-contract, online throttled, reversible (Section 17).
- **DR-5 Residency misplacement** — a regulated datum in a wrong region. *Mitigation:* classification-driven placement enforced at write/replicate/backup; conformance monitoring (Sections 13, 18).
- **DR-6 WORM + erasure tension** — *Mitigation:* crypto-shredding + legal hold (Sections 10, 15).

**Open questions (for `09`/`16`/`32`).**
- **DOQ-1** — Concrete product/version per store class and CDC-vs-polling per service (`09`).
- **DOQ-2** — Partition counts, shard keys, and retention **defaults** per topic/store (calibrated in `32`; `07` EOQ-1).
- **DOQ-3** — WAL technology, replication factor, drain-lag SLO, fail-safe-rate target (`07` EOQ-7; `09`/`32`).
- **DOQ-4** — Per-regime residency topologies and CMK requirements per regulated customer (`16`; `04` AOQ-6).
- **DOQ-5** — TSD product, cardinality budget, and rollup policy (`09`/`32`).
- **DOQ-6** — Dedup-store technology/sizing per consumer (`07` EOQ-6; `09`).
- **DOQ-7** — Backup tooling and cross-region durability specifics per store class (`16`).

---

## 22. Traceability Matrix

| Data-architecture concern | Section | BR | NFR | ADR | PRB |
|---|---|---|---|---|---|
| Polyglot persistence / store-per-service | 4, 6, 7 | BR-011/012/027 | NFR-DS-001 | AD-010/002 | PRB-011/012/030 |
| Classification-driven governance | 5, 14, 15 | BR-018/023 | NFR-DP/PRIV/MR/ENC | AD-012/021/014 | PRB-014/017 |
| Consistency & transactions | 8 | BR-004/012 | NFR-DS/COST | AD-005/016 | PRB-009/012 |
| Outbox / idempotency (DA-D1) | 9 | BR-011/012 | NFR-AUD/COST/RTY | AD-005 | PRB-011/012/009 |
| Audit WORM + crypto-shred | 10, 15 | BR-011/024/022 | NFR-AUD/COMP/PRIV | AD-009/010/012 | PRB-011/014 |
| Metering data | 11 | BR-012/013 | NFR-COST | AD-010/005 | PRB-012 |
| Snapshot/cache/ephemeral | 12 | BR-003/013 | NFR-PERF/CACHE/AV | AD-006/008/022 | PRB-010/030 |
| Residency & multi-region (DA-D2) | 13, 16 | BR-023/031/003 | NFR-MR/DR/BAK | AD-014 | PRB-017 |
| Encryption & key management | 14 | BR-018/020 | NFR-ENC/SEC-SM/PRIV | AD-012 | PRB-014/015 |
| Retention/erasure | 15 | BR-011/018/024 | NFR-AUD/PRIV | AD-010/012 | PRB-011/014 |
| Backup & DR | 16 | BR-003/011 | NFR-DR/BAK/AUD | AD-014/010 | PRB-011 |
| Schema evolution | 17 | BR-007/036 | NFR-VER/UPG/DS | AD-015 | PRB-021/022 |
| Data observability/quality | 18 | BR-010/012 | NFR-OBS/MET/COST | AD-011/005 | PRB-011/012 |
| Data security & access | 19 | BR-018/020/021 | NFR-SEC/AUTHZ/PRIV | AD-012/021 | PRB-013/014/015/029 |
| Audit completeness (C1, RequestFinalized) | 10, 24 | BR-011/024 | NFR-AUD-001 | AD-005/009 | PRB-011 |
| Subject-erasure key model (C2/C6) | 14, 15, 24 | BR-018/024 | NFR-PRIV/AUD | AD-012 | PRB-014 |
| Encryption granularity (C3) | 14, 24 | BR-018/021 | NFR-ENC/PRIV/REL-002 | AD-012/021 | PRB-014/029 |
| WAL hardening & emission (C4/H3) | 6.7, 24 | BR-011 | NFR-AUD/ENC/MR | AD-005/006 | PRB-011/020 |
| Residency-conditional RPO (C5) | 13, 16, 24 | BR-023/003 | NFR-DR/MR/AUD | AD-014 | PRB-017 |
| Saga reliability (H1) | 8, 15, 24 | BR-012/018 | NFR-COST/PRIV | AD-005 | PRB-012/014 |
| Global quota semantics (H2) | 12, 24 | BR-013/017 | NFR-SCALE-002/COST | AD-008 | PRB-018 |
| Coordinated restore (H4) | 16, 24 | BR-003/011 | NFR-DR/AUD | AD-014 | PRB-011 |
| Aggregate durability map (H5) | 24.3 | BR-011/012 | NFR-DS-001 | AD-010/006 | PRB-011/012 |
| Write amplification / raw-usage scale (H6) | 24.7 | BR-012 | NFR-COST/DS | AD-010 | PRB-012 |
| Audit tamper-evidence (H7) | 10 | BR-011 | NFR-AUD-001 | AD-009/010 | PRB-011 |
| Data lineage (H8) | 24.6 | BR-010/011/012 | NFR-OBS/AUD/COST | AD-011/005 | PRB-011/012 |

**Coverage note.** Every store-class and data concern traces to the frozen ownership (`06 §24`), the event mechanics (`07`), and BR/NFR/ADR/PRB. New datasets enter via `06 §24` (ownership) then here (design).

---

## 23. Appendix

**A. Decisions recorded here.** **DA-D1** (transactional outbox + CDC/polling relay — resolves EOQ-3); **DA-D2** (residency topology per classification + tenant regime — informs EOQ-5).

**B. Resolved `07` deferrals.** EOQ-3 outbox mechanism → DA-D1 (§9). EOQ-4 WORM/tiered retention → §10. EOQ-6 dedup design → §6.7/§9. EOQ-7 WAL as a store class → §6.7. EOQ-1 partition/retention defaults → deferred to `32` (calibration) as DOQ-2. EOQ-5 cross-region topology → DA-D2 (§13), finalized in `16`.

**C. Store-class quick reference.** Relational/PostgreSQL (SoR, strong) · Document/MongoDB (records, flexible) · In-memory/Redis (ephemeral, hot path) · Log/Kafka (events, `07`) · Object/WORM (immutable audit + backups) · Time-series/TSD (metrics) · Node-local durable buffers (WAL/dedup).

**D. Relationship to other documents.** Consumes `06 §24` (ownership) and `07` (events/outbox/WAL/WORM/crypto-shred) as frozen inputs; feeds `09-Technology-Decisions.md` (concrete products, CDC/polling, WAL/TSD/dedup tech), `13-Security-Standards.md` (encryption/key/erasure detail), `14-Observability-Standards.md` (data observability), `16-Deployment-Standards.md` (residency topology, backup/DR ops), `32-Capacity-Planning.md` (partition/retention/cardinality calibration), and module docs. Does not modify frozen documents.

**E. Maintenance.** Living document until frozen on review. New datasets: add to `06 §24` first (ownership), then design here. Store-per-service, no-sync-on-hot-path, classification-driven governance, audit RPO=0/WORM/crypto-shred, and residency confinement are stable invariants and may not be weakened without revisiting the frozen documents.

---

## 24. Hardening Resolutions (v1.0 → v1.1)

> This section records the resolution of every finding from the first Principal-Engineer review, and carries the additive specifications for findings whose home is here. All changes are **additive** — no architectural decision was removed; no store choice, ownership, or consistency boundary changed.

### 24.1 Resolution Ledger

| ID | Root Cause | Architectural Decision | Section(s) Modified | Rationale | Backward-Compat Impact | Traceability |
|---|---|---|---|---|---|---|
| **C1** | Audit assembled from N topics with no per-record completeness signal | Add terminal **`RequestFinalized`** manifest event; Audit marks complete only when all manifested events received | `06 §23.11` (new event), `08 §10` | Distinguishes "assembling" from "missing"; per-record completeness | Additive new ZL event; existing consumers unaffected | §22 (C1 row); NFR-AUD-001 |
| **C2** | Erasure of event-log regulated payloads impossible without per-subject encryption | Regulated event payloads encrypted under **per-subject DEKs**; crypto-shred erases them from the log | `08 §14, §15, §10` | Makes log erasure actually achievable, consistent with key model | Additive key-model clarification | §22 (C2 row); NFR-PRIV |
| **C3** | Per-tenant crypto-isolation undefined granularity | Mandate **field/row/tenant-partition-level** envelope encryption for Confidential/Regulated; DB-level prohibited | `08 §14, §19` | Makes the crypto-isolation guarantee real | Additive constraint; strengthens isolation | §22 (C3 row); NFR-ENC/REL-002 |
| **C4** | EV-D3 WAL holds regulated audit data on DP nodes with no stated controls/drain owner | WAL **encrypted (per-tenant), residency-confined, access-controlled, replicated with drain-on-death recovery**; default reconsidered (§24.8) | `08 §6.7, §24.8` | Closes an at-rest regulated-data surface introduced by the `07` fix | Refinement of EV-D3; WAL retained, now secured | §22 (C4 row); NFR-AUD/ENC/MR |
| **C5** | RPO=0 impossible for single-region-residency under full-region loss | Disclose conditional: RPO=0 vs AZ/node loss; reduced disaster resilience vs whole-region loss; multi-AZ+WORM mitigations | `08 §13, §16` | Honesty on a zero-error-budget invariant; residency-vs-continuity trade-off stated | Additive disclosure; no behavior change | §22 (C5 row); NFR-DR/MR |
| **C6** | Per-tenant keys can't erase one subject; per-subject keys sprawl | **Per-subject DEK wrapped by tenant KEK**; destroy DEK to shred one subject; bounded sprawl | `08 §14, §15` | Concrete subject-granular erasure at bounded key cost | Additive key-model specification | §22 (C2/C6 rows); NFR-PRIV |
| **H1** | Sagas named but no failure/compensation design | **Saga specifications** (§24.4): erasure-cascade & entitlement-distribution — idempotent, compensating, verified | `08 §8, §15, §24.4` | Makes cross-service processes recoverable and correct | Additive | §22 (H1 row); NFR-COST/PRIV |
| **H2** | No global (cross-region) quota enforcement | **Global-vs-regional quota semantics** (§24.5): regional enforcement + async global reconciliation; hard caps via bounded-staleness coordinator | `08 §12, §24.5` | Prevents cross-region quota evasion; states the guarantee | Additive | §22 (H2 row); NFR-SCALE-002 |
| **H3** | WAL may be over-engineering vs synchronous acks=all | **Explicit evaluation** (§24.8): WAL default on latency grounds; synchronous-`acks=all` allowed where measurement fits budget (calibration gate) | `08 §6.7, §24.8` | Justifies EV-D3 with a decision gate rather than assertion | Additive rationale; EV-D3 preserved | §22 (C4/H3 row) |
| **H4** | Independent restores → cross-service inconsistency | **Coordinated recovery point + restore order + post-restore reconciliation** | `08 §16` | Guarantees a consistent recovered state | Additive procedure | §22 (H4 row); NFR-DR |
| **H5** | No aggregate→store/durability classification | **Aggregate Durability Map** (§24.3) | `08 §24.3` | Confirms every aggregate has an owner and durability class | Additive table | §22 (H5 row); NFR-DS-001 |
| **H6** | Per-request write amplification & raw-usage scale unaddressed | **Amplification analysis + raw-usage sharding/tiering + columnar option** (§24.7) | `08 §11, §24.7` | Addresses growth/throughput at scale | Additive | §22 (H6 row); NFR-COST/DS |
| **H7** | Global hash chain would bottleneck audit | **Per-record signature + per-partition Merkle checkpoints** | `08 §10` | Tamper-evident and horizontally scalable | Additive mechanism choice | §22 (H7 row); NFR-AUD-001 |
| **H8** | Only correlation, no lineage | **Data Lineage Model** (§24.6): causation carried into derived records + lineage query | `08 §18, §24.6` | Compliance forensics & debugging across stores | Additive | §22 (H8 row); NFR-OBS/AUD |

**Medium/Nice-to-have:** M1 (§24.9 — Policy→Config/Billing→Config are event-based, not shared writes), M2 (§24.9 — document-store outbox variant), M3 (§24.7 — columnar raw-usage option), M4 (§14 — key-version retention for backups), M5 (§16 — single-region trade-off), M6 (§14 — CMK revocation safeguard), M7 (§24.9 — TSD cardinality bounding), M8 (§15 — WORM/erasure/legal-hold precedence). N1–N4 in §24.9; N5 in §16. All applied additively.

### 24.2 New Data-Architecture Decisions
- **DA-D3 (WAL hardening & emission default, §24.8)** — secure the EV-D3 WAL; default WAL on latency grounds with a synchronous-`acks=all` calibration gate.
- **DA-D4 (global quota, §24.5)** — regional enforcement + async global reconciliation; bounded-staleness coordinator for hard global caps.
- **DA-D5 (saga pattern, §24.4)** — all cross-service processes are idempotent, compensating, verified process managers.
- **DA-D6 (lineage, §24.6)** — causation-carried lineage across stores with a lineage query capability.
- **DA-D7 (per-subject DEK, §14)** — per-subject data keys wrapped by tenant KEK for subject-granular crypto-shred.

### 24.3 Aggregate Durability Map (H5)
Every `05` aggregate classified — **Durable-SoR** (relational/document/object), **Ephemeral** (Redis; rebuildable, fail-safe), or **Event-only** (transient per-request; persisted solely via emitted events into Audit):

| Aggregate (`05`) | Owner | Class | Store |
|---|---|---|---|
| Provider, Route | Provider Registry / DP | Durable-SoR (Provider); Event-only (Route) | PostgreSQL; (Route emitted) |
| ReliabilityExecution, CircuitBreaker, CacheEntry | Reliability (DP) | Ephemeral (rebuildable, fail-safe) | Redis |
| OutputValidation, StreamSession, ToolCallValidation | Correctness (DP) | **Event-only** (transient; outcome → Audit) | none durable; events |
| Policy, PolicyDecision, DataClassification | Policy & Governance / DP | Durable-SoR (Policy); Event-only (Decision) | PostgreSQL; (Decision emitted) |
| UsageRecord, ReconciliationRun | Metering | Durable-SoR | PostgreSQL (ledger) + MongoDB (raw) |
| Principal, Session, AccessGrant | Identity & Tenancy | Durable-SoR (Session cached in Redis) | PostgreSQL (+ Redis session cache) |
| Organization, Tenant | Identity & Tenancy | Durable-SoR | PostgreSQL |
| Subscription, Invoice, Entitlement | Billing | Durable-SoR | PostgreSQL |
| AuditRecord, ComplianceReport | Audit | Durable-SoR (immutable) | MongoDB (query) + Object/WORM |
| SLI, TelemetryStream | Observability | Durable-SoR (metrics) | TSD |
| PromptAsset, ConversationReference | Prompt | Durable-SoR | PostgreSQL + MongoDB |
| Plugin, WebhookSubscription, SDKVersion | Extensibility | Durable-SoR | PostgreSQL (+ Object packages) |
| Deployment, SupportCase, SuccessPlan | Administration | Durable-SoR | PostgreSQL |
| Secret | Secrets | Durable-SoR (KMS) | PostgreSQL (metadata) + KMS/Object |
| ConfigSnapshot | Configuration | Durable-SoR (versioned) | PostgreSQL |
| Notification | Notification | Durable-SoR | PostgreSQL |

**Verified:** every aggregate has exactly one owner and a defined durability class; no shared-write; Event-only/Ephemeral aggregates are correctness-safe (rebuildable or captured in audit).

### 24.4 Saga Specifications (H1) — idempotent, compensating, verified
- **Erasure-Cascade Saga (right-to-erasure).** Steps: (1) resolve subject → per-subject DEK; (2) **destroy DEK** (single atomic step erasing all encrypted copies across relational/document/object/WORM/event-log at once); (3) cleanup plaintext-indexed fields per owning store (idempotent, retried-until-verified); (4) **verify** no recoverable copy remains; (5) record erasure (governed, audited). **Failure handling:** any step fails → retried with backoff; the saga is **not marked complete** until verification passes; a stuck erasure raises a compliance-Sev alarm. **Legal hold** blocks step 2. No compensation needed for step 2 (destruction is terminal); steps 3–4 are idempotent.
- **Entitlement-Distribution Saga (plan/subscription change).** Steps: (1) Billing commits plan change (transactional outbox); (2) `EntitlementGranted/PlanChanged` published; (3) Configuration distributes entitlement limits as a snapshot; (4) Data Plane materializes. **Failure/compensation:** if distribution fails, the previous entitlement snapshot remains last-known-good (fail-safe: no over-grant); Billing re-drives distribution idempotently; a persistent mismatch (Billing vs distributed) raises an alarm and a **reconciliation** re-publishes. Enforcement stays correct because the Data Plane always enforces the last-known-good entitlement.
- **General rule:** no saga uses distributed transactions; each step is idempotent and independently recoverable; completion is verified, not assumed.

### 24.5 Global Quota Semantics (H2) — DA-D4
- **Regional enforcement by default.** Rate limits/quotas are enforced in-region via Redis (low latency); this is exact within a region.
- **Async global reconciliation.** Per-tenant global usage/quota is reconciled **asynchronously** across regions (aggregated via `metering.usage` / a global counter) with a **bounded tolerance** — a tenant cannot materially exceed a global quota, but brief cross-region overshoot within tolerance is accepted (the alternative, synchronous cross-region coordination, would break the latency budget).
- **Hard global caps** (where a regime requires a strict ceiling, e.g., a hard spend cap) use a **designated global coordinator with bounded staleness** — regions check out capacity leases; the cap is never exceeded beyond the lease granularity. This is opt-in per tenant/quota because it adds cross-region latency to lease refresh. **Lease-check locality (r3):** lease **checks are node-local** — regions/nodes **pre-fetch** capacity leases and evaluate them **locally on the hot path**; lease **refresh is asynchronous and off the hot path**. A per-request synchronous cross-region lease call is **prohibited** (it would breach the latency budget); the cap holds at lease granularity, not per-request precision.
- **Fairness** (`NFR-SCALE-002`) is enforced regionally; global fairness is reconciled. Trade-off stated: strict global = higher latency; default = regional-exact + globally-reconciled.

### 24.6 Data Lineage Model (H8) — DA-D6
- **Causation-carried lineage.** Derived records (invoices ⟵ usage facts ⟵ usage records ⟵ requests; audit records ⟵ request events) carry **causation identifiers** (from the event envelope, `07 §6`) into the derived store, so any derived datum can be traced to its sources across stores.
- **Lineage query capability.** A governed lineage query answers "what produced this?" and "what did this produce?" across services — for compliance forensics, dispute resolution (billing), and debugging.
- **Scope:** lineage is metadata (ids/causation), not data duplication; it respects tenant isolation and classification (lineage of regulated data is itself access-governed).
- Complements correlation-based tracing (`07 §16`): correlation = "same request"; lineage = "source→derived across time/stores."

### 24.7 Write Amplification & Raw-Usage Scale (H6, M3)
- **Per-request amplification acknowledged:** one request produces ~1 raw-usage document + audit record(s) + ~6–8 events + 1 WAL entry. This is inherent to reliability-first (everything recorded) and is planned-for, not accidental.
- **Raw-usage store (MongoDB-class) sharding & tiering:** sharded by **tenant+time**; **hot** window queryable, **older data tiered to object storage** (columnar/compressed) on a retention schedule; the authoritative **aggregated ledger** (PostgreSQL) — not the raw store — is the accounting SoR, bounded by tenant×time buckets.
- **Columnar/OLAP option (M3):** for large-scale usage analytics/reconciliation, a **columnar/OLAP store** (e.g., ClickHouse-class) is an accepted alternative/complement to the document raw store — decided in `09` per scale; the port abstraction (AD-002) makes this swappable.
- **Capacity:** amplification, raw-usage growth, audit-WORM growth, and TSD cardinality feed `32-Capacity-Planning.md` (partition/retention/cardinality calibration — DOQ-2/5).

### 24.8 WAL Hardening & Synchronous-Emission Evaluation (C4, H3) — DA-D3
- **H3 evaluation.** Two options for zero-loss hot-path emission: (a) **synchronous `acks=all` produce** to co-located brokers — truly stateless, no node-local data, but adds a remote round-trip (~2–10 ms) that may exceed the tight emission budget (`06 §11`, ≤5 ms P99) under load/tail; (b) **EV-D3 WAL** — fast local durable write (sub-ms) + async `acks=all` drain — meets the budget but adds a node-local durable surface.
- **Decision:** **WAL remains the default** because it is latency-superior and the emission budget is tight; **synchronous `acks=all` is permitted where a deployment's measured emission latency fits budget** (a calibration gate, `32`) — in which case the WAL may be omitted, restoring pure statelessness. This preserves EV-D3, answers H3 with a decision rather than an assertion, and gives an escape hatch.
- **C4 — WAL hardening (mandatory wherever the WAL is used):**
  - **Encrypted at rest** under the **per-tenant key** (WAL entries carry classified/regulated audit payloads); never plaintext on disk.
  - **Residency-confined** — WAL lives on nodes within the tenant's permitted region(s); regulated events for a tenant are never buffered on out-of-region nodes.
  - **Access-controlled** — the WAL is service-internal, least-privilege, not readable by other tenants/processes; its content is subject to the same classification/isolation as any store.
  - **Replicated with drain-on-death ownership** — WAL entries are replicated (node + peer / small quorum) so a node loss does not lose captured ZL events; a **drain supervisor** re-drives a dead node's replicated WAL to the backbone. This is **outbound-durability infra, not request-routing state** — nodes remain disposable for routing (AD-006 preserved); only the outbound buffer is recovered.
  - **Observability:** WAL depth, drain lag, replication health, and fail-safe-rejection rate are monitored (`07 §16`).

### 24.9 Medium & Nice-to-have Resolutions
- **M1 — Handoffs are event-based, not shared writes.** Policy & Governance → Configuration and Billing → Configuration handoffs occur via **published events/contracts**; neither writes into Configuration's store. Store-per-service (P-1) holds; no shared-write ownership. *(Clarifies §7/§12.)*
- **M2 — Document/object-store outbox variant.** Services whose SoR is document/object (Audit, Prompt) implement the outbox via **document-store multi-document transactions** or **CDC on the store**, not a relational outbox — same at-least-once + idempotent guarantee (DA-D1 generalized). *(Extends §9.)*
- **M7 — TSD cardinality bounding.** Metric cardinality is bounded by a **label allow-list + per-tenant cardinality caps** with alerting on cardinality growth; high-cardinality dimensions are aggregated/dropped per policy. *(Extends §6.6/§18.)*
- **N1 — Index strategy** (audit query tier, raw-usage) is deferred to module/`09` design but flagged as a known scale concern (tenant+time composite indexes; avoid unbounded scans).
- **N2 — Read-model staleness** may be user-visible (e.g., a policy/config change lags to dashboards within the propagation window); acceptable and disclosed; hot-path enforcement is never stale beyond the snapshot-propagation SLO (`07`/AD-022).
- **N3 — Storage-growth projection** for audit WORM (multi-year retention) and object archives feeds `32` cost/capacity modeling.
- **N4 — Circuit-state loss on Redis flush** resets circuits to closed (fail-safe): brief re-exposure to an unhealthy provider until re-detection — acceptable, fail-safe, and rebuildable from health signals.

### 24.10 Change Log (v1.0 → v1.1)
| Change | Type | Sections |
|---|---|---|
| Added `RequestFinalized` terminal event for audit completeness | Additive (cross-doc: `06 §23.11` v1.3) | `06 §23.11`, `08 §10` |
| Mandated field/row/tenant-level encryption; per-subject DEK model; event-payload keys; key-version retention; CMK safeguard | Additive spec | `08 §14` |
| Per-record signature + Merkle-checkpoint tamper-evidence; terminal-manifest completeness | Additive spec | `08 §10` |
| Subject-erasure via DEK crypto-shred; WORM/erasure/legal-hold precedence; erasure saga | Additive spec | `08 §15, §24.4` |
| Residency-conditional RPO disclosure; coordinated restore + order; read-model rebuild | Additive spec | `08 §13, §16` |
| Saga specifications; global quota semantics; lineage model; amplification/scale; WAL hardening | Additive spec | `08 §24.3–24.9` |
| Aggregate durability map | Additive table | `08 §24.3` |
| Editorial refinements r1–r5 (v1.2): manifest-absence timeout, restore-window boundary, node-local lease checks, DEK-unwrap caching, new-topic mechanics note | Additive editorial | `08 §10, §14, §16, §24.5` |
| Traceability rows for all findings | Additive | `08 §22` |

**Net effect:** all 6 Critical and 8 High findings resolved additively; no decision, store choice, ownership, or consistency boundary removed or redesigned.

---

*End of document — 08-Data-Architecture.md (v1.2, frozen)*
