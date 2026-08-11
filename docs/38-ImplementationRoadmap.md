# 38 — Implementation Roadmap (Implementation-Only · Multi-Year Delivery Plan)

**Document:** Implementation Roadmap — how to build the frozen architecture exactly as written
**Project:** Reliability-First AI Gateway · **Architecture Spec v1.0.0 (FROZEN, Docs 00–37, AD-001…AD-023)**
**Status:** **FROZEN · Version 1.0**
**Nature:** **Implementation-only.** This document changes **no** architecture, ownership, service, context, runtime stage, event, store, or topology. It sequences the build of what is already frozen. Every task cites the frozen document that governs it. On any ambiguity, the roadmap **points to the governing frozen document and invents nothing** (per the governance rules).
**Audience:** Engineering, Platform, Infra, DevOps, QA, TPM, EM
**Subordinate to:** all of `00`–`37` and AD-001…AD-023; especially `09` (stack), `10` (repo/Maven), `11` (coding/ArchUnit), `15` (testing), `16` (deployment/CI), `29` (activation), `35` (implementation governance).

> **Governing principle.** *Implement, do not redesign.* Where a value is unspecified, it is an **operational-baseline** entry (`16 §I.1`) or a deferred-Low item in a frozen doc — set it in config, do not hard-code, do not invent architecture (`35`/§ambiguity rule). The build **fails** on any boundary/invariant violation (`11` CP-2) — good intentions are not enforcement.

---

## ✅ Resolution Notice (Independent Implementation Review Board pass · 2026-07-22)

The eight Independent Implementation Review Board findings (3 High, 5 Medium) are resolved **additively as normative implementation rules** (§IR-1…§IR-8 below) — **implementation sequencing/scheduling/gates/staffing only, no architecture change**. Each rule carries Purpose · Implementation · Dependencies · Validation · Build Gate · Definition of Done · Affected phase · Governing frozen documents. **No new architecture/service/context/runtime stage/store/event/ownership/API/topology/ADR is introduced** — the frozen runtime architecture (`00`–`37`, AD-001…AD-023) is untouched. Resolutions: **IR-1** ZL-durability hard P1 exit gate · **IR-2** continuous DP assembly from P2 · **IR-3** replay/record harness · **IR-4** operational-baseline scheduling · **IR-5** audit-emission tasks (no new module/service) · **IR-6** snapshot-contract-first · **IR-7** KMS/CMK P1 gate · **IR-8** team-topology/solo-dev assumptions.

---

## §IR — Normative Implementation Rules (resolutions)

### §IR-1 — Zero-Loss Event Durability as a hard P1 exit gate *(resolves High #1)*
- **Purpose:** guarantee the ZL event backbone (RPO=0 substrate) exists before any accounting/audit implementation depends on it.
- **Implementation:** stand up the Kafka-class backbone with the frozen **ZL durability contract** — replication ≥3, `acks=all`, `min.insync.replicas ≥2`, idempotent producers, durable/tiered retention, Avro/Apicurio (`07` ZL contract, `07 §9`); provision the node-local **replicated emit WAL** (`07 EV-D3`) and transactional **outbox** (`08 DA-D1`).
- **Dependencies:** P0 (event-envelope lib); infra (§10).
- **Validation:** produce/consume a ZL event; kill a broker/node mid-flight and confirm **no ZL loss** (`07 EV-D3`); WAL commit = RPO=0 boundary verified (`23 §39.1` UC-1).
- **Build Gate:** **P1 cannot exit** until ZL-durability config is verified in CI (topic config assertion + WAL crash/replay test); **P3 accounting work is blocked** until this gate is green.
- **Definition of Done:** ZL topics + WAL + outbox operational and crash-tested; RPO=0 boundary demonstrable.
- **Affected phase:** **P1 (hard exit criterion)**, gates P3.
- **Governing docs:** `07` (ZL/EV-D3), `08 §16` (RPO=0), `23 §39.1`.

### §IR-2 — Continuous Modular-Monolith Assembly from P2 *(resolves High #2)*
- **Purpose:** eliminate late integration of the single DP deployable (AD-020).
- **Implementation:** stand up `gateway-dp-app` (the one deployable) in CI **from the first P2 module**; every merged DP module is **continuously assembled and activated** through the frozen activation DAG (`29 §DAG`) with stubs for not-yet-built stages; an end-to-end integration test runs on every main build.
- **Dependencies:** P0 ports; P2 modules as they land.
- **Validation:** `gateway-dp-app` builds, activates (readiness gate `29 §SRC`), and serves a stubbed end-to-end request on every main pipeline run.
- **Build Gate:** main pipeline **fails** if `gateway-dp-app` cannot assemble/activate; non-bypass integration test (AD-018) is a required check.
- **Definition of Done:** continuous DP assembly + activation green from P2 onward; no "big-bang" integration.
- **Affected phase:** **P2 (introduced), continuous through P7.**
- **Governing docs:** AD-020, `10 §8`, `29 §DAG/§SRC`, `32 §6` (non-bypass order).

### §IR-3 — Replay / Record Harness *(resolves High #3)*
- **Purpose:** make deterministic replay (`32 §CRS`) executable and verifiable — a first-class deliverable, not an afterthought.
- **Implementation:** build a **record/replay harness** that captures each request's **recorded decision trail + version identity** `(codeVersion, snapshotVersions, ExecutionIdentity)` (`29 §CVR`, `33`) and replays **decisions and ordering only** (`32 §CRS` CRS-2), reproducing recorded plugin outputs (`28 §RPC`) and canonical mappings (`25 §23.1`), **never** provider execution/timing/wall-clock/telemetry timing (`32 §CRS` CRS-6). Owner: Reliability/Platform QA (`06 §16`).
- **Dependencies:** `33` models, `32`/`34` (states/sequence), each component's recorded-decision output (`23`/`25`/`27`/`28`/`37`).
- **Validation:** replay a recorded request and assert identical decisions/order; assert timing/provider **not** reproduced (honest-scope test).
- **Build Gate:** `15 §G.1` replay tests (T-035) required per component; harness green before P7.
- **Definition of Done:** harness records + replays every mandatory-stage decision deterministically; overclaim-guard tests pass.
- **Affected phase:** **P2 (scaffold) → P7 (complete).**
- **Governing docs:** `32 §CRS`, `29 §CVR`, `25 §23.1`, `27 §19.1`, `28 §RPC`, `33`.

### §IR-4 — Operational-Baseline Value Establishment *(resolves Medium #4)*
- **Purpose:** ensure every threshold the runtime consumes is a governed config value, not a hard-coded number — **without inventing values**.
- **Implementation:** enumerate all operational-baseline entries (`16 §I.1`) referenced across `17`–`37` (drain/readiness/skew/pool/cardinality/TTL/rollout/budget/finalization-timeout/quota-window/etc.), register each in the config snapshot schema (C15, `36 §OBB`), and schedule **calibration tasks** (owner + method) — values are **set in config by their frozen owners**, never authored here.
- **Dependencies:** P1 config-cache (`36`); C15 config schema.
- **Validation:** a lint asserts **no hard-coded operational numeric** in runtime code (config-lint per each component's `-A14`-class rule); every baseline resolves from a pinned snapshot.
- **Build Gate:** config-lint (no hard-coded numeric) green; baseline registry complete before P7 readiness.
- **Definition of Done:** all baselines are governed config entries with an assigned owner/calibration task; zero hard-coded numerics.
- **Affected phase:** **P1 (registry) → P7 (calibration).**
- **Governing docs:** `16 §I.1`, `36 §OBB`, each component's config-lint rule.

### §IR-5 — Audit-Emission Implementation Tasks *(resolves Medium #5 — no new module/service)*
- **Purpose:** ensure every runtime component emits content-free, complete, RPO=0 audit records — via the **frozen** mechanism, **without** a new audit module or service.
- **Implementation:** implement audit emission **inside each existing component** (each doc's Audit §) as **content-free records** (`13 §19`/`14 §7.1`) over the **ZL event path** (`07`), sealed WORM by C10 (`08 §10`); wire the **loss-detector** (produced/consumed/sealed = 0, `08 §10`) into CI. The emission is the existing "Telemetry/Audit Emitter" node's audit facet (`06 §8`) — **no new module** (`27 §3` deferred this to the frozen `07`/`08`/`13` mechanism).
- **Dependencies:** IR-1 (ZL backbone), `07`/`08 §10`, per-component audit §.
- **Validation:** every mandatory decision/security event emits an audit record; **loss-detector = 0** under load and crash/replay; no content/secret/PII in any audit record (`14 §7.1`).
- **Build Gate:** audit-completeness test (loss-detector = 0) + content-free scan (`15` T-052); required before P7.
- **Definition of Done:** every component emits complete content-free audit records; loss-detector = 0 verified.
- **Affected phase:** **per-component in P2–P5; verified P7.**
- **Governing docs:** `07`, `08 §10`, `13 §19`, `14 §7.1`, `27 §3` (audit facet).

### §IR-6 — Snapshot-Contract-First *(resolves Medium #6)*
- **Purpose:** freeze snapshot schemas in code before any DP consumer builds against them, so consumers never chase a moving target (AD-022).
- **Implementation:** in **P1, before DP-consumer coding**, define and contract-test every AD-022 snapshot schema (config, policy, provider-capability, identity/verification-key, tenant-scope, cost/usage descriptor) with schema-range validation (`29 §SCC`); publish as versioned contracts in `gateway-lib-events`/config schema (C15/C4/C1/C6/C14 owned).
- **Dependencies:** P0 event/contract libs; CP snapshot owners.
- **Validation:** consumer builds against the frozen snapshot contract; old×new / new×old compat tests (`29 §SCC`) green.
- **Build Gate:** **DP-consumer modules cannot merge** until their consumed snapshot contract is frozen-in-code and compat-tested.
- **Definition of Done:** all consumed snapshot schemas contract-frozen + compat-tested before consumer implementation.
- **Affected phase:** **P1 (snapshot-contract sub-phase), precedes P2 consumers.**
- **Governing docs:** AD-022, `29 §SCC`, `36`, `37`, `21`, `19`.

### §IR-7 — KMS / CMK Readiness as a P1 Gate *(resolves Medium #7)*
- **Purpose:** make key-management infra a hard prerequisite for the Secrets Provider (`26`).
- **Implementation:** provision KMS + customer-managed-key (CMK) ACL (`08 §14`, `06 §9.6`) and the short-TTL credential-snapshot path (AD-022) **in P1 infra**; establish rotation/revocation via the frozen high-priority path (`06 §9.6`/`36 §HPP`).
- **Dependencies:** infra (§10); C14 (`06 §9.6`).
- **Validation:** materialize a credential via `26`, zeroize (`26 §17.1`), verify no persistence/log; rotation/revocation ≤ frozen bound.
- **Build Gate:** **P1 cannot exit** for `26` until KMS/CMK + credential-snapshot path are verified; secret-scan clean.
- **Definition of Done:** KMS/CMK operational; `26` materialization/zeroization/rotation demonstrable.
- **Affected phase:** **P1 (hard gate for `26`).**
- **Governing docs:** `26`, `06 §9.6`, `08 §14`, AD-012/022, `36 §HPP`.

### §IR-8 — Team Topology & Solo-Developer Assumptions *(resolves Medium #8 — planning only)*
- **Purpose:** make staffing assumptions explicit so parallelism feasibility is assessable.
- **Implementation (planning assumptions only):**
  - **Minimum team:** ~4–6 engineers (1 platform/foundation, 1–2 hot-path, 1 adapters/CP, 1 QA/replay-harness, part-time infra/DevOps) — critical path (§8) serialized, limited parallel tracks.
  - **Recommended team:** ~10–16 across **stream-aligned squads per `06 §16`** (one per core context C1–C5 + platform + identity/security + QA/SRE) — full parallelism (§9), adapters/CP concurrent with hot path.
  - **Solo developer:** feasible for a reference implementation but **serialize everything on the critical path (§8)**; skip parallel tracks; build **one provider adapter**, defer additional adapters/CP-service depth; treat CP services as **stubs/last-known-good snapshots** (AD-017) and implement CP authoring last; extend timeline proportionally.
  - **Solo sequencing change:** P0 → P1 (config/secrets/authN/observability, snapshot stubs) → critical path P2/P3 with a single adapter → P4 optional → CP + hardening as capacity allows.
- **Dependencies:** none (planning).
- **Validation:** each parallel track in §9 has a named owner/squad (or is explicitly serialized for solo).
- **Build Gate:** none (planning); milestone staffing reviewed at sprint planning (§34).
- **Definition of Done:** team model documented; §9 parallel tracks staffed or serialized.
- **Affected phase:** **all (planning overlay).**
- **Governing docs:** `06 §16` (module→team ownership), AD-017 (DP/CP decoupling).

---

## 1. Executive Implementation Strategy

**Strategy: contracts-first, hexagonal-parallel, critical-path-thin, invariant-gated.**

1. **Contracts-first (Phase 0).** Realize the canonical model (`33`), ports (AD-002/`11 R-018`), event envelope (`07 §6`), and the full **enforcement toolchain** (`35`/`11`/`16`) *before* any component logic. Because everything integrates through ports and canonical objects, freezing these in code unlocks massive parallelism.
2. **Hexagonal-parallel.** Once ports exist, every `gateway-dp-*` module and `gateway-svc-*` service is built **against ports** (`11 R-017/018`) by stream-aligned teams (`06 §16`) in parallel, each stubbing its dependencies. AD-017 (DP runs on last-known-good) lets DP consumers and CP snapshot-producers proceed **independently**.
3. **Critical-path-thin.** The narrowest sequential chain to a **working authenticated, governed, routed, metered, finalized request** is the critical path (§8). Everything else parallelizes around it.
4. **Invariant-gated.** No module merges until its **build-failing rules pass** (the component's `-A` rules + ArchUnit AU-01…AU-12 + mutation target). Non-bypass (AD-018), RPO=0 (`23 §39.1`), determinism (`32 §CRS`), isolation (AD-021) are CI gates, not aspirations.
5. **Ship the modular monolith as one deployable** (AD-020) via the frozen release train (`16 D-014`), rolling/canary (`16 D-031/§E.2`), activated per `29`.

**Cadence:** 2-week sprints, phase-aligned epics; trunk-based (§31). **Target:** production-ready Tier-0 hot path by end of Phase 4; full CP + hardening by Phase 7.

---

## 2. Complete Implementation Phases

| Phase | Name | Delivers | Governing frozen docs |
|---|---|---|---|
| **P0** | Foundation & Contracts | repo, Maven parent/BOM, toolchain/CI, canonical models, ports, event envelope, ArchUnit rules | `09`,`10`,`11`,`33`,`07 §6`,`35` |
| **P1** | Runtime Substrate | config cache, secrets provider, snapshot consumption, observability emission, identity/authN | `36`,`26`,`27`,`37`,AD-022 |
| **P2** | Core Hot Path | Ingress, AuthN, Governance/PEP, Router, Reliability, Adapter, StreamGuard, SchemaLock | `30`,`37`,`21`,`19`,`20`,`25`,`18`,`17` |
| **P3** | Accounting & Cost | Metering (WAL/outbox RPO=0, RequestFinalized), Cost | `23`,`22`,`07 EV-D3`,`08 §16` |
| **P4** | Extensibility | Plugin Runtime, pre-routing optimization plugin | `28`,`28 §EPC`,`24A` |
| **P5** | Control Plane | Provider Registry, Policy/Gov PDP, Config, Identity&Tenancy, Secrets, Metering&Cost ledger, Audit, Observability backend, Extensibility | `06 §9.2–9.12`,`08`,`07` |
| **P6** | Deployment / DR / Lifecycle | activation lifecycle, DR/BC, release train, multi-region | `29`,`31`,`16`,AD-014 |
| **P7** | Hardening & Production Readiness | chaos, perf/load, mutation, contract/integration, security scans, prod-readiness | `15`,`31`,`16 §J.2`,`13` |
| **P8** | Developer Platform *(post-runtime, execution deliverable)* | SDK(s), DX, compliance-evidence program | derived from `12`/`18`/`25`/`30`; `06 §9.9/9.12` |

*(P8 is a product/GRC execution deliverable, not runtime architecture — included for completeness, sequenced last.)*

---

## 3. Repository Bootstrap Plan

Per `10` (monorepo, Maven multi-module) — **do not invent a new tree**.
- Init monorepo `reliability-first-ai-gateway/` (`10 §5`); `docs/` already present.
- `backend/` Maven multi-module (Java 21, Spring Boot, `09`/`10 §7`); namespace `io.reliabilityai.gateway` (`10 §9`/`11`).
- Parent POM + BOM + dependency management (`10 §7`); Spring Boot BOM, Testcontainers BOM, OTel BOM.
- Establish `libs/` (ports/canonical/contracts), `.../modules/gateway-dp-*` (hot path), `gateway-svc-*` (CP) shells (`10 §5`).
- Commit the **enforcement toolchain** config (Spotless/Checkstyle/ErrorProne/SpotBugs/ArchUnit/Sonar/Enforcer/forbidden-APIs, `35 §17`/`11`) so it gates from the first commit (`11` CP-2).
- CODEOWNERS **generated from `06 §16`** (`35 §COO`); branch protection on `main`.
- **Build gate from commit 1:** Spotless + ArchUnit skeleton green.

---

## 4. Module Creation Order

Follows the frozen activation DAG (`29 §DAG`) and hexagonal dependency direction (`11 R-017`). **Foundational libs first; then DP modules in dependency order; CP services in parallel.**

```
0. libs/           canonical-model (33) · ports (AD-002) · event-envelope (07 §6) · common (35 §CmC, primitives only)
1. gateway-dp-config-cache        (36)    ← consumes CP config snapshot (mock early)
2. gateway-dp-secrets-provider    (26)    ← consumes C14 credential snapshot
3. gateway-dp-observability       (27)    ← side-effect-free; wired everywhere
4. gateway-dp-ingress             (30)
5. gateway-dp-authn               (37)    ← consumes C6 key/tenant snapshot
6. gateway-dp-governance-pep      (21)    ← consumes C4 policy snapshot
7. gateway-dp-router              (19)    ← consumes C1 capability snapshot
8. gateway-dp-reliability         (20)
9. gateway-dp-provider-adapters   (25)    ← per-provider adapters in PARALLEL (AD-002/007)
10. gateway-dp-correctness        (17 SchemaLock, 18 StreamGuard)
11. gateway-dp-accounting         (23)  + gateway-dp-cost (22)
12. gateway-dp-telemetry-audit-emitter (27 telemetry facet + audit emission via 07/08 §10)
13. gateway-dp-plugin-runtime     (28)
—— CP services (parallel, from P1, decoupled via AD-017/AD-022) ——
gateway-svc-config (C15) · gateway-svc-identity-tenancy (C6+C7) · gateway-svc-secrets (C14) ·
gateway-svc-provider-registry (C1) · gateway-svc-policy-governance (C4 PDP) ·
gateway-svc-metering-cost (C5 ledger) · gateway-svc-audit (C10) · gateway-svc-observability (C9) ·
gateway-svc-extensibility (C12)
```
**Rule (AU-01/`11 R-013/014`):** every type lands in `io.reliabilityai.gateway.<area>.<context>.{api|internal|domain|application|adapter}` — layers-first is a build failure (`35 §ILA`).

---

## 5. Maven Project Structure

Per `10 §7/§8` (do not alter):
```
backend/pom.xml                      # parent POM (Java 21, plugins, enforcer)
backend/bom/pom.xml                  # platform BOM (dependency management)
backend/libs/
  gateway-lib-canonical/             # 33 canonical models (immutable VOs)
  gateway-lib-ports/                 # AD-002 ports (domain-side interfaces)
  gateway-lib-events/                # 07 §6 envelope + Avro schemas (Apicurio)
  gateway-lib-common/                # 35 §CmC primitives only
backend/dataplane/gateway-dp-app/    # AD-020 single deployable (assembles modules)
backend/dataplane/modules/gateway-dp-*/   # in-process modules (06 §8)
backend/controlplane/gateway-svc-*/  # 13 CP services (store-per-service, 08 §24)
```
- Data-plane app is **one deployable** (AD-020); modules are internal Maven modules with enforced boundaries (`10 §8`, `35`).
- Each `gateway-svc-*` is an independent Spring Boot app owning **one store** (`08 §24`, AU-07).
- **Build gate:** Enforcer (dependency convergence, forbidden-APIs) + ArchUnit slices free of cycles (AU-04).

---

## 6. Package Creation Order

Within each module, per `11 R-013` (context-first, inner layers):
```
<context>/domain/        # aggregates, VOs, domain events, PORTS (R-016/018) — build first (no deps)
<context>/application/    # use-cases depending on domain + ports (R-017)
<context>/adapter/in.*    # inbound adapters (controllers = thin, R-026)
<context>/adapter/out.*   # outbound adapters (persistence, provider, telemetry)
<context>/api/            # public surface (R-013/014); internal = package-private (R-010)
```
Order: **domain → application → adapter → api**. Domain first (external-free, R-017); adapters last (edge). Ports live with domain (R-018).

---

## 7. Dependency Graph

```
canonical(33) ── ports(AD-002) ── events(07)
       │             │
       ▼             ▼
   all DP modules & CP services depend on canonical + ports ONLY (inward, R-017)
DP hot path (06 §8 order):
 ingress(30) → authn(37) → governance(21) → router(19) → reliability(20)
   → adapters(25) → correctness(17/18) → accounting(23) → cost(22) → emitter(27+audit)
   [plugin-runtime(28) hooks at frozen extension points; config-cache(36)/secrets(26) consumed throughout]
DP ⇢ CP: DP consumes CP SNAPSHOTS (AD-022), never CP code/store (AU-07) — decoupled by AD-017.
CP: metering-cost ledger(C5) ← accounting facts(23); billing(C8) ← UsageFact(published language).
```
**Invariants on the graph:** no cross-context `internal` (AU-02); no domain→infra (AU-08); no cross-service store (AU-07); no cycles (AU-04); provider SDK only in adapter (AU-06/`25`); secrets only in `26`.

---

## 8. Critical Implementation Path

The **thin sequential chain** to a working end-to-end request (everything else parallelizes):
```
libs(33/ports/07) → config-cache(36) + secrets(26) → ingress(30) → authn(37)
  → governance-pep(21) → router(19) → reliability(20) → ONE provider-adapter(25)
  → StreamGuard(18)+SchemaLock(17) → accounting/WAL(23, RPO=0) → RequestFinalized(23 §17.1)
  → emitter(27)
```
**Gate: first green end-to-end** = a request authenticates, is governed, routed, invoked on one (mock/real) provider, streamed, validated, metered (WAL-committed), and finalized — with **non-bypass verified** (AD-018) and **RPO=0 verified** (`23 §39.1`). This is the P2→P3 milestone.

---

## 9. Parallel Development Opportunities

- **All DP modules** against ports after P0 (hexagonal, AD-002) — high parallelism.
- **All CP services** in parallel with DP (AD-017 decoupling; DP uses mock/last-known-good snapshots) — high.
- **Per-provider adapters** in parallel (AD-002/007, one adapter per provider, zero downstream diff) — high.
- **Observability (`27`)** wired concurrently (side-effect-free, OT-INV) — no ordering dependency.
- **Testing harnesses** (contract/integration/chaos/perf) built alongside each module.
- **Serialized (critical path):** ingress→authn→governance→router→reliability→adapter→correctness→accounting→finalization (non-bypass ordering, `32 §6`).

---

## 10. Infrastructure Setup

Per `09` stack + `16` deployment (no new topology):
- **Runtime:** JDK 21, Spring Boot, Virtual Threads enabled (`11 R-048`), ZGC (AD-023), container limits (`16 D-044`).
- **Event backbone:** Kafka-class (AD-009) + Avro/Apicurio (`07 §9`); ZL topics repl≥3/acks=all/minISR≥2 (`07` ZL contract) — required before RPO=0 testing.
- **Stores (store-per-service, `08 §24`):** PostgreSQL (relational SoR + outbox DA-D1), MongoDB (documents), Valkey/Redis (ephemeral in-memory tier), Object/WORM (audit + backups, `08 §10/§16`), TSD (metrics).
- **Observability:** OTel collector, Prometheus (Plane A), ClickHouse/OLAP (Plane B, `14 §5.1`), trace backend.
- **Orchestration:** Kubernetes, zone-redundant, multi-region per tier (`16`/AD-014).
- **Secrets/KMS:** for C14 (`26`/`06 §9.6`), customer-managed-key ACL (`08 §14`).
- **Build gate:** infra-as-code reviewed; ZL topic durability config verified before P3.

---

## 11. Local Development Workflow

- `mvn verify` runs full gate locally: Spotless, Checkstyle, ErrorProne, SpotBugs, ArchUnit (AU + component rules), unit + Testcontainers integration, mutation (fast profile).
- Testcontainers for Kafka/Postgres/Mongo/Valkey (`15`) — no shared dev infra needed.
- Snapshot stubs for CP dependencies (AD-022 last-known-good) so any DP module runs standalone.
- VT enabled locally; profile parity with CI (`16`).
- **DoD-local:** `mvn verify` green before push; no boundary suppressions.

---

## 12. CI/CD Pipeline

Per `16`:
- **PR pipeline:** build → static analysis (§14) → security scan (§15) → unit → ArchUnit (AU-01…12 + component `-A` rules) → integration (Testcontainers) → mutation (per-module target) → contract tests → coverage/quality gates → **merge blocked on any failure**.
- **Main pipeline:** full build → package the one DP deployable + CP service images → publish artifacts (independent release trains, `10 §RD-1`) → deploy to staging → smoke/perf-smoke (`16 §J.2`) → promote.
- **Release:** release train (`16 D-014`), canary metric-gated (`16 §E.2`), rolling zero-downtime (`16 D-031`), activated per `29`.

## 13. Build Pipeline
Maven reactor build (parent→bom→libs→dp→svc); Enforcer (convergence/forbidden-APIs); reproducible, immutable artifacts (AD-020); japicmp binary-compat on published `api` (`35 §BCO`/`12 §27`).

## 14. Static Analysis Pipeline
Spotless (format, `11 R-012`), Checkstyle, Error Prone, SpotBugs (immutability/thread-safety `11 R-005/049`), **ArchUnit AU-01…AU-12** (`10`/`11`) + every component's `-A` rules (`17`–`37`, `35`, `36`, `37`), Sonar (complexity/coupling `11 R-020`), package-cycle detection (AU-04). **Every rule is a build gate** (`11` CP-2).

## 15. Security Scanning
Secret/content scanners (`13 §20`, `14 §7.1`) on every module (no secret/PII/content in code/logs/telemetry/audit); dependency CVE scan; forbidden-APIs; SBOM; supply-chain signature verification for plugins (`28 §STC`). **Build-fail:** any secret/PII leak, provider SDK outside adapter (AU-06), credential outside `26`.

## 16. Testing Strategy
Per `15` (each component a `15 §G.1` critical module): unit (JUnit5/Mockito), property-based (jqwik, T-035), **mutation ≥85% (≥90% for security/accounting-critical: `23`,`26`,`28`,`36`,`37`)**, failure-injection (T-018), isolation (T-044), residency (T-045), leak (T-052), provider-neutrality (T-015), streaming (T-012), exactly-once/completeness (T-017/T-046). **Every component's build-failing rules are tests.**

## 17. Contract Testing
Ports and events are contract-tested (`12 §27`/`07 §9`): provider fixtures → canonical objects (`25`); Avro compat (`07 §9`); snapshot schema-compat (`29 §SCC`); the `33` catalog conformance vs frozen owners (`33 §CMA`).

## 18. Integration Testing
Testcontainers end-to-end through the co-located pipeline (`06 §8`): non-bypass (AD-018 — every mandatory stage runs), fail-closed paths, WAL→RequestFinalized ordering (`32 §RFO`), cancellation accounting (`32 §CAC`), pin-after-C6 (`32 §SPT`).

## 19. Chaos Testing
Per `31`/`15`: region/AZ/node/process kill; verify RPO=0 within frozen scope (`31 §RPO`), last-known-good serving (AD-017), no partial serving (`29 §SRC`), drain RPO=0 (`29 §SDC`), split-brain prevention (`31 §SBW`). Quarterly test-restores (`08 §16`).

## 20. Performance Testing
JMH benchmarks per critical path (`15` T-026, `16 §J.2`): added-latency budget (`06 §11`, NFR-LAT-001) excluding provider round-trip; translation/authn/metering slices; VT no-pinning (T-028). Perf-smoke gate on every deploy (`16 §J.2`).

## 21. Load Testing
Sustained + spike load: streaming throughput/backpressure (`25 §31.1`/`30 §BPC`), slow-client memory bound, connection-pool saturation (`25 §30`), cardinality/telemetry cost (`14 §OM-2`), horizontal scaling (AD-006, NFR-SCALE-001).

## 22. Release Strategy
Independent release trains per artifact (`10 §RD-1`); the DP is one immutable deployable per version (AD-020); config/flags via snapshots (`36`, no redeploy); versioning `12 §27`/AD-015/`07 §9`.

## 23. Deployment Strategy
Rolling, zero-downtime (`16 D-031`); activation per `29` (readiness gate `29 §SRC`, DAG `29 §DAG`); snapshot compatibility `29 §SCC`; rollback by re-pin (`29 §RBC`/AD-013); multi-region per tier (AD-014).

## 24. Feature Rollout Strategy
Canary, metric-gated auto-abort (`16 §E.2`); feature flags are **pinned config** (`36 §FFC`) — additive-only (`36 §AMP`, never disable a mandatory stage); optimization ships as a pre-routing plugin (`28 §EPC`/`24A`).

## 25. Migration Strategy
New code migrated **to** R-013/R-014 (`35 §21`); event/schema evolution backward-compatible (`07 §9`/`12 §27`/`29 §SCC`); read-model rebuild-on-restore by replay (`08 §16`); split-to-repo only on evidence (`10 §18`). No migration weakens a frozen boundary.

## 26. Operational Readiness Checklist
- [ ] Runbooks per component (each doc's Operations §) wired to alerts.
- [ ] SLO/SLI dashboards (Plane A, `14`/`27`); canary signals (`16 §E.2`).
- [ ] DR drills passing (`31`); RPO=0 verified within scope.
- [ ] Secret rotation/revocation ≤ frozen bound (`06 §9.6`/`36 §HPP`).
- [ ] Config drift detection + secure defaults (`36 §DRC/§SDS`).
- [ ] Audit completeness (loss-detector = 0, `08 §10`).

## 27. Production Readiness Checklist
- [ ] All component `-A` build-failing rules green in CI.
- [ ] Non-bypass verified end-to-end (AD-018).
- [ ] Exactly-once/RPO=0 verified (`23 §39.1`/T-017/T-046).
- [ ] Deterministic replay verified (`32 §CRS`/T-035).
- [ ] Tenant isolation verified (AD-021/T-044); residency (AD-014/T-045).
- [ ] Mutation targets met (≥85/≥90).
- [ ] Perf-smoke + load within budget (`06 §11`/`16 §J.2`).
- [ ] Zero-downtime deploy + rollback rehearsed (`16 D-031`/`29 §RBC`).
- [ ] Security scans clean (`13`); no secret/PII/content leak.

## 28. Risk Register
| # | Risk | Governing doc | Mitigation |
|---|---|---|---|
| R1 | Event-backbone durability not ready before P3 ⇒ RPO=0 untestable | `07`/`08 §16` | stand up ZL topics in P1 infra; gate P3 on durability config |
| R2 | Modular-monolith late integration (all DP modules must assemble) | AD-020/`10 §8` | ports + mocks from P0; continuous integration of `gateway-dp-app` |
| R3 | Mutation ≥90% on security/accounting modules = high effort | `15 §G.1` | budget extra time for `23`/`26`/`28`/`36`/`37` |
| R4 | Operational-baseline values unset (deferred-Low across docs) | `16 §I.1` | set as config in P1; never hard-code |
| R5 | Provider adapter neutrality drift under schedule pressure | `25`/AD-007 | AU-06 + T-015 gate every adapter |
| R6 | Single-region-residency full-region-loss RPO limit | `31 §RPO-5` | disclosed; operational communication, not a code fix |
| R7 | CP snapshot contract drift vs DP consumers | AD-022/`29 §SCC` | contract tests P1; snapshot schema-range validation |
| R8 | Plugin sandbox (process isolation) infra complexity | `28 §ISO` | prototype substrate in P4; honest guarantees (no absolute claim) |

## 29. Engineering Milestones
- **M0** Foundation green (P0): toolchain gates + libs.
- **M1** Substrate (P1): config/secrets/observability/authN + CP snapshot contracts.
- **M2** First green end-to-end request (P2→P3, critical path §8) — the pivotal milestone.
- **M3** Accounting RPO=0 + RequestFinalized (P3).
- **M4** Extensibility live (P4).
- **M5** CP services integrated (P5).
- **M6** DR/deploy/multi-region rehearsed (P6).
- **M7** Production-ready (P7): all readiness checklists green.
- **M8** Developer platform (P8, post-runtime).

## 30. Estimated Implementation Order
P0 → P1 → (P2 critical path serialized; adapters/CP parallel) → P3 → P4 → P5 (parallel from P1) → P6 → P7 → P8. **Indicative** phasing for a multi-year enterprise build (exact durations are team-sizing, not architecture): P0–P1 foundation; P2–P3 core+accounting; P4–P5 extensibility+CP; P6–P7 hardening/GA; P8 developer platform. Durations are TPM estimates, not frozen.

## 31. Suggested Git Branching Strategy
**Trunk-based** (fits AD-020 one-deployable): protected `main`; short-lived feature branches (`feat/<context>-<ticket>`); PR + full gate to merge; **release branches per train** (`release/<train>`, `16 D-014`) for canary/rollback; tags per artifact (independent trains, `10 §RD-1`). No long-lived divergent branches.

## 32. Suggested Repository Milestones
`m0-foundation`, `m1-substrate`, `m2-e2e-request`, `m3-accounting-rpo0`, `m4-extensibility`, `m5-control-plane`, `m6-dr-deploy`, `m7-production-ready`, `m8-sdk` — mapped 1:1 to §29.

## 33. Suggested Issue Labels
- **context:** `c1-routing`…`c16-notification` (from `05`/`06 §16`).
- **module:** `dp-ingress`,`dp-authn`,…,`svc-config`,…
- **phase:** `p0`…`p8`.
- **type:** `feat`,`bug`,`test`,`arch-conformance`,`infra`,`docs`.
- **invariant:** `non-bypass`,`rpo0`,`determinism`,`isolation`,`neutrality`,`fail-closed`.
- **tier:** `tier0`,`tier1`,`tier2`.
- **gate:** `build-failing-rule`,`mutation`,`contract`,`chaos`,`perf`.

## 34. Suggested Sprint Planning
2-week sprints; one epic per module/phase; every story references its frozen doc (§Implementation Rules) and carries its build-failing rule as acceptance. Critical-path stories (§8) prioritized to hit M2 early; parallel tracks (adapters, CP, observability) staffed by stream-aligned teams (`06 §16`). Sprint review = green gates demo, not slideware.

## 35. Definition of Done (per phase)
**Universal DoD (every phase):** code in R-013 layout (AU-01); all applicable ArchUnit AU + component `-A` rules green; mutation target met; unit + Testcontainers integration green; contract tests green; no secret/PII/content leak (`13`/`14 §7.1`); traceability to the governing frozen doc; non-bypass preserved (AD-018); no boundary suppressions.

| Phase | Phase-specific DoD |
|---|---|
| P0 | libs published; toolchain gates enforced from commit 1; ports/canonical/event-envelope contract-tested (`33 §CMA`, `07 §9`). |
| P1 | config-cache pins post-C6 (`36 §SPT`); secrets zeroize (`26 §17.1`); observability side-effect-free (OT-INV); authN fail-closed (`37`); CP snapshot contracts frozen-in-code. |
| P2 | first green end-to-end request (§8); non-bypass + fail-closed verified; tenant scope only post-C6 (`32 §SPT`). |
| P3 | per-attempt UsageFact WAL-committed; RequestFinalized only after all facts (`32 §RFO`); no double-count (`23 §14.1`); RPO=0 crash/replay test green. |
| P4 | plugins only at frozen 5 points (`28 §EPC`); process-isolated (`28 §ISO`); additive-only (no mandatory bypass); optimization pre-routing plugin only. |
| P5 | each CP service owns one store (AU-07); publishes ZL snapshots/events (`07`); DP runs on last-known-good with CP down (AD-017). |
| P6 | readiness-gated activation (`29 §SRC`); rollback by re-pin rehearsed (`29 §RBC`); DR drill RPO=0 within scope (`31`); multi-region per-region readiness (`31 §MRA`). |
| P7 | all production-readiness checklist items green (§27); chaos/perf/load pass; security scans clean. |
| P8 | SDK generated from frozen contracts (`12`/`18`/`25`/`30`); client idempotency/streaming/retry conform; compliance-evidence program consuming `08 §10` trail. |

---

## Implementation Rules (applied throughout)
Every task references its frozen doc. Every phase includes: **Purpose · Inputs · Outputs · Dependencies · Risks · Build gates · Validation · Test requirements · DoD** (embedded in §2/§28/§35 and per-module tables). On ambiguity: **cite the governing frozen document, invent nothing.**

*Illustrative per-phase expansion (P2 Core Hot Path):* **Purpose** build the mandatory non-bypass pipeline (`06 §8`/`32`). **Inputs** libs (P0), substrate (P1), CP snapshot stubs. **Outputs** ingress→…→correctness modules. **Dependencies** P0/P1; ports. **Risks** R2 (integration), R5 (neutrality). **Build gates** AU-01…12 + `30/37/21/19/20/25/18/17 -A` rules. **Validation** end-to-end non-bypass integration test. **Test requirements** T-015/T-012/T-018/T-044. **DoD** M2 green.

---

## S. Reviews (post-resolution)

### 1. Implementation Consistency Review
| Finding | Resolution rule | Sequencing/gate | ✓ |
|---|---|---|---|
| ZL durability late (High-1) | §IR-1 | hard P1 exit gate; blocks P3 | ✅ |
| Late monolith integration (High-2) | §IR-2 | continuous DP assembly from P2 | ✅ |
| Replay harness unstaffed (High-3) | §IR-3 | scaffold P2 → complete P7; T-035 gate | ✅ |
| Baselines unscheduled (Med-4) | §IR-4 | P1 registry → P7 calibration; config-lint | ✅ |
| Audit emission no owner (Med-5) | §IR-5 | per-component P2–P5; loss-detector=0 gate; no new module | ✅ |
| Snapshot contracts trail consumers (Med-6) | §IR-6 | P1 contract-first precedes P2 consumers | ✅ |
| KMS/CMK not a gate (Med-7) | §IR-7 | hard P1 gate for `26` | ✅ |
| Staffing unstated (Med-8) | §IR-8 | min/recommended/solo topology documented | ✅ |

**All eight findings resolved as normative implementation rules.** No architecture/service/context/stage/store/event/ownership/API/topology/ADR introduced — sequencing/scheduling/gates/staffing only.

### 2. Dependency Review
Corrected ordering now explicit: **P0 libs → P1 {ZL durability (IR-1), KMS/CMK (IR-7), snapshot contracts (IR-6), substrate}** → P2 consumers (against frozen snapshot contracts) with **continuous DP assembly (IR-2)** → P3 accounting (gated by IR-1) → P4 → P5 (parallel, AD-017) → P6 → P7 (audit-completeness IR-5, replay IR-3, baseline calibration IR-4). Inward-only/no-cross-store/ports-first dependencies unchanged (`11 R-017`/AU).

### 3. Critical-Path Review
The thin critical path (§8) is unchanged, but its **prerequisites are now hard-gated in P1** (ZL durability, KMS/CMK, snapshot contracts) so M2/M3 cannot start on a moving foundation. Continuous assembly (IR-2) de-risks the first green end-to-end request.

### 4. Build-Gate Review
New gates added, all additive: P1-exit ZL-durability crash/replay; P1-exit KMS/CMK + secret-scan; DP-consumer-merge snapshot-contract compat (`29 §SCC`); main-pipeline DP assembly+activation (`29 §SRC`); loss-detector=0 audit completeness; config-lint (no hard-coded numeric); replay T-035. No gate weakens a frozen build-failing rule.

### 5. Risk Review
R1 (backbone) → mitigated by IR-1 hard gate. R2 (late integration) → mitigated by IR-2 continuous assembly. R4 (baselines) → IR-4 scheduled. R7 (snapshot drift) → IR-6 contract-first. R8 (plugin substrate) → note: IR-8 recommends earlier substrate prototyping via staffing; residual scheduling risk tracked, not architectural. R3 (mutation effort) → IR-8 sizing makes it staffable. R6 (residency RPO limit) → disclosed operational item, unchanged.

### 6. Engineering-Feasibility Review
With IR-8 team topology, parallel tracks (§9) are staffable (recommended ~10–16) or explicitly serialized (solo). Standard stack/toolchain (`09`/`11`/`16`) keeps gates achievable. Durations remain indicative TPM estimates (not frozen). No feasibility blocker remains.

**New findings:** 🔴 Critical: none. 🟠 High: none. 🟡 Medium: none blocking. 🟢 Low (deferred): §L.

**Recommendation: FREEZE (implementation plan).** All 3 High + 5 Medium resolved as normative implementation rules with gates; no architecture changed; the roadmap is execution-ready.

---

## §L — Deferred Low Implementation Items (non-blocking)
- **L1** exact per-baseline numeric values → set by frozen owners in config during IR-4 calibration (never here).
- **L2** provider-adapter fixture corpus per provider → `benchmarks/`/test fixtures (`15`).
- **L3** dashboard/alert catalog wiring → `27`/`16` ops.
- **L4** SDK/DX (P8) detailed plan → developer-platform track (post-runtime).

These are scheduling/detail items; none affects sequencing correctness, dependency order, or any build gate.

---

*End of document — 38-ImplementationRoadmap.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
