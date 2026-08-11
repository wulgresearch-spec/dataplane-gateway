# 22 — Cost Engine (Runtime Cost-Calculation Engine · Domain C5)

**Document:** Component Implementation Architecture — Cost Engine
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Version:** 1.0 (frozen 2026-07-21)
**Bounded context:** **C5 — Metering & Cost** (Core Domain, `05`/`06`)
**Module:** `dp-cost-engine` — the **runtime cost-calculation module** of C5's data-plane presence (a facet of the frozen C5 data-plane modules, `06 §137/206/459`), co-located per **AD-020/AD-006** — **not** a new service, **not** a new bounded context, **owns no store**.
**Audience:** Cost/FinOps/metering/data-plane engineers, SRE, security, compliance, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`21` and ADRs **AD-001…AD-023**. Everything here is **additive**. Introduces **no new architecture decision, no new service, no new module, no new store, no ownership/event/data-ownership change, no consistency/security/deployment/observability/testing-model change, and no provider coupling.** Every CE-Dx is a *component-internal implementation decision* inside the already-frozen C5, expressed through hexagonal ports (AD-002).

> **What this is.** The complete implementation architecture of the **Cost Engine** — the **authoritative runtime cost-calculation** component. For every request it computes the **normalized, never-underestimated cost** that downstream stages consume: **Governance** for budget admission (`21 GV-D6`), the **Router** as its soft cost objective (`19 §33.1`), and **Reliability** to gate hedging (`20 §24`). It **only calculates** — it does **not** charge customers, create invoices, process payments, or own billing/subscriptions/accounting/the finance ledger (those are C5's control-plane Metering & Cost Service and C8 Billing). Every major decision carries **Problem · Decision · Alternatives · Why selected · Trade-offs · Failure Modes · Security Impact · Performance Impact · Enforcement**.
>
> **THE COST-ENGINE INVARIANT (CE-INV):** *The Cost Engine must never expose an incorrect cost decision, never underestimate cost, never violate customer pricing rules / tenant budgets / negotiated pricing, never silently guess or fabricate prices, and never estimate when authoritative pricing is unavailable. On any uncertainty it fails closed and surfaces.* This is the cost-layer realization of **AD-016 (reliability first)** and **AD-018 (non-bypassable correctness)** applied to cost — a wrong cost is a correctness failure, and *cost is never optimistically rounded down*.

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Interfaces** (§6–7) · **C. Major Decisions** (CE-D1…CE-D12) · **D. Pricing Model** (§8–14) · **E. Pricing Precedence** (§15–19) · **F. Calculation** (§20–25) · **G. Accounting Boundaries** (§26–29) · **H. Interfaces** (§30–36) · **I. Determinism & Replay** (§37–38) · **J. Failure Handling** (§39) · **K. Performance & Concurrency** (§40–42) · **L. Security** (§43) · **M. Observability** (§44–47) · **N. Residency & Compliance** (§48–49) · **O. Testing** (§50) · **P. Build-Failing Rules** (§51) · **Q. Operations** (§52–54) · **R. Traceability** (§55) · **S. Reviews**

---

## A. Charter

### §1 — Purpose
The Cost Engine **calculates cost** — authoritatively, deterministically, and never-underestimated — from **versioned pricing snapshots** and **normalized usage**. It produces the **cost metadata** that Governance (budget admission), the Router (cost-aware routing), and Reliability (hedge cost-gating) consume, and it emits **usage/cost facts** to C5's authoritative ledger for accounting/reconciliation. It realizes C5's runtime cost-calculation responsibility (`05`/`06`) as a **stateless, provider-neutral, calculation-only** module.

### §2 — Scope
- **In scope:** runtime **token/model pricing**; provider-**neutral** pricing abstraction; **currency + usage normalization**; **budget/quota cost** contribution (calculation, not enforcement); **reserved-capacity / committed-use / enterprise / negotiated** pricing; **burst pricing**; **cost projections** (never-underestimated upper bounds for admission); **per-request** and **per-stream** cost; **estimated remaining budget** (calculation); **admission cost projection** for Governance; **cost metadata** for Router/Governance/Reliability; emitting **usage/cost facts** to C5's ledger.
- **Applies to:** every request on the hot path — a **projection** at admission (before invocation) and an **actual** cost after usage is known.

### §3 — Responsibilities
1. Resolve the applicable **pricing descriptor(s)** for the request (model, region, tenant contract) from **versioned pricing snapshots** (AD-022).
2. **Normalize** usage + currency into a canonical comparative unit.
3. Compute a **never-underestimated projection** (admission upper bound) and an **actual** cost (post-usage).
4. Apply the **pricing precedence hierarchy** (negotiated > enterprise > committed > list; regional; burst) deterministically.
5. Emit **cost metadata** to Router/Governance/Reliability and **usage/cost facts** to C5's ledger (effectively-once, §26).
6. **Fail closed** on unknown/expired/missing authoritative pricing (CE-INV).

### §4 — Non-Responsibilities (what the Cost Engine NEVER does)
- ❌ **charge customers · create invoices · process payments** — owned by **C8 Billing & Commerce**.
- ❌ **own billing / subscriptions / accounting / finance ledger** — owned by **C8** (billing) and **C5 control-plane Metering & Cost Service** (usage ledger, RPO=0, `06 §307`).
- ❌ **tax / GST / financial reports** — owned by **C8** (commerce/finance).
- ❌ **enforce budgets / quota** — owned by **Governance (C4)** (`21 GV-D5/D6`); the Cost Engine **provides the cost** Governance enforces against.
- ❌ **route / retry / validate correctness** — C1/C2/C3.
- ❌ **author pricing policy** — pricing is **authored** by the C5 control-plane (list pricing + negotiated contracts) + C8 (commercial terms); the Cost Engine **consumes** distributed pricing snapshots and **calculates**.
- ❌ **hold credentials / call providers / own network / persist** — credential-free, SDK-free, HTTP-free, store-free (CE-D12).

### §5 — What the Cost Engine is NOT
| Cost Engine (C5 — calculation) | Not the Cost Engine |
|---|---|
| ✓ calculate never-underestimated cost from versioned pricing | ✗ charge / invoice / take payment (C8) |
| ✓ produce cost metadata for Router/Governance/Reliability | ✗ enforce budget/quota (C4 Governance) |
| ✓ emit usage/cost facts to the ledger | ✗ own the usage ledger (C5 control plane) |
| ✓ apply negotiated/enterprise/committed pricing | ✗ author pricing policy (C5 CP / C8) |
| ✓ provider-neutral pricing descriptors | ✗ know providers / branch on provider names |

---

## B. Domain & Interfaces

### §6 — Domain model (cost subdomain)
Pure domain types (no infrastructure, `11` hexagonal); immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `CostRequest` | VO | Neutral request context: tenant scope, canonical model/capability id, region, correlation ids (`07 §6`), phase (`projection` \| `actual`) |
| `NormalizedUsage` | VO | Canonical usage (input/output/cached tokens, requests, units) — from providers via C1/StreamGuard (`18 CV-5`); **flags** missing/estimated usage, never fabricated |
| `PricingDescriptor` | VO (immutable, snapshot) | Canonical, **provider-neutral** price definition: unit rates (per input/output/cached token, per request), tiers, region, currency, effective window, `pricingClass` (list/enterprise/committed/negotiated/burst), version |
| `PricingSnapshot` | VO (immutable) | Versioned, TTL'd set of `PricingDescriptor`s + FX table, from the C5 pricing distribution (AD-022) |
| `FxRate` | VO | Authoritative currency conversion rate (versioned, in the snapshot) |
| `CostResult` | VO (immutable) | `{amount (canonical unit), currency, breakdown, pricingClass, pricingVersion, fxVersion, phase, confidence}` — or a **fail-closed `CostUnavailable`** |
| `CostProjection` | VO | Never-underestimated **upper-bound** cost for admission (§22) |
| `CostMetadata` | VO (immutable) | The read-only cost view handed to Router/Governance/Reliability (§30) |
| `PricingResolution` | VO | Resolved precedence outcome (which descriptor won, §15) |

**Aggregate:** `CostCalculation` — the transient per-request aggregate coordinating resolve-pricing → normalize → apply-precedence → compute; holds **no shared mutable state across requests** (AD-021).

### §7 — Public interfaces (ports — AD-002 hexagonal)
**Inbound (called by the data-plane pipeline — projection at admission, actual post-usage):**
```
CostEnginePort:
  project(CostRequest) -> CostProjection | CostUnavailable     // upper-bound for admission; fail-closed
  compute(CostRequest, NormalizedUsage) -> CostResult | CostUnavailable   // actual; fail-closed
```
**Outbound (all reads are cached snapshots; no hot-path network, no credentials, no provider SDK):**
```
PricingSnapshotPort   // <- C5 control-plane Metering & Cost Service: versioned pricing + FX (snapshot, AD-022)
ContractSnapshotPort  // <- C8 Billing / C5: negotiated/enterprise/committed terms (snapshot)
UsageSourcePort       // <- C1/StreamGuard: NormalizedUsage (18 CV-5), incl. missing/estimated flags
LedgerSinkPort        // -> C5 control-plane ledger: usage/cost facts (events, effectively-once, §26)
AuditSinkPort         // -> Audit (C10): cost-decision events (content-free)
MeteringSinkPort      // -> Metering (C5): cost counters (content-free)
TelemetryPort         // -> Observability (C9): cost metrics/traces (content-free)
ClockPort / IdPort    // deterministic time / ids
```
- **Enforcement:** ArchUnit — the Engine imports **no** provider SDK (AU-06), **no** HTTP client, **no** persistence driver (AU-07), **no** secrets/credential API, **no** billing/payment/invoice types. All inputs are **cached snapshots** + normalized usage; outputs are `CostResult`/`CostProjection`/facts. **Build-Fail:** any forbidden import; a hot-path network call; pricing authored (not consumed) in the Engine.

---

## C. Major Decisions

### CE-D1 — Calculation-only (never bill, invoice, charge, or ledger)
- **Problem:** cost calculation must not creep into billing/accounting ownership (C8 / C5-CP).
- **Decision:** the Cost Engine **only calculates** cost and **emits usage/cost facts**. It **never** charges, invoices, takes payment, computes tax, produces financial reports, or persists a ledger. Authoritative accounting/reconciliation/billing is **downstream** (C5 control-plane ledger + C8). The Engine's output is a **cost number + facts**, not a **charge**.
- **Alternatives:** fold billing into the Engine — rejected (ownership drift to C8/C5-CP; a new store); calculate cost inside the Router/Governance — rejected (duplication; C5 owns cost calculation, `19 §33.1`).
- **Why selected:** single, auditable, provider-neutral cost-calculation locus; clean C5/C8 separation.
- **Trade-offs:** the Engine depends on downstream reconciliation for authoritative billing (correct — that is C5-CP/C8's).
- **Failure Modes:** none from scope; a calculation failure ⇒ fail closed (§39).
- **Security Impact:** no payment/credential surface.
- **Performance Impact:** calculation is cheap (§40); no ledger I/O on the hot path.
- **Enforcement:** ArchUnit — no billing/invoice/payment/ledger types (CE-A9/A10). **Build-Fail:** the Engine charging/invoicing/persisting a ledger.

### CE-D2 — Provider-neutral pricing descriptors (no provider names, no provider pricing logic)
- **Problem:** pricing must stay provider-neutral (AD-007) despite wildly different provider pricing models.
- **Decision:** all pricing is expressed as **canonical, provider-neutral `PricingDescriptor`s** keyed by **canonical model/capability id** (`19 §9.2`), region, and pricing class — **never** by provider name. Provider-native pricing (per-token/per-request/tiered/cached-input discounts) is **mapped into descriptors upstream** (by the C5 pricing sources / adapters). The Engine **consumes descriptors only** and **never branches on provider identity** or provider-native pricing fields.
- **Alternatives:** per-provider pricing code — rejected (coupling, AD-007); provider SDK pricing APIs — rejected (network + coupling).
- **Why selected:** neutral, extensible (a new provider = a new descriptor, no Engine change), auditable.
- **Trade-offs:** requires a rich enough canonical descriptor model (governed, §8).
- **Failure Modes:** unmappable provider pricing ⇒ **no descriptor** ⇒ fail closed (never guessed).
- **Security Impact:** no provider identity/coupling.
- **Performance Impact:** O(1) descriptor lookup.
- **Enforcement:** ArchUnit — no provider-name reference (CE-A2). **Build-Fail:** a provider-name branch/switch or provider-native pricing field in the Engine.

### CE-D3 — Versioned pricing snapshots with TTL (authoritative, cached)
- **Problem:** cost must use authoritative pricing without hot-path network to a pricing service.
- **Decision:** pricing is a **versioned, TTL'd snapshot** (AD-022) authored/distributed by the **C5 control-plane Metering & Cost Service** (list pricing + FX) + **C8** (contracts). The Engine consumes the **current versioned snapshot** locally; every `CostResult` stamps the **pricing + FX versions** used (reproducibility, §37). Snapshots are **immutable**; a pricing change is a **new version** (atomic swap), never in-place mutation.
- **Alternatives:** live pricing API per request — rejected (latency, coupling); hardcoded prices — rejected (stale, un-versioned, CE-INV violation).
- **Why selected:** authoritative + fast + reproducible + neutral.
- **Trade-offs:** snapshot freshness bounds pricing currency (§13, fail-closed beyond TTL).
- **Failure Modes:** missing/expired snapshot ⇒ fail closed (§39).
- **Security Impact:** no pricing network surface.
- **Performance Impact:** O(1) local read.
- **Enforcement:** version stamped; snapshot immutable. **Build-Fail:** a hardcoded price; a mutable pricing structure; a pricing read that is not versioned (CE-A3/A5/A10).

### CE-D4 — Fail-closed on unknown / expired / missing authoritative pricing
- **Problem:** cost calculation without authoritative pricing is a guess — forbidden (CE-INV).
- **Decision:** if the authoritative pricing descriptor (or required FX rate) is **unknown, absent, or expired beyond TTL**, the Engine **fails closed** — returns `CostUnavailable` (surfaced; e.g. Governance denies admission `POLICY_UNAVAILABLE`/`BUDGET_INDETERMINATE`) — and **never** fabricates, guesses, interpolates, or estimates a missing price. "No authoritative price" is a **surfaced error**, never a silently-assumed number.
- **Alternatives:** fall back to a default/last-seen price — rejected (guessing; CE-INV); estimate from similar models — rejected (fabrication).
- **Why selected:** the core CE-INV guarantee — never wrong, never guessed.
- **Trade-offs:** a pricing-snapshot gap can **block** requests (availability cost) — deliberate (correct cost over availability, §13/§39).
- **Failure Modes:** unknown/expired ⇒ `CostUnavailable` (fail closed).
- **Security Impact:** prevents cost-manipulation via missing-price fallback.
- **Performance Impact:** O(1) check.
- **Enforcement:** fault tests (`15` T-018). **Build-Fail:** any fallback/default/interpolated/guessed price; calculation on an expired snapshot (CE-A4/A6).

### CE-D5 — Never-underestimate: conservative upper-bound projections
- **Problem:** admission cost validation runs **before** usage is known (output length unknown); an optimistic projection could admit an over-budget request.
- **Decision:** a **projection** (admission phase) is a **never-underestimated upper bound** computed from **authoritative pricing** and the request's **maximum possible usage** (e.g. `max_output_tokens`, max tool rounds) — a bounded calculation from authoritative prices, **not** a guess. Projections **always round in the direction of higher cost** (conservative), so Governance's budget check (`21 GV-D6`) can never admit an over-budget request due to under-projection. The **actual** cost (post-usage) is computed exactly from `NormalizedUsage`. Projection ≥ actual, always.
- **Alternatives:** projection = expected/average cost — rejected (can underestimate → over-budget admission); no projection — rejected (Governance can't admission-check budget).
- **Why selected:** never-underestimate (CE-INV) at admission, exact after usage.
- **Trade-offs:** conservative projection may **over-reserve** budget (a request admitted with headroom to spare) — corrected at actual (§29); intended over availability-vs-correctness.
- **Failure Modes:** unknown max-usage bound ⇒ fail closed (can't upper-bound safely).
- **Security Impact:** prevents budget-bypass via under-projection.
- **Performance Impact:** O(1) from authoritative pricing.
- **Enforcement:** property test: projection ≥ actual for all inputs (`15` T-035). **Build-Fail:** a projection that can underestimate actual cost (CE-A7).

### CE-D6 — Currency & usage normalization to a canonical comparative unit
- **Problem:** providers price in different currencies/units; comparison + budgets need one canonical unit.
- **Decision:** all cost is normalized to a **single canonical comparative unit** using **authoritative FX rates** (versioned, in the pricing snapshot) and **canonical usage normalization** (`18 CV-5`). Currency conversion uses **only** authoritative FX; a **missing/expired FX rate ⇒ fail closed** (never guess FX). For **projections**, FX uses a **conservative (never-underestimating) rate** within the authoritative rate's validity (§17). Usage normalization consumes `NormalizedUsage` (input/output/cached tokens, requests); **missing/estimated usage is flagged** (`18 CV-5`) and handled per §24 (never fabricated).
- **Alternatives:** live FX API — rejected (network/coupling); fixed FX — rejected (stale/wrong, CE-INV).
- **Why selected:** one comparable unit; authoritative FX; never-underestimate on conversion.
- **Trade-offs:** FX freshness bounds accuracy (fail-closed beyond validity).
- **Failure Modes:** missing/expired FX ⇒ fail closed.
- **Security Impact:** prevents cost manipulation via FX guessing.
- **Performance Impact:** O(1) conversion.
- **Enforcement:** FX fault tests. **Build-Fail:** a guessed/fixed FX rate; conversion on an expired FX version.

### CE-D7 — Enterprise / negotiated / committed-use pricing precedence
- **Problem:** a tenant may have list, enterprise-override, committed-use-discount, and negotiated-contract pricing simultaneously — resolution must be total and deterministic.
- **Decision:** a **strict, deterministic precedence** resolves which price applies (§15):
  ```
  1. Negotiated contract price   (explicit per-tenant contract — highest)
  2. Enterprise override price   (enterprise agreement)
  3. Committed-use discount      (committed capacity / reserved)
  4. Burst / on-demand price     (above committed tier)
  5. List price                  (default — lowest precedence)
  ```
  The **highest-precedence applicable** descriptor wins; ties are impossible (each class is distinct per tenant/model/region/window). Committed-use is applied up to the committed quantity, then burst/on-demand for the excess (§18). Contracts/overrides come from the **C8/C5 contract snapshot** (authored upstream); the Engine **applies**, never negotiates.
- **Alternatives:** cheapest-wins — rejected (ignores contractual precedence); ambiguous overlap — rejected (non-deterministic).
- **Why selected:** honors contracts deterministically; auditable resolution.
- **Trade-offs:** requires accurate contract snapshots (governed, C8/C5).
- **Failure Modes:** ambiguous/missing contract for a claimed class ⇒ fail closed (never default to list silently when a contract is expected-but-unresolved).
- **Security Impact:** prevents pricing-rule violation (CE-INV).
- **Performance Impact:** O(applicable classes), bounded.
- **Enforcement:** precedence property tests (`15` T-035). **Build-Fail:** a non-deterministic precedence; a list-price fallback when a negotiated contract is expected-but-unresolved.

### CE-D8 — Deterministic cost calculation (replayable)
- **Problem:** cost must be reproducible for audit/dispute/replay.
- **Decision:** calculation is a **pure, deterministic function** of `(CostRequest, NormalizedUsage, pricing snapshot version, FX version, contract snapshot version, injected clock)` — **no wall-clock/random** in the core (`11` R-063). Given identical inputs (with recorded versions), the **same `CostResult`** results every time. Every result stamps the **snapshot/FX/contract versions** used → **exact replay** from recorded versions.
- **Alternatives:** nondeterministic rounding/time-based pricing without recorded versions — rejected (un-auditable, dispute-prone).
- **Why selected:** auditable/replayable cost; enables property/mutation testing.
- **Trade-offs:** determinism is over **recorded inputs** (pricing changes over time) — reproducible **given the recorded versions** (honest, §37).
- **Failure Modes:** none from determinism.
- **Security Impact:** cache-friendly; enables strong testing.
- **Enforcement:** ArchUnit forbids wall-clock/random in the core; replay tests (`15` T-035). **Build-Fail:** wall-clock/random in the cost core; unstamped pricing/FX versions (CE-A8).

### CE-D9 — Effectively-once accounting boundaries (delivered-vs-attempt)
- **Problem:** retries/hedges (`20`) invoke providers multiple times; cost accounting must be exactly-once for what is **charged** and honest about **attempts**.
- **Decision:** cost facts follow the frozen **effectively-once** model (`07`/AD-005, `20 §25`): the Engine emits **usage/cost facts** keyed by an **idempotency key** (`12 §14`) so the C5 ledger reconciles **exactly-once**; it distinguishes **delivered cost** (the winning attempt) from **attempt cost** (retry/hedge/failover losers). **Whether a customer is charged for failed attempts is a pricing-policy decision authored upstream (C5/C8)** — the Engine **applies** the policy's rule (bill delivered-only, or bill all attempts) as expressed in the pricing descriptor; it **does not invent** the retry-charging policy. **Provider-side cost** (what the platform pays, which includes all attempts) is always emitted as a **fact** so the ledger reconciles true spend; **customer-side cost** follows the applied pricing rule. Never double-count a delivered charge; never under-report provider spend.
- **Alternatives:** count every attempt as a customer charge by default — rejected (policy is upstream's, not the Engine's to assume); count delivered-only for provider spend — rejected (under-reports true spend).
- **Why selected:** exactly-once customer accounting + honest provider-spend facts; policy stays upstream.
- **Trade-offs:** the retry-charging **policy** must be pinned upstream (C5/C8) — adversarially flagged.
- **Failure Modes:** ambiguous delivered/attempt state ⇒ fail closed (do not emit a possibly-double charge).
- **Security Impact:** prevents double-charge / spend under-report.
- **Performance Impact:** idempotency-keyed emission, O(1).
- **Enforcement:** effectively-once tests (`15` T-017/T-035). **Build-Fail:** a customer charge emitted more than once per idempotency key; provider spend under-reported vs attempts (CE-A11).

### CE-D10 — Cost metadata interfaces (read-only, no downstream cost computation)
- **Problem:** Router/Governance/Reliability need cost, but must not compute it themselves (duplication/drift, `19 §33.1`).
- **Decision:** the Engine produces a **read-only, immutable `CostMetadata`** (normalized cost/projection + pricing versions + confidence) consumed by the **Router** (soft cost objective, `19 §33.1`), **Governance** (budget admission, `21 GV-D6`), and **Reliability** (hedge cost-gate, `20 §24`). **No downstream stage computes cost** — they consume the Engine's numbers. Cost metadata is **provider-neutral** and **content-free**.
- **Alternatives:** each stage computes cost — rejected (duplication/drift, `19 §33.1`).
- **Why selected:** single cost-calculation authority; consistent numbers everywhere.
- **Trade-offs:** the metadata contract must be stable (`12 §27`).
- **Failure Modes:** `CostUnavailable` ⇒ downstream fail-closed (Governance denies).
- **Security Impact:** neutral, content-free.
- **Performance Impact:** O(1) read downstream.
- **Enforcement:** ArchUnit — no cost computation in Router/Governance/Reliability (they consume). **Build-Fail:** a downstream stage computing cost/pricing (cross-checked with `19`/`20`/`21`).

### CE-D11 — Regional pricing + residency-aware cost
- **Problem:** prices differ by region; cost data carries tenant/usage context that must respect residency (AD-014).
- **Decision:** pricing is **region-keyed** (a `PricingDescriptor` is scoped by region); the Engine resolves the descriptor for the request's **resolved region** (from the governance/residency context, `21 §36.1`). Cost **calculation happens in-region** (the data plane is regional, `16` D-009); cost data/metadata is **residency-confined** (never shipped cross-region beyond id-only correlation, `14 §18.1`, `08` DA-D2). A region with **no authoritative price** ⇒ fail closed (§39).
- **Alternatives:** single global price — rejected (wrong for regional pricing); cross-region price lookup — rejected (residency/latency).
- **Why selected:** correct regional pricing; residency-safe.
- **Trade-offs:** regional pricing snapshots per region (bounded).
- **Failure Modes:** no regional price ⇒ fail closed.
- **Security/compliance impact:** residency confinement of cost data.
- **Performance Impact:** O(1) regional lookup.
- **Enforcement:** residency tests (`15` T-045). **Build-Fail:** cost data crossing a residency boundary; a cross-region price lookup on the hot path.

### CE-D12 — Security (credential-free, SDK-free, HTTP-free, store-free)
- **Problem:** the Engine sits on the hot path with tenant/usage context and must present zero credential/SDK/network/storage surface.
- **Decision:** the Engine holds **no secrets, no credentials, no provider SDK, makes no direct HTTP, and owns no store**. All inputs are **cached snapshots + normalized usage via ports**; outputs are cost numbers + facts. Pricing sources/credentials live upstream (C5-CP/C8); the Engine only reads distributed snapshots.
- **Alternatives:** Engine fetching live pricing with credentials — rejected (credential/network surface, ownership drift).
- **Why selected:** minimal attack surface; pure calculation.
- **Trade-offs:** none material.
- **Failure Modes:** n/a (no credential/network path).
- **Security Impact:** eliminates credential/payment surface from the cost layer.
- **Performance Impact:** neutral.
- **Enforcement:** ArchUnit — no secrets/HTTP/SDK/persistence (CE-A9/A10/A13). **Build-Fail:** any credential/HTTP/SDK/store reference in the Engine.

---

## D. Pricing Model

### §8 — Pricing descriptors
- A **`PricingDescriptor`** is canonical + provider-neutral (CE-D2): `{canonicalModelId, region, currency, pricingClass, unitRates{inputToken, outputToken, cachedToken, perRequest, …}, tiers[], effectiveWindow, version}`. It expresses **any** provider pricing shape (per-token, per-request, tiered, cached-input-discount) in neutral terms. The Engine reads descriptors only; provider→descriptor mapping is **upstream** (C5 pricing sources).

### §9 — Pricing snapshots & §10 — versioned pricing
- Descriptors + FX are bundled into a **versioned, immutable `PricingSnapshot`** (AD-022), distributed by the C5 control plane. Each snapshot has a **version**; every decision stamps the version used (§37). Updates are **atomic** (one internally-consistent version per decision, never half-applied).

### §11 — Pricing TTL
- Each snapshot carries a **TTL / `validUntil`** (operational baseline, `16 §I.1`). Within TTL, pricing is trusted (AD-022 last-known-good); **beyond TTL**, pricing is **untrusted ⇒ fail closed** (CE-D4/§39) — never calculate on expired pricing.

### §12 — Currency conversion
- Uses **authoritative FX** (versioned, in the snapshot, §CE-D6). Missing/expired FX ⇒ fail closed; projections use a **conservative** rate (never-underestimating, §17).

### §13 — Snapshot freshness (fail-closed reconciliation with AD-022)
- Pricing/FX are **hard-correctness inputs** (unlike soft health/latency): within the freshness window, **last-known-good** (AD-022); **beyond tolerance, fail closed** (surface `CostUnavailable`). This mirrors `19 §17`/`21 §28.1` for hard-constraint inputs — **correct cost over availability** (CE-INV). Tolerances are operational-baseline entries (`16 §I.1`, §41-baseline). Full normative contract: **§13.1**.

### §13.1 — Pricing Snapshot Contract *(resolves High CEH-1 — additive; consumes the frozen AD-022 snapshot mechanism, no new store, no ownership change)*

This is the **signed, testable contract** for pricing-snapshot consumption. It uses the **already-frozen AD-022 cached-snapshot mechanism** distributed by the **C5 control plane** — **no new store, no new service, no ownership move.** **The Cost Engine NEVER refreshes pricing; it ONLY consumes immutable pricing snapshots; snapshot refresh belongs to the C5 Control Plane.**

| # | Aspect | Contract |
|---|---|---|
| PSC-1 | **Authoritative pricing snapshot** | The single source of pricing truth is the **versioned, immutable `PricingSnapshot`** authored + distributed by the **C5 control-plane Metering & Cost Service** (AD-022). The Engine holds no other pricing source. |
| PSC-2 | **Snapshot version** | Every snapshot carries a monotonic **version**; every `CostResult`/`CostProjection` stamps the version used (reproducibility, §37/CEM-5). |
| PSC-3 | **Snapshot timestamp** | Each snapshot records its **producer timestamp** (when the C5 CP produced it) — the basis for freshness (PSC-8). |
| PSC-4 | **`validUntil` / TTL** | Each snapshot carries a **`validUntil`/TTL** (operational-baseline value, `16 §I.1`). Within `validUntil`, pricing is trusted (last-known-good, AD-022); at/after `validUntil` it is **untrusted**. |
| PSC-5 | **Freshness ownership** | The **freshness policy (TTL, propagation window) is owned by the C5 control plane** (`16 §I.1`); the Engine **enforces** it (fail closed beyond TTL), it does not set it. |
| PSC-6 | **Snapshot producer** | **C5 control plane** — produces, versions, signs (integrity), and distributes snapshots (AD-022). The Engine never produces a snapshot. |
| PSC-7 | **Snapshot consumer** | The **Cost Engine consumes** the current snapshot read-only via `PricingSnapshotPort`. It never mutates, edits, merges, or refreshes it. |
| PSC-8 | **Stale handling** | Within `validUntil`: use **last-known-good** (AD-022). **Beyond `validUntil` (stale):** the snapshot is **untrusted ⇒ fail closed** (`CostUnavailable`, reason `PRICING_STALE`). Staleness can only make the Engine **fail closed**, never guess. |
| PSC-9 | **Missing-snapshot handling** | If **no** pricing snapshot is available (cold start, distribution outage): **fail closed** (`CostUnavailable`, reason `PRICING_MISSING`) — never a default/last-known-forever/guessed price. |
| PSC-10 | **TTL lifecycle** | A snapshot is **valid** from its timestamp until `validUntil`; a **new version** supersedes it atomically (one internally-consistent version per calculation, never half-applied); a request pins the version at its start (§14, so mid-flight pricing changes don't retroactively alter an in-progress calculation). |
| PSC-11 | **Refresh ownership** | **The Cost Engine NEVER refreshes pricing.** Snapshot refresh/propagation is **owned by the C5 control plane** (AD-022 distribution). The Engine only observes the newest propagated version. |
| PSC-12 | **Fail-closed guarantee** | Any pricing uncertainty (stale, missing, unknown descriptor, unverifiable) ⇒ **`CostUnavailable` (fail closed)** — the Engine never emits a cost from non-authoritative pricing (CE-INV). |

- **Downstream guarantees (normative):** because the Engine fails closed on stale/missing pricing, **Governance never receives guessed pricing** (a `CostUnavailable` ⇒ Governance denies admission `BUDGET_INDETERMINATE`, `21 GV-D6`); **the Router never scores from stale pricing** (a `CostUnavailable` candidate is treated as cost-unknown, not preferred, `19 §33.1`); **Reliability never hedges from stale pricing** (`CostUnavailable` ⇒ no hedge, `20 §24`). No downstream stage ever acts on a non-authoritative cost.
- **Guarantees that EXIST:** versioned, immutable, authoritative pricing within TTL; fail-closed beyond TTL; version-stamped reproducibility. **Guarantees that DO NOT EXIST (honest):** no *live* pricing (pricing is as fresh as the last propagated snapshot within TTL); no cross-region pricing coordination on the hot path (regional, CE-D11); the deliberate **deny-vs-availability trade-off** (a pricing-distribution outage cascades to admission denials) is stated, chosen for correctness over availability (CE-INV). *(Soft, non-budget-critical cost metadata does not exist as a separate class — all cost the Engine emits is authoritative-or-`CostUnavailable`; there is no "degraded cost" mode, by CE-INV.)*
- **Enforcement (CE-A18):** contract tests (`15` T-018) + chaos (`15` T-019) assert: fail-closed on stale/missing pricing; version-stamped results; the Engine never refreshes/produces a snapshot; downstream never receives a guessed/stale cost. **Build-Fail:** the Engine refreshing/producing/mutating a pricing snapshot; a calculation on a stale (beyond-`validUntil`) or missing snapshot; a cost emitted without a stamped snapshot version.

### §14 — Authoritative / unknown / expired pricing & provider pricing changes
- **Authoritative:** the current versioned snapshot is the single source of truth (C5-CP-authored).
- **Unknown** (no descriptor for model/region/class): **fail closed** — never guess (CE-D4).
- **Expired** (beyond TTL): **fail closed** (§11).
- **Provider pricing changes:** a provider changing its prices is handled **upstream** — the C5 pricing source updates the descriptor → a **new snapshot version** propagates (AD-022, bounded window, §41). The Engine is **unchanged**; it simply uses the new version once propagated. A pricing change **never** requires an Engine code change (CE-D2/neutrality). Mid-request pricing changes: a request uses the snapshot version **pinned at its start** (recorded), so a mid-flight pricing change does not retroactively alter an in-progress calculation (deterministic, §37).

---

## E. Pricing Precedence

### §15 — Precedence resolution (see CE-D7)
- Deterministic: negotiated > enterprise > committed > burst > list (§CE-D7). The resolved descriptor + class are recorded in `PricingResolution` (audit/replay). Ambiguity or an expected-but-unresolved contract ⇒ fail closed (never silent list-price fallback).

### §16 — Negotiated contracts & §17 — enterprise overrides
- **Negotiated contracts** (per-tenant explicit pricing) and **enterprise overrides** (agreement-level) come from the **C8/C5 contract snapshot** (authored upstream). The Engine **applies** them at their precedence; it never negotiates, computes discounts from scratch, or infers a contract. **Conservative FX for projections:** where a contract is in a foreign currency, projection uses the **most-expensive** authoritative rate within validity (never-underestimate).

### §16.1 — Currency Normalization Contract *(resolves High CEH-3 — additive; authoritative FX only, no live lookup)*

This is the **signed, testable contract** for currency handling. **No live FX lookup. No runtime exchange-rate download.** FX comes **only** from the authoritative, immutable, versioned FX table inside the pricing snapshot (§13.1). Unknown FX ⇒ **fail closed**.

| # | Aspect | Contract |
|---|---|---|
| FX-1 | **Authoritative FX source** | The **only** FX source is the **versioned FX table inside the `PricingSnapshot`** (authored/distributed by the C5 control plane, PSC-1). The Engine performs **no live FX lookup and downloads no exchange rate** at runtime. |
| FX-2 | **Snapshot version** | Every conversion stamps the **FX table version** used (part of the recorded versions, §37/CEM-5) for exact replay. |
| FX-3 | **Regional currencies** | Regional pricing (CE-D11) may be denominated in regional currencies; the descriptor's currency is authoritative for that region. |
| FX-4 | **Pricing currency** | Cost is first computed in the **descriptor's pricing currency**, then normalized to the **canonical comparative unit** via the authoritative FX table (FX-1). |
| FX-5 | **Billing currency** | Any conversion to a **customer billing currency** is a downstream **C8 billing** concern (from the reconciled facts); the Engine emits cost in the **canonical unit + pricing currency**, not a customer bill (CE-D1). |
| FX-6 | **Conversion ownership** | The Engine **applies** authoritative FX to normalize; it **never sets, derives, or fetches** an exchange rate. FX authoring/distribution is C5 control plane. |
| FX-7 | **Stale FX** | An FX table **beyond `validUntil`** (PSC-4) is **untrusted ⇒ fail closed** (`CostUnavailable`, reason `FX_STALE`) — never convert on a stale rate. |
| FX-8 | **Missing FX** | If the required FX pair is **absent** from the authoritative table ⇒ **fail closed** (`CostUnavailable`, reason `FX_MISSING`) — never guess/interpolate a rate. |
| FX-9 | **Conservative conversion** | For **projections** (never-underestimate, CE-D5): where an FX rate has a validity range/band, projection uses the **most-cost-increasing (worst-case) authoritative rate within the table's stated validity** — never the mid or best rate — so a projection can never underestimate due to FX. **Conservative rounding** is applied in the cost-increasing direction for projections. For **actual** cost, the single authoritative rate for the recorded FX version is used (deterministic). |
| FX-10 | **Replay** | Cost replay (§38) uses the **historical FX table version** recorded on the original decision — a dispute is reproduced against the FX rate that was authoritative at calculation time, not a current rate. |

- **Rules (normative):** **no live FX lookup; no runtime rate download; FX tables are immutable/versioned; unknown/stale FX ⇒ FAIL CLOSED; conservative (cost-increasing) rounding for projections; replay uses the historical snapshot/FX version.**
- **Enforcement (CE-A20):** FX fault tests (`15` T-018) + property test (projection FX never underestimates) assert: no live FX path; fail-closed on stale/missing FX; conservative projection FX; replay reproduces from the recorded FX version. **Build-Fail:** a live FX lookup / runtime rate download; a guessed/interpolated/mid FX rate in a projection; a conversion on a stale/missing FX pair; an FX conversion without a stamped FX version.

### §18 — Committed-use discounts & reserved capacity
- **Committed-use / reserved capacity:** priced at the committed rate **up to the committed quantity** (tracked as usage against the commitment — the quantity state is a **bounded-staleness counter** in the shared tier like quota, `21 §28.1`, owned-accounting by C5), then **burst/on-demand** for the excess (§19). The Engine **applies** the commitment tiers; the **authoritative commitment ledger is C5's**.
- **Fail-safe direction (resolves Medium CEM-3, normative):** the commitment-usage counter is **bounded-staleness** (`21 §28.1` model, no new model). On staleness/uncertainty the Engine errs **toward the higher-cost outcome (never-underestimate, CE-INV)**: a stale/unknown counter is treated as **committed capacity exhausted** → apply **burst/on-demand** (the more expensive tier), **never** apply the cheaper committed rate above the commitment. A stale counter can therefore only **over-cost** (safe — reconciled down by C5, §29), **never under-cost**. Applying the committed rate above the commitment (under-charge, CE-INV violation) or burst below it (over-charge of a paid-for committed tier) is forbidden. **Build-Fail (extends CE-A11):** applying the committed rate above the commitment on a stale/uncertain counter (under-cost); a commitment-tier boundary that can underestimate on staleness.

### §19 — Burst pricing
- Usage **above** committed capacity is priced at the **burst/on-demand** descriptor. The tier boundary is deterministic given the recorded commitment-usage value; burst is never applied *below* the commitment (would over-charge) nor committed applied *above* it (would under-charge — CE-INV).

---

## F. Calculation

### §20 — Usage normalization
- Consumes `NormalizedUsage` (`18 CV-5`) — canonical input/output/cached tokens, requests. **Missing/estimated usage is flagged** (never fabricated, `18 CV-5`); handling per §24.

### §21 — Per-request cost
- `actual = Σ (unitRate × normalizedUnits)` over the resolved descriptor (with tiers/commitment/burst), converted to the canonical unit via authoritative FX. Deterministic (§CE-D8). Records breakdown + versions.

### §22 — Per-stream cost & §23 — projections (never-underestimate)
- **Per-stream:** streaming output cost accrues as tokens are produced; the **actual** stream cost is computed at stream completion from the final `NormalizedUsage` (StreamGuard delivers final usage, `18`). A running estimate MAY be tracked but the **authoritative** stream cost is the completion figure.
- **Projection (admission):** a **never-underestimated upper bound** = resolved-price × **maximum possible usage** (`max_output_tokens`, max tool rounds, worst-case tier) × **conservative FX** (§CE-D5/§16.1 FX-9). Projection ≥ actual always.

### §23.1 — Projection defaults for unknown/unbounded usage *(resolves Medium CEM-1 — additive; upper-bound only, never underestimate)*
- **Rule:** a projection is **always an upper bound**; it is **never** an average/expected estimate. When the request-declared usage bound is **known** (`max_output_tokens` set, bounded tool rounds), the projection uses that bound × worst-case tier × conservative FX (§16.1 FX-9).
- **Unknown/unbounded output:** when no request-declared bound exists, the Engine uses, **in this order**, the **most restrictive authoritative bound available** so it can still upper-bound safely: (1) the tenant-policy / governance-resolved **max-output default** (authored by C4, delivered in the governance context `21 §36.1` / operational baseline `16 §I.1`), else (2) the **model's authoritative maximum context/output limit** (from the pricing/capability descriptor, `19 §9.2`) as the absolute ceiling. Only if **neither** an authored default **nor** an authoritative model ceiling exists does the Engine **fail closed** (`CostUnavailable`, reason `USAGE_UNBOUNDED`) — because it cannot construct an upper bound without an authoritative ceiling. **The Engine never invents a bound; it uses an authoritative ceiling or fails closed.**
- **Ownership:** the **max-output default policy is authored by C4/C5 upstream** (the Engine consumes it); the Engine never sets a default. This keeps availability high (most models have an authoritative ceiling → a projection is always constructable) while never underestimating (CE-INV). **Build-Fail (extends CE-A7):** a projection using an invented/average bound; a projection below actual.

### §24 — Missing / estimated / authoritative usage (three distinct classes) *(resolves Medium CEM-2 — additive)*
Usage is handled in **three explicitly-separated classes**; the class is **recorded** on the `CostResult` (`confidence`) and in the fact (§28) — never conflated:

| Usage class | Meaning | Cost handling | Fact label |
|---|---|---|---|
| **Authoritative** | provider returned exact usage (`18 CV-5`) | compute **actual** cost exactly (§21) | `usage=authoritative` |
| **Projected** | pre-usage admission upper bound (§23/§23.1) | never-underestimated **projection** (not a charge) | `usage=projected` |
| **Estimated** | provider returned only an estimate, or usage missing (`18 CV-5` flagged) | **fail closed by default** (`CostUnavailable`); **only if upstream policy explicitly permits an estimated charge**, apply the **provider-reported estimate rounded conservatively (never-underestimate)**, **flagged `usage=estimated`** in the fact for downstream reconciliation/dispute | `usage=estimated` |

- **Rule (normative):** the three classes are **never conflated** — a projection is never emitted as an actual charge; an estimate is **never silently** treated as authoritative. **Default for estimated/missing usage = fail closed** (CE-INV); an estimated charge is permitted **only** under explicit upstream policy and **always flagged** (so C5/C8 can reconcile or dispute it). The estimate itself is **provider-reported, never fabricated** (`18 CV-5`), and rounded in the cost-increasing direction. **Build-Fail (extends CE-A16):** an estimated/projected usage emitted as an authoritative charge; an estimated charge without the `usage=estimated` flag or without an applied upstream policy.

### §24.1 — Runtime Cost Attribution Contract *(resolves High CEH-2 — additive; Cost Engine owns attribution, Reliability owns execution; no ownership move)*

This is the **signed, testable contract** for attributing cost across the retry/hedge/failover attempts of a single request. **Provider spend ≠ customer bill.** **Every provider attempt has a real cost.** **The Cost Engine reports facts (attribution); it never invents customer charges and never suppresses provider spend.** Customer *charging policy* is authored upstream (C5/C8) and applied, not invented (CE-D9).

| # | Aspect | Contract |
|---|---|---|
| CA-1 | **Attempt cost** | Each individual invocation **attempt** (initial, failover, hedge, retry) that consumes provider units has an **attempt cost**, computed from the attempt's `NormalizedUsage` (`18 CV-5`) × authoritative pricing. |
| CA-2 | **Provider spend** | **Provider spend = Σ attempt costs** (all attempts that reached the provider, including failed/cancelled ones that consumed tokens). This is **always** emitted as a **content-free fact** to the C5 ledger (§28) so true platform spend is reconciled — **provider spend is never suppressed** (CE-INV). |
| CA-3 | **Customer billable usage** | **Customer billable cost** = a function of `NormalizedUsage` **per the upstream-authored charging policy** (in the pricing descriptor / contract snapshot). The Engine **applies** the policy (e.g. bill-delivered-only, or bill-all-attempts); it **never invents** the rule. Default when policy is unspecified: **fail closed** — the Engine does not assume a charging rule. |
| CA-4 | **Hedged attempts** | A hedge (`20 §RE-D6`) launches a competing attempt. **All hedge attempts' provider spend is a fact (CA-2).** The **customer charge** follows the charging policy (typically delivered-only — the loser is cancelled+discarded, `20 §25`). The Engine attributes each hedge attempt distinctly (winner=delivered, losers=attempt). |
| CA-5 | **Failed attempts** | A failed attempt that consumed provider units has **provider spend (CA-2, a fact)**; whether the customer is charged follows policy (CA-3). Never silently drop failed-attempt provider spend. |
| CA-6 | **Cancelled attempts** | A cancelled attempt (`20 §27`, `18 §27`) whose provider work was billed by the provider has **provider spend (CA-2)** for what was consumed before cancellation; customer charge per policy. Never assume a cancelled attempt was free. |
| CA-7 | **Stream retries** | A pre-first-emission stream restart (`18 §26`) is a **new attempt** (CA-1); post-first-emission is non-retryable (`18 §26`), so no additional stream-retry attempt cost arises after emission. Attribution aligns with `18`'s point-of-no-return. |
| CA-8 | **Guided retries** | A SchemaLock guided re-ask (`17 §24`, `20 §20.1`) is a **new attempt** (a fresh generation) with its own provider spend (CA-2); customer charge per policy. It shares the request's idempotency key (CA-10). |
| CA-9 | **Transport retries** | A StreamGuard pre-emission transport retry (`18 §26`) that re-invokes is a **new attempt** (CA-1); a transport retry that reuses the same provider response is **not** a new provider spend. Attribution reflects actual provider invocations, not internal transport retries. |
| CA-10 | **Shared idempotency** | All attempts of one request share the **request idempotency key** (`12 §14`); the Engine keys **cost facts** by (idempotencyKey, attemptId) for provider spend and by **idempotencyKey** for the **single customer charge** — so the ledger reconciles **exactly-once customer charge** while retaining **all** provider-spend facts. |
| CA-11 | **Exactly-once accounting boundary** | The **customer charge is emitted exactly-once per idempotency key** (delivered outcome, `20 §25`); **provider-spend facts are at-least-once but idempotency-keyed per attempt** so the ledger dedups per (key, attempt). Never double-charge the customer; never lose a provider-spend fact. |
| CA-12 | **Authoritative ownership** | **Reliability (C2) owns execution** (which attempts happen, `20`); **the Cost Engine (C5) owns attribution** (assigning cost to each attempt + the delivered customer charge); **the C5 control-plane ledger owns authoritative accounting/reconciliation** (`06 §307`); **C8 owns customer billing** from the reconciled facts. No overlap. |

- **Rule (normative):** **Provider spend ≠ customer bill.** The Engine emits **provider-spend facts for every attempt** (true platform cost) **and** a **single delivered customer-charge fact per request** (per upstream policy). It **never invents** a customer charge and **never suppresses** provider spend. Ambiguous delivered/attempt state ⇒ **fail closed** (do not emit a possibly-double or possibly-missing charge).
- **Enforcement (CE-A19):** effectively-once + attribution tests (`15` T-017/T-035) assert: exactly-one customer charge per idempotency key; all attempt provider-spend facts present; no invented customer charge without an upstream policy; no suppressed provider spend. **Build-Fail:** a customer charge emitted more than once per idempotency key; a provider attempt with consumed units and no provider-spend fact; a customer charge invented without an applied upstream policy.

### §25 — Estimated remaining budget
- On request, the Engine computes **estimated remaining budget** = `budgetLimit − currentSpend(bounded-staleness, C5) − projectedCost` for Governance/tenant visibility. This is a **calculation** (never an enforcement — enforcement is `21`); it is **conservative** (uses projection, never-underestimate) so remaining-budget is never over-stated.

---

## G. Accounting Boundaries

### §26 — Idempotency & §27 — exactly-once accounting (see CE-D9)
- Usage/cost facts are **idempotency-keyed** (`12 §14`) so the C5 ledger reconciles **exactly-once**; delivered-vs-attempt distinguished (`20 §25`). Never double-count a delivered customer charge; always report true provider spend.

### §28 — Emit facts to the ledger
- Via `LedgerSinkPort` (events, async, AD-005): the Engine emits **content-free usage/cost facts** (canonical amounts, versions, delivered/attempt flags, idempotency key) to the **C5 control-plane ledger** for authoritative accounting/reconciliation (RPO=0, `06 §307`). The **ledger is C5's**; the Engine emits facts, it does not persist.

### §29 — Reconciliation ownership
- **C5 owns reconciliation** (authoritative ledger, RPO=0). The Engine's fast-path calculation (projection/actual) is **reconciled** against the ledger by C5; any bounded over/under from staleness (commitment counters, §18) is corrected in C5's authoritative accounting. The Engine never reconciles; it emits truthful facts.

---

## H. Interfaces

### §30 — Cost metadata for Router / Governance / Reliability (see CE-D10)
- Read-only, immutable `CostMetadata`; consumed by Router (`19 §33.1`), Governance (`21 GV-D6`), Reliability (`20 §24`). No downstream cost computation.

### §31 — Governance interface (budget admission)
- The Engine's **projection** (never-underestimate, §22) is the cost Governance uses at admission (`21 GV-D6`). `CostUnavailable` ⇒ Governance fails closed (denies). The Engine **provides** cost; **Governance enforces** budget — no enforcement in the Engine.

### §32 — Router interface (soft cost objective)
- The Engine provides normalized comparative cost per candidate (from the resolved descriptor) that the Router uses as its **soft** cost objective (`19 §33.1`, tier 7). Provider-neutral (the Router sees canonical cost, not provider pricing).

### §33 — Reliability interface (hedge cost-gate)
- The Engine provides the projected extra cost of a hedge, which Reliability's cost-gate uses to permit/deny hedging (`20 §24`). Missing/stale cost ⇒ Reliability fail-safe (does not hedge, `20 §24`).

### §34 — Usage interface (§35) & Quota/accounting interface
- **Usage:** consumes `NormalizedUsage` (`18 CV-5`) via `UsageSourcePort`. **Quota accounting:** the Engine's cost/usage facts feed C5's ledger and Governance's quota/budget counters (`21 §28.1`) — the Engine **provides**; C4 **enforces** quota/budget, C5 **accounts**.

### §36 — Audit interface
- Every cost decision (projection/actual/`CostUnavailable` + pricing versions + resolution) is a **content-free, tamper-evident** cost-decision audit record (WORM+Merkle, `08 §10`, `13 §19`) via `AuditSinkPort` — the cost-dispute evidence trail. Audit completeness (RPO=0) preserved.

---

## I. Determinism & Replay

### §37 — Determinism (see CE-D8) & §38 — replay
- Deterministic over recorded inputs (usage + pricing/FX/contract versions + injected clock). **Replay:** given the recorded inputs (§38.1), a cost is **exactly reproducible** — essential for cost disputes, audit, and forensics. Honest scope: reproducible **given the recorded versions**, not "stable across pricing changes" (pricing evolves; the recorded version fixes the calculation).

### §38.1 — Complete replay-input set for cost-dispute reproduction *(resolves Medium CEM-5 — additive; normative)*
Every cost decision's **audit record (§36)** MUST capture the **complete input set** required to **exactly reproduce** the calculation for a dispute — no input needed for reproduction may be absent:

| # | Recorded input | Purpose |
|---|---|---|
| RI-1 | `NormalizedUsage` (input/output/cached tokens, requests) + **usage class** (authoritative/projected/estimated, §24) | the units the cost was computed over |
| RI-2 | **pricing snapshot version** (PSC-2) | the descriptor rates applied |
| RI-3 | **resolved `PricingDescriptor` id + pricing class** (precedence outcome, §15) | which price/precedence won |
| RI-4 | **FX table version** (FX-2) + the pair(s) used | the conversion applied |
| RI-5 | **contract snapshot version** (negotiated/enterprise/committed terms, §16) | which contract applied |
| RI-6 | **commitment-usage value** used for the tier boundary (§18) | committed-vs-burst attribution |
| RI-7 | **region** (CE-D11) + **canonical model id** | regional pricing basis |
| RI-8 | **phase** (projection/actual) + **conservatism basis** (FX-9/§23.1 bound used) | reproduce a projection's upper bound |
| RI-9 | **idempotency key + attemptId** (CA-10) | delivered-vs-attempt attribution |
| RI-10 | **canonical result + breakdown + confidence** | the produced figure to compare against |

- **Rule (normative):** a cost dispute is reproduced by **re-running the deterministic core (§37) against RI-1…RI-9 and comparing to RI-10** — with **no live lookup** (pricing/FX are the recorded historical versions, PSC/FX-10). If any RI-* is absent, the record is **non-reproducible** — which is a **defect** (a cost that cannot be reproduced fails the never-wrong guarantee). **Build-Fail (extends CE-A8):** a cost audit record missing any RI-* input required for exact reproduction.

---

## J. Failure Handling

### §39 — Failure handling (every failure fails closed)
Every failure/uncertainty resolves to **`CostUnavailable` (surfaced)** — never a guessed, fabricated, or under-estimated cost.

| Failure | Handling |
|---|---|
| Unknown pricing (no descriptor) | **`CostUnavailable`** (fail closed; never guess) |
| Expired pricing/FX (beyond TTL) | **`CostUnavailable`** (fail closed; never calc on stale) |
| Missing/expired FX for conversion | **`CostUnavailable`** (never guess FX) |
| Missing/estimated usage | fail closed, or flagged estimate **only if policy permits** (§24) |
| Unbounded max-usage (can't upper-bound projection) | **`CostUnavailable`** (can't safely project) |
| Ambiguous/unresolved negotiated contract | **`CostUnavailable`** (no silent list fallback) |
| No regional price | **`CostUnavailable`** (fail closed) |
| Partial failure (some inputs available, some not) | **fail closed** for the missing part — never a partial guess (§39.1) |
| Any unknown/internal error | **`CostUnavailable`** (fail closed) |
- **Downstream effect:** `CostUnavailable` ⇒ Governance denies admission (`BUDGET_INDETERMINATE`/`POLICY_UNAVAILABLE`), Reliability does not hedge, the Router treats the candidate as cost-unknown (does not prefer it) — **never** a silent proceed on unknown cost.

### §39.1 — Partial failures
- If **some** pricing inputs resolve and **others** don't (e.g. list price present but negotiated-contract snapshot stale), the Engine **fails closed on the unresolved part** — it does **not** compute a partial cost or substitute list price for an expected negotiated price (that would violate CE-INV pricing rules). A cost is emitted **only** when **all** required inputs are authoritative; otherwise `CostUnavailable`.

---

## K. Performance & Concurrency

### §40 — Performance model
- Cost calculation is **sub-millisecond CPU** over cached snapshots (O(1) descriptor/FX lookup + O(tiers) arithmetic). On the **projection** path it adds to the critical path (admission) but well within the added-latency budget (`06 §11`); the **actual** path runs post-usage (off the response-latency critical path, feeding the ledger async). No hot-path network. JMH benchmarks baseline the calculation path (`15` T-026, `16 §J.2`).

### §41 — Memory model & baselines
- Per-request state in the transient `CostCalculation` aggregate; pricing/FX/contract snapshots are **shared immutable** reads. Bounded (no unbounded structures). Sized within container limits (`16` D-044, ZGC AD-023).

### §41.1 — All numeric thresholds are configurable operational baselines *(resolves Medium CEM-4 — additive; normative)*
- **Every** numeric value in this document — pricing snapshot **TTL/`validUntil` + freshness window** (PSC-4/§13), **FX validity/freshness** (FX-7), **projection conservatism margins** (§23.1/FX-9), **max-output default ceiling** (§23.1, C4/C5-authored), **commitment-counter staleness** (§18), reconciliation cadence, **telemetry cardinality-N** (§45), calculation time budgets — is a **named entry in the per-environment `operational-baseline`** (`16 §I.1`), schema-validated and change-audited, delivered via cached snapshots (C5-CP/C8/Config, AD-022). **No immutable numeric constants** live in the Engine. Values shown in this document are **illustrative defaults**. Baselines may tighten freely; **loosening a value derived from a frozen guarantee** (added-latency `06 §11`, never-underestimate/CE-INV, residency) requires a recorded exception (`16` D-065) and may **never** cross the frozen floor. **Build-Fail (CE-A15):** a hard-coded cost threshold/TTL bypassing the operational baseline; a baseline value set beyond a frozen `06`/`13`/`16` bound.

### §42 — Concurrency & Virtual Threads
- Runs on **Virtual Threads** (AD-023): per-request calculation is a lightweight CPU task; **no shared mutable per-request state** (AD-021); snapshot reads are lock-free; the only shared state (commitment-usage counter, §18) uses the frozen bounded-staleness tier (`21 §28.1`, keyed by tenant/commitment). No blocking in `synchronized` on the hot path (`11` R-049). **Build-Fail:** shared mutable per-request state; a mutable pricing structure; blocking-in-`synchronized` on the hot path.

---

## L. Security

### §43 — Security considerations
- **Credential-free / SDK-free / HTTP-free / store-free** (CE-D12).
- **Never-underestimate (CE-INV):** cost is never optimistically rounded down — prevents budget-bypass / cost-manipulation via under-costing.
- **Tenant isolation (AD-021):** per-tenant pricing/contract resolution; commitment counter keyed by (tenant, commitment); a tenant's pricing never affects another's cost.
- **Pricing integrity:** authoritative versioned pricing only; no guessed/fabricated/default prices (CE-D4); fail closed on uncertainty.
- **No provider leak:** cost metadata is provider-neutral; provider identity internal-only, never customer-facing (`12 §16.10`, AD-007).
- **Decision integrity & audit:** immutable `CostResult`; content-free, tamper-evident cost audit (§36).
- **No content/secret in telemetry** (`13 §20`, `14 §7.1`).
- **Enforcement:** secret/content-leak scanner (`14 §7.1`, `15` T-052); isolation tests (`15` T-044). **Build-Fail:** secret/content in a cost result/event/log; cross-tenant pricing access; a provider-native pricing field exposed.

---

## M. Observability

### §44 — Posture
- Full instrumentation per `14`, **content-free**, **low-cardinality**: spans per calculation (phase, pricing class, versions, confidence), metrics (§45), structured logs (§46), events (§47). Two-tier (`14 §5.1`): operational counters → Plane A; high-cardinality cost detail → Plane B. Privacy (`14 §7.1`): never prompt/completion/secret/raw pricing-contract detail.

### §45 — Metrics (SLI-bearing)
- `cost_calculations_total{phase}` (projection/actual), `cost_unavailable_total{reason}` — a **cost/pricing canary signal** (`16 §E.2`; a spike = pricing-snapshot pipeline health).
- `cost_pricing_class_total{class}`, `cost_projection_over_actual_ratio` (should be ≥ 1 — never-underestimate SLI), `cost_stale_pricing_total`, `cost_fx_unavailable_total`, `cost_calculation_latency`.
- Model/region/class labels **bounded** (top-N + `other`, Plane A; high-cardinality on Plane B); **no provider label** on external surfaces (provider-neutral, CE-D2); no content/secret labels.

### §46 — Tracing & Logging
- One span per calculation; neutral attributes (phase, pricing class, versions, confidence, canonical amount bucket) — never raw prices/content/secret. Structured content-free logs. Correlation/causation ids propagated (`07 §6`, `14`).

### §47 — Events
- Off the hot path (async, AD-005), **Avro** via Apicurio (`07 §9`), standard envelope (`07 §6`), **content-free**, within C5's **already-owned** event space (no new topic owner; `06 §23` — C5 emits usage/cost facts). Consumed by the C5 ledger (accounting), Audit (C10), Observability (C9). **No provider-native events** (`12 §16.10`). Event evolution follows `16` D-033. **Build-Fail:** a provider-native event; content/raw-price in an event.

---

## N. Residency & Compliance

### §48 — Residency (see CE-D11)
- Regional pricing; cost calculation **in-region**; cost data **residency-confined** (never cross-region beyond id-only correlation, `14 §18.1`, `08` DA-D2, `16` D-051). **Build-Fail:** cost data crossing a residency boundary.

### §49 — Compliance
- Cost decisions are **audited** (§36) as compliance evidence (`13 §19`, `08 §10`). Cost data is classified as tenant financial/usage data (`08`); handled per classification (no leak, residency-confined). The Engine **enforces no compliance policy** (that is C4, `21 GV-D8`); it operates within the already-governed request and confines cost data per residency/classification.

---

## O. Testing

### §50 — Testing strategy (Cost Engine is a `15 §G.1`-class critical module)
- **Unit (JUnit5/Mockito):** descriptor resolution, precedence, normalization, projection/actual, missing-usage handling, fail-closed paths.
- **Property-based (jqwik, `15` T-035):** *projection ≥ actual for all inputs* (never-underestimate); *identical recorded inputs ⇒ identical cost* (determinism); *no unknown/expired/missing input yields a computed cost* (fail-closed); *precedence resolution is total + deterministic*.
- **Mutation (`15 §G.1`):** **≥ 85%** on the Engine; a mutant that underestimates, guesses a price, or bypasses fail-closed must be killed.
- **Failure injection (`15` T-018):** unknown/expired pricing, missing FX, missing/estimated usage, unbounded output, ambiguous contract, partial failure ⇒ `CostUnavailable`, correct reason, zero silent cost.
- **Effectively-once (`15` T-017/T-035):** delivered-vs-attempt; idempotency-keyed facts; no double customer charge; true provider spend.
- **Consistency/isolation (`15` T-027/T-044):** massive concurrency, no cross-tenant pricing/commitment leakage; VT no-pinning (`15` T-028).
- **Provider-neutrality (`15` T-015):** no provider branch; adding a provider = new descriptor, zero Engine diff; cost identical regardless of provider for equal descriptors.
- **Residency (`15` T-045):** cost data in-region; no cross-region price lookup.
- **Performance (`15` T-021/T-026, `16 §J.2`):** projection path within budget; perf-smoke gate.

---

## P. Build-Failing Rules

### §51 — Build-failing architectural rules
| # | Rule | Gate |
|---|---|---|
| CE-A1 | No provider pricing logic; no provider-native pricing field | ArchUnit AU-06 |
| CE-A2 | No provider-name branch/switch/lookup (provider-neutral descriptors only) | ArchUnit + `15` T-015 |
| CE-A3 | No hardcoded prices/rates | ArchUnit/lint |
| CE-A4 | No guessed/default/interpolated/fallback price on unknown pricing (fail closed) | fault test (`15` T-018) |
| CE-A5 | No mutable pricing structure; snapshots immutable + versioned | ArchUnit |
| CE-A6 | No calculation on stale/expired pricing or FX (beyond TTL) | fault test (§13) |
| CE-A7 | No projection that can underestimate actual cost (projection ≥ actual) | property test (`15` T-035) |
| CE-A8 | No wall-clock/random in cost core; every result stamps pricing/FX/contract versions | ArchUnit (`11` R-063) |
| CE-A9 | No credential/secret/API-key/provider SDK/direct HTTP | ArchUnit AU-06 + secret scanner (`13 §20`) |
| CE-A10 | No persistence / private store / ledger / billing / invoice / payment / tax in the Engine | ArchUnit AU-07 |
| CE-A11 | No double customer charge per idempotency key; no provider-spend under-report (effectively-once) | property test (`15` T-017) |
| CE-A12 | No budget/quota enforcement in the Engine (calculates only; C4 enforces) | ArchUnit |
| CE-A13 | Cost data never crosses a residency boundary; no cross-region price lookup | `15` T-045 |
| CE-A14 | No provider-native field/event exposed; no content/secret/raw-price in telemetry/events | `14 §7.1` scanner, `15` T-052 |
| CE-A15 | No hard-coded cost numeric; all thresholds/TTLs from operational baseline (§41) | config lint |
| CE-A16 | No silent estimate when authoritative pricing/usage unavailable (fail closed, CE-INV) | fault test (`15` T-018) |
| CE-A17 | Mutation score ≥ 85% (critical module) | PITest (`15 §G.1`) |
| CE-A18 | Pricing-snapshot contract (§13.1): Engine never refreshes/produces/mutates a snapshot; fail-closed on stale/missing; version-stamped; downstream never receives guessed/stale cost | contract + chaos (`15` T-018/T-019) |
| CE-A19 | Cost-attribution contract (§24.1): exactly-one customer charge per idempotency key; every provider attempt has a spend fact; no invented customer charge; no suppressed provider spend | property test (`15` T-017/T-035) |
| CE-A20 | Currency contract (§16.1): no live FX lookup / runtime rate download; fail-closed on stale/missing FX; conservative projection FX; replay uses recorded FX version | FX fault + property test (`15` T-018/T-035) |

---

## Q. Operations

### §52 — Operational runbook
- **`cost_unavailable` spike:** pricing-snapshot/FX pipeline health (AD-022 distribution) — the Engine correctly fails closed; **fix the pricing source, never disable the fail-closed gate** (would risk wrong cost, CE-INV). Cascades to Governance denials (budget-indeterminate) — expected, protective.
- **`cost_projection_over_actual_ratio` < 1 (any):** a **never-underestimate violation** — Sev1 (a projection under actual could admit an over-budget request); root-cause the projection logic; canary auto-aborts (`16 §E.2`).
- **Stale-pricing spike:** snapshot distribution health; never relax TTL to "unblock" (CE-INV).
- **Pricing change rollout:** confirm new snapshot version propagated within the bounded window (§14); the Engine picks it up automatically.
- **FX-unavailable spike:** FX source health; the Engine fails closed on conversion — fix the FX feed.

### §53 — Upgrade strategy
- Ships within the **data-plane release train** (`16` D-014/D-055): rolling/canary, zero-downtime (`16` D-031), cost-signal-gated (`16 §E.2`). Pricing/FX/contract changes are **snapshot/config** (C5-CP/C8) — no Engine redeploy needed to change prices.

### §54 — Backward compatibility
- The **cost contract** (`CostResult`, `CostProjection`, `CostMetadata`, `CostUnavailable`, `PricingDescriptor` shape) is a stable internal API (`12 §27`): additive/backward-compatible within a major. Pricing-snapshot schema evolves under versioned compat (`12 §27`). Emitted facts evolve under Avro compat (`07 §9`, `16` D-033). Deterministic core (§37) guarantees identical costs for identical recorded inputs across versions.

---

## Appendix A — Implementation notes *(non-normative; deferred Low items)*
- **Event naming (CEL-1):** usage/cost-fact event names/topics are reconciled with the `06 §23` registry within C5's already-owned event space — **no new topic owner**.
- **Benchmarks (CEL-2):** the projection + actual calculation paths have a JMH microbenchmark set in `benchmarks/` (`15` T-026, `16 §J.2`); the perf-smoke gate (`16 §J.2`) guards P99 projection-path added-latency regression.
- **Pricing-descriptor DSL / snapshot format (CEL-3):** the descriptor DSL + compiled snapshot format are a **C5 control-plane authoring** concern (this document specifies calculation/enforcement, not authoring) — deferred to the C5 pricing standard.
- **Commitment-counter data structures (CEL-4):** the bounded-staleness commitment counter (§18) is a shared-in-memory-tier concern (`06 §376`, `21 §28.1`); the Engine is tier-implementation-agnostic (contract §18, not a data-structure choice).

## R. Traceability

### §55 — Traceability matrix
| Cost concern | BR | NFR | ADR | Domain/Svc | API/Event | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|---|
| Calculation-only / CE-INV (CE-D1/§39) | BR-012 | NFR-COST | AD-016/018 | C5 (`06`) | — | §19 | §45 | T-018 | `16 §E.2` |
| Provider-neutral pricing (CE-D2/§8) | BR-006 | NFR-IF-001 | AD-007 | C5 | `12 §16.10` | — | — | T-015 | — |
| Versioned pricing snapshots / TTL (CE-D3/§9–11) | BR-012 | NFR-COST/CFG | AD-022 | C5 CP | `12 §27` | — | — | T-018 | §I.1 |
| Fail-closed on unknown/expired (CE-D4/§39) | BR-012 | NFR-COST | AD-016 | C5 | — | §19 | §45 | T-018 | — |
| Never-underestimate projection (CE-D5/§22) | BR-012 | NFR-COST | AD-016 | C4 (budget) | — | — | §45 | T-035 | — |
| Currency/usage normalization (CE-D6/§12/§20) | BR-012 | NFR-COST | AD-022 | C5 | — | — | — | T-018 | — |
| Precedence: negotiated/enterprise/committed (CE-D7/§15–19) | BR-012/COMM | NFR-COST | AD-010 | C8/C5 | — | §15 | — | T-035 | — |
| Pricing Snapshot Contract (§13.1) | BR-012 | NFR-COST/CFG | AD-022 | C5 CP | `12 §27` | §19 | §45 | T-018/T-019 | §I.1 |
| Cost Attribution Contract (§24.1) | BR-012 | NFR-COST/AUD | AD-005/016 | C2 exec / C5 attribution / C8 bill | `07 §6` | — | §47 | T-017/T-035 | — |
| Currency Normalization Contract (§16.1) | BR-012 | NFR-COST | AD-022/016 | C5 CP | `12 §27` | — | — | T-018/T-035 | — |
| Projection defaults / usage classes (§23.1/§24) | BR-012 | NFR-COST | AD-016 | C4/C5 | — | — | §45 | T-035 | — |
| Complete replay inputs (§38.1) | BR-024 | NFR-COST/AUD | AD-009/016 | C10 | — | §19 | §8 | T-035 | — |
| Deterministic / replay (CE-D8/§37) | BR-004 | NFR-COST | AD-016 | `11` R-063 | `12 §14` | — | — | T-035 | — |
| Effectively-once accounting (CE-D9/§26–29) | BR-012 | NFR-COST/AUD | AD-005 | C5 ledger | `07 §6` | — | §47 | T-017 | — |
| Cost metadata interfaces (CE-D10/§30–33) | BR-005/012 | NFR-COST | AD-002 | C1/C2/C4 | — | — | — | T-008 | — |
| Governance budget (§31) | BR-012 | NFR-GOV | AD-018/019 | C4 (`21 GV-D6`) | — | §13.1 | — | T-031 | — |
| Router cost objective (§32) | BR-005 | NFR-COST | AD-007 | C1 (`19 §33.1`) | — | — | §10 | T-018 | — |
| Reliability hedge cost-gate (§33) | BR-005 | NFR-PERF/COST | AD-016 | C2 (`20 §24`) | — | — | — | T-017 | — |
| Regional pricing / residency (CE-D11/§48) | BR-023 | NFR-MR | AD-014 | C5 | — | §15/§18 | §18.1 | T-045 | D-051 |
| Module in data plane, stateless (§7/§41) | BR-005 | NFR-PERF | AD-020/006 | `06 §10/§24` | — | — | — | T-051 | D-014 |
| Credential-free / ports (CE-D12/§7) | BR-019 | NFR-SEC | AD-002/012 | `05`/`11` | — | §20/§21 | — | T-008 | — |
| Commitment counter (shared tier) (§18/§42) | BR-012 | NFR-CONC | AD-021/022 | in-memory tier (`06 §376`) | — | §15 | — | T-027/T-044 | — |
| Ledger facts / reconciliation (§28/§29) | BR-012 | NFR-AUD-001 | AD-005/009 | C5 ledger/C10 | `07 §6` | §19 | §8 | T-046 | — |
| Usage from StreamGuard (§20/§24) | BR-002 | NFR-STRM | AD-018 | C3 (`18 CV-5`) | — | — | §11 | T-012 | — |
| Testing (critical module) (§50/§51) | BR-012 | NFR-TEST-001 | AD-016 | — | — | §27 | §19 | §G.1/T-018/T-035 | §E.2 |
| Upgrade/back-compat (§53/§54) | BR-028 | NFR-UPG/VER | AD-015 | — | `12 §27` | — | — | T-049 | D-014/D-055 |

---

## S. Reviews

### 1. Internal Consistency Review
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-016/018 / CE-INV | correctness applied to cost; never underestimate | §1/§39 (fail-closed, never guess/underestimate) | ✅ |
| AD-007 | provider neutrality | CE-D2 (neutral descriptors, no provider branch) | ✅ |
| AD-022 | cached snapshots / last-known-good | CE-D3/§13 (versioned pricing; fail-closed beyond TTL) | ✅ |
| AD-021 | tenant isolation | §42/§43 (per-tenant pricing; keyed commitment counter) | ✅ |
| AD-014 | residency confinement | CE-D11/§48 (regional pricing; in-region cost data) | ✅ |
| AD-020/AD-006 | data plane co-located stateless module | §7/§41 (no store), stateless | ✅ |
| AD-002 | hexagonal replaceable adapters | §7 ports; snapshots via ports | ✅ |
| AD-005/`07` | effectively-once | CE-D9/§26 (idempotency-keyed facts, delivered-vs-attempt) | ✅ |
| `06` (C5 = Metering & Cost) | cost-calc facet of C5 DP; ledger = C5 CP | §1/§4 (calculation module; CP owns ledger) | ✅ |
| `06 §307` | C5 ledger RPO=0 accounting | §28/§29 (emit facts; C5 reconciles) | ✅ |
| `06 §23`/§24 | C5 events; store-per-service | §47/§7 (owned space; no store) | ✅ |
| `06 §376` | shared in-memory tier | §18/§42 (commitment counter, no new store) | ✅ |
| `12 §14` | idempotency | CE-D9/§26 | ✅ |
| `12 §16.10` | no provider leak | §32/§43/§45 (neutral cost, provider internal-only) | ✅ |
| `18 CV-5` | usage accounting; missing/estimated flagged | §20/§24 (consume normalized usage; never fabricate) | ✅ |
| `19 §33.1` | C5 produces normalized cost; Router consumes | §30/§32 (Engine produces; Router consumes soft objective) | ✅ |
| `20 §24/§25` | hedge cost-gate; delivered-vs-attempt | §33/CE-D9 | ✅ |
| `21 GV-D6/§28.1` | Governance enforces budget using cost | §25/§31 (Engine calculates; C4 enforces) | ✅ |
| `13`/`14`/`15`/`16` | security/observability/testing/deploy models | §43/§44/§50/§53 | ✅ |
| AD-022 / `06` C5 CP | snapshot mechanism; C5 owns pricing distribution | §13.1 PSC-1…PSC-12 (consume-only; C5 refreshes) | ✅ |
| `20 §25` / `12 §14` | delivered-vs-attempt; idempotency | §24.1 CA-1…CA-12 (exactly-once customer charge; all provider spend) | ✅ |
| `18 CV-5` | usage; missing/estimated flagged | §24 (authoritative/projected/estimated classes) | ✅ |
| `16 §I.1` | operational baselines | §41.1 (all numerics), §13.1 PSC-4, §16.1 | ✅ |

**No contradictions found** against `00`–`21` and AD-001…AD-023. The corrective pass (§13.1, §24.1, §16.1, §23.1, §24, §18, §38.1, §41.1, Appendix A, CE-A18…A20) is **additive only**: **no new ADR, no new service, no new module, no new store** (§13.1/§18 use the frozen AD-022 snapshot mechanism + `06 §376` shared tier), **no new consistency model** (reuses `21 §28.1` bounded-staleness + `20 §25` effectively-once), **no ownership/event/data-ownership change, no responsibility moved, no consistency/security/deployment/observability/testing-model change, no provider coupling, and no invariant weakened** (CE-INV, never-underestimate, fail-closed, provider-neutrality, C5 ownership are *reinforced*). The pricing-snapshot contract (§13.1) **specifies** consumption of the existing C5-distributed snapshot; the attribution contract (§24.1) **separates** provider spend (fact) from customer charge (upstream policy) without moving billing to the Engine; the currency contract (§16.1) **forbids** live FX and fails closed.

### 2. Architecture Validation
- **Ownership:** calculation-only; ledger = C5-CP, billing = C8, budget-enforcement = C4 — no ownership moved (CE-D1/§4). ✅
- **Boundaries/contexts:** cost-calc facet of C5; produces cost metadata consumed by C1/C2/C4; no boundary crossed. ✅
- **Events:** emits within C5's owned event space; no new topic owner (§47). ✅
- **Stores:** none; commitment counter on the frozen shared tier (§18/§42). ✅
- **Consistency:** effectively-once accounting (`07`/AD-005); bounded-staleness commitment counter (`21 §28.1` model, no new model). ✅
- **Residency:** regional pricing, in-region cost data (CE-D11). ✅
- **Provider neutrality:** neutral descriptors, no provider branch (CE-D2, AD-007). ✅
- **Deployment/security/testing:** data-plane release train (`16`), credential-free (`13`), critical-module testing (`15 §G.1`). ✅
- **Pricing-snapshot honesty (§13.1):** consume-only, fail-closed-on-stale, version-stamped; states what is and is NOT guaranteed; deny-vs-availability trade-off explicit; C5 owns refresh. ✅
- **Attribution clarity (§24.1):** provider spend (fact, all attempts) ≠ customer charge (upstream policy, exactly-once); Reliability executes / Cost attributes / C5 accounts / C8 bills. ✅
- **Currency safety (§16.1):** no live FX; fail-closed on stale/missing FX; conservative projection FX; replay from recorded version. ✅
- Introduces **no new architecture decision**; every CE-Dx is an implementation choice inside frozen C5.

### 3. Adversarial Review (re-run after corrective pass)
*Independent Review Board: Principal Enterprise Architect · Distributed Systems Architect · FinOps Architect · Cloud Economist · Staff Reliability Engineer · Security Architect · Performance Engineer · Compliance Auditor · Enterprise SaaS Architect · CTO.*

#### Executive Summary
The three load-bearing seams are now signed, testable contracts; the medium policy gaps are pinned. The Cost Engine remains disciplined, provider-neutral, calculation-only, and honest about its distributed limits (no live pricing/FX, fail-closed over availability). All accepted findings resolved additively with no architecture/ownership/model change.

#### Accepted findings — resolution
- **CEH-1 → RESOLVED (§13.1).** Pricing Snapshot Contract PSC-1…PSC-12: authoritative snapshot, version, timestamp, `validUntil`, freshness/refresh ownership (C5 CP), producer/consumer, stale/missing handling, TTL lifecycle, fail-closed. **Engine never refreshes; consume-only.** Downstream guarantees (Governance never guessed, Router never stale, Reliability never hedges stale) explicit; what IS/ISN'T guaranteed stated (no live pricing, deny-vs-availability). Build-enforced (CE-A18).
- **CEH-2 → RESOLVED (§24.1).** Cost Attribution Contract CA-1…CA-12: attempt cost, **provider spend (all attempts, always a fact) ≠ customer bill (upstream policy, exactly-once)**, hedged/failed/cancelled/stream/guided/transport attempts, shared idempotency, exactly-once boundary, ownership (C2 executes / C5 attributes / ledger accounts / C8 bills). **Never invent a customer charge; never suppress provider spend.** Build-enforced (CE-A19).
- **CEH-3 → RESOLVED (§16.1).** Currency Normalization Contract FX-1…FX-10: authoritative FX source (snapshot table), version, regional/pricing/billing currencies, conversion ownership, stale/missing FX, conservative conversion, replay. **No live FX lookup; unknown FX ⇒ fail closed; conservative projection rounding.** Build-enforced (CE-A20).
- **CEM-1 → RESOLVED (§23.1):** projection upper-bound only; unknown/unbounded output uses an authoritative ceiling (C4/C5 max-output default → model ceiling) or fails closed; never an invented bound.
- **CEM-2 → RESOLVED (§24):** three distinct usage classes (authoritative/projected/estimated); estimated = fail-closed-by-default, flagged charge only under explicit upstream policy.
- **CEM-3 → RESOLVED (§18):** commitment-counter fail-safe direction — stale ⇒ treat as exhausted → burst (higher cost); never apply committed rate above the commitment (never under-cost).
- **CEM-4 → RESOLVED (§41.1):** all numeric thresholds are operational-baseline entries; no immutable constants; never below frozen floors.
- **CEM-5 → RESOLVED (§38.1):** complete replay-input set RI-1…RI-10 recorded in the audit record; a non-reproducible cost record is a defect (build-fail).

#### New findings from the re-run
- **🔴 Critical:** none.
- **🟠 High:** none.
- **🟡 Medium:** none blocking. (All numeric baselines are governed operational-baseline entries per `16 §I.1`; the C5 control-plane pricing/FX authoring + distribution and the upstream charging-policy authoring are cross-team deliverables referenced here and contract-/chaos-tested (`15` T-018/T-019) — noted as Low deferrals, not open design gaps.)
- **🟢 Low (deferred, non-blocking):**
  - **CEL-1** — cost/usage-fact event names/topics → reconcile with `06 §23` registry (Appendix A).
  - **CEL-2** — JMH benchmark set → `benchmarks/` (Appendix A).
  - **CEL-3** — pricing-descriptor DSL / snapshot format → C5 control-plane authoring standard.
  - **CEL-4** — commitment-counter data structures → detailed design.

#### Internal Contradictions
- **None.** (The prior §24 fail-closed-vs-estimate note is now a single normative rule with three explicit usage classes, §24.)

#### Cross-document Contradictions
- **None.** Consistent with `19 §33.1`, `20 §24/§25`, `21 GV-D6/§28.1`, `18 CV-5`, `06 §307/§376`, AD-005/007/014/021/022.

#### Scores
- **Architecture Readiness: 97 / 100** — all three High and five Medium resolved additively; three load-bearing seams (pricing, attribution, FX) now signed/testable; no new architecture/ownership/model change; CE-INV/never-underestimate/neutrality/C5-ownership reinforced. Residual 3 points are cross-team-deliverable/calibration items (Low, deferred).
- **Documentation Health: 96 / 100** — contradictions/gaps closed; contracts honest about distributed limits.
- **Implementation Readiness: 94 / 100** — every load-bearing seam specified precisely enough to implement without inventing a pricing/FX/consistency guarantee the design cannot keep.

#### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`21` or AD-001…AD-023; no new architecture; no ownership/module/service/boundary/event/store/consistency/security/deployment/observability/testing change; no provider coupling; no invariant weakened (CE-INV reinforced).

---

*End of document — 22-CostEngine.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
