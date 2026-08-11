# 23 — Usage Metering Engine (Runtime Usage-Fact Engine · Domain C5)

**Document:** Component Implementation Architecture — Usage Metering Engine
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Bounded context:** **C5 — Metering & Cost** (Core Domain, `05`/`06`)
**Module:** `dp-usage-metering-engine` — the **runtime usage-fact module** of C5's data-plane presence (the frozen "Accounting Emitter" facet, `06 §137/206/459`), co-located per **AD-020/AD-006** — **not** a new service, **not** a new bounded context, **owns no store**.
**Audience:** Metering/FinOps/data-platform/data-plane engineers, SRE, security, compliance, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`22` and ADRs **AD-001…AD-023**. Everything here is **additive**. Introduces **no new architecture decision, no new service, no new module, no new store, no ownership/event/data-ownership change, no consistency/security/deployment/observability/testing-model change, and no provider coupling.** Every UME-Dx is a *component-internal implementation decision* inside the already-frozen C5, expressed through hexagonal ports (AD-002).

> **What this is.** The complete implementation architecture of the **Usage Metering Engine** — the component that turns **runtime execution facts** (what actually happened: tokens, tool calls, attempts, streams) into **immutable, exactly-once usage facts** emitted to the **C5 control-plane ledger** (RPO=0, `06 §307`). It is the **sibling of the Cost Engine (`22`)**: the Cost Engine **prices**; the Usage Metering Engine **records what was used**. It consumes facts from Reliability (`20`, attempts), StreamGuard (`18 CV-5`, streaming/final usage), and SchemaLock (`17`, guided retries); it **never** prices, bills, routes, retries, or governs. Every major decision carries **Problem · Decision · Alternatives · Why selected · Trade-offs · Failure Modes · Security Impact · Performance Impact · Enforcement**.
>
> **THE USAGE-METERING INVARIANT (UME-INV):** *Never lose usage. Never duplicate usage. Never fabricate usage. Never estimate usage. Never silently discard usage. On any uncertainty it fails closed and surfaces.* This is the metering-layer realization of **AD-016 (reliability first)**, **AD-005/AD-009 (zero-loss, RPO=0 for accounting)**, and **AD-018 (non-bypassable correctness)** applied to usage — usage facts are the source of truth for cost, budget, quota, and billing, so they are **complete, exactly-once, and never invented**.

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Interfaces** (§6–7) · **C. Major Decisions** (UME-D1…UME-D12) · **D. Usage Fact Model** (§8–12) · **E. Exactly-Once & Ordering** (§13–17) · **F. Attempt & Stream Semantics** (§18–25) · **G. Provider vs Customer Usage** (§26–28) · **H. Interfaces** (§29–35) · **I. Determinism & Replay** (§36–37) · **J. Failure Handling** (§38–39) · **K. Performance & Concurrency** (§40–42) · **L. Security** (§43) · **M. Observability** (§44–47) · **N. Residency & Compliance** (§48–49) · **O. Testing** (§50) · **P. Build-Failing Rules** (§51) · **Q. Operations** (§52–54) · **R. Traceability** (§55) · **S. Reviews**

---

## A. Charter

### §1 — Purpose
The Usage Metering Engine **records runtime usage** — completely, exactly-once, and immutably. For every request it captures the **actual** units consumed (prompt/completion/reasoning/cached tokens, tool calls/tokens, per-attempt/stream usage) from runtime execution facts, and emits **immutable usage facts** to the **C5 control-plane ledger** (the authoritative accounting source of truth, RPO=0, `06 §307`) and to the **quota/budget counters** Governance enforces (`21 §28.1`) and the **usage** the Cost Engine prices (`22 §20`). It realizes C5's runtime metering responsibility (`05`/`06`) as a **stateless, provider-neutral, record-only** module.

### §2 — Scope
- **In scope (record only):** **Prompt / Completion / Reasoning / Cached tokens**; **Tool Calls / Tool Token Usage**; **Streaming Usage**; **Retry / Guided-Retry / Hedged / Failed / Cancelled / Successful attempts**; **Provider Usage** (all attempts) and **Customer Usage** (delivered, per §26); **Budget / Quota usage facts**; **Immutable Usage Facts** to the ledger — all as **facts**, never estimates.
- **Applies to:** every request on the hot path — usage facts emitted as execution completes (post-attempt, post-stream), off the response-latency critical path where possible (§40).

### §3 — Responsibilities
1. Consume **runtime execution facts**: attempts + outcomes (Reliability `20`), final/streamed usage (StreamGuard `18 CV-5`), guided retries (SchemaLock `17`).
2. **Normalize** them into canonical **`UsageFact`s** (provider-neutral, `18 CV-5`).
3. Emit **immutable, exactly-once usage facts** to the C5 ledger (idempotency-keyed, §13).
4. Distinguish **provider usage** (all attempts) from **customer usage** (delivered), and record attempt classes (§18/§26).
5. Feed **quota/budget usage counters** (Governance `21 §28.1`) and **usage inputs** to the Cost Engine (`22 §20`).
6. **Fail closed** on any usage uncertainty (missing/ambiguous/unverifiable usage) — never lose, duplicate, fabricate, estimate, or silently discard (UME-INV).

### §4 — Non-Responsibilities (what the Engine NEVER does)
- ❌ **pricing / cost calculation** — owned by the **Cost Engine (`22`)**. The Metering Engine records **usage**; the Cost Engine prices it.
- ❌ **invoices / billing / payments / taxation** — owned by **C8 Billing & Commerce**.
- ❌ **accounting / ledger persistence** — the **authoritative ledger is the C5 control-plane Metering & Cost Service** (RPO=0, `06 §307`); the Engine **emits facts**, it does not persist the ledger.
- ❌ **routing** (C1), **retries / execution** (C2), **governance / quota-budget enforcement** (C4). The Engine records usage that Governance **enforces** against; it does not enforce.
- ❌ **hold credentials / call providers / own network / persist / own a database** — credential-free, SDK-free, HTTP-free, store-free (UME-D12).

### §5 — What the Engine is NOT
| Usage Metering Engine (C5 — record) | Not the Engine |
|---|---|
| ✓ record actual usage as immutable facts | ✗ price usage (Cost Engine `22`) |
| ✓ exactly-once, complete, never-fabricated | ✗ invoice / bill / take payment (C8) |
| ✓ emit facts to the C5 ledger | ✗ own/persist the ledger (C5 control plane) |
| ✓ feed quota/budget counters + Cost Engine | ✗ enforce quota/budget (C4 Governance) |
| ✓ provider-neutral usage facts | ✗ know providers / branch on provider names |

---

## B. Domain & Interfaces

### §6 — Domain model (metering subdomain)
Pure domain types (no infrastructure, `11` hexagonal); immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `ExecutionFact` | VO | A runtime fact consumed from an execution stage: an attempt outcome (`20`), a stream's final usage (`18 CV-5`), a guided retry (`17`) — the raw input |
| `UsageFact` | VO (**immutable**) | The canonical, provider-neutral usage record: `{idempotencyKey, attemptId, tenantScope, canonicalModelId, region, usageClass, units{prompt, completion, reasoning, cached, toolCalls, toolTokens}, attemptClass, delivered:bool, timestamp, sourceVersions}` |
| `UsageClass` | enum | `authoritative` \| `estimated` (provider-flagged, `18 CV-5`) — **never fabricated**; drives §24 |
| `AttemptClass` | enum | `initial` \| `failover` \| `hedge` \| `guided_retry` \| `transport_retry` \| `cancelled` \| `failed` \| `successful` (§18) |
| `UsageDescriptor` | VO (immutable, snapshot) | Canonical definition of **what units are metered** for a model/capability (which token classes, tool-usage semantics) — from the C5 usage-descriptor snapshot (AD-022) |
| `MeteringResult` | VO | Terminal outcome: emitted `UsageFact`(s) or a **fail-closed `UsageUnrecorded`** (surfaced) |
| `RequestUsageManifest` | VO | The per-request roll-up of all attempt facts (provider usage = Σ attempts; customer usage = delivered) — the `RequestFinalized` completeness manifest (`06 §23.11`) |

**Aggregate:** `MeteringExecution` — the transient per-request aggregate coordinating consume → normalize → emit; holds **no shared mutable state across requests** (AD-021).

### §7 — Public interfaces (ports — AD-002 hexagonal)
**Inbound (called by the data-plane pipeline as execution facts arrive):**
```
UsageMeteringPort:
  recordAttempt(ExecutionFact) -> MeteringResult          // per-attempt usage fact; fail-closed
  finalizeRequest(requestId) -> RequestUsageManifest       // completeness manifest (RequestFinalized, 06 §23.11)
```
**Outbound (all emissions are events/facts; no store, no credentials, no provider SDK):**
```
UsageSourcePort         // <- Reliability (20)/StreamGuard (18 CV-5)/SchemaLock (17): runtime execution facts
UsageDescriptorPort     // <- C5 control plane: canonical usage descriptors (snapshot, AD-022)
LedgerSinkPort          // -> C5 control-plane ledger: immutable usage facts (events, exactly-once, ZL/RPO=0, §13/§29)
QuotaCounterPort        // -> shared tier: quota/budget usage increments (bounded-staleness, 21 §28.1)
CostUsagePort           // -> Cost Engine (22): NormalizedUsage for pricing (22 §20)
AuditSinkPort           // -> Audit (C10): usage-fact audit records (content-free)
TelemetryPort           // -> Observability (C9): metering metrics/traces (content-free)
ClockPort / IdPort      // deterministic time / ids
```
- **Enforcement:** ArchUnit — the Engine imports **no** provider SDK (AU-06), **no** HTTP client, **no** persistence driver / database (AU-07), **no** secrets/credential API, **no** pricing/billing types (those are `22`/C8). All inputs are execution facts + snapshots; outputs are immutable usage facts. **Build-Fail:** any forbidden import; a hot-path network call; a database; pricing/billing logic in the Engine.

---

## C. Major Decisions

### UME-D1 — Record-only (never price, bill, or persist a ledger)
- **Problem:** metering must not creep into pricing (C5 Cost Engine `22`), billing (C8), or ledger ownership (C5-CP).
- **Decision:** the Engine **only records usage** as **immutable facts** and **emits** them. It **never** prices, invoices, bills, taxes, accounts, or persists the ledger. The **authoritative ledger is C5-CP** (RPO=0, `06 §307`); pricing is the **Cost Engine** (`22`); billing is **C8**. The Engine's output is a **usage fact**, not a cost or a charge.
- **Alternatives:** fold pricing/ledger into the Engine — rejected (ownership drift, a new store); meter inside Reliability/StreamGuard — rejected (scattered, un-auditable; C5 owns metering).
- **Why selected:** single, auditable, provider-neutral usage-record locus; clean C5-Cost / C5-Metering / C5-CP-ledger / C8 separation.
- **Trade-offs:** depends on the C5-CP ledger for authoritative accounting (correct — that is C5-CP's).
- **Failure Modes:** any recording failure ⇒ fail closed (§38).
- **Security Impact:** no pricing/billing/credential surface.
- **Performance Impact:** cheap (§40); no ledger I/O owned on the hot path (emits events).
- **Enforcement:** ArchUnit — no pricing/billing/ledger types (UME-A5/A6). **Build-Fail:** the Engine pricing/billing/persisting a ledger.

### UME-D2 — Immutable usage facts (append-only, never mutated)
- **Problem:** usage is the source of truth for cost/budget/billing; a mutable usage record is a correctness and audit disaster.
- **Decision:** every `UsageFact` is **immutable and append-only**. A correction is a **new compensating fact** (referencing the original), **never** an edit/delete of an emitted fact. Facts carry an **idempotency key + attemptId** (identity) and **source versions** (reproducibility). The ledger is append-only (`08 §10` WORM discipline for accounting).
- **Alternatives:** update-in-place usage — rejected (destroys audit, enables silent tampering, UME-INV); soft-delete — rejected (same).
- **Why selected:** append-only immutability is the foundation of exactly-once, audit, and dispute reproduction.
- **Trade-offs:** corrections are compensating facts (more facts) — correct and auditable.
- **Failure Modes:** none from immutability; a correction failure ⇒ fail closed.
- **Security Impact:** tamper-evidence (`08 §10` Merkle) + no mutation surface.
- **Performance Impact:** append is O(1).
- **Enforcement:** ArchUnit — no mutation of a `UsageFact` (UME-A3). **Build-Fail:** a mutable/updatable/deletable usage fact.

### UME-D3 — Exactly-once metering (effectively-once, idempotency-keyed)
- **Problem:** usage must be counted **exactly once** for what is metered — never lost (RPO=0), never duplicated.
- **Decision:** metering follows the frozen **effectively-once** model (`07`/AD-005, `20 §25`, `22 §24.1`): usage facts are **idempotency-keyed** (`12 §14`) — provider-usage facts keyed by **(idempotencyKey, attemptId)**, the **customer/delivered** roll-up keyed by **idempotencyKey** — so the **ledger dedups exactly-once** (no double-count) while retaining **all attempt facts** (no loss). Emission is **at-least-once with idempotent ledger consumption** = effectively-once. Zero-loss (ZL delivery class, `07`) ensures no fact is dropped.
- **Alternatives:** at-most-once emission — rejected (can lose usage, UME-INV); count without idempotency keys — rejected (duplicates under retry).
- **Why selected:** RPO=0 completeness + exactly-once counting; matches the platform accounting invariant.
- **Trade-offs:** requires idempotency-keyed emission + a durable WAL/outbox for ZL (§13).
- **Failure Modes:** ambiguous delivered/attempt state ⇒ fail closed (do not emit a possibly-double or possibly-missing fact).
- **Security/Cost impact:** prevents usage under/over-count → correct budgets/cost/billing.
- **Performance Impact:** idempotency-keyed append, O(1).
- **Enforcement:** effectively-once tests (`15` T-017/T-035). **Build-Fail:** a usage fact emitted without an idempotency key; a customer-usage roll-up counted more than once per key; a lost provider-attempt fact (UME-A1/A2).

### UME-D4 — Zero-loss emission (durable, RPO=0 for accounting)
- **Problem:** accounting requires **RPO=0** (`03` NFR-AUD/COST, `06 §307`, `08 §16`) — no usage fact may be lost even on crash.
- **Decision:** usage facts are emitted with the **zero-loss (ZL) delivery class** (`07 §6`) via a **durable transactional outbox / WAL** (`08` DA-D1, `07` EV-D3) — the fact is durably captured **before** the request is considered accounted; on crash/restart the outbox replays (at-least-once + idempotent ledger = effectively-once, UME-D3). A fact that **cannot** be durably captured ⇒ **fail closed** (surface; the request's accounting is not silently dropped). Uses the **frozen** outbox/WAL mechanism (`08`/`07`) — **no new store** (the outbox is the owning-service's, here C5's data-plane emission path into the ledger).
- **Alternatives:** best-effort emit — rejected (usage loss, UME-INV); synchronous ledger write on the hot path — rejected (latency, `06 §11`; the ledger is C5-CP).
- **Why selected:** RPO=0 completeness without hot-path ledger coupling.
- **Trade-offs:** durable capture adds a bounded write (WAL) — accepted for zero-loss (`07` EV-D3 fail-safe).
- **Failure Modes:** WAL saturation/failure ⇒ fail-safe rejection (`07` EV-D3) — never silent drop.
- **Security Impact:** completeness = audit/compliance integrity.
- **Performance Impact:** node-local WAL append (`07` EV-D3), off the response critical path.
- **Enforcement:** completeness tests (`15` T-046), crash/replay tests. **Build-Fail:** best-effort (non-ZL) usage emission; a usage-loss path on crash (UME-A2).

### UME-D5 — Never fabricate / never estimate usage
- **Problem:** usage must reflect **what actually happened** — a guessed or fabricated figure corrupts cost/budget/billing.
- **Decision:** the Engine records **only** usage reported by execution (`18 CV-5` authoritative usage). It **never fabricates** a token count and **never estimates** when authoritative usage is unavailable. Provider-**flagged estimated** usage (`18 CV-5`) is recorded **as `usageClass=estimated`, flagged**, and only when downstream policy permits an estimated fact (mirrors `22 §24`) — it is **never silently** treated as authoritative. Default on missing/unverifiable usage = **fail closed** (`UsageUnrecorded`).
- **Alternatives:** estimate from averages/model defaults — rejected (fabrication, UME-INV); silently record zero — rejected (usage loss).
- **Why selected:** the core UME-INV guarantee — usage is real, never invented.
- **Trade-offs:** missing-usage requests fail closed (accounting-visible) — deliberate over silent-wrong.
- **Failure Modes:** missing/ambiguous usage ⇒ fail closed.
- **Security Impact:** prevents usage manipulation.
- **Performance Impact:** O(1).
- **Enforcement:** fault tests (`15` T-018). **Build-Fail:** a fabricated/estimated-as-authoritative usage figure; a silent-zero on missing usage (UME-A4).

### UME-D6 — Provider-neutral usage (no provider names, no provider logic)
- **Problem:** usage must stay provider-neutral (AD-007) despite different provider usage reporting.
- **Decision:** usage is recorded as **canonical, provider-neutral units** (`18 CV-5`) keyed by **canonical model id** (`19 §9.2`), **never** by provider name. Provider-native usage fields are **normalized upstream** (StreamGuard/adapters, `18 CV-5`); the Engine consumes `NormalizedUsage` and **never branches on provider identity** or provider-native usage fields.
- **Alternatives:** per-provider usage parsing — rejected (coupling, AD-007; that is StreamGuard/adapter's, `18`).
- **Why selected:** neutral, extensible, auditable.
- **Trade-offs:** depends on upstream normalization (`18 CV-5` — frozen).
- **Failure Modes:** unnormalizable usage ⇒ upstream flags/fails; the Engine fails closed on missing normalized usage.
- **Security Impact:** no provider coupling.
- **Performance Impact:** O(1).
- **Enforcement:** ArchUnit — no provider-name reference (UME-A7). **Build-Fail:** a provider-name branch/switch or provider-native usage field in the Engine.

### UME-D7 — Attempt-class metering (provider = all attempts; customer = delivered)
- **Problem:** retries/hedges/failovers (`20`) invoke providers multiple times; usage must record **all** attempts (provider usage) yet identify the **delivered** one (customer usage) — never conflated.
- **Decision:** every attempt (initial/failover/hedge/guided-retry/transport-retry/failed/cancelled/successful, §18) produces a **provider-usage fact** (§26); the **delivered** attempt is marked (`delivered=true`) for the **single customer-usage roll-up** (`20 §25`, `22 §24.1`). **Provider usage = Σ all attempt facts** (true consumption); **customer usage = delivered** (per upstream policy, applied by Cost `22 §24.1`, not decided here). The Engine **records both**; it never suppresses an attempt's usage nor invents a delivered figure.
- **Alternatives:** record only delivered — rejected (loses provider consumption facts, under-reports true usage); record all as customer usage — rejected (over-counts customer usage; that policy is upstream's).
- **Why selected:** complete provider facts + exactly-once customer roll-up; mirrors `22 §24.1` attribution.
- **Trade-offs:** more facts (one per attempt) — correct and auditable.
- **Failure Modes:** ambiguous delivered state ⇒ fail closed.
- **Security Impact:** prevents usage under/over-report.
- **Performance Impact:** O(attempts), bounded (`20` retry/hedge caps).
- **Enforcement:** attempt-class tests (`15` T-017). **Build-Fail:** a provider attempt with consumed units and no usage fact; a fabricated delivered figure; conflating attempt and delivered usage (UME-A4/A9).

### UME-D8 — Deterministic, replayable metering
- **Problem:** usage facts must be reproducible for audit/dispute/reconciliation.
- **Decision:** fact production is a **pure, deterministic function** of `(ExecutionFact, usage-descriptor version, injected clock)` — **no wall-clock/random** in the core (`11` R-063). Given identical execution facts + recorded descriptor version, the **same `UsageFact`** results. Every fact stamps the **descriptor version + source versions** → exact replay/reproduction (dispute, reconciliation).
- **Alternatives:** nondeterministic/time-derived usage — rejected (un-auditable, dispute-prone).
- **Why selected:** auditable/replayable usage; enables property/mutation testing.
- **Trade-offs:** determinism over recorded inputs (honest, §36).
- **Failure Modes:** none from determinism.
- **Security Impact:** enables strong testing + audit.
- **Enforcement:** ArchUnit forbids wall-clock/random in the core; replay tests (`15` T-035). **Build-Fail:** wall-clock/random in the metering core; unstamped versions (UME-A8).

### UME-D9 — Completeness manifest (RequestFinalized)
- **Problem:** a request may span many attempts/streams; metering must prove **completeness** (all usage accounted, no gap).
- **Decision:** on request finalization the Engine emits a **`RequestUsageManifest`** aligned with the frozen **`RequestFinalized`** completeness event (`06 §23.11`) — a roll-up asserting **all attempt/stream usage facts for the request are accounted** (a completeness assertion the ledger/audit uses to detect gaps, `15 §T-046` audit completeness). A request that **cannot** be finalized completely ⇒ **fail closed** (surface an accounting incident, never a silent partial-metering).
- **Alternatives:** no completeness assertion — rejected (silent usage gaps undetectable, UME-INV/RPO=0).
- **Why selected:** completeness is provable + gap-detectable (loss detector = 0, `08 §10`).
- **Trade-offs:** a finalization step per request (cheap).
- **Failure Modes:** incomplete finalization ⇒ fail closed.
- **Security Impact:** completeness = accounting integrity.
- **Performance Impact:** O(attempts) roll-up.
- **Enforcement:** completeness tests (`15` T-046). **Build-Fail:** a finalized request whose manifest does not account for all its attempt/stream usage.

### UME-D10 — Usage interfaces (Ledger / Cost / Governance) — facts only
- **Problem:** Cost (`22`), Governance quota/budget (`21`), and the ledger (C5-CP) all need usage — but must not re-derive it.
- **Decision:** the Engine is the **single source of runtime usage facts**; it emits (a) **immutable facts to the C5-CP ledger** (accounting), (b) **`NormalizedUsage` to the Cost Engine** (`22 §20`, for pricing), and (c) **usage increments to Governance's quota/budget counters** (`21 §28.1`, for enforcement). **No downstream stage re-derives usage.** All outputs are provider-neutral, content-free facts.
- **Alternatives:** each consumer parses usage independently — rejected (duplication/drift; C5 owns usage).
- **Why selected:** single usage-truth source; consistent facts everywhere.
- **Trade-offs:** the fact contract must be stable (`12 §27`).
- **Failure Modes:** `UsageUnrecorded` ⇒ downstream fail-closed (Cost `CostUnavailable` `22`, Governance budget-indeterminate `21`).
- **Security Impact:** neutral, content-free.
- **Performance Impact:** O(1) reads downstream.
- **Enforcement:** ArchUnit — no usage re-derivation downstream (cross-checked `21`/`22`). **Build-Fail:** a downstream stage re-parsing provider usage (cross-doc).

### UME-D11 — Residency-aware, regionally-scoped metering
- **Problem:** usage facts carry tenant/usage context that must respect residency (AD-014).
- **Decision:** usage metering happens **in-region** (data plane is regional, `16` D-009); usage facts are **residency-confined** (never shipped cross-region beyond id-only correlation, `14 §18.1`, `08` DA-D2); the **ledger** is region-scoped (cross-region aggregation is a C5-CP accounting concern, post-hoc, not a hot-path metering operation). Usage data is classified as tenant usage/financial data (`08`) and handled per classification (no leak).
- **Alternatives:** central cross-region metering — rejected (residency/latency).
- **Why selected:** residency-safe, regional.
- **Trade-offs:** cross-region roll-up is post-hoc (C5-CP).
- **Failure Modes:** none material.
- **Security/compliance impact:** residency confinement of usage data.
- **Performance Impact:** in-region, O(1).
- **Enforcement:** residency tests (`15` T-045). **Build-Fail:** usage data crossing a residency boundary.

### UME-D12 — Security (credential-free, SDK-free, HTTP-free, store-free)
- **Problem:** the Engine handles tenant/usage context and must present zero credential/SDK/network/storage surface.
- **Decision:** the Engine holds **no secrets, no credentials, no provider SDK, makes no direct HTTP, and owns no store/database**. All inputs are execution facts + snapshots via ports; outputs are immutable facts/events. Durable emission uses the **frozen** outbox/WAL (`08`/`07`), not a Metering-private store.
- **Alternatives:** Engine owning a usage database — rejected (store ownership; C5-CP owns the ledger).
- **Why selected:** minimal attack surface; pure recording.
- **Trade-offs:** none material.
- **Failure Modes:** n/a.
- **Security Impact:** eliminates credential/store surface from the metering layer.
- **Performance Impact:** neutral.
- **Enforcement:** ArchUnit — no secrets/HTTP/SDK/persistence (UME-A5/A6/A13). **Build-Fail:** any credential/HTTP/SDK/database reference in the Engine.

---

## D. Usage Fact Model

### §8 — Usage Fact (see UME-D2)
- Canonical, immutable `UsageFact` (§6): identity `(idempotencyKey, attemptId)`, tenant/region scope, canonical model id, **units** (prompt/completion/reasoning/cached tokens, tool calls/tokens), `usageClass` (authoritative/estimated), `attemptClass`, `delivered`, timestamp, source versions. **Content-free** (no prompt/completion text — only counts, `13 §20`/`14 §7.1`).

### §9 — Usage Descriptor & §10 — snapshot versioning
- A **`UsageDescriptor`** (versioned snapshot, AD-022, authored by C5-CP) defines **which units are metered** for a canonical model/capability (which token classes exist, tool-usage semantics) — provider-neutral. The Engine consumes the versioned descriptor and **stamps its version** on every fact (§36). Descriptor updates are **atomic/versioned** (`12 §27`); a request pins the version at start (deterministic, §36).

### §11 — Fact lifecycle
```
ExecutionFact (attempt/stream/guided-retry) → normalize (usage descriptor) → UsageFact (immutable)
  → durably capture (ZL outbox/WAL, UME-D4) → emit to ledger (idempotency-keyed, exactly-once)
  → increment quota/budget counters (21 §28.1) + feed Cost Engine (22 §20)
  → on request finalization: RequestUsageManifest (RequestFinalized, UME-D9)
```
Every step is fail-closed on uncertainty (§38); facts are append-only (UME-D2).

### §12 — Immutable facts (see UME-D2)
- Append-only; corrections are compensating facts referencing the original; tamper-evident in the ledger (`08 §10` Merkle). Never edited/deleted.

### §12.1 — Corrections workflow (compensating facts only) *(resolves Medium UMEM-5 — additive; never edits)*
- **Rule (normative):** a usage correction is **ALWAYS a new compensating fact** — never an edit or delete of an emitted fact (UME-D2). A compensating fact **references the original** `(idempotencyKey, attemptId, originalFactId)`, carries the **delta** (or a reversal + a corrected fact), and is itself **immutable, idempotency-keyed, audited, and version-stamped** — so the net accounting is correct while the **full history is preserved** (an original + its correction are both visible, tamper-evident, `08 §10`).
- **Ownership:** the **authority to issue a correction is the C5 control plane's** (reconciliation/dispute resolution owner, `06 §307`) — the data-plane Metering Engine emits facts as execution happens; **corrections are a control-plane reconciliation concern** applied as compensating facts. The Engine does not silently self-correct an emitted fact; it emits truthfully and any correction is a governed, audited compensating fact. **Build-Fail:** an edit/delete/overwrite of an emitted usage fact; a correction that is not a referenced, idempotency-keyed, audited compensating fact.

---

## E. Exactly-Once & Ordering

### §13 — Exactly-once metering (see UME-D3/D4)
- Idempotency-keyed facts + ZL durable emission → the ledger reconciles exactly-once (no loss, no duplicate). Provider facts keyed by `(key, attemptId)`; customer roll-up keyed by `key`.

### §14 — Idempotency
- The request **idempotency key** (`12 §14`) is the metering identity anchor; a replayed emission (crash/outbox replay) with the same `(key, attemptId)` is **deduped by the ledger** (idempotent consumer, `07`), so re-emission is safe and produces no duplicate count.

### §14.1 — Deduplication key, window & expiry *(resolves Medium UMEM-2 — additive)*
- **Dedup key (normative):** provider-usage facts dedup by **`(idempotencyKey, attemptId)`**; the **customer/delivered roll-up** dedups by **`idempotencyKey`** (a single customer roll-up per request). The `attemptId` is a stable, deterministic per-attempt identity assigned by the execution stage (`20`), not a random value.
- **Dedup window / expiry:** the **ledger's idempotent consumer** (`07`) retains dedup keys for a **retention window** ≥ the maximum possible replay horizon (the outbox/WAL replay bound + finalization timeout, §17.1 FC-7). The retention window is an **operational-baseline value** (`16 §I.1`, §41.1) sized so a key is **never forgotten while a replay of it is still possible** (else a duplicate could slip) and **not retained unbounded** (memory). Expiry is safe because a fact whose key has expired is also beyond any possible replay (the outbox/WAL no longer holds it, UC-11).
- **Ownership:** dedup is performed by the **ledger's idempotent consumer** (`07`, C5-CP), not the Engine; the Engine supplies stable keys. **Build-Fail:** a usage fact emitted without a stable `(idempotencyKey, attemptId)`; a dedup retention window shorter than the replay horizon.

### §15 — Replay (see §36)
- Outbox/WAL replay after crash re-emits un-acknowledged facts (at-least-once); the ledger's idempotent consumption makes the net effect **exactly-once**. Deterministic fact production (§UME-D8) guarantees a replayed fact is identical to the original.

### §16 — Ordering
- Usage facts for a request are **causally ordered** by `(key, attemptId, timestamp)`; the ledger does not require strict global order (facts are commutative for accounting sums) but the **completeness manifest (UME-D9)** provides the per-request closure. Per-attempt facts may arrive out of order; the manifest reconciles them. **No fact is dropped for being out of order** (buffered/reconciled, never discarded, UME-INV).

### §17 — Partial failures
- If **some** attempt facts emit and **others** don't (partial emission failure), the Engine **does not finalize** the request as complete — it **fails closed** on the request's completeness (`UsageUnrecorded` / no `RequestFinalized`) so the gap is **detected** (`08 §10` loss detector), never silently accepted. A partial-metering is never presented as complete. Full normative contract: **§17.1**.

### §17.1 — Completion / RequestFinalized Contract *(resolves High UMEH-2 — additive; aligns with the frozen `06 §23.11` RequestFinalized + `08 §10` loss detector)*

This is the **signed, testable contract** for request completion. **`RequestFinalized` is emitted ONLY after every authoritative usage fact for the request has been durably recorded (WAL-committed, §39.1 UC-1) — never before.**

| # | Aspect | Contract |
|---|---|---|
| FC-1 | **Multi-attempt completion** | A request finalizes only when **all** its attempt facts (initial + every failover/hedge/guided-retry/transport-retry, §18) that consumed provider units are **durably recorded** (UC-1). A pending/in-flight attempt blocks finalization. |
| FC-2 | **Streaming completion** | A streaming request finalizes only after StreamGuard's **terminal verdict** (`18 §30`) yields the **authoritative final usage** (`18 CV-5`) and that fact is durably recorded. No finalization on a stream still in flight. |
| FC-3 | **Late usage facts** | A usage fact arriving **after** a naïve completion attempt is **not dropped** — the request is **not finalized** until the expected fact set is complete; a late fact is durably recorded and reconciled into the manifest **before** finalization. **A fact arriving after `RequestFinalized` is a completeness violation (FC-11) — because finalization asserts all facts are already recorded, no authoritative fact should arrive later.** |
| FC-4 | **Retry completion** | Each retry/failover attempt (`20`) is an expected fact; finalization waits for all of them (or their terminal outcomes) to be durably recorded. |
| FC-5 | **Hedged completion** | Both the hedge winner (`delivered`) and every hedge loser (cancelled attempt, its consumed provider usage, `22 §24.1` CA-4) must be durably recorded before finalization — provider usage of losers is never skipped. |
| FC-6 | **Cancelled completion** | A cancelled attempt's consumed provider usage (`18 §27`/`20 §27`, CA-6) is an expected fact; finalization waits for its authoritative figure (or a StreamGuard terminal for what was consumed) to be recorded. |
| FC-7 | **Timeout completion** | If expected facts do not arrive within a **finalization timeout** (operational baseline, `16 §I.1`, §41.1), the request is finalized as **INCOMPLETE** (a surfaced accounting incident / `UsageUnrecorded`), **not** as complete — the timeout **never** silently finalizes a gap as complete (fail closed, UME-INV). |
| FC-8 | **RequestFinalized ownership** | The **Usage Metering Engine emits `RequestFinalized`** (the frozen completeness event, `06 §23.11`) within C5's already-owned event space — **no new topic owner**. It asserts "all authoritative usage facts for this request are durably recorded." |
| FC-9 | **Completeness Manifest ownership** | The Engine produces the **`RequestUsageManifest`** (§6/UME-D9) — the per-request roll-up (provider usage = Σ attempts, customer usage = delivered) — as the **payload/evidence** of `RequestFinalized`. The **authoritative reconciliation is C5-CP's** (the ledger, RPO=0); the Engine produces the manifest, the ledger reconciles it. |
| FC-10 | **Gap detection** | The manifest + `RequestFinalized` feed the frozen **`08 §10` loss detector**: a request that **started** but has **no** `RequestFinalized` (or an INCOMPLETE finalization) is a **detectable gap** (loss detector ≠ 0 ⇒ alarm, `15` T-046). Completeness is **provable**, not assumed. |
| FC-11 | **Terminal conditions** | A request reaches a **single terminal state**: **`RequestFinalized(complete)`** — all authoritative facts durably recorded — **or** **`RequestFinalized(incomplete)`/`UsageUnrecorded`** — a surfaced gap. There is **no third "silently done" state** (UME-INV). |
| FC-12 | **Failure conditions** | Any inability to durably record an expected fact, or a fact arriving after finalization (FC-3), or a finalization-timeout gap (FC-7) ⇒ **fail closed** (INCOMPLETE + gap-detected), never a silent complete. |

- **Cardinal rule (normative):** **`RequestFinalized(complete)` is emitted ONLY after every authoritative usage fact for the request is durably WAL-committed (§39.1 UC-1) — never before.** Finalization is the **assertion of completeness**, backed by the manifest and gap-detectable by `08 §10`.
- **Enforcement (UME-A18):** completeness tests (`15` T-046) assert: no `RequestFinalized(complete)` before all facts durably recorded; late/lost facts ⇒ detected gap; timeout ⇒ INCOMPLETE (never silent complete); loss detector catches a missing finalization. **Build-Fail:** a `RequestFinalized(complete)` emitted before all authoritative facts are durably recorded; a request finalized as complete with a missing expected attempt/stream fact; a timeout that finalizes a gap as complete.

---

## F. Attempt & Stream Semantics

### §18 — Attempt classes (metered distinctly)
- Every attempt is recorded with its `AttemptClass`: **initial / failover / hedge / guided_retry / transport_retry / successful / failed / cancelled** (aligned with `20 §24.1` CA-1…CA-12). Each attempt that consumed provider units gets a **provider-usage fact** (§26); the delivered one is marked. Classes are **facts** (what happened), not decisions.

### §19 — Streaming usage
- Streaming usage is recorded from the **final `NormalizedUsage` at stream completion** (StreamGuard delivers it, `18 CV-5`/`18 §21`) — the **authoritative** stream usage. A running estimate is **not** a fact; only the **completion** figure is metered (never mid-stream fabrication).

### §20 — Partial streams & §21 — terminal streams
- A **terminal** (cleanly-completed) stream yields authoritative final usage → metered (§19). A **partial** stream (mid-stream failure/truncation, `18 §26/§53`) yields whatever authoritative usage StreamGuard reports for what was consumed — metered as a **failed/cancelled attempt's provider usage** (never fabricated for the un-delivered remainder). No completion event without StreamGuard's terminal verdict (`18 §30`).
- **Partial-stream usage accounting (resolves Medium UMEM-1, normative):** for a partial/truncated stream, the Engine records **exactly what StreamGuard authoritatively reports as consumed** (`18 CV-5`) — no more (never fabricate the un-delivered remainder), no less (never drop the consumed portion — the platform paid for it, `22 §24.1` CA-5/CA-6). If StreamGuard reports the partial usage as **flagged-estimated** (`18 CV-5`), it is recorded `usageClass=estimated` per §24 (fail-closed-by-default, flagged-only-if-policy). If StreamGuard reports **no** usage for a partial stream (unknown consumption), the Engine **fails closed** (`UsageUnrecorded`) for that attempt — it never assumes zero. The partial attempt is a **provider-usage fact** (its `attemptClass` = failed/cancelled) and is **not** the `delivered` customer usage (a truncated stream did not deliver, `18 §26`).

### §22 — Failed streams & §23 — cancelled streams
- **Failed stream:** the provider units consumed before failure (per `18 CV-5`) are a **failed-attempt provider-usage fact** (never dropped — the platform paid for them, `22 §24.1` CA-5). **Cancelled stream:** units consumed before cancellation (`18 §27`, `20 §27`) are a **cancelled-attempt provider-usage fact** (never assumed free, CA-6). Customer usage follows delivered-vs-attempt policy (upstream, `22 §24.1`).

### §24 — Estimated vs authoritative usage
- **Authoritative** usage (provider returned exact figures, `18 CV-5`) ⇒ metered as `usageClass=authoritative`. **Estimated/missing** usage (provider flagged, `18 CV-5`) ⇒ **fail closed by default**; recorded as `usageClass=estimated` **only** under explicit upstream policy, **always flagged** — never silently treated as authoritative (mirrors `22 §24`, UME-INV never-estimate). The estimate is provider-reported, never fabricated.

### §25 — Retry / guided-retry / hedged usage
- **Retry / failover / hedge** attempts each produce a provider-usage fact (§18/§26). **Guided retry** (`17 §24`, `20 §20.1`) is a fresh attempt with its own provider usage. **Hedge losers** (`20 §RE-D6`, `22 §24.1` CA-4) are **cancelled attempts** with their consumed provider usage recorded (never suppressed); only the winner is `delivered`. All share the request idempotency key (§14).

---

## G. Provider vs Customer Usage

### §26 — Provider usage (all attempts, always recorded)
- **Provider usage = Σ all attempt facts** (every attempt that consumed provider units, including failed/cancelled/hedge-loser). Always emitted as facts to the ledger (true platform consumption, `22 §24.1` CA-2). **Never suppressed** (UME-INV).

### §27 — Customer usage (delivered, single roll-up)
- **Customer usage** = the **delivered** attempt's usage, rolled up **once per idempotency key**. **Whether failed attempts count toward customer usage is an upstream charging policy** (applied by Cost `22 §24.1` CA-3) — the Metering Engine **records the delivered fact + attempt facts**; it does **not** decide the customer-charging rule. Never invent a customer-usage figure; never double-count the delivered roll-up.

### §28 — Provider ≠ customer (boundary)
- The Engine records **both**, distinctly (`delivered` flag + attempt classes). **Provider usage (fact, all attempts) ≠ customer usage (delivered, per policy).** This mirrors `22 §24.1` exactly — the Cost Engine prices these facts; the Metering Engine produces them. No conflation. Full normative contract: **§28.1**.

### §28.1 — Provider vs Customer Attribution Contract *(resolves High UMEH-3 — additive; aligns with `20 §25` delivered + `22 §24.1` attribution; no ownership duplication)*

This is the **signed, testable contract** for provider-vs-customer usage attribution, pinned against the frozen `20 §25` (delivered outcome) and `22 §24.1` (cost attribution). **Usage Metering records facts; the Cost Engine calculates cost; the ledger stores accounting facts; billing is owned elsewhere (C8). Ownership is never duplicated.**

| # | Aspect | Contract |
|---|---|---|
| AT-1 | **Provider usage** | **Provider usage = Σ usage facts of ALL attempts** that consumed provider units (initial/failover/hedge/guided-retry/transport-retry/failed/cancelled). Always recorded as facts (true platform consumption). **Never suppressed** (UME-INV). |
| AT-2 | **Customer usage** | **Customer usage = the `delivered` attempt's usage, rolled up once per idempotencyKey.** The Metering Engine **records** the delivered fact + attempt facts; whether failed attempts *count toward the customer* is an **upstream charging policy applied by the Cost Engine** (`22 §24.1` CA-3) — **the Metering Engine does not decide the charging rule.** |
| AT-3 | **Delivered usage** | **`delivered` is determined by the Reliability Engine's outcome** (`20 §25` — the single committed successful attempt); the Metering Engine **records** the `delivered=true` flag on that attempt's fact **as reported by Reliability** — it **does not independently decide** which attempt was delivered (that is C2's execution outcome). Exactly one attempt per request is `delivered` (or none, if the request surfaced a failure). |
| AT-4 | **Retry usage** | Each retry/failover attempt (`20`) → a **provider-usage fact** (AT-1); only the delivered one is customer usage (AT-2). |
| AT-5 | **Failed usage** | A failed attempt's consumed provider units → a **provider-usage fact** (never dropped — the platform paid, CA-5); customer inclusion per policy (Cost). |
| AT-6 | **Cancelled usage** | A cancelled attempt's consumed provider units (`18 §27`/`20 §27`) → a **provider-usage fact** (never assumed free, CA-6); customer inclusion per policy. |
| AT-7 | **Hedged usage** | Every hedge attempt → a **provider-usage fact** (winner + losers, CA-4); the **winner** is `delivered` (AT-3), losers are cancelled attempts (AT-6). |
| AT-8 | **Guided-retry usage** | A SchemaLock guided re-ask (`17 §24`, `20 §20.1`) is a fresh attempt → its own **provider-usage fact**; the conformant delivered one is customer usage. |
| AT-9 | **Streaming usage** | Streaming usage is the **authoritative final usage** from StreamGuard (`18 CV-5`, §19); a partial/failed/cancelled stream's consumed units are a provider-usage fact for that attempt (§20–23). |
| AT-10 | **Ownership** | **Usage Metering (C5) records** attempt + delivered facts (this document). **Reliability (C2) owns** which attempts happen and which is delivered (`20`). **Cost Engine (C5) calculates** cost from these facts and applies the charging policy (`22 §24.1`). **Ledger (C5-CP) stores** accounting facts (`06 §307`). **Billing (C8)** bills. **No ownership duplication** — each owns one thing. |
| AT-11 | **Cost Engine interface** | The Metering Engine emits `NormalizedUsage` facts (provider + delivered, labelled by attempt class + `delivered`) to the Cost Engine (`22 §20/§30`); the Cost Engine **prices** them per `22 §24.1`. The Metering Engine **never prices**; the Cost Engine **never re-derives usage** (`22 §CE-D10`). |
| AT-12 | **Ledger interface** | The Metering Engine emits all attempt + delivered usage facts (idempotency-keyed, §39.1) to the **C5-CP ledger** for authoritative accounting/reconciliation (RPO=0). The ledger **stores**; the Metering Engine **produces**; no persistence in the Engine. |

- **Cardinal rule (normative):** **Provider usage (all attempts, always a fact) ≠ customer usage (delivered, per upstream policy).** The Metering Engine **records facts only** — it never invents a customer-usage figure, never suppresses a provider-attempt fact, and never decides the charging rule (that is the Cost Engine applying upstream policy). `delivered` is Reliability's outcome, recorded not decided.
- **Enforcement (UME-A19):** attribution tests (`15` T-017/T-035) assert: provider usage = Σ all attempt facts (none suppressed); exactly-one `delivered` per request; customer roll-up once per key; the Engine never prices/charges; `delivered` matches Reliability's outcome. **Build-Fail:** a provider attempt with consumed units and no fact; more than one `delivered` per request; the Metering Engine deciding the charging rule or pricing usage; a customer roll-up counted more than once.

---

## H. Interfaces

### §29 — Ledger interface (C5 control plane)
- Via `LedgerSinkPort` (events, ZL/RPO=0, `07 §6`/`06 §307`): emits **immutable usage facts** to the **C5-CP authoritative ledger** for accounting/reconciliation. The **ledger is C5-CP's**; the Engine emits facts (durable, exactly-once, UME-D3/D4), it does not persist.

### §30 — Cost Engine interface (`22`)
- Via `CostUsagePort`: provides `NormalizedUsage` to the Cost Engine (`22 §20`) for pricing. The Metering Engine produces usage; the Cost Engine prices it. `UsageUnrecorded` ⇒ Cost `CostUnavailable` (`22 §39`).

### §31 — Governance interface (`21`)
- Via `QuotaCounterPort`: increments the **bounded-staleness quota/budget usage counters** (`21 §28.1`) so Governance can enforce caps at admission. The Engine **provides** usage increments; **Governance enforces** (`21 GV-D5/D6`) — no enforcement here.
- **Idempotent increment key (resolves Medium UMEM-3, normative):** each quota/budget increment is keyed by **`(idempotencyKey, attemptId, counterKey)`** so a retried/replayed emission (crash/outbox replay, §39.1) **increments the counter at-most-once** for a given attempt against a given counter — the counter's **atomic compare-and-increment** (`21 §28.1` GC-2) dedups by this key. A replay therefore **never double-increments** a tenant's quota/budget usage. The counter authority + reconciliation remain **C5/C4's** (`21 §28.1`, `06 §307`); the Engine supplies the idempotent increment. **Build-Fail:** a quota/budget increment without an idempotent `(idempotencyKey, attemptId, counterKey)`; a replay path that can double-increment.

### §32 — Reliability interface (`20`) & §33 — Correctness interface (`17`/`18`)
- Consumes **attempt outcomes** from Reliability (`20`), **final/streamed usage** from StreamGuard (`18 CV-5`), and **guided-retry** facts from SchemaLock (`17 §24`) via `UsageSourcePort`. These are **runtime execution facts** the Engine records; the Engine drives none of them.

### §34 — Observability interface (§44) & §35 — Audit interface
- **Audit:** via `AuditSinkPort`, every usage fact (+ manifest) is a **content-free, tamper-evident** audit record (WORM+Merkle, `08 §10`, `13 §19`) — the usage-dispute/compliance evidence trail; completeness (RPO=0) preserved.

---

## I. Determinism & Replay

### §36 — Determinism (see UME-D8) & §37 — replay
- Deterministic over recorded inputs (execution facts + descriptor version + injected clock). **Replay:** an outbox/WAL replay (crash recovery) re-emits identical facts; the ledger dedups (idempotent), net effect exactly-once. **Dispute reproduction:** a usage fact is reproduced from its recorded `(ExecutionFact, descriptor version)` — no live lookup. Honest scope: reproducible **given the recorded versions**, not "stable across descriptor changes."

---

## J. Failure Handling

### §38 — Failure handling (every failure fails closed)
Every failure/uncertainty resolves to **`UsageUnrecorded` (surfaced) / no `RequestFinalized`** — never lose, duplicate, fabricate, estimate, or silently discard usage (UME-INV).

| Failure | Handling |
|---|---|
| Missing/unverifiable usage | **`UsageUnrecorded`** (fail closed; never fabricate/estimate) |
| Estimated/flagged usage (`18 CV-5`) | fail closed by default; flagged `estimated` fact only if policy permits (§24) |
| Durable-capture (WAL) failure/saturation | **fail-safe rejection** (`07` EV-D3) — never silent drop |
| Partial emission (some attempts unrecorded) | **fail closed on completeness** — no `RequestFinalized` (§17) |
| Ambiguous delivered/attempt state | **fail closed** (no possibly-double/missing fact) |
| Missing usage descriptor/version | **fail closed** (can't normalize authoritatively) |
| Out-of-order attempt facts | **buffer + reconcile** via manifest — never discard (§16) |
| Any unknown/internal error | **`UsageUnrecorded`** (fail closed) |
- **Downstream effect:** `UsageUnrecorded` ⇒ Cost `CostUnavailable` (`22`), Governance budget/quota-indeterminate (`21`) — never a silent proceed on unknown usage. A missing usage fact is an **accounting incident** (surfaced), never a silent gap.

### §39 — Recovery behavior
- The Engine holds **no durable state** except the **frozen** ZL outbox/WAL (`07` EV-D3, not a Metering-private store); on restart the outbox **replays un-acknowledged facts** (at-least-once + idempotent ledger = effectively-once) — **no usage lost, no duplicate counted**. On outbox unavailability the Engine **fails closed** (fail-safe rejection, `07` EV-D3) — never proceeds with un-captured usage. Recovery never fabricates missing usage. Full normative contract: **§39.1**.

### §39.1 — Zero-Loss Durable Emission Contract *(resolves High UMEH-1 — additive; uses the frozen `07` EV-D3 WAL + `08` DA-D1 outbox, no new store)*

This is the **signed, testable contract** for durable, zero-loss usage emission. It uses the **already-frozen** WAL (`07` EV-D3) + transactional outbox (`08` DA-D1) mechanism — **no new store, no new consistency model.** It states **exactly what is guaranteed and what is NOT**, so **RPO=0** is honest, not overclaimed.

| # | Aspect | Contract |
|---|---|---|
| UC-1 | **Authoritative durability boundary** | A usage fact is **"durably recorded"** the instant it is **committed to the node-local WAL** (`07` EV-D3) — that commit, **not** the ledger acknowledgement, is the RPO=0 boundary. Before WAL commit the fact is *not yet* durable; after it, the fact is guaranteed to reach the ledger via replay (UC-6). |
| UC-2 | **WAL ownership** | The **node-local WAL is the frozen `07` EV-D3 mechanism** of the data-plane emission path (part of C5's data-plane presence, `06 §376` emission, not a Metering-private store). The Engine **writes to it**; it does not own a new store. |
| UC-3 | **Durable outbox ownership** | The **transactional outbox** (`08` DA-D1) that forwards WAL-committed facts to the C5-CP ledger is the frozen outbox mechanism; the **ledger is C5-CP's** (`06 §307`). The Engine emits into the outbox; the outbox/ledger own delivery/persistence. |
| UC-4 | **Ordering** | Facts are appended to the WAL in **causal order per request** `(idempotencyKey, attemptId, timestamp)`; the WAL preserves append order. The **ledger does not require strict global order** (accounting sums are commutative, §16); per-request closure is the manifest (UMEH-2). |
| UC-5 | **Durability before acknowledgement** | A request's usage is considered **accounted only after WAL commit (UC-1)**. The **response to the caller MAY return before ledger acknowledgement** (accounting completes durably behind it, §40) — but **only after the WAL commit**; a fact that has **not** WAL-committed is **never** treated as accounted, and the request path **fails closed** if WAL commit cannot occur (UC-9). |
| UC-6 | **Replay ownership** | On crash/restart, **replay is driven by the outbox/WAL machinery** (`07` EV-D3 / `08` DA-D1): un-acknowledged WAL entries are re-forwarded to the ledger. The Engine does not hand-roll replay; it relies on the frozen mechanism. |
| UC-7 | **Replay idempotency** | Replayed facts carry the **same identity** `(idempotencyKey, attemptId)`; the **ledger's idempotent consumer** (`07`) dedups them → re-forwarding is **safe** and produces **no duplicate count**. |
| UC-8 | **Duplicate suppression** | The **ledger suppresses duplicates** by `(idempotencyKey, attemptId)` (provider facts) and `idempotencyKey` (customer roll-up); the dedup key + retention window are pinned in **§14.1** (UMEM-2). At-least-once emission + idempotent ledger = **effectively-once**. |
| UC-9 | **Crash recovery** | A crash **before WAL commit** ⇒ the fact was not durable ⇒ the request path **failed closed** (UC-5), so no partial/silent accounting occurred (the un-committed fact is simply absent, and the request was surfaced as unaccounted). A crash **after WAL commit** ⇒ the fact **survives** and is replayed (UC-6/UC-7). There is **no window** where a WAL-committed fact is silently lost. |
| UC-10 | **Restart recovery** | On restart, the outbox **resumes forwarding** all WAL-committed-but-un-acknowledged facts before the instance resumes normal emission; no committed fact is skipped. |
| UC-11 | **Replay completion** | Replay is **complete** when every WAL-committed fact has a ledger acknowledgement; the **completeness manifest (UMEH-2)** cannot finalize a request until all its facts are ledger-acknowledged (or durably WAL-committed + queued), so a replay-in-progress request is not prematurely finalized. |
| UC-12 | **Fail-safe on saturation** | On WAL saturation/unavailability the Engine **fail-safe-rejects** (`07` EV-D3) — the request path surfaces `UsageUnrecorded` (fail closed) rather than proceed with un-captured usage. Backpressure never becomes silent loss. |

- **RPO = 0 (guaranteed):** once a fact **WAL-commits (UC-1)**, it is **never lost** — it survives crash/restart and reaches the ledger via replay (UC-6/UC-9/UC-10). No usage that was durably captured is ever dropped.
- **Guarantees that EXIST (testable):** (1) WAL-committed ⇒ eventually ledger-recorded exactly-once (UC-1/UC-6/UC-7); (2) crash before commit ⇒ request failed closed, no silent partial accounting (UC-9); (3) at-least-once + idempotent ledger = effectively-once (UC-7/UC-8); (4) saturation ⇒ fail-safe reject, never silent loss (UC-12).
- **Guarantees that DO NOT EXIST (explicit, no overclaim):** (1) **no synchronous ledger write on the hot path** — durability is at the **WAL** (UC-1), ledger acknowledgement is **asynchronous** (so a very recently-committed fact may be WAL-durable but not-yet-ledger-visible for a bounded window — it is *not lost*, only *not-yet-forwarded*); (2) **no strict global ordering** at the ledger (commutative sums, UC-4); (3) **no cross-region durability coordination** on the hot path (regional, AD-014). RPO=0 is at the **WAL commit**, honestly — not "instantly globally in the ledger."
- **Enforcement (UME-A17):** crash/replay + WAL-saturation tests (`15` T-046/T-018) assert: WAL-committed facts never lost across crash/restart; replay dedups exactly-once; crash-before-commit ⇒ fail-closed (no silent partial); saturation ⇒ fail-safe reject. **Build-Fail:** a fact treated as accounted before WAL commit; a best-effort (non-WAL) emission; a replay path that can duplicate a ledger-counted fact or drop a WAL-committed one.

---

## K. Performance & Concurrency

### §40 — Performance model
- Usage-fact production is **sub-millisecond CPU** (O(1) normalize + O(attempts) roll-up). Facts are captured to a **node-local WAL** (`07` EV-D3) and emitted **asynchronously** — **off the response-latency critical path** (the response returns; accounting completes durably behind it, RPO=0). Adds **near-zero** to the added-latency budget (`06 §11`). JMH benchmarks baseline the fact path (`15` T-026, `16 §J.2`).

### §41 — Memory model & baselines
- Per-request state in the transient `MeteringExecution` aggregate; usage-descriptor snapshots are **shared immutable** reads. Bounded WAL/outbox buffer (`07` EV-D3, fail-safe on saturation). No unbounded structures. Sized within container limits (`16` D-044, ZGC AD-023). **All numeric values** — WAL/outbox bounds, freshness/TTL, buffer/window sizes, telemetry cardinality-N — are **operational-baseline entries** (`16 §I.1`), never hard-coded.

### §42 — Concurrency & Virtual Threads
- Runs on **Virtual Threads** (AD-023): per-request fact production is a lightweight CPU task; **no shared mutable per-request state** (AD-021); the quota-counter increment uses the frozen bounded-staleness tier (`21 §28.1`, keyed by tenant); WAL append is lock-light/append-only. No blocking in `synchronized` on the hot path (`11` R-049). **Build-Fail:** shared mutable per-request state; a mutable usage fact; blocking-in-`synchronized` on the hot path.

---

## L. Security

### §43 — Security considerations
- **Credential-free / SDK-free / HTTP-free / store-free** (UME-D12).
- **Completeness/exactly-once (UME-INV):** usage never lost/duplicated/fabricated — the integrity basis of all cost/budget/billing.
- **Tenant isolation (AD-021):** per-tenant usage facts; quota counter keyed by (tenant, key); a tenant's usage never affects another's.
- **Immutable + tamper-evident:** append-only facts, Merkle-chained in the ledger (`08 §10`); no mutation surface.
- **No content in usage facts:** counts only — **no prompt/completion text**, no secrets (`13 §20`, `14 §7.1`).
- **No provider leak:** provider-neutral usage; provider identity internal-only, never customer-facing (`12 §16.10`, AD-007).
- **Enforcement:** secret/content-leak scanner (`14 §7.1`, `15` T-052); isolation tests (`15` T-044). **Build-Fail:** content/secret in a usage fact/event/log; cross-tenant usage access; a provider-native usage field exposed.

---

## M. Observability

### §44 — Posture
- Full instrumentation per `14`, **content-free**, **low-cardinality**: spans per fact/manifest (attempt class, usage class, versions), metrics (§45), structured logs (§46), events (§47). Two-tier (`14 §5.1`): operational counters → Plane A; high-cardinality usage detail → Plane B. Privacy (`14 §7.1`): never prompt/completion/secret.

### §45 — Metrics (SLI-bearing)
- `ume_usage_facts_total{attempt_class, usage_class}`, `ume_usage_unrecorded_total{reason}` — a **metering-integrity canary signal** (`16 §E.2`; a spike = usage/WAL pipeline health).
- `ume_completeness_gap_total` (should be 0 — a `RequestFinalized` completeness gap = Sev1), `ume_wal_saturation`, `ume_dedup_dropped_total` (duplicate emissions safely deduped), `ume_fact_latency`.
- Model/region/class labels **bounded** (top-N + `other`, Plane A; high-cardinality on Plane B); **no provider label** externally (provider-neutral, UME-D6); no content/secret labels.

### §46 — Tracing & Logging
- One span per fact/manifest; neutral attributes (attempt/usage class, versions, unit-count bucket) — never content/secret/raw tokens. Structured content-free logs. Correlation/causation ids propagated (`07 §6`, `14`).

### §47 — Events
- Off the hot path (async, AD-005), **Avro** via Apicurio (`07 §9`), standard envelope (`07 §6`), **ZL** for usage facts (RPO=0), **content-free**, within C5's **already-owned** event space (no new topic owner; `06 §23` — C5 emits usage/measured events + `RequestFinalized`, `06 §23.11`). Consumed by the C5 ledger (accounting), Cost Engine (`22`), Governance counters (`21`), Audit (C10), Observability (C9). **No provider-native events** (`12 §16.10`). Event evolution follows `16` D-033. **Build-Fail:** a provider-native event; content/raw-token in an event; a non-ZL usage-fact emission.

---

## N. Residency & Compliance

### §48 — Residency (see UME-D11)
- In-region metering; usage facts residency-confined (`14 §18.1`, `08` DA-D2, `16` D-051). **Build-Fail:** usage data crossing a residency boundary.

### §49 — Compliance
- Usage facts are **audited** (§35) as compliance/billing evidence (`13 §19`, `08 §10`); classified as tenant usage/financial data (`08`), handled per classification (no leak, residency-confined). The Engine enforces no compliance policy (that is C4, `21`); it operates within the already-governed request and confines usage data per residency/classification.

---

## O. Testing

### §50 — Testing strategy (Usage Metering Engine is a `15 §G.1`-class critical module)
- **Unit (JUnit5/Mockito):** normalization, attempt-class recording, manifest roll-up, idempotency keying, fail-closed paths.
- **Property-based (jqwik, `15` T-035):** *usage is never lost* (every attempt with units ⇒ a fact); *never duplicated* (idempotency-keyed dedup); *never fabricated/estimated-as-authoritative*; *provider usage = Σ attempts, customer usage = delivered exactly-once*; *identical execution facts ⇒ identical usage facts* (determinism).
- **Mutation (`15 §G.1`):** **≥ 85%** on the Engine; a mutant that loses, duplicates, fabricates, or drops usage must be killed.
- **Failure injection (`15` T-018):** missing usage, estimated usage, WAL saturation, partial emission, ambiguous delivered state, out-of-order facts ⇒ fail closed / buffer-reconcile, zero silent gap.
- **Exactly-once + completeness (`15` T-017/T-046):** delivered-vs-attempt; idempotency dedup; `RequestFinalized` completeness = 100% (loss detector = 0); crash/outbox replay = no loss + no duplicate.
- **Consistency/isolation (`15` T-027/T-044):** massive concurrency, no cross-tenant usage leakage; VT no-pinning (`15` T-028).
- **Provider-neutrality (`15` T-015):** no provider branch; adding a provider = new descriptor, zero Engine diff.
- **Residency (`15` T-045):** usage facts in-region.
- **Performance (`15` T-021/T-026, `16 §J.2`):** fact path off critical path; perf-smoke gate.

---

## P. Build-Failing Rules

### §51 — Build-failing architectural rules
| # | Rule | Gate |
|---|---|---|
| UME-A1 | No duplicated usage: customer roll-up counted once per idempotency key; ledger dedups per (key, attemptId) | property test (`15` T-017) |
| UME-A2 | No lost usage: ZL durable emission; crash/replay = no loss; best-effort emission forbidden | crash/replay test (`15` T-046) |
| UME-A3 | No mutable usage: facts append-only; corrections are compensating facts (no edit/delete) | ArchUnit |
| UME-A4 | No fabricated/estimated-as-authoritative usage; no silent-zero on missing usage (fail closed) | fault test (`15` T-018) |
| UME-A5 | No pricing/cost calculation in the Engine (records only; Cost Engine `22` prices) | ArchUnit |
| UME-A6 | No billing/invoice/payment/tax/ledger-persistence in the Engine | ArchUnit AU-07 |
| UME-A7 | No provider-name branch/switch or provider-native usage field (provider-neutral) | ArchUnit + `15` T-015 |
| UME-A8 | No wall-clock/random in the metering core; every fact stamps descriptor/source versions | ArchUnit (`11` R-063) |
| UME-A9 | Provider usage = Σ all attempts (no suppressed attempt); customer usage = delivered (not conflated) | property test (`15` T-017) |
| UME-A10 | No completeness gap: a finalized request accounts all its attempt/stream usage (`RequestFinalized`) | completeness test (`15` T-046) |
| UME-A11 | No credential/secret/API-key/provider SDK/direct HTTP | ArchUnit AU-06 + secret scanner (`13 §20`) |
| UME-A12 | No governance/quota-budget enforcement in the Engine (provides usage; C4 enforces) | ArchUnit |
| UME-A13 | No persistence / private store / database (stateless; ZL outbox is the frozen `07`/`08` mechanism) | ArchUnit AU-07 |
| UME-A14 | Usage data never crosses a residency boundary; no content/secret in facts/telemetry/events | `15` T-045/T-052, `14 §7.1` |
| UME-A15 | No hard-coded metering numeric; all thresholds from operational baseline (§41) | config lint |
| UME-A16 | Mutation score ≥ 85% (critical module) | PITest (`15 §G.1`) |
| UME-A17 | Zero-loss durable emission (§39.1): a fact treated as accounted before WAL commit; a best-effort (non-WAL) emission; a replay path that can duplicate a ledger-counted fact or drop a WAL-committed one | crash/replay + WAL-saturation test (`15` T-046/T-018) |
| UME-A18 | Completion / RequestFinalized (§17.1): a `RequestFinalized(complete)` before all authoritative facts are durably recorded; a complete finalization with a missing attempt/stream fact; a timeout that finalizes a gap as complete | completeness test (`15` T-046) |
| UME-A19 | Provider-vs-customer attribution (§28.1): a provider attempt with consumed units and no fact; more than one `delivered` per request; the Engine deciding the charging rule or pricing usage; a customer roll-up counted more than once | property/attribution test (`15` T-017/T-035) |

---

## Q. Operations

### §52 — Operational runbook
- **`ume_usage_unrecorded` spike:** usage/WAL pipeline health — the Engine correctly fails closed; **fix the source, never disable the fail-closed gate** (would risk usage loss, UME-INV). Cascades to Cost `CostUnavailable` + Governance budget-indeterminate — expected, protective.
- **`ume_completeness_gap` > 0 (any):** a **usage-loss / completeness violation** — Sev1 (a request's usage not fully accounted, RPO=0 breach); root-cause immediately; canary auto-aborts (`16 §E.2`).
- **`ume_wal_saturation` spike:** durable-emission backpressure — the Engine fail-safe-rejects (`07` EV-D3); scale/relieve the WAL, never drop usage.
- **Duplicate-dedup spike (`ume_dedup_dropped`):** expected under retry/replay (idempotent dedup working); investigate only if abnormal (points to a keying issue).

### §53 — Upgrade strategy
- Ships within the **data-plane release train** (`16` D-014/D-055): rolling/canary, zero-downtime (`16` D-031), metering-signal-gated (`16 §E.2`). In-flight WAL is drained/replayed across deploys (`16` D-031, `07` EV-D3) — **no usage lost**. Usage-descriptor changes are snapshot/config (C5-CP) — no Engine redeploy to change metered units.

### §54 — Backward compatibility
- The **usage-fact contract** (`UsageFact`, `RequestUsageManifest`, `UsageDescriptor` shape) is a stable internal API (`12 §27`): additive/backward-compatible within a major. Usage-descriptor schema evolves under versioned compat (`12 §27`). Emitted facts evolve under Avro compat (`07 §9`, `16` D-033). Deterministic core (§36) guarantees identical facts for identical recorded inputs across versions.

---

## R. Traceability

### §55 — Traceability matrix
| Metering concern | BR | NFR | ADR | Domain/Svc | API/Event | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|---|
| Record-only / UME-INV (UME-D1/§38) | BR-012 | NFR-AUD/COST | AD-016/018 | C5 (`06`) | — | §19 | §45 | T-018 | `16 §E.2` |
| Immutable facts (UME-D2/§12) | BR-024 | NFR-AUD-001 | AD-009 | C10/ledger | `08 §10` | §19 | §8 | T-046 | — |
| Exactly-once metering (UME-D3/§13) | BR-012 | NFR-AUD/RTY | AD-005 | C5 ledger | `07 §6` | — | §47 | T-017/T-035 | — |
| Zero-loss emission RPO=0 (UME-D4/§39) | BR-011/012 | NFR-AUD-001 | AD-005/009 | outbox/WAL (`07` EV-D3, `08` DA-D1) | `07 §6` | §19 | §8 | T-046 | — |
| Never fabricate/estimate (UME-D5/§24) | BR-012 | NFR-COST | AD-016 | C5 | — | §19 | §45 | T-018 | — |
| Provider-neutral usage (UME-D6/§8) | BR-006 | NFR-IF-001 | AD-007 | C5 | `12 §16.10` | — | — | T-015 | — |
| Attempt-class / provider-vs-customer (UME-D7/§18/§26–28) | BR-012 | NFR-COST | AD-005 | C5 (`22 §24.1`) | — | — | §45 | T-017 | — |
| Deterministic / replay (UME-D8/§36) | BR-004 | NFR-AUD | AD-016 | `11` R-063 | `12 §14` | — | — | T-035 | — |
| Completeness manifest (UME-D9/§17) | BR-011/024 | NFR-AUD-001 | AD-009 | `06 §23.11` | RequestFinalized | §19 | §8 | T-046 | — |
| Usage interfaces (UME-D10/§29–33) | BR-012 | NFR-COST/GOV | AD-002 | C5 ledger/C1?/C2/C4/`22` | `07 §6` | — | — | T-008 | — |
| Ledger interface (§29) | BR-012 | NFR-AUD-001 | AD-005/009 | C5 CP (`06 §307`) | `07 §6` | §19 | §8 | T-046 | — |
| Cost Engine interface (§30) | BR-012 | NFR-COST | AD-002 | C5 Cost (`22 §20`) | — | — | — | T-008 | — |
| Governance counters (§31) | BR-013 | NFR-GOV | AD-018/019 | C4 (`21 §28.1`) | — | §31 | — | T-031 | — |
| Reliability/Correctness facts (§32/§33) | BR-002/003 | NFR-STRM/RTY | AD-018 | C2/C3 (`20`/`18`/`17`) | — | — | §11 | T-012/T-017 | — |
| Streaming/partial/failed/cancelled (§19–25) | BR-002 | NFR-STRM-001 | AD-018 | C3 (`18 CV-5`) | — | — | §11 | T-012 | — |
| Regional / residency (UME-D11/§48) | BR-023 | NFR-MR | AD-014 | C5 | — | §15/§18 | §18.1 | T-045 | D-051 |
| Module in data plane, stateless (§7/§41) | BR-005 | NFR-PERF | AD-020/006 | `06 §10/§24` | — | — | — | T-051 | D-014 |
| Credential-free / ports (UME-D12/§7) | BR-019 | NFR-SEC | AD-002/012 | `05`/`11` | — | §20/§21 | — | T-008 | — |
| Testing (critical module) (§50/§51) | BR-012 | NFR-TEST-001 | AD-016 | — | — | §27 | §19 | §G.1/T-017/T-046 | §E.2 |
| Upgrade/back-compat (§53/§54) | BR-028 | NFR-UPG/VER | AD-015 | — | `12 §27` | — | — | T-049 | D-014/D-055 |

---

## S. Reviews

### 1. Internal Consistency Review
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-016/018 / UME-INV | correctness applied to usage; never lose/dup/fabricate | §1/§38 (fail-closed, complete, exactly-once) | ✅ |
| AD-005/`07` | effectively-once; ZL delivery | UME-D3/D4/§13 (idempotency-keyed, ZL outbox) | ✅ |
| AD-009 / `08 §16` | RPO=0 accounting | UME-D4/§39 (durable, no loss) | ✅ |
| AD-007 | provider neutrality | UME-D6 (neutral usage, no provider branch) | ✅ |
| AD-022 | cached snapshots | UME-D9/§9 (versioned usage descriptors) | ✅ |
| AD-021 | tenant isolation | §42/§43 (per-tenant facts; keyed counter) | ✅ |
| AD-014 | residency confinement | UME-D11/§48 (in-region metering) | ✅ |
| AD-020/AD-006 | data plane co-located stateless module | §7/§41 (no store; ZL outbox is frozen mechanism) | ✅ |
| AD-002 | hexagonal replaceable adapters | §7 ports | ✅ |
| `06` (C5 = Metering & Cost) | metering facet of C5 DP; ledger = C5 CP | §1/§4 (record module; CP owns ledger) | ✅ |
| `06 §307` | C5 ledger RPO=0 accounting | §29/§39 (emit facts; C5 reconciles) | ✅ |
| `06 §23`/§23.11 | C5 events; RequestFinalized completeness | §47/UME-D9 (owned space; no new owner) | ✅ |
| `06 §24`/§376 | store-per-service; shared tier | §7/§31 (no store; frozen quota tier) | ✅ |
| `07` EV-D3 / `08` DA-D1 | WAL/outbox zero-loss | UME-D4/§39 (frozen mechanism, no new store) | ✅ |
| `12 §14` | idempotency | UME-D3/§14 | ✅ |
| `12 §16.10` | no provider leak | §43/§45 (neutral usage, provider internal-only) | ✅ |
| `17 §24` / `18 CV-5` / `20 §24.1/§25` | guided retry; usage; attempts/delivered | §18/§25/§26–28 (facts consumed; delivered-vs-attempt) | ✅ |
| `21 §28.1` | quota/budget counters | §31 (usage increments; C4 enforces) | ✅ |
| `22 §20/§24.1` | Cost consumes usage; attribution | §30/§26–28 (Metering produces; Cost prices) | ✅ |
| `13`/`14`/`15`/`16` | security/observability/testing/deploy models | §43/§44/§50/§53 | ✅ |
| `07` EV-D3 / `08` DA-D1 / `06 §307` | WAL durability boundary; outbox; ledger RPO=0 | §39.1 UC-1…UC-12 (WAL-commit = RPO=0 boundary; frozen outbox/WAL; C5-CP ledger) | ✅ |
| `06 §23.11` / `08 §10` | RequestFinalized completeness; loss detector | §17.1 FC-1…FC-12 (finalize only after all facts durably recorded; gap-detectable) | ✅ |
| `20 §25` / `22 §24.1` | delivered outcome; cost attribution | §28.1 AT-1…AT-12 (provider = Σ attempts; customer = delivered; no ownership duplication) | ✅ |
| `07` / `12 §14` | idempotent consumer; dedup | §14.1 (dedup key `(idempotencyKey, attemptId)` / roll-up key; retention ≥ replay horizon) | ✅ |
| `21 §28.1` GC-2 | atomic compare-and-increment counter | §31 (idempotent increment key `(idempotencyKey, attemptId, counterKey)`; no double-increment) | ✅ |
| `08 §10` / `06 §307` | append-only ledger; reconciliation ownership | §12.1 (corrections = compensating facts only; C5-CP authorizes; never edit/delete) | ✅ |
| `16 §I.1` | operational baselines | §41.1 (all numerics), §14.1 dedup window, §17.1 FC-7 finalization timeout | ✅ |

**No contradictions found** against `00`–`22` and AD-001…AD-023. The corrective pass (§39.1, §17.1, §28.1, §14.1, §12.1, §31 idempotent key, §19–20 partial-stream rule, §41.1, UME-A17…A19) is **additive only**: **no new ADR, no new service, no new module, no new store** (§39.1 uses the frozen `07` EV-D3 WAL + `08` DA-D1 outbox; §31 uses the frozen `21 §28.1` shared tier), **no new consistency model** (reuses effectively-once + RPO=0 and bounded-staleness), **no ownership/event/data-ownership change, no responsibility moved, no consistency/security/deployment/observability/testing-model change, no provider coupling, and no invariant weakened** (UME-INV, exactly-once, RPO=0, provider-neutrality, C5 ownership are *reinforced*). The zero-loss contract (§39.1) **specifies** the WAL-commit durability boundary without a synchronous hot-path ledger write; the completion contract (§17.1) **forbids** premature finalization; the attribution contract (§28.1) **separates** provider usage (fact) from customer usage (upstream policy) without moving billing into the Engine.

### 2. Architecture Validation
- **Ownership:** record-only; ledger = C5-CP, pricing = C5 Cost Engine (`22`), billing = C8, quota/budget-enforcement = C4 — no ownership moved (UME-D1/§4). ✅
- **Boundaries/contexts:** metering facet of C5; produces usage facts consumed by ledger/Cost/Governance; no boundary crossed. ✅
- **Events:** emits within C5's owned event space (usage facts + `RequestFinalized`); no new topic owner (§47). ✅
- **Stores:** none; ZL emission uses the frozen outbox/WAL (`07` EV-D3, `08` DA-D1) — no new store. ✅
- **Consistency:** effectively-once + RPO=0 (AD-005/009); bounded-staleness quota counter (`21 §28.1` model, no new model). ✅
- **Provider neutrality:** neutral usage descriptors, no provider branch (UME-D6, AD-007). ✅
- **Deployment/security/testing:** data-plane release train (`16`), credential-free (`13`), critical-module testing (`15 §G.1`). ✅
- **Durability honesty (§39.1):** RPO=0 boundary is the **WAL commit** (UC-1), stated explicitly; ledger acknowledgement is asynchronous; what IS and is NOT guaranteed spelled out (no synchronous hot-path ledger write, no strict global order, no cross-region hot-path coordination) — no overclaim. ✅
- **Completion clarity (§17.1):** `RequestFinalized(complete)` only after every authoritative fact is durably recorded; timeout ⇒ INCOMPLETE (never silent complete); gap-detectable via `08 §10`; single terminal state. ✅
- **Attribution clarity (§28.1):** provider usage (fact, all attempts) ≠ customer usage (delivered, upstream policy); `delivered` is Reliability's outcome (`20 §25`) recorded not decided; Metering records / Cost prices / ledger stores / C8 bills — no ownership duplication. ✅
- Introduces **no new architecture decision**; every UME-Dx is an implementation choice inside frozen C5.

### 3. Adversarial Review — Independent Review Board (re-run after corrective pass)
*Board: Principal Enterprise Architect · Distributed Systems Architect · Data Platform Architect · FinOps Architect · Staff Reliability Engineer · Security Architect · Performance Engineer · Compliance Auditor · Enterprise SaaS Architect · CTO. Mandate: attack, not improve.*

#### Executive Summary
The three load-bearing seams are now signed, testable contracts: the **zero-loss durable-emission contract** (§39.1 UC-1…UC-12), the **completion / `RequestFinalized` contract** (§17.1 FC-1…FC-12), and the **provider-vs-customer attribution contract** (§28.1 AT-1…AT-12). The five medium policy gaps are pinned (§19–20 partial-stream rule, §14.1 dedup key/window, §31 idempotent increment key, §41.1 operational baselines, §12.1 corrections). The Usage Metering Engine remains disciplined, provider-neutral, record-only, and honest about its distributed limits — RPO=0 is at the **WAL commit**, not "instantly globally in the ledger," stated without overclaim. All accepted findings resolved additively with no architecture/ownership/model change.

#### Accepted findings — resolution
- **UMEH-1 → RESOLVED (§39.1).** Zero-Loss Durable Emission Contract UC-1…UC-12: authoritative durability boundary (**WAL commit = RPO=0**, UC-1), WAL ownership (frozen `07` EV-D3, UC-2), durable-outbox ownership (frozen `08` DA-D1 → C5-CP ledger, UC-3), ordering (causal per request; commutative at ledger, UC-4), durability-before-acknowledgement (response may return after WAL commit but never before; fail-closed if WAL commit cannot occur, UC-5), replay ownership/idempotency/duplicate-suppression (UC-6/7/8), crash + restart recovery (no window of silent loss, UC-9/10), replay completion (UC-11), fail-safe on saturation (UC-12). **RPO=0 stated honestly at the WAL commit**; what IS and is NOT guaranteed enumerated (no synchronous hot-path ledger write; no strict global order; no cross-region hot-path coordination) — no overclaim. Build-enforced (**UME-A17**).
- **UMEH-2 → RESOLVED (§17.1).** Completion / `RequestFinalized` Contract FC-1…FC-12: multi-attempt / streaming / late-fact / retry / hedged / cancelled / timeout completion, `RequestFinalized` + Completeness-Manifest ownership, gap detection (`08 §10` loss detector), terminal conditions, failure conditions. **`RequestFinalized(complete)` is emitted ONLY after every authoritative usage fact is durably WAL-committed — never before**; timeout ⇒ INCOMPLETE (never a silent complete); a fact after finalization is a detectable completeness violation. Build-enforced (**UME-A18**).
- **UMEH-3 → RESOLVED (§28.1).** Provider-vs-Customer Attribution Contract AT-1…AT-12: provider / customer / delivered / retry / failed / cancelled / hedged / guided-retry / streaming usage, ownership map, Cost-Engine interface, ledger interface. **Provider usage = Σ all attempt facts (never suppressed) ≠ customer usage = delivered (per upstream charging policy applied by Cost).** `delivered` is Reliability's outcome (`20 §25`) recorded not decided; Metering records / Cost prices / ledger stores / C8 bills — **no ownership duplication**. Build-enforced (**UME-A19**).
- **UMEM-1 → RESOLVED (§19–20):** partial/truncated-stream usage = exactly what StreamGuard authoritatively reports as consumed (no fabricated remainder, no dropped consumed portion); flagged-estimated ⇒ `usageClass=estimated` per §24; no reported usage ⇒ fail closed (never assume zero); the partial attempt is a provider-usage fact, never `delivered`.
- **UMEM-2 → RESOLVED (§14.1):** dedup key = `(idempotencyKey, attemptId)` (provider) / `idempotencyKey` (customer roll-up); retention window ≥ replay horizon (outbox/WAL bound + finalization timeout), an operational baseline; expiry safe because an expired key is beyond any possible replay. Dedup owned by the ledger's idempotent consumer (`07`); the Engine supplies stable keys.
- **UMEM-3 → RESOLVED (§31):** each quota/budget increment keyed by `(idempotencyKey, attemptId, counterKey)`; the counter's atomic compare-and-increment (`21 §28.1` GC-2) dedups → a replay never double-increments; counter authority/reconciliation remain C5/C4's.
- **UMEM-4 → RESOLVED (§41.1):** all numerics — WAL/outbox bounds, dedup window, finalization timeout, freshness/TTL, telemetry cardinality-N — are operational-baseline entries (`16 §I.1`); none hard-coded.
- **UMEM-5 → RESOLVED (§12.1):** a correction is ALWAYS a new referenced, idempotency-keyed, audited, immutable **compensating fact** — never an edit/delete; the authority to issue a correction is **C5-CP's** (reconciliation/dispute owner, `06 §307`); the Engine emits truthfully and never self-edits an emitted fact.

#### New findings from the re-run
- **🔴 Critical:** none.
- **🟠 High:** none.
- **🟡 Medium:** none blocking. (All numeric baselines are governed operational-baseline entries per `16 §I.1`; the C5 control-plane ledger dedup/retention + reconciliation authority, and the upstream delivered-vs-attempt charging-policy authoring, are cross-team deliverables referenced here and contract-/chaos-tested (`15` T-017/T-018/T-046) — noted as Low deferrals, not open design gaps.)
- **🟢 Low (deferred, non-blocking):**
  - **UMEL-1** — exact usage-fact / `RequestFinalized` event names/topics → reconcile with `06 §23/§23.11` registry.
  - **UMEL-2** — JMH benchmark set → `benchmarks/`.
  - **UMEL-3** — usage-descriptor DSL / snapshot format → C5 control-plane authoring standard.
  - **UMEL-4** — WAL/outbox data structures → detailed design.

#### Internal Contradictions
- **None.** Consistent internal use of fail-closed, exactly-once, RPO=0-at-WAL, provider-vs-customer, immutability (corrections are compensating facts, §12.1).

#### Cross-document Contradictions
- **None.** Consistent with `18 CV-5` (usage), `20 §24.1/§25` (attempts/delivered), `21 §28.1` (quota counters), `22 §20/§24.1` (Cost consumes usage; attribution), `06 §307/§23.11` (C5 ledger RPO=0, RequestFinalized), `07` EV-D3 (WAL), `08` DA-D1 (outbox), `08 §10` (loss detector), AD-005/009/014/021/022. The three former High items are now **signed, testable contracts**, not gaps.

#### Scores
- **Architecture Readiness: 97 / 100** — all three High and five Medium resolved additively; three load-bearing seams (durable emission, completion, attribution) now signed/testable; no new architecture/ownership/model change; UME-INV/exactly-once/RPO=0/neutrality/C5-ownership reinforced. Residual 3 points are cross-team-deliverable/calibration items (Low, deferred).
- **Documentation Health: 96 / 100** — contradictions/gaps closed; contracts honest about distributed limits (RPO=0 at the WAL commit, no overclaimed global durability).
- **Implementation Readiness: 94 / 100** — every load-bearing seam specified precisely enough to implement without inventing a durability/completeness/attribution guarantee the design cannot keep; dedup + quota-increment keys and corrections ownership decided.

### Architecture Readiness Score: **97 / 100**
All three High findings and all five Medium findings resolved additively, with **no new architecture, no new module/service/store, no ownership/event/data-ownership change, no provider coupling, and no invariant weakened** (UME-INV/exactly-once/RPO=0/never-fabricate/provider-neutrality/C5-ownership reinforced). The zero-loss durable-emission contract (§39.1), completion/`RequestFinalized` contract (§17.1), and provider-vs-customer attribution contract (§28.1) — the crux of metering correctness at scale — are now signed, testable, honest about their limits (RPO=0 at the WAL commit, no overclaimed global durability), and machine-enforceable (UME-A1…A19, critical-module mutation ≥85%). Residual 3 points are honest cross-team-deliverable/calibration items (Low, deferred).

### Documentation Health: **96 / 100** · Implementation Readiness: **94 / 100**
Contradictions/gaps closed; the durability/completion/attribution models state exactly what they guarantee and what they do not; every load-bearing seam (WAL durability boundary, finalization, provider-vs-customer split, dedup key/window, idempotent quota increment, corrections) is specified precisely enough to implement without inventing a durability/completeness/attribution guarantee the design cannot keep.

### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`22` or AD-001…AD-023; no new architecture; no ownership/module/service/boundary/event/store/consistency/security/deployment/observability/testing change; no provider coupling; no invariant weakened (UME-INV reinforced).

---

*End of document — 23-UsageMeteringEngine.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
