# 09 — Technology Decisions

**Document:** Technology Decisions & Selection Rationale
**Project:** Reliability-First AI Gateway
**Status:** **Approved & Frozen (v1.0, 2026-07-20)** — **binding technology-selection standard**
**Review changes applied:** TD-009 (Transactional Outbox + polling publisher default; Debezium optional for high-throughput); TD-011 (objective ClickHouse adoption criteria); TD-013 (Java 21 + Spring Boot primary; Rust profiling-gated future hot-path option; no Go, no dual-language). Frozen after internal consistency check.
**Editorial (v1.0.1):** TD-013 promoted to **AD-023 (Runtime Strategy)**; cross-references updated. No decision changed.
**Audience:** Architecture, Platform & Engineering Teams, SRE, Security, Procurement, Auditors
**Classification:** Internal — Architecture Standard
**Builds on (approved, frozen):** `00`–`08` and the ADR register in `adr/` (referenced by ID; see `adr/000-ADR-Index.md`)

> **Authority & scope.** The ADRs (AD-008/009/010/011 etc.) fixed technology **classes** behind ports (AD-002 hexagonal); this document selects the **concrete technologies** that implement those classes, justified from first principles. Because every selection sits behind a port, each is **swappable without redesign** — "future replacement strategy" is uniform: replace the adapter, not the core. This document does not generate code, schemas, or infrastructure definitions (those are `10`–`16` and module docs). Where a selection was not previously fixed by an ADR (notably runtime/language, TD-013), it has been **decided from first principles (Java 21 + Spring Boot primary)** and is to be promoted to an ADR.

---

## Table of Contents
1. Executive Summary
2. Purpose & Scope
3. Constraints from the Frozen Documents
4. Technology Selection Principles
5. Selection Summary
6. Technology Decisions (TD-001 … TD-013)
7. Cross-Cutting Stances (managed-vs-self-hosted, multi-cloud, licensing posture)
8. Risks & Open Questions
9. Traceability Matrix
10. Appendix

---

## 1. Executive Summary

The architecture already committed to technology *classes* (relational, document, in-memory, event log, object/WORM, time-series, instrumentation) behind ports. This document selects the concrete technologies and justifies each from first principles — the problem it solves, the requirements it must meet, the alternatives, why it wins, why the others lose, and its operational, scalability, failure, security, licensing, cost, migration, and replacement characteristics.

The selections are deliberately **conservative and proven** (AD-016: reliability-first prefers boring, dependable technology on the critical path), **open and licensing-safe** wherever practical, and **portable** (every choice behind a port). Headline decisions:

- **PostgreSQL** for relational systems-of-record; **MongoDB** for flexible document records (with an explicit SSPL-licensing caveat and mitigations); **Valkey** (BSD-licensed, Redis-compatible) for the in-memory tier — chosen over Redis specifically to avoid the SSPL/RSALv2 licensing risk while preserving compatibility; **Apache Kafka** (Apache-2.0) for the event backbone; **S3-compatible object storage with Object-Lock/WORM** for immutable audit and backups; a **Prometheus-ecosystem TSD with long-term storage** for metrics; **OpenTelemetry** for instrumentation.
- Supporting choices surfaced by `07`/`08`: a **schema registry** for event contracts, **Debezium-class CDC** for the transactional-outbox relay (DA-D1), **cloud-KMS/HSM + Vault-class secret management** for the key hierarchy and secrets, and an optional **ClickHouse-class columnar store** for large-scale usage analytics.
- **Kubernetes** for cloud-native orchestration (AD deployment/immutable-infra).
- **Runtime/language:** **Java 21 + Spring Boot** as the single primary runtime — chosen for enterprise maturity, development velocity across 14 services, Virtual Threads (massive, simple concurrency for streaming), sub-millisecond Generational-ZGC pauses (the latency budget is **provider-dominated, not GC-bound**), best-in-class observability/security ecosystems, Kubernetes fit, and the largest 10-year hiring pool. **Rust** is retained only as a **profiling-gated future optimization for an isolated hot-path component** if Java is ever proven unable to meet a specific latency target — **no Go, no dual-language at launch**.

Every choice is justified against the frozen NFRs (latency budget, RPO=0 audit, tenant isolation, residency, scalability) and remains replaceable behind its port, so no selection is a lock-in.

---

## 2. Purpose & Scope

**Purpose.** Give teams the authoritative "what technology, and why" — with enough first-principles justification that the choice can be defended to engineering, security, procurement, and auditors, and revisited rationally.

**In scope.** Concrete technology selections implementing the ADR-fixed classes and the supporting technologies from `07`/`08`; runtime/language recommendation; managed-vs-self-hosted and multi-cloud stance; licensing posture.

**Out of scope (deferred).** Repository/build layout (`10`); coding, API, security, observability, testing, deployment standards (`11`–`16`); capacity calibration numbers (`32`); code, schemas, IaC. Technology *classes* and ports are fixed by the ADRs and are not re-decided here.

---

## 3. Constraints from the Frozen Documents

- **AD-002** — everything external is behind a port; selections are adapters → swappable.
- **AD-008/009/010/011** — in-memory (Redis-class), event log (Kafka-class), polyglot persistence (PostgreSQL + MongoDB + specialized), instrumentation (OpenTelemetry) classes are fixed; this document picks products.
- **AD-016** — reliability-first: prefer proven, boring, dependable technology on the critical path.
- **AD-006/020/`03` NFR-LAT/PERF** — the Data-Plane hot path has a strict latency/tail budget → runtime and in-memory choices must respect it.
- **`03` NFR-AUD/COST/MR/ENC/DR** — RPO=0 audit, accounting accuracy, residency, encryption, DR → drive Kafka durability config, WORM object storage, KMS, and replication choices.
- **`08` DA-D1** — transactional-outbox relay → CDC/polling tooling.
- **`00` BRULE-4 / neutrality** — no provider lock-in; extends to a preference for open, portable infrastructure and multi-cloud capability.

---

## 4. Technology Selection Principles

1. **Proven over novel on the critical path** (AD-016). Prefer mature, widely-operated technology for anything the request path or compliance depends on.
2. **Open and licensing-safe** where practical — avoid licenses that constrain how we operate or offer the product (SSPL/BSL scrutiny); prefer permissive (Apache-2.0/BSD/MIT/PostgreSQL).
3. **Portable — behind a port.** Every selection is swappable; no core coupling (AD-002). Multi-cloud capable; avoid single-vendor lock-in (neutrality, `00`).
4. **Right tool per class** (AD-010) — no forcing one technology to do everything.
5. **Operable & observable** — strong operational tooling, OpenTelemetry-compatible, mature failure semantics.
6. **Security-first** — encryption, access control, and auditability are selection criteria, not afterthoughts.
7. **Managed where it reduces risk without lock-in** — prefer managed services for undifferentiated heavy lifting where a portable/open equivalent exists as an exit.
8. **Total-cost-aware** — licensing + operational + scaling cost considered, not just sticker price.

---

## 5. Selection Summary

| TD | Class (ADR) | **Selected** | Key alternatives | License | Managed option |
|---|---|---|---|---|---|
| TD-001 | Relational (AD-010) | **PostgreSQL** | MySQL, CockroachDB, cloud SQL | PostgreSQL License (permissive) | Yes (portable) |
| TD-002 | Document (AD-010) | **MongoDB** *(SSPL caveat)* | DocumentDB, Couchbase, FerretDB | SSPL ⚠ | Yes |
| TD-003 | In-memory (AD-008) | **Valkey** (Redis-compatible) | Redis, Memcached, Dragonfly | BSD-3 ✅ | Yes |
| TD-004 | Event log (AD-009) | **Apache Kafka** | Redpanda, Pulsar, cloud pub/sub | Apache-2.0 ✅ | Yes |
| TD-005 | Object/WORM (AD-010) | **S3-compatible + Object-Lock** | MinIO, cloud object stores | Apache-2.0 / cloud | Yes |
| TD-006 | Time-series (TSD) | **Prometheus + long-term (Mimir/Thanos/VictoriaMetrics)** | InfluxDB, TimescaleDB | Apache-2.0 ✅ | Yes |
| TD-007 | Instrumentation (AD-011) | **OpenTelemetry** | vendor agents | Apache-2.0 ✅ | N/A (standard) |
| TD-008 | Schema Registry | **Registry (Apicurio / Confluent-compatible)** | protobuf-only, custom | Apache-2.0 (Apicurio) ✅ | Yes |
| TD-009 | Outbox relay (DA-D1) | **Transactional Outbox + polling publisher (default)** · Debezium (optional, high-throughput) | cloud CDC | Apache-2.0 ✅ | Via connectors |
| TD-010 | Key mgmt / secrets (`08 §14`) | **Cloud KMS/HSM + Vault-class** | cloud-only, HSM-only | varies / MPL-BSL | Yes |
| TD-011 | Columnar/OLAP (optional, H6) | **ClickHouse** | Druid, Pinot | Apache-2.0 ✅ | Yes |
| TD-012 | Orchestration (cloud-native) | **Kubernetes** | Nomad, cloud container svc | Apache-2.0 ✅ | Yes |
| TD-013 | Runtime/language | **Java 21 + Spring Boot (primary)** · Rust (profiling-gated future hot-path opt) | Go, Rust-only, dual-language | OpenJDK GPL+CE / Spring Apache-2.0 | N/A |

Legend: ✅ licensing-safe · ⚠ requires caveat/mitigation.

---

## 6. Technology Decisions

> Each decision uses the 13-field template: Problem · Requirements · Alternatives · Why selected · Why alternatives rejected · Operational · Scalability · Failure modes · Security · Licensing · Cost · Migration · Future replacement. Fields are kept concise. **Future replacement** is uniform (behind the port, AD-002) and stated once per entry.

### TD-001 — PostgreSQL (Relational systems-of-record)
- **Problem:** strongly-consistent, transactional, relational systems-of-record (config, tenancy, policy, identity, accounting ledger, billing, registries) and the transactional-outbox home (`08 §6.1/§9`).
- **Requirements:** ACID, relational integrity, versioning, moderate throughput off the hot path (`NFR-DS/CFG/COST`, DA-D1).
- **Alternatives:** MySQL/MariaDB; CockroachDB/Yugabyte (distributed SQL); cloud-native SQL (Aurora/Cloud SQL).
- **Why selected:** the most proven, feature-rich open relational engine; excellent transactional integrity, rich types/JSONB, mature replication/PITR, ubiquitous operational knowledge and tooling; permissive license; runs self-managed or managed on every cloud (portability).
- **Why alternatives rejected:** MySQL — weaker feature set (types, extensibility) for our needs; distributed SQL (Cockroach/Yugabyte) — added complexity/latency not justified when each store is service-scoped and horizontally partitioned by ownership (we don't need global distributed SQL); cloud-native SQL — acceptable as a *managed deployment of Postgres-compatible*, but selecting a proprietary engine directly would risk lock-in (neutrality).
- **Operational:** mature; well-understood backup/PITR/replication; strong ecosystem.
- **Scalability:** vertical + read replicas; partition/shard by tenant/time where needed; each service's Postgres is independently scaled (store-per-service).
- **Failure modes:** primary failure → replica promotion; handled by HA + PITR; not on hot path (Data Plane uses snapshots).
- **Security:** at-rest encryption, TLS, row/field-level encryption for regulated data (`08 §14` C3), least-privilege roles, access audit.
- **Licensing:** PostgreSQL License (permissive, BSD-like) — **no constraints**.
- **Cost:** open-source; cost is operations/managed-service; efficient at our per-service scale.
- **Migration:** greenfield; expand-then-contract schema evolution (`08 §17`).
- **Future replacement:** swap the relational port adapter (Postgres-compatible engines ease this).

### TD-002 — MongoDB (Document store) — with SSPL caveat
- **Problem:** high-volume, flexible, semi-structured records (raw usage, queryable audit, prompt content, plugin manifests, logs) (`08 §6.2`).
- **Requirements:** flexible schema, high write throughput, flexible query (`NFR-DS/AUD`).
- **Alternatives:** AWS DocumentDB / cloud document DBs; Couchbase; **FerretDB** (Mongo-compatible on Postgres); PostgreSQL JSONB.
- **Why selected:** best-in-class document model, mature, high write throughput, rich query, wide operational familiarity; fixed as the document class by AD-010.
- **Why alternatives rejected:** Postgres JSONB — viable but weaker at document-scale write/query ergonomics; Couchbase — smaller ecosystem for our needs; DocumentDB — proprietary/cloud-locked.
- **Operational:** mature; replica sets, sharding, backup/snapshot.
- **Scalability:** horizontal sharding (by tenant+time for raw usage, `08 §24.7`); replica sets for HA.
- **Failure modes:** replica-set failover; shard rebalancing; not on hot path.
- **Security:** at-rest encryption, field-level encryption for regulated data (`08 §14`), TLS, RBAC, access audit.
- **Licensing:** ⚠ **SSPL** — acceptable for **internal self-managed/managed use** (we use it as a component, not offering the database itself as a service), but a real consideration. **Mitigations:** (a) use a **managed Mongo-compatible service**; (b) keep the document port abstraction so **FerretDB (Apache-2.0, Mongo-compatible on Postgres)** or another engine can replace it if licensing posture demands; (c) legal review of SSPL vs our deployment model. This caveat is recorded, not ignored.
- **Cost:** open-core/managed; sharding cost at raw-usage scale is a driver (mitigated by tiering to object storage, `08 §24.7`).
- **Migration:** greenfield.
- **Future replacement:** document port → FerretDB/managed-compatible; the SSPL caveat makes this exit path deliberately preserved.

### TD-003 — Valkey (In-memory tier, Redis-compatible) — licensing-driven choice
- **Problem:** ultra-low-latency ephemeral state on the hot path — rate-limit/quota counters, circuit/health, response cache, snapshot/secret caches, dedup, delivery state (`08 §6.3`, AD-008).
- **Requirements:** sub-ms latency, rich data structures (atomic counters, TTL), clustering, redundancy, fail-safe (`NFR-PERF/CACHE`).
- **Alternatives:** **Redis** (RSALv2/SSPL since 2024); Memcached; DragonflyDB; cloud-managed caches.
- **Why selected:** **Valkey** is the **BSD-3-licensed, Redis-compatible fork** (Linux Foundation) — it delivers Redis's data structures/performance/ecosystem **without the RSALv2/SSPL licensing risk** Redis introduced. AD-008 explicitly anticipated "Redis or a compatible/derivative such as Valkey"; we select Valkey to keep licensing clean while remaining drop-in Redis-compatible.
- **Why alternatives rejected:** **Redis** — the 2024 license change (RSALv2/SSPL) creates licensing risk for infrastructure software; avoided on principle (P-2). Memcached — lacks the data structures needed for rate-limiting/atomic ops (rejected in AD-008). DragonflyDB — promising but less proven and its own licensing (BSL) concerns. Cloud-managed caches — acceptable as managed deployments behind the same port.
- **Operational:** Redis-compatible tooling/knowledge; clustering, replication, sentinel/operator.
- **Scalability:** partitioned/sharded clustering; scales with the stateless Data Plane (`08 §37`-ref).
- **Failure modes:** node loss → fail-safe (rate limits conservative, cache miss → origin); not a system of record (loss tolerable).
- **Security:** TLS, AUTH/ACLs, tenant-namespaced keys, no sensitive data cached by default; WAL/cache regulated-data rules per `08 §12/§24.8`.
- **Licensing:** **BSD-3 (Valkey)** — clean, permissive ✅.
- **Cost:** open-source; memory-bound cost; managed option available.
- **Migration:** Redis-compatible → drop-in.
- **Future replacement:** in-memory port → Redis/Dragonfly/managed; compatibility makes this trivial.

### TD-004 — Apache Kafka (Event backbone)
- **Problem:** durable, ordered, replayable, high-throughput event log with differentiated delivery (ZL/RL/BE), compacted snapshots, DLQ (`07`, AD-009).
- **Requirements:** RPO=0 for ZL (repl≥3, acks=all, min-ISR≥2), per-key ordering, replay, compaction, backpressure (`NFR-AUD/COST/Q`).
- **Alternatives:** **Redpanda** (Kafka-compatible, C++, BSL); **Apache Pulsar**; **cloud pub/sub** (Kinesis/PubSub/Event Hubs); NATS JetStream.
- **Why selected:** the most proven durable log; exactly the durability/ordering/replay/compaction semantics `07` depends on; huge ecosystem (Connect, Debezium, schema registry, OTel); Apache-2.0.
- **Why alternatives rejected:** Redpanda — Kafka-compatible and attractive operationally but **BSL license** (source-available, time-delayed OSS) — a licensing consideration; kept as a compatible fallback behind the port. Pulsar — capable but smaller ecosystem/more operational complexity for our patterns. Cloud pub/sub — acceptable as managed equivalents but each is proprietary/lock-in and some lack Kafka's compaction/replay ergonomics; kept as managed options. NATS — lighter, weaker for long-retention replay/compaction at our compliance bar.
- **Operational:** mature but non-trivial; strong tooling; the highest-value operational investment (audit/metering depend on it).
- **Scalability:** partitioned horizontal scale; tiered storage for long retention/replay.
- **Failure modes:** broker loss → ISR handles; the critical dependency for audit/metering (mitigated by repl/quorum, `07`/`08 §16`).
- **Security:** TLS, mTLS, topic ACLs (least-privilege), tenant scope in envelope, encryption; DLQ inherits controls (`07 §17`).
- **Licensing:** **Apache-2.0** ✅.
- **Cost:** open-source; storage (tiered) and operational cost are the drivers; managed options trade cost for ops.
- **Migration:** greenfield; Kafka-compatible alternatives ease exit.
- **Future replacement:** streaming port → Redpanda (compatible) / managed pub/sub; `07` designed around the abstraction.

### TD-005 — Object Storage with Object-Lock/WORM (immutable audit + backups)
- **Problem:** immutable, tamper-evident, long-retention audit records + backups + large objects (`08 §6.5/§10/§16`).
- **Requirements:** WORM immutability (Object-Lock), high durability, residency-confined, crypto-shred-compatible, cheap at scale (`NFR-AUD/BAK/MR`).
- **Alternatives:** **cloud object stores** (S3/GCS/Azure Blob with Object-Lock/immutability); **MinIO** (S3-compatible, self-hosted, AGPL); Ceph.
- **Why selected:** **S3-compatible object storage with Object-Lock (WORM)** — the standard, compliance-grade home for immutable audit and backups; cheap, highly durable, residency-controllable; S3 API is the portability standard (MinIO/Ceph/every cloud speak it).
- **Why alternatives rejected:** none rejected outright — this is a **standard/interface** (S3 API) with multiple conformant implementations (cloud or MinIO) selected per deployment; MinIO (AGPL) noted for licensing where self-hosted.
- **Operational:** managed (cloud) or self-hosted (MinIO/Ceph); lifecycle tiering.
- **Scalability:** effectively unbounded; the growth driver for audit retention (`08 §24.9 N3`).
- **Failure modes:** high durability by design; cross-(permitted-)region replication for DR (`08 §16`, residency-bound).
- **Security:** at-rest encryption, Object-Lock (WORM), crypto-shred via key destruction (`08 §15`), access-governed, residency-confined.
- **Licensing:** cloud (service terms) / MinIO AGPL (self-host caveat) — choose per deployment.
- **Cost:** low per-GB; long-retention audit is the main cost line (tiering mitigates).
- **Migration:** S3 API portability eases moves.
- **Future replacement:** object port → any S3-compatible store.

### TD-006 — Prometheus-ecosystem TSD with long-term storage (metrics/SLIs)
- **Problem:** high-ingest, high-cardinality, time-range metrics and SLIs (`08 §6.6`, AD-011).
- **Requirements:** high-cardinality ingest, time-range query, tiered retention, OTel-compatible, cardinality bounding (`NFR-MET/OBS`, `08 §24.9 M7`).
- **Alternatives:** **InfluxDB**; **TimescaleDB** (Postgres extension); Prometheus + **Mimir/Thanos/VictoriaMetrics** for long-term/HA.
- **Why selected:** **Prometheus** is the de-facto standard for metrics with the richest ecosystem and OTel/exposition compatibility; paired with **Mimir/Thanos/VictoriaMetrics** for long-term, horizontally-scalable, multi-tenant storage — matching our multi-tenant, high-cardinality needs; all Apache-2.0.
- **Why alternatives rejected:** InfluxDB — capable but smaller ecosystem and licensing/version churn history; TimescaleDB — good for some series but not ideal for Prometheus-native metrics cardinality; kept as options behind the port.
- **Operational:** mature; well-understood; strong alerting integration (`07 §16`).
- **Scalability:** horizontally scalable long-term backends; cardinality is the risk (bounded per `08 §24.9 M7`).
- **Failure modes:** ingest backpressure, cardinality explosion → mitigated by caps/allow-lists; metrics are operational (not compliance), loss tolerable.
- **Security:** tenant-scoped, access-governed, no sensitive data in metrics (`07 §17`).
- **Licensing:** Apache-2.0 ✅.
- **Cost:** storage + cardinality driven; tiering/downsampling controls cost.
- **Migration:** OTel/Prometheus standards ease it.
- **Future replacement:** TSD port → alternative Prometheus-compatible backend.

### TD-007 — OpenTelemetry (Instrumentation) — AD-011
- **Problem:** vendor-neutral, correlated instrumentation (metrics/traces/logs) exportable to any backend (AD-011, `07 §16`).
- **Requirements:** unified correlated signals, portability, low overhead with sampling (`NFR-OBS/TRC/MET`).
- **Alternatives:** proprietary APM agents; bespoke telemetry.
- **Why selected:** the industry standard, vendor-neutral, correlated, exportable — already fixed by AD-011; avoids lock-in and fits enterprise SIEM/APM export.
- **Why alternatives rejected:** proprietary agents lock in; bespoke reinvents a solved problem (AD-011).
- **Operational:** SDKs + collector; standard tooling; interchangeable backends.
- **Scalability:** sampling keeps overhead within budget (`NFR-TRC`).
- **Failure modes:** collector outage → buffered/degraded telemetry (not on hot path).
- **Security:** governed export prevents leakage (`NFR-LOG`, `07 §17`).
- **Licensing:** Apache-2.0 ✅.
- **Cost:** minimal (standard); backend cost separate (TD-006).
- **Migration:** it *is* the portability layer.
- **Future replacement:** standard; backends swap freely.

### TD-008 — Schema Registry (event contracts)
- **Problem:** governed, compatibility-checked event schemas for `07`'s versioning (`07 §9`).
- **Requirements:** backward-compatibility enforcement, multi-format (Avro/Protobuf/JSON-Schema), locally-cacheable (registry-outage resilience, `07 ER-7`).
- **Alternatives:** **Apicurio** (Apache-2.0); **Confluent Schema Registry** (Confluent Community License — usage restrictions); protobuf-with-buf; custom.
- **Why selected:** a schema registry enforcing `BACKWARD`/`FULL` compatibility at publish/consume; **Apicurio (Apache-2.0)** preferred for licensing cleanliness; Confluent-compatible API kept for ecosystem.
- **Why alternatives rejected:** Confluent Community License — usage restrictions make it a licensing consideration (Apicurio avoids this); custom — reinvents governance/compat checking.
- **Operational:** standard; schemas cached locally by producers/consumers (registry outage doesn't stop traffic, `07 ER-7`).
- **Scalability:** low-load metadata service.
- **Failure modes:** registry outage → cached schemas keep working; only new registration blocked.
- **Security:** access-controlled; schemas are non-sensitive metadata.
- **Licensing:** Apache-2.0 (Apicurio) ✅.
- **Cost:** low.
- **Migration/replacement:** registry behind a thin abstraction; Apicurio/Confluent-compatible interchange.

### TD-009 — Transactional Outbox + Polling Publisher (default); Debezium optional — DA-D1
- **Problem:** move outbox records from relational stores to Kafka reliably (DA-D1, `08 §9`).
- **Requirements:** at-least-once relay preserving per-key/commit order; low lag; per-service applicability; **minimal operational surface by default** (`NFR-AUD/COST`).
- **Alternatives:** **Transactional Outbox + polling publisher** (application-level, in-process); **Debezium** (log-based CDC, Kafka Connect); cloud CDC services.
- **Why selected (default):** the **Transactional Outbox + polling publisher** is the **default relay for all services** — the outbox record is written in the same transaction as the state change (DA-D1), and an **in-process poller** publishes it to Kafka and marks it sent. It adds **no additional infrastructure** (no Kafka Connect cluster), is simple to operate, and fully satisfies at-least-once + per-key ordering with idempotent consumers. This is the operationally-simplest choice that meets the requirement — preferred per AD-016 (proven, simple on the critical integration path).
- **Debezium is an optional optimization, not a requirement:** for **specific high-throughput services** where poll-based relay lag or DB load becomes a bottleneck, **Debezium (log-based CDC)** may be adopted for that service to reduce lag and offload the source DB. **Debezium is never required platform-wide** — it is opt-in per service, behind the same DA-D1 abstraction.
- **Why alternatives rejected:** requiring **Debezium everywhere** — rejected: it imposes a **Kafka Connect cluster + per-source connectors** (significant operational complexity) on every service for a problem most services don't have; over-engineered as a default. Cloud CDC — proprietary/lock-in; behind the same abstraction if ever needed.
- **Operational:** default = in-process poller (nothing extra to run); optional Debezium = Kafka Connect for the few services that need it.
- **Scalability:** polling scales for the vast majority of services; Debezium reserved for genuine high-throughput CDC; relay-lag monitored either way (`07 §16`).
- **Failure modes:** poller/connector stall → outbox durable, events delayed not lost; idempotent consumers tolerate bursts.
- **Security:** least-privilege DB access; encrypted transport; Debezium (where used) uses least-privilege replication access.
- **Licensing:** Apache-2.0 (Debezium, where used) ✅; the default poller is application code (no third-party licensing).
- **Cost:** default poller = near-zero added infra cost; Debezium = Connect cluster cost, incurred only where adopted.
- **Migration/replacement:** default polling everywhere; adopt Debezium per high-throughput service as an optimization; both behind the DA-D1 abstraction — switching is a per-service decision, not a redesign.

### TD-010 — Cloud KMS/HSM + Vault-class secret management (keys & secrets)
- **Problem:** the key hierarchy (platform/tenant-KEK/subject-DEK/CMK) and secret storage/rotation (`08 §14`, `06 §9.6`, AD-012).
- **Requirements:** HSM-backed root keys, envelope encryption, per-tenant/subject DEK wrapping, rotation, revocation ≤5 min, customer-managed keys, crypto-shred, audit (`NFR-ENC/SEC-SM/PRIV`).
- **Alternatives:** **cloud KMS/HSM** (AWS KMS/CloudHSM, GCP KMS, Azure Key Vault) + **HashiCorp Vault** (secrets, MPL→BSL); cloud-only; HSM-only.
- **Why selected:** **cloud KMS/HSM for root/tenant keys** (HSM-backed, CMK support, compliance certifications) **+ a Vault-class secret manager** for provider-credential lifecycle/rotation and dynamic secrets. Together they realize the envelope key hierarchy and secret non-exposure.
- **Why alternatives rejected:** cloud-only secrets — weaker dynamic-secret/rotation ergonomics than Vault-class; HSM-only — operationally heavy; each usable behind the port. **Vault licensing (BSL since 2023)** noted — OpenBao (Apache-2.0 fork) is the licensing-safe alternative kept behind the secret port.
- **Operational:** managed KMS + secret-manager cluster; rotation automation.
- **Scalability:** KMS scales; DEK-unwrap caching avoids per-record KMS calls (`08 §14 r4`).
- **Failure modes:** KMS outage → cached unwrapped DEKs (short TTL) continue; secret-manager outage → short-TTL cache then fail-safe (`06 §9.6`).
- **Security:** the core of the security model — HSM roots, least-privilege, full audit, crypto-shred.
- **Licensing:** cloud KMS (service terms); Vault BSL ⚠ → **OpenBao (Apache-2.0)** exit preserved.
- **Cost:** KMS per-operation + secret-manager ops; DEK-caching reduces KMS cost.
- **Migration/replacement:** secret/KMS ports → OpenBao/alternative KMS.

### TD-011 — ClickHouse (optional columnar/OLAP for usage analytics) — H6/M3
- **Problem:** large-scale usage analytics/reconciliation over billions of raw-usage records (`08 §24.7`).
- **Requirements:** fast aggregation over huge volumes, columnar compression, time-series-friendly (`NFR-COST`).
- **Alternatives:** **ClickHouse**; Apache Druid; Apache Pinot; keep MongoDB-only.
- **Why selected:** **ClickHouse** — best-in-class open columnar OLAP for high-volume aggregation, Apache-2.0, operationally proven; an *optional complement* to the document raw store where analytics scale demands it (not required at MVP).
- **Why alternatives rejected:** Druid/Pinot — capable but heavier operationally for our aggregation patterns; MongoDB-only — insufficient for large-scale aggregation (the H6 concern).
- **Adoption criteria (objective — provision ClickHouse only when ANY threshold is crossed; below all of them, MongoDB raw + PostgreSQL ledger suffice and ClickHouse is NOT deployed):**
  - **Daily events:** raw-usage ingest exceeds **~100M events/day** (or **~1B** retained records in the queryable window).
  - **Retention period:** more than **90 days** of *queryable raw usage* is required for analytics/reconciliation (beyond that, MongoDB query performance and cost degrade).
  - **Query latency:** analytics/reconciliation queries on the MongoDB raw store exceed **~10 s at p95** for standard reports (aggregation no longer meets operational needs).
  - **Storage cost:** the document-store footprint for raw usage exceeds the cost of an equivalent columnar footprint (columnar compression typically **5–10×**), i.e., ClickHouse becomes cheaper at the retained volume.
  - *(Thresholds are calibration targets, revisited in `32-Capacity-Planning.md`; crossing any one triggers a ClickHouse-adoption review, not automatic deployment.)*
- **Operational:** self-managed or managed; introduced **only** when a criterion above is crossed.
- **Scalability:** excellent aggregation scale; columnar compression.
- **Failure modes:** analytics store (not SoR) — the Postgres ledger remains authoritative; loss tolerable/rebuildable.
- **Security:** tenant-scoped, access-governed; regulated-data rules apply.
- **Licensing:** Apache-2.0 ✅.
- **Cost:** storage-efficient (compression); introduced only at scale.
- **Migration/replacement:** analytics port; optional/deferred (DOQ).

### TD-012 — Kubernetes (cloud-native orchestration)
- **Problem:** cloud-native, immutable, horizontally-scaled, multi-region deployment of the Data Plane and Control Plane (`04 §34`, immutable-infra, `NFR-SCALE/HA/UPG/DEP`).
- **Requirements:** horizontal scaling, rolling/zero-downtime deploys, multi-zone/region, immutable images, autoscaling, portability across clouds (neutrality).
- **Alternatives:** **Kubernetes**; HashiCorp Nomad; cloud container services (ECS/Cloud Run); bare VMs.
- **Why selected:** **Kubernetes** — the de-facto cloud-native orchestration standard; portable across all clouds and on-prem (critical for enterprise/regulated deployment models and neutrality), rich ecosystem (operators, autoscaling, service mesh, OTel), zero-downtime rollout/rollback.
- **Why alternatives rejected:** Nomad — simpler but smaller ecosystem, and Kubernetes portability is decisive for multi-deployment-model enterprise sales; cloud container services — proprietary/lock-in (against neutrality) though usable as managed K8s (EKS/GKE/AKS); bare VMs — insufficient for the operational model.
- **Operational:** mature but complex; managed K8s reduces burden; strong tooling.
- **Scalability:** horizontal pod autoscaling; the Data Plane's stateless scaling substrate (AD-006).
- **Failure modes:** node/zone loss → rescheduling; multi-AZ/region for HA/DR (`04 §34–36`).
- **Security:** network policies, mTLS (service mesh), RBAC, secrets integration, zero-trust substrate (AD-012).
- **Licensing:** Apache-2.0 ✅ (managed K8s per cloud terms).
- **Cost:** cluster + control-plane cost; managed reduces ops.
- **Migration/replacement:** portability is the point — runs on any cloud/on-prem; deployment models per `16`.

### TD-013 — Runtime/Language: Java 21 + Spring Boot (primary); Rust (future hot-path optimization)
- **Problem:** select a **single primary implementation runtime** for the Data Plane and Control Plane that best balances enterprise maturity, development velocity, concurrency/streaming, observability, security, Kubernetes fit, and 10-year maintainability against a latency budget that is **dominated by provider latency**, not by the runtime.
- **Requirements:** meet the added-latency budget (≤50 ms P99 added overhead, `06 §11`, `NFR-LAT/PERF`); massive concurrency for streaming/long-lived connections (`NFR-CONC`, `07`/streaming); memory safety; first-class observability (OTel); strong enterprise security ecosystem; Kubernetes-native; large hiring pool and long-term stability for a 10-year regulated-market company (`02`, `NFR-VER`, AD-016).
- **Alternatives evaluated:** **Java 21 + Spring Boot**; Go; Rust; Java + Rust; Go + Rust; C++; Node/.NET. (See the five-option comparison in the review record.)
- **Why selected — Java 21 + Spring Boot (primary):**
  - **Enterprise maturity:** the most battle-tested enterprise runtime, with the deepest track record in the exact regulated segments we target — healthcare, finance, insurance, government (`02`). Lowest technology risk for a 10-year platform.
  - **Development velocity:** Spring Boot's ecosystem (data, security, web, integration, batch, observability) accelerates the **broad control-plane surface** (governance, identity, billing, admin, extensibility) dramatically faster than systems languages — velocity is decisive across 14 services.
  - **Virtual Threads (Project Loom, Java 21):** cheap, massive lightweight concurrency with **simple blocking code**, ideal for the streaming, high-connection-count hot path (`NFR-CONC`, streaming `07/§14`) — Loom removes the need for reactive complexity while scaling to very high concurrency.
  - **Latency fit:** **Generational ZGC** delivers **sub-millisecond GC pauses**, negligible against a budget dominated by provider latency (hundreds of ms–seconds); the ≤50 ms P99 *added-overhead* budget is comfortably met. **GraalVM native-image** is available where startup/footprint matter. (The "GC tail" objection does not bind here — the budget is not GC-bound.)
  - **Observability:** best-in-class OpenTelemetry/Micrometer instrumentation and the deepest APM/tracing maturity (`NFR-OBS/TRC`, AD-011).
  - **Security ecosystem:** memory-safe runtime with the **richest enterprise security ecosystem** (Spring Security, mature auth/crypto libraries, SBOM/supply-chain tooling) — supports zero-trust and the security NFRs (`NFR-SEC`, AD-012). *(Framework CVE discipline required — see Security.)*
  - **Kubernetes support:** excellent — containerized, health/actuator endpoints, operators, GraalVM/jlink slim images, mature autoscaling behavior (`NFR-SCALE/HA`).
  - **Long-term maintainability & hiring:** the **largest enterprise talent pool** and the most stable long-term platform — decisive for a company that must be staffed and maintained over a decade (lowest continuity/staffing risk).
- **Why alternatives rejected (for the initial product):**
  - **Go** — excellent for infrastructure, but the broad enterprise control-plane surface benefits more from Spring's ecosystem, and its latency/GC edge is **immaterial to our provider-dominated budget**; not adopted for the initial product.
  - **Rust-only / Go+Rust / Java+Rust (dual-language upfront)** — **premature**: the no-GC benefit does not bind against the real latency budget, while a second general-purpose language **doubles toolchain, hiring, and operational cost** for no required launch benefit. **A dual-language architecture is explicitly NOT adopted for the initial product.**
  - **C++** — performance without memory safety; higher defect risk against correctness invariants (against AD-016).
  - **Node/.NET** — weaker fit for the enterprise/regulated ecosystem and the concurrency/streaming model here.
- **Rust — future, profiling-gated optimization only:** Rust MAY be introduced for a **single, isolated, measured hot-path component** (e.g., token/stream reassembly or a crypto path) **only if production profiling demonstrates that Java 21 cannot meet a specific latency target** for that component. It is **never** speculative, **never** a default, and **never** a second general-purpose language. The `06 §10` module boundaries make such a surgical, isolated replacement possible without redesign. Until profiling proves a need, the platform is **single-runtime (Java 21 + Spring Boot)**.
- **Operational:** **single primary runtime** → one toolchain, one hiring profile, one operational model — the simplest, lowest-risk org posture. JVM tuning (Generational ZGC) and image slimming (GraalVM/jlink) are well-understood.
- **Scalability:** horizontal (stateless Data Plane, AD-006); Virtual Threads scale concurrency; per-node efficiency strong on modern JVM.
- **Failure modes:** memory-safe runtime (no buffer overflows/use-after-free); sub-ms GC pauses (Generational ZGC) immaterial to the budget; framework-CVE exposure is a managed patch-discipline concern, not a runtime-reliability risk.
- **Security:** memory-safe; richest enterprise security tooling. **Java/Spring CVE history (Log4Shell, Spring4Shell) mandates strict dependency scanning, SBOM, and rapid patching** (`NFR-SEC-001`, `NFR-SECT-001`) — a managed risk, not a blocker.
- **Licensing:** **OpenJDK** (GPLv2 + Classpath-Exception — safe for our use), **Spring** (Apache-2.0), **Rust** (MIT/Apache, if/when adopted) — all safe ✅.
- **Cost:** higher memory footprint than Go/Rust (JVM) — **offset by development velocity, the largest hiring pool, and ecosystem leverage**; GraalVM native-image reduces footprint where it matters.
- **Migration:** greenfield.
- **Future replacement:** the runtime sits behind the `06 §10` module and service boundaries; the **Rust escape hatch is the sanctioned per-component optimization path**. **Decision confirmed (TDOQ-1 resolved: Java 21 + Spring Boot primary) — recorded as AD-023 (Runtime Strategy).**

---

## 7. Cross-Cutting Stances

- **Managed vs self-hosted:** prefer **managed** for undifferentiated heavy lifting (databases, Kafka, KMS, K8s) **where a portable/open equivalent preserves an exit** — so we gain operational leverage without lock-in. Regulated/on-prem deployment models (`16`) may require self-hosted; every selection supports both.
- **Multi-cloud & portability (neutrality, `00`):** every core technology is open/standard and runs on any major cloud or on-prem (Postgres, Kafka, Valkey, S3-API, K8s, OTel). This is a deliberate anti-lock-in posture matching the product's own neutrality promise and enterprise deployment-model needs (`BR-031`).
- **Licensing posture:** prefer permissive (Apache-2.0/BSD/MIT/PostgreSQL); flag and mitigate restrictive licenses (SSPL: MongoDB → FerretDB/managed exit; BSL: Redis→Valkey, Vault→OpenBao, Redpanda→Kafka); legal review for any SSPL/BSL component before commitment. **No copyleft/AGPL on distributed components without review.**
- **Consistency with the product's values:** the technology stack embodies the same values the product sells — neutral, portable, no lock-in, reliability-first, secure-by-default.

---

## 8. Risks & Open Questions

**Risks.**
- **TDR-1 — MongoDB SSPL exposure.** *Mitigation:* internal-use posture + FerretDB/managed exit + legal review (TD-002).
- **TDR-2 — Kafka operational weight.** *Mitigation:* managed option; it's the highest-value operational investment; Redpanda-compatible fallback.
- **TDR-3 — JVM footprint & Java/Spring framework CVEs.** *Mitigation:* Generational ZGC + GraalVM/jlink slim images for footprint; strict dependency scanning, SBOM, and rapid patching for framework CVEs (`NFR-SEC-001/SECT-001`).
- **TDR-4 — Single-runtime concentration.** *Mitigation:* the `06 §10` module boundaries preserve a **profiling-gated Rust escape hatch** for isolated hot-path components; port/module abstractions keep the runtime replaceable per component.
- **TDR-5 — Managed-service lock-in creep.** *Mitigation:* portability behind ports; open equivalents as exits; multi-cloud stance.
- **TDR-6 — Licensing drift** (vendors changing licenses, as Redis/Vault did). *Mitigation:* permissive-preferred + port abstraction so any single relicensing is survivable.

**Open questions.**
- **TDOQ-1 — RESOLVED.** Runtime/language decided: **Java 21 + Spring Boot (primary)**, with Rust as a profiling-gated future hot-path optimization (TD-013). **Recorded as AD-023 (Runtime Strategy).**
- **TDOQ-2 — Managed vs self-hosted default per environment/tier** (`16`).
- **TDOQ-3 — RESOLVED (in `10 §7`).** Kafka events → **Apache Avro** (Apicurio registry); REST APIs → **OpenAPI 3.1 + JSON Schema**; AI structured output → **JSON Schema**; config/policy → **JSON Schema**.
- **TDOQ-4 — CDC (Debezium) vs polling per service** (`08` DA-D1 per-service call).
- **TDOQ-5 — When to introduce ClickHouse** (scale threshold, `32`).
- **TDOQ-6 — Cloud KMS choice vs Vault/OpenBao split per deployment** (`16`).
- **TDOQ-7 — Service mesh** (for mTLS/zero-trust substrate) selection — deferred to `16`.

---

## 9. Traceability Matrix

| TD | Technology | Class/ADR | NFR | Frozen source |
|---|---|---|---|---|
| TD-001 | PostgreSQL | AD-010 relational | NFR-DS/CFG/COST | `08 §6.1` |
| TD-002 | MongoDB | AD-010 document | NFR-DS/AUD | `08 §6.2` |
| TD-003 | Valkey | AD-008 in-memory | NFR-PERF/CACHE | `08 §6.3` |
| TD-004 | Apache Kafka | AD-009 log | NFR-AUD/COST/Q | `07`, `08 §6.4` |
| TD-005 | S3+Object-Lock | AD-010 object/WORM | NFR-AUD/BAK/MR | `08 §6.5/§10/§16` |
| TD-006 | Prometheus+LTS | TSD | NFR-MET/OBS | `08 §6.6` |
| TD-007 | OpenTelemetry | AD-011 | NFR-OBS/TRC/MET | `07 §16` |
| TD-008 | Schema Registry | `07 §9` | NFR-VER | `07 §9` |
| TD-009 | Outbox+polling (Debezium optional) | DA-D1 | NFR-AUD/COST | `08 §9` |
| TD-010 | KMS/HSM + Vault-class | `08 §14` | NFR-ENC/SEC-SM/PRIV | `08 §14`, `06 §9.6` |
| TD-011 | ClickHouse (opt) | H6/M3 | NFR-COST | `08 §24.7` |
| TD-012 | Kubernetes | cloud-native | NFR-SCALE/HA/UPG/DEP | `04 §34` |
| TD-013 | Java 21 + Spring Boot (Rust future opt) | runtime | NFR-LAT/PERF/CONC | AD-006/016/020/**023** |

**Coverage note.** Every ADR-fixed class has a concrete selection; supporting technologies from `07`/`08` are covered; runtime/language is **decided** (Java 21 + Spring Boot; TDOQ-1 resolved, **AD-023**). All selections sit behind ports (AD-002) and are swappable.

---

## 10. Appendix

**A. Licensing quick reference.** Permissive ✅: PostgreSQL, Valkey (BSD), Kafka, Apicurio, Debezium, ClickHouse, Prometheus/Mimir/Thanos/VictoriaMetrics, OpenTelemetry, Kubernetes, **Spring (Apache-2.0)**, **Rust (MIT/Apache, if adopted)**, MinIO caveat (AGPL). **OpenJDK** = GPLv2 + Classpath-Exception (safe for our use). Restrictive ⚠ (mitigated): MongoDB (SSPL → FerretDB/managed exit), Redis (RSALv2/SSPL → chose Valkey), Vault (BSL → OpenBao), Redpanda (BSL → Kafka), Confluent SR (Community License → Apicurio).

**B. Portability principle.** Because AD-002 places every external behind a port, each TD is an *adapter* selection. "Future replacement" for all: implement an alternative adapter; the core domain is untouched. This is the structural guarantee that no technology decision is a lock-in.

**C. Relationship to other documents.** Selects concrete technologies for the classes fixed in the ADRs and `08`; feeds `10-Repository-Structure.md`, `11`–`16` standards (which assume these technologies), and `32-Capacity-Planning.md` (calibration). Does not modify frozen documents. TD-013 (runtime) should be promoted to an ADR upon confirmation.

**D. Maintenance.** Living document until frozen on review. New technology selections carry the full 13-field template + a traceability row + the frozen source they serve. Licensing posture (permissive-preferred) and portability-behind-ports are stable principles.

---

*End of document — 09-Technology-Decisions.md*
