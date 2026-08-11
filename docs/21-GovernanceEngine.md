# 21 — Governance Engine (Policy Enforcement Point · Domain C4)

**Document:** Component Implementation Architecture — Governance Engine
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Version:** 1.0 (frozen 2026-07-21)
**Bounded context:** **C4 — Governance & Policy** (Core Domain, `05`/`06`)
**Module:** `dp-governance-engine` — a **module inside the Data Plane** deployable, co-located per **AD-020/AD-006** — **not** a separate service, **owns no store**. It is the **Policy Enforcement Point (PEP)** of the frozen PEP/PDP model (AD-019).
**Audience:** Governance/security/compliance/data-plane engineers, SRE, QA, auditors, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`20` and ADRs **AD-001…AD-023**. Everything here is **additive**. Introduces **no new architecture decision, no new service, no new module, no new store, no ownership/event/data-ownership change, no consistency/security/deployment/observability/testing-model change, and no provider coupling.** Every GV-Dx is a *component-internal implementation decision* inside the already-frozen C4, expressed through hexagonal ports (AD-002).

> **What this is.** The complete implementation architecture of the **Governance Engine** — the **non-bypassable admission gate** (AD-018) that runs **first** in the request pipeline (`06`: authenticate → **govern** → route → invoke → correctness → meter → emit). For every request it evaluates the tenant's **authorization, quota, budget, feature-flag, compliance, and residency** policy and either **PERMITs** (emitting the resolved governance context the Router then consumes) or **DENYs** (surfacing an explainable, neutral reason). It is the **PEP** of AD-019: it **enforces** policy that is **authored** by the C4 control-plane Policy & Governance Service and distributed as cached snapshots (AD-022) — it does not author policy. Every major decision carries **Problem · Decision · Alternatives · Why selected · Trade-offs · Failure modes · Security impact · Performance impact · Enforcement**.
>
> **THE GOVERNANCE-ENGINE INVARIANT (GV-INV):** *The Governance Engine must never allow a request that violates tenant policy, security policy, residency policy, compliance policy, authorization policy, quota policy, budget policy, or feature-flag policy. On any uncertainty it fails closed and surfaces — it never silently permits, never silently bypasses, never silently downgrades policy.* This is the governance-layer realization of **AD-018 (non-bypassable governance)**, **AD-019 (PEP/PDP)**, **AD-021 (isolation)**, and **AD-014 (residency)** — deny-by-default is the ground state (`13`).

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Interfaces** (§6–7) · **C. Major Decisions** (GV-D1…GV-D12) · **D. Policy Lifecycle & Evaluation** (§8–13) · **E. Enforcement Domains** (§14–20) · **F. Decision Output** (§21–23) · **G. Isolation & Security** (§24–25) · **H. Performance & Concurrency** (§26–29) · **I. Observability** (§30–33) · **J. Data & Integration** (§34–41) · **K. Failure & Recovery** (§42–43) · **L. Testing** (§44) · **M. Build-Failing Rules** (§45) · **N. Operations** (§46–48) · **O. Traceability** (§49) · **P. Reviews**

---

## A. Charter

### §1 — Purpose
The Governance Engine is the **admission authority** for every request: it decides **PERMIT / DENY** against the tenant's full governance policy and, on PERMIT, produces the **resolved governance context** (authorized scope + resolved residency/compliance/quota/budget constraints) that downstream stages (the Router `19`, Reliability `20`) consume. It realizes C4's "trustworthy governance" responsibility as the **PEP** (`05`/`06`, AD-019), running **before** routing so a denied request never reaches a provider.

### §2 — Scope
- **In scope:** evaluate **authorization** (RBAC + ABAC) for the authenticated principal; enforce **quota** and **budget** caps; evaluate **feature-flag** gating; determine **compliance** applicability and **residency** scope; authorize **tool use**; produce an **explainable PERMIT/DENY decision** with a resolved governance context; emit governance telemetry + the frozen governance/audit events (`06 §23`). All against **cached, C4-authored policy snapshots** (AD-019/022).
- **Applies to:** every request on the hot path, immediately after authentication (C6), before routing (C1).

### §3 — Responsibilities
1. Consume the **authenticated principal + tenant scope** (from C6 Identity, upstream).
2. Resolve the applicable **policy set** from cached snapshots (authored by C4 control-plane + Configuration/Billing for entitlements/flags, AD-022).
3. Evaluate the **policy hierarchy** (GV-D2) — authorization, quota, budget, feature flags, compliance, residency, tool authorization — **deny-overrides** (§9).
4. Produce a **PERMIT + resolved governance context** or a **DENY + explainable reason** (§21–22).
5. **Fail closed** on any uncertainty (stale/missing hard policy, ambiguous decision) (§42).
6. Emit a **content-free, tamper-evident** governance audit record for every decision (§33).

### §4 — Out of Scope (what the Engine never does)
- **Authentication / identity establishment** — owned by **C6 Identity & Access** (`06`). The Engine **consumes** the authenticated principal; it does not authenticate.
- **Policy authoring** — owned by the **C4 control-plane Policy & Governance Service + Configuration** (`06`, AD-019). The Engine is the **PEP** (enforce), not the **PDP-author** (it evaluates distributed policy locally, it does not create policy).
- **Routing / provider selection** — owned by **C1** (`19`). The Engine emits resolved constraints; the Router applies them as routing filters.
- **Correctness (schema/streaming)** — owned by **C3** (`17`/`18`).
- **Retry/failover/rate-limit *execution*** — owned by **C2** (`20`). The Engine authors nothing at execution time; rate-limit **policy** is C4-authored (`20 §17`), enforced by C2 at invocation.
- **Usage ledger / billing** — owned by **C5 Metering** / **C8 Billing**. The Engine **consumes** entitlement/quota/usage snapshots; it computes no bill and owns no ledger.
- **Feature-flag definition/lifecycle** — owned by **C15 Configuration & Feature Management** (`06`). The Engine **evaluates** flags; it does not define them.

### §5 — What the Engine is NOT
| Governance Engine (C4 — PEP/admission) | Not the Engine |
|---|---|
| ✓ PERMIT/DENY + resolved governance context | ✗ authenticate the principal (C6) |
| ✓ evaluate authz/quota/budget/flags/compliance/residency | ✗ author policy (C4 control plane / Config) |
| ✓ produce resolved residency/compliance scope | ✗ route to providers (C1) |
| ✓ explainable, deterministic decisions | ✗ own usage ledger / billing (C5/C8) |
| ✓ deny-by-default, fail-closed | ✗ define feature flags (C15) |

---

## B. Domain & Interfaces

### §6 — Domain model (governance subdomain)
Pure domain types (no infrastructure, `11` hexagonal); immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `GovernanceRequest` | VO | Neutral request context: authenticated principal (from C6), tenant scope, requested action/capabilities, requested tools, correlation ids (`07 §6`) |
| `Principal` | VO | Authenticated identity + roles/attributes (from C6, consumed) |
| `PolicySet` | VO (immutable, snapshot) | Resolved applicable policies (authz/quota/budget/flags/compliance/residency/tool) for the tenant, from cached snapshots + version |
| `PolicyRule` | VO | A single canonical, provider-neutral rule (subject/action/resource/condition + effect) |
| `Decision` | VO (immutable) | `PERMIT`(+`ResolvedGovernanceContext`) or `DENY`(+`DenialReason`) + `explanation` + policy versions (§21) |
| `ResolvedGovernanceContext` | VO (immutable) | On PERMIT: authorized scope + **resolved residency scope, compliance regimes, quota headroom, budget headroom, enabled flags** — the input the Router (`19`) consumes as hard filters |
| `DenialReason` | VO | Neutral, explainable binding-policy reason (§22) |
| `QuotaState` / `BudgetState` | VO | Current bounded-staleness usage vs cap (from shared tier / C5 snapshot, §17/§18) |
| `GovernancePolicy` | VO (injected) | Precedence weights + evaluation config, from cached snapshot (C4/Config, AD-019/022) |

**Aggregate:** `GovernanceEvaluation` — the transient per-request aggregate coordinating resolve → evaluate → decide; holds **no shared mutable state across requests** (AD-021).

### §7 — Public interfaces (ports — AD-002 hexagonal)
**Inbound (called by the data-plane pipeline, after authentication, before routing):**
```
GovernanceEnginePort:
  govern(GovernanceRequest) -> Decision   // PERMIT(+context) | DENY(+reason); deterministic, fail-closed
```
**Outbound (all reads are cached snapshots; no hot-path network, no credentials):**
```
PolicySnapshotPort       // <- C4 Policy & Governance Service / Configuration: resolved policy set (snapshot, AD-019/022)
EntitlementSnapshotPort  // <- C8 Billing / C5: entitlement/quota/budget limits (snapshot)
UsageStatePort           // <- shared tier / C5: bounded-staleness quota/budget usage counters (§17/§18/§28)
FeatureFlagSnapshotPort  // <- C15 Configuration & Feature Mgmt: flag state (snapshot)
IdentityContextPort      // <- C6 Identity: authenticated principal + tenant scope (per-request input)
AuditSinkPort            // -> Audit (C10): governance-decision events (content-free)
MeteringSinkPort         // -> Metering (C5): governance counters (content-free)
TelemetryPort            // -> Observability (C9): governance metrics/traces (content-free)
ClockPort / IdPort       // deterministic time / ids
```
- **Enforcement:** ArchUnit — the Engine imports **no** provider SDK (AU-06), **no** HTTP client, **no** persistence driver (AU-07), **no** secrets/credential API, **no** routing/correctness types. All inputs are **cached snapshots** or the authenticated principal; the only output is a `Decision`. **Build-Fail:** any forbidden import; a hot-path network call; policy authored (not consumed) in the Engine.

---

## C. Major Decisions

### GV-D1 — Policy evaluation pipeline (non-bypassable, first gate)
- **Problem:** governance must be impossible to skip and must run before any provider work.
- **Decision:** a fixed **evaluation pipeline** runs **first** in the request path (AD-018): `resolve principal + policy set → evaluate hierarchy (GV-D2) with deny-overrides → PERMIT(+resolved context) | DENY(+reason)`. No request reaches the Router/invocation without a **PERMIT `Decision`**; a DENY terminates the request with a surfaced reason. The pipeline is **structurally non-bypassable** — there is no code path from ingress to routing that skips `govern()`.
- **Alternatives:** governance as a late/optional check — rejected (AD-018 violation; wasted provider work on denied requests); per-stage scattered checks — rejected (un-auditable, gap-prone).
- **Why selected:** one auditable admission gate; deny before spend.
- **Trade-offs:** all governance latency is on the critical path (bounded, §26).
- **Failure modes:** any evaluation failure ⇒ DENY (fail closed, §42).
- **Security impact:** the single enforcement choke-point for all policy invariants.
- **Performance impact:** local evaluation over cached snapshots, sub-ms (§26).
- **Enforcement:** ArchUnit — routing/invocation unreachable without a PERMIT decision (GV-A1/A2). **Build-Fail:** a path to the Router/provider that bypasses `govern()`.

### GV-D2 — Policy precedence hierarchy (strict, deny-overrides)
- **Problem:** multiple policy domains can conflict; resolution must be total, deterministic, and safe.
- **Decision:** a **strict precedence with global deny-overrides** — **any** DENY at any level denies the request; a PERMIT requires **all** levels to permit:
  ```
  1. Security policy        (platform-level guards; deny-overrides everything)
  2. Tenant policy          (tenant allow/deny, contractual)
  3. Compliance policy      (regime requirements applicable)
  4. Residency policy       (permitted-region scope)
  5. Authorization (RBAC+ABAC) (principal may perform action on resource)
  6. Feature-flag policy    (feature enabled for tenant/cohort)
  7. Quota policy           (usage caps not exceeded)
  8. Budget policy          (spend caps not exceeded)
  ```
  **Deny-overrides** (`13` deny-by-default): the moment any level yields DENY, evaluation short-circuits to DENY with that binding reason; a request is PERMITted only if **every** level PERMITs. Precedence governs **explanation ordering** (the highest-precedence binding denial is the reported reason) and **evaluation order for cheap short-circuit** — but because it is deny-overrides, order never changes the *outcome*, only the *reported reason* and *speed*.
- **Alternatives:** permit-overrides / weighted blend — rejected (a permit could mask a compliance/security denial — unacceptable, GV-INV); configurable precedence — rejected (security/compliance/residency must never be demotable).
- **Why selected:** deny-overrides is the only safe combinator for an admission gate; precedence gives stable, explainable reasons.
- **Trade-offs:** a single failing domain denies the whole request — correct for a gate.
- **Failure modes:** ambiguous/failed domain ⇒ DENY (§42).
- **Security/compliance impact:** the encoding of GV-INV.
- **Performance impact:** short-circuits on first DENY.
- **Enforcement:** deny-overrides is fixed; the hard domains (1–5) are non-demotable. **Build-Fail:** a permit-overrides path; a code path where a lower domain's PERMIT overrides a higher domain's DENY (GV-A3).

### GV-D3 — Tenant policy model
- **Problem:** each tenant has distinct governance (allow/deny lists, contractual constraints, compliance regime, region, entitlements) that must be applied in strict isolation.
- **Decision:** policy is **resolved per tenant** from the cached policy snapshot, **scoped by tenant identity** (AD-021), and evaluated against the request. A tenant's policy **never** influences another's decision; the resolved `PolicySet` is tenant-scoped and read-only. Tenant policy expresses allow/deny, applicable compliance regimes, residency scope, entitlement references, and feature entitlements — all **provider-neutral** (GV-D11).
- **Alternatives:** global policy with tenant overrides mixed at runtime — rejected (isolation risk); per-tenant compiled binaries — rejected (deploy churn).
- **Why selected:** strict per-tenant isolation with fast snapshot resolution.
- **Trade-offs:** snapshot size grows with tenants (bounded per-tenant lookup, §26/§28).
- **Failure modes:** missing/stale tenant policy (hard) ⇒ DENY (§42).
- **Security impact:** per-tenant isolation of policy (AD-021).
- **Performance impact:** O(1) tenant-scoped snapshot lookup.
- **Enforcement:** isolation tests (`15` T-044); tenant-scoped resolution. **Build-Fail:** cross-tenant policy access; a policy decision keyed on another tenant's state.

### GV-D4 — RBAC + ABAC evaluation
- **Problem:** authorization must combine role-based and attribute-based rules deterministically and safely.
- **Decision:** authorization evaluates **RBAC** (principal roles → permitted actions) **and ABAC** (attributes of principal/resource/environment → conditions) with **deny-overrides** (a deny from either denies): the request is authorized iff RBAC grants the action **and** all ABAC conditions hold **and** no explicit deny matches. The principal + roles + attributes come from **C6 Identity** (consumed, §4). Evaluation is **provider-neutral** and **deterministic** (GV-D12).
- **Alternatives:** RBAC-only — rejected (insufficient for attribute conditions like residency/data-class); permit-overrides combination — rejected (unsafe).
- **Why selected:** expressive + safe (deny-overrides) + standard PEP model (AD-019).
- **Trade-offs:** ABAC condition evaluation cost (bounded, cached attributes).
- **Failure modes:** unresolved attribute / ambiguous rule ⇒ DENY (fail closed).
- **Security impact:** the core authorization control (`13 §7`, `12 §19` 403/404 model).
- **Performance impact:** O(rules) over a bounded rule set; short-circuits.
- **Enforcement:** authz negative-first tests (`15` T-031); deny-overrides. **Build-Fail:** a protected action without an authz check; an authz permit that ignores an applicable deny.

### GV-D5 — Quota enforcement
- **Problem:** usage caps (requests/tokens per window per tenant/plan) must be enforced at admission without a private ledger.
- **Decision:** the Engine enforces **quota** as a hard gate: it reads the tenant's **quota limit** (entitlement, from C8/Config snapshot) and **current usage** (bounded-staleness counter from the shared tier / C5 snapshot, §17/§28), and **DENYs** if the request would exceed the cap. Usage is **owned by C5**; the Engine **consumes** it. On usage-counter staleness/uncertainty, quota is enforced **fail-safe** within the freshness window (last-known-good, AD-022) and **fail-closed (DENY) beyond tolerance** for hard quotas (§17/§28). Quota (usage caps at admission) is **distinct from** rate-limiting (per-second throttle at invocation, owned/executed by C2, `20 §17`).
- **Alternatives:** private quota ledger in the Engine — rejected (ownership drift to C5; new store); no admission quota — rejected (GV-INV).
- **Why selected:** admission-time cap enforcement, ledger stays C5's.
- **Trade-offs:** bounded-staleness counters mean quota is enforced to a **tolerance**, not exact-to-the-request (honest, §28) — over-count risk bounded; adversarially flagged.
- **Failure modes:** over-quota ⇒ DENY (`QUOTA_EXCEEDED`); stale-beyond-tolerance ⇒ DENY.
- **Security/cost impact:** prevents usage abuse/cost overrun.
- **Performance impact:** O(1) counter read.
- **Enforcement:** quota fault tests (`15` T-018). **Build-Fail:** the Engine owning a quota ledger; quota bypassed on stale counter beyond tolerance (GV-A4).

### GV-D6 — Budget enforcement
- **Problem:** spend caps (cost budgets per tenant/period) must be enforced at admission.
- **Decision:** analogous to GV-D5 for **cost budget**: the Engine reads the tenant's **budget limit + current spend** (normalized, from C5/C8 snapshot — `19 §33.1` normalization, never computed here) and **DENYs** if admitting the request would exceed the hard budget ceiling. The Engine **never computes cost/pricing** (that is C5/C8); it consumes normalized spend vs budget. Fail-safe/fail-closed identical to quota (§17/§28). Budget (admission spend cap) complements the Router's soft cost objective (`19 §33.1`) and C2's cost-gated hedging (`20 §24`).
- **Alternatives:** compute cost in the Engine — rejected (billing ownership, `19 §33.1`); no budget gate — rejected (cost-overrun risk, GV-INV).
- **Why selected:** admission-time budget enforcement without cost computation.
- **Trade-offs:** same bounded-staleness honesty as quota (§28).
- **Failure modes:** over-budget ⇒ DENY (`BUDGET_EXCEEDED`); stale-beyond-tolerance ⇒ DENY.
- **Security/cost impact:** hard cost-overrun protection.
- **Performance impact:** O(1) read.
- **Enforcement:** budget fault tests. **Build-Fail:** the Engine computing cost; budget bypass on stale state beyond tolerance.

### GV-D7 — Feature flags (evaluate, never own)
- **Problem:** features must be gated per tenant/cohort without the Engine owning the flag system.
- **Decision:** the Engine **evaluates** feature-flag policy — is the requested feature **enabled** for this tenant/cohort? — from the **C15 Configuration & Feature Management** snapshot (AD-022). A disabled feature ⇒ DENY (`FEATURE_DISABLED`) or graceful capability removal per policy. Flags are **evaluated within tenant isolation** (a tenant's flag never affects another, AD-021) and **cannot** enable a bypass of a hard invariant (a flag can never turn off compliance/residency/security — those are not flag-gated; `16 §D-057`). The Engine **does not define, create, or manage** flags (C15 owns their lifecycle).
- **Alternatives:** flags owned by the Engine — rejected (ownership drift to C15); flags able to gate invariants — rejected (invariant weakening, forbidden).
- **Why selected:** decoupled deploy-vs-release gating without ownership drift.
- **Trade-offs:** flag freshness bounded by snapshot (§28).
- **Failure modes:** unknown/absent flag for a gated feature ⇒ fail-safe (feature treated as **off**, deny/remove — never assumed-on).
- **Security impact:** flags cannot bypass invariants (build-enforced).
- **Performance impact:** O(1) flag read.
- **Enforcement:** invariant-flag prohibition (`16 §D-057`); flag-eval tests. **Build-Fail:** a flag gating a hard invariant off; the Engine owning flag lifecycle (GV-A7).

### GV-D8 — Compliance enforcement
- **Problem:** regulated tenants require that only compliant execution paths are admitted.
- **Decision:** the Engine **resolves the applicable compliance regime(s)** (HIPAA/GDPR/SOC2/…) for the tenant/request (from tenant policy snapshot) and **admits only if the request can be satisfied compliantly** — encoding the regime into the **resolved governance context** so the Router (`19 §PR-D9`) filters to attested providers. A request that **inherently cannot** be satisfied under its required regime ⇒ DENY (`COMPLIANCE_CONFLICT`). Compliance is a **hard domain** (GV-D2 tier 3), never traded, never downgraded. The Engine **enforces** compliance requirements; it does not author them (C4 control plane).
- **Alternatives:** post-hoc compliance check — rejected (admit-then-reject, risky); Engine authoring compliance rules — rejected (ownership).
- **Why selected:** compliance decided up front; downstream stages inherit the resolved regime.
- **Trade-offs:** requires accurate tenant compliance policy (governed, control plane).
- **Failure modes:** compliance unresolvable ⇒ DENY.
- **Security/compliance impact:** core regulated-admission control.
- **Performance impact:** O(1) resolution.
- **Enforcement:** compliance tests (`15` T-031); regime recorded in audit (§33). **Build-Fail:** admitting a request whose required compliance regime cannot be resolved/satisfied; compliance downgrade (GV-A5).

### GV-D9 — Residency enforcement
- **Problem:** residency is an absolute invariant (AD-014); admission must resolve and enforce it.
- **Decision:** the Engine **resolves the permitted residency scope** for the tenant/request (from tenant policy snapshot) and encodes it into the **resolved governance context** so the Router (`19 §PR-D8`) routes only to permitted regions and Data/Deployment confine physically (`08`/`16`). A request whose data classification **requires** a region with **no** permitted path ⇒ DENY (`RESIDENCY_CONFLICT`). Residency is a **hard domain** (GV-D2 tier 4), never crossed, never downgraded. Defense-in-depth: Governance **resolves + admits**, Router **filters**, Data/Deploy **confine** — three layers, all enforcing AD-014.
- **Alternatives:** rely only on the Router for residency — rejected (defense-in-depth; the Engine denies inherently-unsatisfiable requests earlier); Engine authoring residency rules — rejected (ownership).
- **Why selected:** earliest, cheapest residency admission gate; downstream inherits the resolved scope.
- **Trade-offs:** residency policy must be current (hard-domain freshness, §12).
- **Failure modes:** residency unresolvable/unsatisfiable ⇒ DENY.
- **Security/compliance impact:** residency-confinement admission control.
- **Performance impact:** O(1) resolution.
- **Enforcement:** residency tests (`15 §I.1`, T-045). **Build-Fail:** admitting a request whose residency scope is unresolved/unsatisfiable; residency downgrade (GV-A6).

### GV-D10 — Tool authorization
- **Problem:** tool-use (function/tool calling, computer-use, MCP) must be authorized — not every tenant/principal may invoke every tool.
- **Decision:** the Engine authorizes the **requested tools/capabilities** against tenant + principal policy (RBAC+ABAC, GV-D4): each requested tool must be **permitted** for the tenant and the principal; a request invoking an **unauthorized tool** ⇒ DENY (`TOOL_UNAUTHORIZED`) or removal of the tool from the authorized scope per policy. Tool **authorization** (may this principal use this tool?) is the Engine's; tool **transport/argument correctness** is C3's (`17`/`18`); tool **execution** is out of platform scope (the caller executes tools). Tool authorization is **provider-neutral** (a "tool" is a canonical capability, not a provider feature, `19 §9.2`).
- **Alternatives:** authorize tools downstream at invocation — rejected (late; wasted work); no tool authz — rejected (privilege escalation risk).
- **Why selected:** admission-time least-privilege for tool use.
- **Trade-offs:** requires tool-scope in tenant policy (governed).
- **Failure modes:** unauthorized/unknown tool ⇒ DENY / remove.
- **Security impact:** least-privilege tool use; prevents tool-based escalation (`13`).
- **Performance impact:** O(requested tools) over bounded set.
- **Enforcement:** tool-authz tests (`15` T-031). **Build-Fail:** admitting an unauthorized tool; a tool authorized by ignoring an applicable deny.

### GV-D11 — Provider-neutral governance
- **Problem:** governance must be about tenants/policy/compliance, never providers, to stay neutral (AD-007).
- **Decision:** the Governance Engine is **fully provider-neutral**: it knows nothing of providers. Its decisions produce **provider-neutral constraints** (residency scope, compliance regimes, authorized capabilities/tools) that the Router (`19`) later maps onto providers. The Engine **never branches on provider names** and never reads provider-native fields. Provider selection is entirely downstream (C1).
- **Alternatives:** per-provider governance rules — rejected (coupling; governance belongs above providers).
- **Why selected:** neutrality; governance is a tenant/policy concern, orthogonal to providers.
- **Trade-offs:** none material.
- **Failure modes:** n/a.
- **Security/Performance impact:** neutral.
- **Enforcement:** ArchUnit — no provider-name reference in the Engine (GV-A9). **Build-Fail:** a provider-name branch/switch/field in the Engine.

### GV-D12 — Deterministic policy evaluation
- **Problem:** governance decisions must be **reproducible** for audit, compliance forensics, and testing.
- **Decision:** evaluation is a **pure, deterministic function** of `(GovernanceRequest, resolved PolicySet, usage/entitlement snapshots, injected clock)` — **no wall-clock/random** in the decision core (`11` R-063); time via `ClockPort`. Given identical inputs (including policy versions + usage-snapshot values recorded in the decision), the **same PERMIT/DENY + same explanation** result every time. Decisions record **policy versions + snapshot values** for exact reproducibility.
- **Alternatives:** nondeterministic evaluation — rejected (un-auditable, un-testable; compliance needs reproducible decisions).
- **Why selected:** auditable/replayable governance; enables property/mutation testing.
- **Trade-offs:** determinism is over **recorded inputs** — usage counters change continuously, so a decision is reproducible **given the recorded snapshot values**, not "stable across time" (honest, §23).
- **Failure modes:** none from determinism.
- **Security/Performance impact:** cache-friendly; enables strong testing.
- **Enforcement:** ArchUnit forbids wall-clock/random in the core; replay tests (`15` T-035). **Build-Fail:** wall-clock/random in the policy decision core.

---

## D. Policy Lifecycle & Evaluation

### §8 — Policy lifecycle (author → distribute → consume; the Engine only consumes)
- Policy is **authored** in the C4 control-plane **Policy & Governance Service** (+ Configuration for flags/config, C8 for entitlements) — versioned, reviewed, governed (`06`, AD-019). It is **compiled + distributed** as **cached, versioned policy snapshots** (AD-022). The Governance Engine **only consumes** the current snapshot; it never authors, edits, or persists policy. A policy change is a **new snapshot version** (atomic swap, §12), never an in-place mutation.

### §9 — Evaluation pipeline (detailed)
```
govern(request):
  principal   ← IdentityContextPort (authenticated, C6)          // consume, not authenticate
  policySet   ← PolicySnapshotPort (tenant-scoped, versioned)    // fail closed if stale/missing (hard, §12)
  usage/ent   ← UsageStatePort / EntitlementSnapshotPort         // bounded-staleness (§28)
  flags       ← FeatureFlagSnapshotPort (C15)
  for domain in [Security, Tenant, Compliance, Residency, Authz(RBAC+ABAC), Flags, Quota, Budget]:  // GV-D2
      d ← evaluate(domain, request, policySet, usage, flags)
      if d == DENY: return DENY(binding=domain, reason, explanation, policyVersions)   // deny-overrides short-circuit
  return PERMIT(ResolvedGovernanceContext{authorizedScope, residencyScope, complianceRegimes, quotaHeadroom, budgetHeadroom, enabledFlags, authorizedTools}, policyVersions)
```
Deterministic (GV-D12), deny-overrides (GV-D2), fail-closed on any evaluation error (§42).

### §10 — Policy hierarchy (see GV-D2)
- The strict deny-overrides hierarchy (§GV-D2). Hard domains (Security, Tenant, Compliance, Residency, Authz) are **non-demotable**; Quota/Budget/Flags are hard gates too (deny-overrides) but sourced from bounded-staleness state (§28).

### §11 — Quota & budget lifecycle
- **Limits** (entitlements) are authored by **C8 Billing** (plan → quota/budget), distributed via Config snapshots. **Usage/spend** is owned by **C5 Metering** (the authoritative ledger). The Engine reads a **bounded-staleness usage counter** (shared tier / C5 snapshot) at admission and compares to the limit. On admission of a PERMITted request, the request's projected consumption is **reflected** into the shared counter (bounded-staleness increment, §28) so concurrent requests see it — but the **authoritative accounting is C5's** (post-hoc reconciliation, `06 §307` RPO=0 for accounting). The Engine never becomes the ledger.

### §12 — Policy versioning & invalidation
- Every policy/entitlement/flag snapshot is **versioned**; the Engine records the **versions used** in each decision (§21, for reproducibility/audit). Snapshot updates are **atomic** (a decision always evaluates against **one internally-consistent snapshot version**, never a half-applied mix). **Invalidation/propagation:** a policy change propagates to data-plane replicas within a **bounded window** (operational baseline, `16 §I.1`); until propagated, replicas evaluate the **last-known-good** version (AD-022). The Engine always fails **closed** on a **missing/expired-beyond-tolerance** hard-domain snapshot (§42). Propagation semantics: **§12.1**.

### §12.1 — Policy-Tightening Propagation *(resolves Medium GVM-1 — additive)*
- **Ownership:** the **C4 control-plane Policy & Governance Service owns policy authoring, compilation, versioning, and propagation** (`06`, AD-019); the **Configuration service owns snapshot distribution** (AD-022). The Governance Engine (PEP) **only consumes** the propagated versioned snapshot — it owns none of the propagation machinery.
- **Eventual consistency:** snapshot propagation to data-plane replicas is **eventually consistent within a bounded window** (AD-022) — an **operational-baseline value** (`16 §I.1`), never hard-coded. During the window, replicas may hold **different versions**; each decision is internally consistent to **one** version and stamps that version (§21).
- **Tightening exposure window (compliance-relevant, honest):** because a stale replica enforces the **previous** (looser) rules until propagation completes, a **policy tightening** (e.g. a new compliance restriction, a narrower residency scope, a revoked authorization) has a **bounded exposure window** equal to the propagation delay. This window is:
  - a **governed operational parameter** (`16 §I.1`) with a **maximum acceptable bound** set by policy,
  - **audited** — the effective-version transition is recorded so the exposure window is reconstructable for compliance,
  - and, for **hard-domain tightenings** (security/compliance/residency/authz revocation), the **control plane** may **withhold "effective" status until propagation is confirmed** across serving replicas (a control-plane responsibility, **referenced not owned here**) — so a critical tightening is not considered enforced until it demonstrably is.
- **Loosening** (a *relaxation*) carries no invariant risk from staleness (a stale replica merely enforces the stricter prior rule longer — fail-safe).
- **The Engine's part:** consume the current version; **fail closed** on a missing/expired-beyond-tolerance hard-domain snapshot (§42); stamp the version used (§21). It never accelerates, confirms, or owns propagation. **Build-Fail:** the Engine evaluating against a half-applied/mixed snapshot version; proceeding on an expired-beyond-tolerance hard-domain snapshot.

### §13 — Caching
- The Engine consumes **local, in-process, read-only snapshots** (AD-022) — policy, entitlement, flags — keyed by tenant + version; per-instance, bounded, refreshed via the snapshot pipeline. No hot-path network. The only mutable shared state is the **bounded-staleness quota/budget counter** in the shared in-memory tier (`06 §376`), coordinated like the reliability budget (`20 §30.1`) — **not** a Governance-private store (§28/§37).

---

## E. Enforcement Domains

### §14 — Authorization (RBAC + ABAC) — see GV-D4
- Deny-overrides combination of role grants + attribute conditions + explicit denies, over the C6-authenticated principal. Neutral 403/404 model (`12 §19`, `13 §7`). Full normative semantics: **§14.1**.

### §14.1 — Normative RBAC + ABAC Contract *(resolves High GVH-3 — additive; all uncertainty fails closed)*

**Evaluation order (deterministic, GV-D12):**
```
1. Identity resolved?         (principal present + authenticated, from C6)
2. Explicit DENY match?       (an explicit deny rule for subject/action/resource)
3. RBAC grant?                (a role grants the action on the resource)
4. ABAC conditions hold?      (all attribute conditions on the granting rule satisfied)
→ PERMIT iff: identity resolved AND no explicit deny AND RBAC grants AND ABAC conditions all hold
→ DENY otherwise (deny-overrides)
```

**Normative truth table** (P = PERMIT, D = DENY; deny-overrides throughout):

| Identity | Explicit deny | RBAC grant | ABAC conditions | Result | Reason |
|---|---|---|---|---|---|
| resolved | none | grants | all hold | **P** | authorized |
| resolved | none | grants | some **fail** | **D** | `UNAUTHORIZED` (ABAC condition unmet) |
| resolved | none | grants | some **unresolved/unknown** | **D** | `UNAUTHORIZED` (fail-closed: unresolved attribute ⇒ condition unmet) |
| resolved | none | **no grant** | — | **D** | `UNAUTHORIZED` (no RBAC grant; deny-by-default) |
| resolved | **deny matches** | (any) | (any) | **D** | `UNAUTHORIZED` (explicit deny overrides any grant) |
| **unresolved / unknown identity** | (any) | (any) | (any) | **D** | `UNAUTHORIZED` (no authenticated principal ⇒ fail closed) |
| resolved | (any) | (any) | (any) — **policy/eval error** | **D** | `POLICY_UNAVAILABLE` (evaluation error ⇒ fail closed) |
| resolved | **unresolved deny state** (can't determine if a deny applies) | (any) | (any) | **D** | `UNAUTHORIZED` (fail closed: cannot rule out a deny) |

**Rules (normative):**
- **Deny precedence:** an **explicit deny always wins** over any grant (deny-overrides). If it cannot be determined whether a deny applies, **assume it does** (fail closed).
- **Missing/unknown attributes:** an ABAC condition referencing a **missing or unknown** attribute **evaluates to false** (the condition is *unmet*) ⇒ **DENY**. Attributes are **never** defaulted to a permissive value.
- **Attribute freshness:** principal/resource attributes come from the C6 identity context + cached snapshots; an attribute **stale beyond its freshness tolerance** (operational baseline, `16 §I.1`) is treated as **unresolved** ⇒ **DENY** (fail closed). Attribute freshness never silently permits.
- **Default behavior:** **deny-by-default** (`13`) — absence of an explicit grant is a DENY.
- **Conflicting rules:** deny-overrides resolves all conflicts (any applicable deny ⇒ DENY; a grant only matters if no deny applies). Rule evaluation is **deterministic** and **order-independent for the outcome** (deny-overrides is commutative for the result).
- **Unknown identity:** no authenticated principal ⇒ **DENY** (`UNAUTHORIZED`); the Engine never authorizes an unauthenticated request (C6 authenticates upstream; absence = deny).
- **Policy errors:** any evaluation/policy error ⇒ **DENY `POLICY_UNAVAILABLE`** (fail closed, never permit-on-error).
- **All uncertainty FAILS CLOSED** (GV-INV): unresolved attribute, unknown identity, stale attribute, indeterminate deny, or evaluation error ⇒ **DENY**.
- **Enforcement (GV-A18):** authz negative-first + property tests (`15` T-031/T-035) assert the truth table exactly, deny-overrides totality, and fail-closed on every uncertainty. **Build-Fail:** an authz path that permits on a missing/unknown/stale attribute, unknown identity, indeterminate deny, or evaluation error; a permit that ignores an applicable deny.

### §15 — Tenant isolation
- Every evaluation is **tenant-scoped** (GV-D3, AD-021): resolved `PolicySet`, usage counters, and flags are keyed by tenant; **no cross-tenant read**; a tenant's decision is a pure function of **its own** policy/usage. The shared quota counter is keyed by **(tenant, quota-key)**, never shared across tenants (§28). No cross-tenant leakage in decisions, explanations, or telemetry (§30/§33).

### §16 — Compliance (see GV-D8) & §17 — Residency (see GV-D9)
- Resolved as hard domains, encoded into the resolved governance context for the Router; DENY on unsatisfiable. Defense-in-depth with C1/C8-data/C16-deploy layers.

### §17b — Quota/Budget bounded-staleness enforcement (detail)
- See §28 for the coordination/consistency model of the shared usage counter (mirrors `20 §30.1`); fail-safe = **more restrictive** (deny) on uncertainty, honoring GV-INV.

### §18 — Feature flags (see GV-D7) & §19 — Tool authorization (see GV-D10)
- Flag evaluation (C15 snapshot, never gate an invariant) and tool least-privilege authorization (RBAC+ABAC).

### §20 — Quota vs Rate-Limit ownership *(resolved precisely, GVM-3 — no overlap)*
A clean three-way split with **no overlap**:

| Concern | **Author** | **Enforce/Execute** | What it caps |
|---|---|---|---|
| **Rate limit** (requests/tokens **per unit time**) | **C4 Governance** authors the policy (`20 §17`) | **C2 Reliability executes** the throttle at invocation (`20 §17`) | short-term throughput / burst |
| **Quota** (total usage **per window**) | **C8 Billing** (entitlement) + **C4** (policy) | **C4 Governance Engine enforces** at admission (GV-D5/§28.1) | cumulative usage over a period |
| **Budget** (total spend **per period**) | **C8 Billing** (entitlement) + **C4** (policy) | **C4 Governance Engine enforces** at admission (GV-D6/§28.1); **C5** normalizes spend | cumulative cost over a period |

- **Rule (normative):** **C4 authors** rate-limit/quota/budget policy; **Governance (C4) enforces quota + budget at admission**; **Reliability (C2) executes rate-limit throttling at invocation**. **No overlap:** Governance does **not** perform per-second rate throttling; Reliability does **not** perform admission quota/budget denial. A request is (a) admitted by Governance against quota/budget, then (b) throttled by Reliability against rate limits during invocation — two distinct gates.
- **Interaction & surfacing (normative):** the gates are **sequential and independent** — Governance quota/budget DENY surfaces **first** (at admission, before routing) as `QUOTA_EXCEEDED`/`BUDGET_EXCEEDED`; a Reliability rate-limit throttle surfaces **later** (at invocation) as the neutral rate-limited outcome (`20 §17`). A request denied at admission never reaches rate-limiting; a request admitted may still be rate-limited downstream — the client sees whichever gate binds first, each with its own neutral reason. No double-denial, no ambiguity about which layer owns which cap.

---

## F. Decision Output

### §21 — Explainability
- Every `Decision` is **explainable**: it records the **binding domain**, the **matched policy rule(s)** (by canonical id/version, not raw sensitive content), the **policy versions** used, and — on PERMIT — the **resolved governance context**. Explanations are **provider-neutral** and **content-free** (no prompt/completion/secret; no raw sensitive policy internals beyond what compliance requires, §25). Explainability serves audit, compliance evidence, and neutral error surfacing.

### §22 — Denial reasons
- A DENY surfaces a **neutral, stable reason** (mapped to `12 §12` / `12 §19` 403/404 model) naming the **binding domain**: `SECURITY_DENIED`, `TENANT_POLICY_DENIED`, `COMPLIANCE_CONFLICT`, `RESIDENCY_CONFLICT`, `UNAUTHORIZED` (RBAC/ABAC), `FEATURE_DISABLED`, `QUOTA_EXCEEDED`, `BUDGET_EXCEEDED`, `TOOL_UNAUTHORIZED`, `POLICY_UNAVAILABLE` (fail-closed on stale hard policy). Reasons **avoid leaking** enumeration/authorization detail that would aid probing (403 vs 404 per `12 §19`/`13`), while remaining auditable internally (§33). Default on any unknown ⇒ `POLICY_UNAVAILABLE` DENY (fail closed).

### §23 — Deterministic evaluation (see GV-D12) — honest scope
- Deterministic over **recorded inputs** (request + policy versions + usage-snapshot values + injected clock). Because usage/quota counters change with concurrent traffic, a decision is **reproducible given the recorded snapshot values**, not context-free stable — the same honest scope as `19 §16`/`20 §26`. Hard-domain decisions (authz/compliance/residency/tenant/security) depend only on **versioned policy** (not live counters), so they are **fully reproducible from the policy version**; only quota/budget outcomes carry the counter-value dependence.

---

## G. Isolation & Security

### §24 — Security considerations
- **Deny-by-default** (`13`): the ground state is DENY; PERMIT requires all domains to pass (GV-D2).
- **Credential-free** (no secrets/SDK/HTTP, §7).
- **Tenant isolation (AD-021):** per-tenant policy/usage/flags; shared quota counter keyed by (tenant, key); no cross-tenant leakage (§15).
- **No invariant bypass:** flags/quota/budget can never turn off security/compliance/residency (build-enforced, §45); governance itself is non-bypassable (AD-018).
- **Decision integrity & audit:** every decision is **content-free, tamper-evident** into Audit (C10, WORM+Merkle, `08 §10`, `13 §19`) — a complete, immutable governance record (RPO=0).
- **Probing resistance:** neutral 403/404 denial model (`12 §19`); explanations internal-audit-rich but externally minimal (§22).
- **No content/secret in telemetry** (`13 §20`, `14 §7.1`).
- **Enforcement:** secret/content-leak scanner (`14 §7.1`, `15` T-052); isolation tests (`15` T-044); authz negative-first (`15` T-031). **Build-Fail:** secret/content in a decision/event/log; cross-tenant policy/usage access; a flag/quota gating a hard invariant off.

### §25 — Explainability vs sensitivity *(resolved precisely, GVM-2)*
Explanations exist at **two levels** with an explicit, normative split:

| | **External (surfaced to caller)** | **Internal (Audit C10 + operator)** |
|---|---|---|
| **What is exposed** | the **neutral binding-domain code only** (`SECURITY_DENIED`, `UNAUTHORIZED`, `COMPLIANCE_CONFLICT`, `RESIDENCY_CONFLICT`, `QUOTA_EXCEEDED`, `BUDGET_EXCEEDED`, `FEATURE_DISABLED`, `TOOL_UNAUTHORIZED`, `POLICY_UNAVAILABLE`), mapped to the `12 §19` 403/404 model | the **full explanation**: binding domain, matched rule id(s) + version, policy versions, resolved-scope summary, evaluated attributes (by canonical id, not raw sensitive values) |
| **What is hidden** | matched rule detail, other domains' outcomes, resource existence beyond the 403/404 policy, other tenants' policy, attribute values, enumeration hints | raw prompt/completion/secret content (never recorded anywhere, `14 §7.1`); another tenant's policy (isolation) |
| **Why** | **probing resistance** — a rich external reason is an oracle for policy/enumeration attacks (`13`); 403-vs-404 discipline (`12 §19`) prevents resource-existence leakage | **auditability/compliance evidence** — a complete, reproducible decision trail is required for regulated operation (`13 §19`, `08 §10`) |

- **Normative guarantees:** the **external** reason (a) never reveals another tenant's policy, (b) never provides an enumeration/authorization oracle beyond the 403/404 model, (c) never contains prompt/completion/secret/raw-attribute content; the **internal** explanation (a) is content-free of prompt/completion/secrets, (b) is tenant-scoped (an audit record for tenant A never contains tenant B's policy), (c) is complete enough to reproduce the decision (§23). **Build-Fail (extends GV-A14):** an external denial reason carrying rule detail / another tenant's policy / an enumeration oracle / raw attribute values; an audit explanation containing prompt/completion/secret content or another tenant's policy.

---

## H. Performance & Concurrency

### §26 — Performance model
- Governance evaluation is **sub-millisecond CPU** over cached snapshots (O(domains × rules) over bounded rule sets, short-circuiting on first DENY). It adds to the **critical path** (it runs first) but well within the added-latency budget (`06 §11`). **No hot-path network** (all inputs cached, AD-022). JMH microbenchmarks baseline the evaluation path (`15` T-026, `16 §J.2`).

### §27 — Memory model
- Per-request state in the transient `GovernanceEvaluation` aggregate; policy/entitlement/flag snapshots are **shared immutable** reads. Bounded rule/attribute sets. No unbounded structures. Sized within container limits (`16` D-044, ZGC AD-023).

### §28 — Concurrency & shared-state (quota/budget counters) — bounded-staleness
- **Per-request evaluation has no shared mutable state** (AD-021). The **only** genuinely shared state — **quota/budget usage counters** — is coordinated through the **frozen shared in-memory tier** (`06 §376`, AD-022) using the **same bounded-staleness eventual-consistency model as `20 §30.1`**: **atomic per-(tenant,quota-key) increment/read**, **bounded staleness**, **tenant-sharded**, **fail-safe = more restrictive**. The authoritative ledger is **C5's** (RPO=0 reconciliation, `06 §307`). The full normative contract is **§28.1**. Coordination is lock-light, **non-pinning** on Virtual Threads (`11` R-049), keyed by (tenant, key) so **no cross-tenant leakage** (AD-021).

### §28.1 — Quota/Budget Coordination Contract *(resolves High GVH-1 — additive; specifies the existing shared-tier mechanism, no new store, no new consistency model, no ownership change)*

This is the **signed, testable contract** for quota/budget usage coordination. It uses the **already-frozen shared in-memory tier** (`06 §376`, AD-022) and the **already-frozen bounded-staleness model of `20 §30.1`** — **no new store, no new service, no new consistency model, no ownership move.** **Governance ENFORCES admission caps; C5 owns accounting; the Governance Engine never owns the ledger.**

| # | Aspect | Contract |
|---|---|---|
| GC-1 | **Authoritative ownership** | The **authoritative usage/spend ledger is owned by C5 Metering** (RPO=0, `06 §307`); the **quota/budget *limits* (entitlements) are owned by C8 Billing** (`06 D-6`). The Governance Engine **owns neither** — it **consumes** limits (C8/Config snapshot) and a bounded-staleness usage view, and **enforces** the cap at admission. |
| GC-2 | **Shared counter model** | Admission uses a **per-(tenant, quota-key) atomic counter** in the frozen shared in-memory tier (`06 §376`): a PERMITted request performs an **atomic increment-if-under-cap** (compare-and-increment); over-cap ⇒ DENY. The counter is a **fast admission cache**, not the ledger — it is periodically **reconciled against C5's authoritative ledger** (GC-11). |
| GC-3 | **Bounded staleness** | Replica-local counter views are **bounded-stale** (an operational-baseline freshness window, `16 §I.1`, §28.2). The tier's atomic compare-and-increment is authoritative **within a region** (GC-12); cross-replica convergence is eventual within the window. |
| GC-4 | **Eventual consistency** | The model is **bounded-staleness eventual consistency** (identical to `20 §30.1` CC-3), **NOT linearizable/strong**. Governance makes **no** claim of globally-exact, instantaneously-consistent quota accounting. |
| GC-5 | **Deny-safe behavior** | On any uncertainty (stale/unknown counter, tier hiccup) the Engine errs **more restrictive** — treats usage as **closer to the cap** → biases toward **DENY within tolerance**; it **never** biases toward permitting. Staleness can only make Governance **stricter**, never looser (GV-INV). |
| GC-6 | **Stale-snapshot handling** | Within the freshness window: use **last-known-good** (AD-022) — a slightly-stale-but-fresh-enough counter is trusted (fail-safe conservative). **Beyond** the window: the counter is **untrusted** → **hard quotas/budgets fail closed (DENY `POLICY_UNAVAILABLE`/cap-exceeded)**; **soft quotas** follow §28.3 grace behavior. |
| GC-7 | **Partition behavior** | If the coordination tier is **unreachable/partitioned**, the Engine operates on **last-known-good** (AD-022) under GC-5/GC-6: **hard** caps fail closed (DENY); **soft** caps may enter a **bounded grace** (§28.3) that is **audited** and **reconciled** (GC-11). A partition **never** silently over-admits a hard cap. |
| GC-8 | **Fail-closed behavior** | Any coordination ambiguity that cannot be safely resolved ⇒ **DENY** for hard caps (GV-INV). Coordination failure never fails open into a silent over-admission of a hard cap. |
| GC-9 | **Over-admit prevention** | Because increment is **atomic compare-and-increment at the tier** per (tenant,key), the **regional** counter never issues admissions beyond the cap under concurrency. The only over-admission possible is a **bounded, honest** amount from **cross-replica staleness** ≤ the freshness window (GC-10), which is **detected and reconciled** by C5 (GC-11). |
| GC-10 | **Under-admit behavior** | Because Governance biases toward DENY on uncertainty (GC-5), a **stale counter can cause a bounded false-DENY** (under-admission) of legitimate traffic during a tier glitch. This is the **deliberate deny-safe trade-off** (availability sacrificed for cap-integrity, honoring GV-INV); its blast radius is bounded by the freshness window and is **observable** (`gov_stale_snapshot_total`, `gov_policy_unavailable_total`, §31) so operators can react. Soft quotas mitigate this via grace (§28.3). |
| GC-11 | **Reconciliation ownership** | **C5 owns reconciliation** (RPO=0 accounting, `06 §307`): the fast admission counter is periodically reconciled against C5's authoritative ledger; any bounded over-admission (GC-9) or grace-window usage (GC-7/§28.3) is **captured by C5's authoritative accounting** and billed/reported truthfully. Governance triggers no billing; it emits usage facts (§37) that C5 reconciles. |
| GC-12 | **Regional behavior** | Coordination is **regional** (per data-plane region, AD-014 / `16` D-009) — **no cross-region quota coordination on the hot path**. A tenant's cap is enforced regionally against the regional counter; cross-region aggregation (for a global cap) is a **C5 accounting concern** (post-hoc), not a hot-path Governance operation. |
| GC-13 | **Quota exhaustion** | When the atomic compare-and-increment fails (usage ≥ cap) ⇒ **DENY `QUOTA_EXCEEDED`** (hard) or grace/degrade (soft, §28.3), audited (§33). |
| GC-14 | **Budget exhaustion** | When normalized spend ≥ budget cap ⇒ **DENY `BUDGET_EXCEEDED`** (hard) or grace/degrade (soft, §28.3), audited. Budget uses **normalized spend** consumed from C5/C8 (`19 §33.1`); Governance **never computes cost**. |

- **Guarantees that EXIST (testable):** (1) the **regional atomic counter never issues admissions beyond a hard cap** under concurrency (per tenant,key); (2) **staleness/partition only make Governance stricter** — no silent over-admission of a hard cap; (3) **C5 reconciles** any bounded over-admission truthfully; (4) quota/budget are **never a function of provider identity** (provider-neutral, GV-D11).
- **Guarantees that DO NOT EXIST (explicit, no overclaim):** (1) **no globally-exact, instantaneously-consistent quota/budget** across replicas/regions — hard caps are enforced to a **bounded staleness tolerance**, honestly; (2) **no cross-region hot-path coordination** (regional, GC-12); (3) **no strong consistency/consensus** — deliberately eventual+atomic-per-shard for hot-path latency (`06 §11`). A **bounded, C5-reconciled** over-admission (≤ freshness window) is possible; a **bounded false-DENY** is possible (GC-10) — both are stated honestly, neither weakens GV-INV.
- **Enforcement (GV-A16):** contract tests (`15` T-018) + chaos (`15` T-019) assert: atomic per-shard cap bounding; stale/partition ⇒ **stricter** (never looser) for hard caps; C5 reconciliation captures bounded over-admission; the Engine never owns a ledger and never computes cost. **Build-Fail:** a quota/budget increment that is not atomic-at-the-tier; a stale/partition path that **loosens** a hard cap; the Engine persisting a usage ledger or computing a monetary charge.

### §28.2 — Freshness & propagation baselines
- Counter **freshness window**, propagation window, and reconciliation cadence are **operational-baseline entries** (`16 §I.1`), never hard-coded (§29-baseline / REM-4-analog GVM-4). Values are tuned per environment against the deny-safe trade-off (GC-10).

### §28.3 — Soft vs Hard quota/budget behavior *(resolves Medium GVM-5 — additive)*
Quota/budget policies are classified by the **C4-authored policy** (Governance enforces the classification, does not author it):

| Class | Over-cap behavior | Ordering | Failure mode (stale/partition) | Audit |
|---|---|---|---|---|
| **Hard quota/budget** | **DENY** (`QUOTA_EXCEEDED`/`BUDGET_EXCEEDED`) — inviolable | evaluated in the deny-overrides hierarchy (GV-D2 tiers 7–8) | **fail closed (DENY)** beyond freshness window (GC-6) | every denial audited (§33) |
| **Soft quota/budget** | **grace/degrade per policy** (e.g. allow with a warning event, or degrade capability) — bounded, never silent | same hierarchy position; a soft-cap breach does **not** DENY unless policy says so | **bounded grace** within a governed window (GC-7), then escalate per policy | grace usage + escalation **audited + reconciled** by C5 (GC-11) |

- **Rule:** the hard/soft classification is **explicit in policy**; the Engine **never invents** a class. A soft breach is **never silent** (it emits an audited grace/warning event). A hard breach **always** DENYs. This distinguishes cap-integrity-critical caps (hard) from advisory caps (soft) so a tier glitch does not mass-deny advisory traffic — while hard caps remain inviolable. **Build-Fail (extends GV-A4):** a soft-grace path applied to a hard-classified cap; a soft breach with no audit event.

### §29 — Virtual Threads compatibility
- Runs on **Virtual Threads** (AD-023): each governance evaluation is a lightweight CPU task; snapshot reads + atomic counter ops are non-blocking/lock-light (§28). No blocking in `synchronized` on the hot path (`11` R-049). Per-request isolation preserved. **Build-Fail:** shared mutable per-request state; blocking-in-`synchronized` on the hot path; a global mutable policy/counter guarded by hot-path locks.

### §29.1 — All numeric thresholds are configurable operational baselines *(resolves Medium GVM-4 — additive)*
- **Every** numeric value in this document — quota/budget counter **freshness windows**, policy-propagation window (§12.1), attribute-freshness tolerance (§14.1), `ResolvedGovernanceContext` **TTL** (§36.1), soft-quota **grace window** (§28.3), reconciliation cadence, telemetry **cardinality-N** (§31), evaluation time budgets — is a **named entry in the per-environment `operational-baseline`** (`16 §I.1`), schema-validated and change-audited, delivered via cached snapshots (C4/Config, AD-019/022). **No immutable numeric constants** live in the Engine. Values shown in this document are **illustrative defaults**. Baselines may tighten freely; **loosening a value derived from a frozen guarantee** (added-latency `06 §11`, deny-safe cap-integrity `13`, residency/compliance) requires a recorded exception (`16` D-065) and may **never** cross the frozen floor. **Build-Fail:** a hard-coded governance threshold bypassing the operational baseline; a baseline value set beyond a frozen `06`/`13`/`16` bound.

---

## I. Observability

### §30 — Posture
- Full instrumentation per `14`, **content-free**, **low-cardinality**: spans per evaluation (binding domain, decision, policy versions), metrics (§31), structured logs (§32), events (§33). Two-tier (`14 §5.1`): operational counters → Plane A; high-cardinality decision detail → Plane B. Privacy (`14 §7.1`): never prompt/completion/secret/raw sensitive policy.

### §31 — Metrics (SLI-bearing)
- `gov_decisions_total{decision}` (permit/deny), `gov_denials_total{binding_domain}` — a **governance/compliance canary signal** (`16 §E.2`).
- `gov_quota_denials_total`, `gov_budget_denials_total`, `gov_authz_denials_total`, `gov_compliance_denials_total`, `gov_residency_denials_total`, `gov_policy_unavailable_total` (fail-closed count — a snapshot-pipeline health signal).
- `gov_evaluation_latency`, `gov_stale_snapshot_total{domain}`, `gov_quota_utilization{tenant-bounded}`.
- Tenant/domain labels **bounded** (top-N + `other`, Plane A; high-cardinality on Plane B, `14 §5.1`); **no content/secret** labels; provider-neutral (no provider label — governance is provider-agnostic, GV-D11).

### §32 — Tracing & Logging
- One span per evaluation; neutral attributes (binding domain, decision, policy versions, tenant-scoped id) — never content/secret/raw policy. Structured content-free logs. Correlation/causation ids propagated (`07 §6`, `14`).

### §33 — Events (governance audit)
- Off the hot path (async, AD-005), **Avro** via Apicurio (`07 §9`), standard envelope (`07 §6`), **content-free**, within C4's **already-owned** event space (no new topic owner; `06 §23`). Every decision (permit/deny + binding domain + policy versions + resolved-scope summary) is a **tamper-evident audit event** (WORM+Merkle, `08 §10`, `13 §19`) — governance audit is **complete** (RPO=0), the compliance evidence trail. Consumed by Audit (C10), Observability (C9), Metering (C5). **No provider-native events** (`12 §16.10`). Event evolution follows `16` D-033. **Build-Fail:** a governance decision without an audit event; content/secret in an event.

---

## J. Data & Integration

### §34 — Data ownership
- The Engine is **stateless** (AD-020) and owns **no persistent store** (no ownership/data-model change). It holds only **ephemeral in-memory** per-request state + read-only snapshots + the shared bounded-staleness quota counter in the frozen in-memory tier (`06 §376`, not a Governance-private store, §28). Durable governance records are owned by Audit (C10) / usage by Metering (C5) via events/snapshots. **Build-Fail:** the Engine acquiring a private persistent store.

### §35 — Integration with C6 Identity & Access
- Via `IdentityContextPort`: consumes the **authenticated principal + roles/attributes + tenant scope** (C6 owns authentication, `06`). The Engine **authorizes** (RBAC+ABAC) the authenticated principal; it never authenticates.

### §36 — Integration with C1 Provider Router (the resolved-context handoff)
- On PERMIT, the Engine produces the **`ResolvedGovernanceContext`** (residency scope, compliance regimes, authorized capabilities/tools, quota/budget headroom). This is the **C4-authored resolved policy** the Router consumes as hard filters (`19 §10/§31/§PR-D8/D9`) — **the Router does not re-evaluate governance policy; it applies the already-resolved constraints.** No ownership change: C4 resolves/enforces governance; C1 routes within the resolved scope. (Seam contract detail: §36.1.)

### §36.1 — Normative Governance → Router Contract: `ResolvedGovernanceContext` *(resolves High GVH-2 — additive; signed, testable; no ownership overlap; consistent with `19 §31`)*

This is the **signed contract** for the C4→C1 handoff. It resolves the ambiguity in `19 §31`: **the Governance Engine is the single resolution authority; the Router consumes the resolved context and NEVER re-evaluates governance policy.** No ownership overlap, no double-evaluation, no drift.

**Immutable fields (the `ResolvedGovernanceContext`):**
| Field | Meaning | Owner |
|---|---|---|
| `decisionId`, `correlationId`, `tenantScope` | ids only (`07 §6`) | Governance |
| `residencyScope` | permitted region set (resolved, GV-D9) | Governance resolves; Router filters within (never widens) |
| `complianceRegimes` | required regimes (resolved, GV-D8) | Governance resolves; Router filters to attested providers (`19 §PR-D9`) |
| `authorizedCapabilities` | canonical capabilities the principal may use (GV-D10, `19 §9.2`) | Governance resolves; Router matches |
| `authorizedTools` | canonical tools authorized (GV-D10) | Governance resolves |
| `quotaHeadroom`, `budgetHeadroom` | remaining admission headroom at decision time (bounded-staleness, §28.1) | Governance resolves; informational for downstream cost gates (`20 §24`) |
| `enabledFlags` | feature flags enabled for this request (GV-D7) | Governance resolves |
| `policyVersions` | policy/entitlement/flag snapshot versions used (reproducibility) | Governance stamps |
| `validUntil` / `ttl` | context validity deadline (operational baseline, `16 §I.1`) | Governance sets |

| # | Concern | Contract |
|---|---|---|
| RGC-1 | **Ownership** | **Governance emits** the context; **the Router consumes** it. Governance never routes; the Router never governs. Exactly one resolution authority (Governance). |
| RGC-2 | **Immutability** | The context is **immutable once emitted**. The Router (and any downstream stage) treats it as **read-only, authoritative resolved policy**. |
| RGC-3 | **Lifecycle** | Produced **only on PERMIT** (a DENY terminates the request at Governance, no context). Handed forward in-process (data-plane pipeline) to the Router. |
| RGC-4 | **TTL / `validUntil`** | The context carries a **TTL** (operational baseline, `16 §I.1`). It is valid for the request's execution within TTL. On **TTL expiry** (e.g. a very long-lived streaming request outlasting policy validity), the pipeline **re-governs** (a fresh `govern()` call) — it **never** extends or re-derives the context downstream. |
| RGC-5 | **Consumption rules** | The Router may only **narrow** within the resolved scope (filter residency/compliance/capability); it **MUST NOT widen** the residency/compliance scope, add a capability/tool not authorized, or re-derive any governance decision. Consuming a field it does not understand ⇒ **fail closed** (RGC-8). |
| RGC-6 | **Refresh rules** | Only **Governance** produces/refreshes a context (via `govern()`). The Router **requests re-governance** on TTL expiry; it never constructs or edits a context. |
| RGC-7 | **Failure behavior** | A missing/expired/invalid context downstream ⇒ **fail closed** (the request cannot proceed un-governed, AD-018) — the Router surfaces, never proceeds on an absent/stale-beyond-TTL context. |
| RGC-8 | **Unknown-field behavior** | If the Router encounters an **unknown/unparseable** context field (version skew), it **fails closed** for the affected constraint (never ignores a constraint it cannot interpret) — forward/backward compat is additive (`12 §27`), and an uninterpretable authorization/residency/compliance field is treated as **most restrictive** (deny/narrow), never permissive. |

- **No re-evaluation (normative):** **the Router NEVER re-evaluates governance policy.** It consumes the resolved context; the residency/compliance/capability hard filters it applies (`19 §11`) operate on the **already-resolved scope**, not on raw policy. This eliminates the `19 §31` double-evaluation/drift ambiguity: there is **one** governance evaluation (the Engine's), and the Router applies its result.
- **Enforcement (GV-A17):** contract tests (`15` T-008/T-018) assert: no context without PERMIT; context immutable; Router narrows-only (never widens); Router re-governs on TTL expiry (never extends); unknown field ⇒ fail closed. **Build-Fail:** the Router widening a resolved scope, adding an unauthorized capability/tool, re-evaluating governance policy, or proceeding on a missing/expired context.

### §37 — Integration with C5 Metering / C8 Billing
- Via `EntitlementSnapshotPort`/`UsageStatePort`: consumes entitlement limits (C8) + usage/spend counters (C5, bounded-staleness §28). Never owns the ledger; never computes cost (`19 §33.1`). Emits governance counters to C5 for accounting.

### §38 — Integration with C15 Configuration & Feature Management
- Via `FeatureFlagSnapshotPort`: consumes flag state (C15 owns flag definition/lifecycle). Evaluates flags as governance gates (GV-D7); never defines flags.

### §39 — Integration with the C4 control-plane Policy & Governance Service
- Via `PolicySnapshotPort`: consumes **versioned, compiled policy snapshots** authored by the control plane (`06`, AD-019/022). The Engine is the **PEP** (enforce); the control plane is the **policy authority** (author/PDP-compile). No ownership change — this document specifies enforcement, not authoring.

### §40 — Integration with Audit (C10)
- Via `AuditSinkPort`: every decision is a **content-free, tamper-evident** governance audit record (§33). Audit completeness invariant (RPO=0) preserved — the governance decision trail is the compliance evidence.

### §41 — Integration with C2 Reliability (rate-limit boundary)
- The Engine authors nothing at execution; rate-limit **policy** it resolves is enforced/executed by **C2** at invocation (`20 §17`). The Engine's admission caps (quota/budget) are distinct and complementary (§20).

---

## K. Failure & Recovery

### §42 — Failure handling (every failure fails closed to DENY)
Every failure/uncertainty resolves to **DENY + surfaced reason** — never a silent permit, bypass, or downgrade.

| Failure | Handling |
|---|---|
| Missing/stale **hard-domain** snapshot (security/tenant/compliance/residency/authz) beyond tolerance | **DENY** `POLICY_UNAVAILABLE` (fail closed) |
| Ambiguous/unresolvable authz rule/attribute | **DENY** `UNAUTHORIZED` |
| Compliance/residency unsatisfiable | **DENY** `COMPLIANCE_CONFLICT` / `RESIDENCY_CONFLICT` |
| Quota/budget over cap (or stale-beyond-tolerance) | **DENY** `QUOTA_EXCEEDED` / `BUDGET_EXCEEDED` |
| Feature flag unknown/absent for a gated feature | fail-safe **off** → DENY/remove `FEATURE_DISABLED` |
| Unauthorized/unknown tool | **DENY** `TOOL_UNAUTHORIZED` |
| Usage-counter tier unavailable/partitioned | fail-safe **more restrictive** → DENY hard quotas (§28) |
| Any unknown/internal error | **DENY** `POLICY_UNAVAILABLE` (fail closed) |
- **Enforcement:** fault/chaos injection asserts **zero silent permit** under every failure (`15` T-018/T-031). **Build-Fail:** any failure branch that permits, bypasses, or downgrades on uncertainty.

### §43 — Recovery behavior
- The Engine holds **no durable state**, so recovery is **stateless**: a restarted instance serves immediately (`16` D-041) on last-known-good snapshots (AD-022). On snapshot-pipeline outage it operates on **last-known-good within tolerance**, then **fails closed** (DENY) for hard domains beyond tolerance — **never fail-open into silent permits**. The shared quota tier recovers from its source; on tier unavailability the Engine errs **more restrictive** (§28). Governance never degrades toward permitting.

---

## L. Testing

### §44 — Testing strategy (Governance Engine is a `15 §G.1` Security-critical module)
- **Unit (JUnit5/Mockito):** each domain evaluator, deny-overrides combination, resolution, decision assembly, explanation.
- **Property-based (jqwik, `15` T-035):** *deny-overrides is total* (any domain DENY ⇒ overall DENY); *identical recorded inputs ⇒ identical decision + explanation* (determinism); *no PERMIT ever violates a hard domain*; *fail-closed on every uncertainty*.
- **Mutation (`15 §G.1`/§J.3 Security-critical):** **≥ 90%** on the Engine (authz/tenant-scope enforcement is the Security-critical tier); a mutant that flips deny-overrides, relaxes a hard domain, or permits on uncertainty must be killed.
- **Authorization negative-first (`15` T-031):** deny-by-default, RBAC+ABAC, cross-tenant impossible, tool authz, 403/404 model.
- **Chaos testing (`15` T-019):** stale/partitioned policy + usage snapshots ⇒ **fail closed (DENY)**, never silent permit; quota counter under contention correct within tolerance.
- **Failure injection (`15` T-018):** each denial class, stale hard-domain snapshot, over-quota/budget, flag-off, unauthorized-tool ⇒ correct binding domain + fail-closed.
- **Consistency/isolation (`15` T-027/T-044):** massive concurrency, no cross-tenant policy/usage leakage; VT no-pinning (`15` T-028).
- **Provider-neutrality (`15` T-015):** no provider branch; governance identical regardless of downstream provider.
- **Performance (`15` T-021/T-026, `16 §J.2`):** evaluation within budget; perf-smoke gate.

---

## M. Build-Failing Rules

### §45 — Build-failing architectural rules
| # | Rule | Gate |
|---|---|---|
| GV-A1 | No path to routing/invocation without a PERMIT `Decision` (non-bypass, AD-018) | ArchUnit + `15` T-018 |
| GV-A2 | Governance runs first; nothing governance-relevant reaches a provider un-governed | ArchUnit + contract test |
| GV-A3 | Deny-overrides only; no lower domain PERMIT overrides a higher DENY | property test (`15` T-035) |
| GV-A4 | No quota bypass; over-quota / stale-beyond-tolerance ⇒ DENY | fault test (§28) |
| GV-A5 | No budget/compliance bypass or downgrade | fault test (`15` T-031) |
| GV-A6 | No residency bypass or downgrade; residency scope always resolved/enforced | `15` T-045 |
| GV-A7 | No authorization bypass; no feature-flag gating a hard invariant off | `15` T-031, `16 §D-057` |
| GV-A8 | Fail closed (DENY) on any uncertainty; never silent permit | fault test (`15` T-018) |
| GV-A9 | No provider-name branch/switch/field (provider-neutral, GV-D11) | ArchUnit + `15` T-015 |
| GV-A10 | No persistence / private store (stateless, `06 §24`) | ArchUnit AU-07 |
| GV-A11 | No provider SDK / direct HTTP / credential access | ArchUnit AU-06 + secret scanner (`13 §20`) |
| GV-A12 | No policy authored/mutated in the Engine (PEP consumes, never authors) | ArchUnit |
| GV-A13 | No wall-clock/random in the decision core; no shared mutable per-request state | ArchUnit (`11` R-063/R-049) |
| GV-A14 | Every decision emits a content-free audit event; no content/secret in telemetry | `15` T-052, `14 §7.1` |
| GV-A15 | Mutation score ≥ 90% (Security-critical module, `15 §J.3`) | PITest |
| GV-A16 | Quota/budget increment atomic-at-tier; stale/partition ⇒ stricter (never looser) for hard caps; Engine never owns a ledger / never computes cost (§28.1) | contract test + chaos (`15` T-018/T-019) |
| GV-A17 | Router consumes `ResolvedGovernanceContext` read-only, narrows-only, never re-evaluates governance; no context without PERMIT; re-govern on TTL expiry; unknown field ⇒ fail closed (§36.1) | contract test (`15` T-008/T-018) |
| GV-A18 | RBAC+ABAC per the §14.1 truth table; every authz uncertainty (missing/unknown/stale attribute, unknown identity, indeterminate deny, policy error) ⇒ DENY | authz/property test (`15` T-031/T-035) |
| GV-A19 | Soft-grace never applied to a hard-classified cap; soft breach always audited (§28.3) | fault test (`15` T-018) |
| GV-A20 | External denial reason carries only the neutral binding-domain code; no rule detail / cross-tenant policy / enumeration oracle / raw attributes (§25) | scanner + `15` T-052 |

---

## N. Operations

### §46 — Operational runbook
- **Denial-rate spike (`gov_denials_total{binding_domain}`):** identify the binding domain — authz (identity/role change), compliance/residency (policy tightening or coverage gap), quota/budget (entitlement exhaustion → C8), flag (rollout), policy-unavailable (snapshot-pipeline health → C4/Config). Canary auto-aborts on threshold (`16 §E.2`).
- **`gov_policy_unavailable` spike:** snapshot-distribution health (AD-022 pipeline) — the Engine correctly fails closed; **fix the snapshot source, never disable the fail-closed gate**.
- **Quota/budget denial spike:** entitlement exhaustion or a usage-counter tier issue; verify bounded-staleness tier health; never relax quota to "unblock" (GV-INV).
- **Evaluation-latency spike:** check policy-set size + snapshot read path; policy compilation/indexing is a control-plane concern.
- **Policy tightening rollout:** confirm propagation within the bounded window (§12); audit the exposure window; for hard-domain tightenings confirm propagation before considering effective (control-plane, §12).

### §47 — Upgrade strategy
- Ships within the **data-plane release train** (`16` D-014/D-055): rolling/canary, zero-downtime (`16` D-031), governance-signal-gated (`16 §E.2`). Policy changes are **config/snapshot** (control plane) — no Engine redeploy needed to change policy. In-flight evaluations drained (`16` D-031) or fail closed.

### §48 — Backward compatibility
- The **governance contract** (`Decision`, `ResolvedGovernanceContext`, denial-reason taxonomy, resolved-scope shape) is a stable internal API (`12 §27`): changes are additive/backward-compatible within a major. Policy-snapshot schema evolves under versioned compat (`12 §27`). Emitted events evolve under Avro compat (`07 §9`, `16` D-033). Deterministic core (§23) guarantees identical decisions for identical recorded inputs across versions.

---

## Appendix A — Implementation notes *(non-normative; deferred Low items)*
- **Event naming (GVL-1):** the Engine emits governance-decision events within C4's already-owned event space (`06 §23`); exact names/topics reconciled with the `06 §23` registry — **no new topic owner**.
- **Benchmarks (GVL-2):** the evaluation hot path (resolve → deny-overrides evaluate → decide) has a JMH microbenchmark set in `benchmarks/` (`15` T-026, `16 §J.2`); the perf-smoke gate (`16 §J.2`) guards P99 added-latency regression.
- **Policy DSL / compiled-policy format (GVL-3):** the rule DSL and compiled-policy snapshot format are a **C4 control-plane authoring** concern (this document specifies enforcement, not authoring) — deferred to the C4 governance standard.
- **Shared quota-counter data structures (GVL-4):** the atomic per-(tenant,key) counter (§28.1) is a shared-in-memory-tier concern (`06 §376`); the Engine is tier-implementation-agnostic (contract §28.1, not a data-structure choice).

## O. Traceability

### §49 — Traceability matrix
| Governance concern | BR | NFR | ADR | Domain/Svc | API/Event | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|---|
| Non-bypass admission / GV-INV (GV-D1/§42) | BR-013 | NFR-GOV/SEC | AD-018/019 | C4 (`06`) | `12 §19` | §7 | `14` | T-018/T-031 | `16 §E.2` |
| Deny-overrides hierarchy (GV-D2/§9) | BR-013 | NFR-GOV | AD-018 | C4 | — | §7 | §31 | T-035 | — |
| Tenant policy / isolation (GV-D3/§15) | BR-021 | NFR-REL-002 | AD-021 | C4 | — | §15 | — | T-044 | — |
| RBAC + ABAC authz (GV-D4/§14) | BR-018 | NFR-SEC/AUTHZ | AD-019 | C6→C4 | `12 §19` | §7 | — | T-031 | — |
| Quota enforcement (GV-D5/§11/§28) | BR-013 | NFR-GOV/COST | AD-022 | C5/C8 | — | §13.1 | §31 | T-018 | — |
| Budget enforcement (GV-D6/§11/§28) | BR-005/012 | NFR-COST | AD-010/022 | C5/C8 (`19 §33.1`) | — | §13.1 | §31 | T-018 | — |
| Feature flags (GV-D7/§18) | BR-029 | NFR-CFG | AD-022 | C15 | — | — | §31 | T-031 | D-057 |
| Compliance (GV-D8/§16) | BR-021/024 | NFR-SEC/COMPL | AD-018 | C4 | — | §26 | — | T-031 | — |
| Residency (GV-D9/§17) | BR-023 | NFR-MR | AD-014 | C4→C1 | — | §15/§18 | §18.1 | T-045 | D-051 |
| Tool authorization (GV-D10/§19) | BR-018 | NFR-SEC | AD-019 | C4 | — | §7/§11 | — | T-031 | — |
| Provider-neutral governance (GV-D11) | BR-006 | NFR-IF-001 | AD-007 | C4 | `12 §16.10` | — | — | T-015 | — |
| Deterministic evaluation (GV-D12/§23) | BR-004 | NFR-GOV | AD-016 | `11` R-063 | `12 §14` | — | — | T-035 | — |
| Resolved-context → Router (§36/§36.1) | BR-013 | NFR-GOV/MR | AD-019/014 | C4→C1 (`19 §31`) | — | §15 | — | T-008 | — |
| Quota/budget coordination (§28.1/§28.3) | BR-013 | NFR-CONC/COST | AD-021/022/014 | C5/C8 + tier (`06 §376`) | — | §13.1 | §31 | T-018/T-019 | D-009 |
| RBAC+ABAC truth table (§14.1) | BR-018 | NFR-SEC/AUTHZ | AD-019 | C6→C4 | `12 §19` | §7 | — | T-031/T-035 | — |
| Policy-tightening propagation (§12.1) | BR-013/024 | NFR-GOV/COMPL | AD-019/022 | C4 CP | `12 §27` | §26 | — | T-018 | §I.1 |
| Explainability/sensitivity split (§25) | BR-024 | NFR-GOV/AUD/SEC | AD-009 | C10 | `12 §19` | §19 | §8 | T-052 | — |
| Quota vs rate-limit ownership (§20) | BR-013 | NFR-GOV | AD-018/019 | C4/C2 (`20 §17`) | — | §13.1 | — | T-031 | — |
| Policy lifecycle / versioning (§8/§12) | BR-013 | NFR-GOV/CFG | AD-019/022 | C4 CP | `12 §27` | — | — | T-018 | §I.1 |
| Shared quota counter tier (§28) | BR-013 | NFR-CONC/COST | AD-021/022 | in-memory tier (`06 §376`) | — | §13.1 | §31 | T-027/T-044 | — |
| Module in data plane, stateless (§34) | BR-005 | NFR-PERF | AD-020/006 | `06 §10/§24` | — | — | — | T-051 | D-014 |
| Explainability / denial reasons (§21/§22) | BR-024 | NFR-GOV/AUD | AD-009 | C10 | `12 §12/§19` | §19 | §8 | T-031 | — |
| Audit (§33/§40) | BR-024 | NFR-AUD-001 | AD-009 | C10 | `07 §6` | §19 | §8 | T-046 | — |
| Performance / VT (§26–29) | BR-005 | NFR-PERF/CONC | AD-020/023 | `06 §11` | — | §15 | §15 | T-021/T-028 | D-044 |
| Testing (Security-critical) (§44/§45) | BR-013/018 | NFR-TEST-001 | AD-016 | — | — | §27 | §19 | §G.1/§J.3/T-031 | §E.2 |
| Upgrade/back-compat (§47/§48) | BR-028 | NFR-UPG/VER | AD-015 | — | `12 §27` | — | — | T-049 | D-014/D-055 |

---

## P. Reviews

### 1. Internal Consistency Review
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-018 | non-bypassable governance | GV-D1/§9/§42 (first gate; no path without PERMIT) | ✅ |
| AD-019 | PEP/PDP | §4/§8/§39 (Engine = PEP; control plane = policy authority) | ✅ |
| AD-021 | tenant isolation | GV-D3/§15/§28 (tenant-scoped policy/usage; keyed counters) | ✅ |
| AD-014 | residency confinement | GV-D9/§17/§36 (resolve + admit; Router filters) | ✅ |
| AD-022 | cached snapshots / last-known-good | §8/§12/§13/§28 (consume snapshots; fail-closed on stale hard policy) | ✅ |
| AD-020/AD-006 | data plane co-located stateless module | §34 (no store), §43 (stateless) | ✅ |
| AD-002 | hexagonal replaceable adapters | §7 ports; snapshots via ports | ✅ |
| AD-016 | reliability first / deny-by-default fail-closed | GV-INV, §42 | ✅ |
| AD-007 | provider neutrality | GV-D11 (no provider branch) | ✅ |
| AD-023 | Java 21 + VT + ZGC | §29 (VT, no pinning), §27 | ✅ |
| `06` (C4 = Governance PEP) | PEP/Governance-enforcement module in DP | §1/§5 (C4 PEP module; CP authors) | ✅ |
| `06 §8` pipeline | authenticate → govern → route | GV-D1/§9 (govern first, before Router) | ✅ |
| `06 D-6` | quota enforcement Governance; entitlement Billing | GV-D5/§11 (enforce caps; C8 entitlements) | ✅ |
| `06 §23` | governance events owned by C4 | §33 (already-owned space, no new owner) | ✅ |
| `06 §24`/§376 | store-per-service; shared in-memory tier | §34/§28 (no store; frozen tier) | ✅ |
| `13` | deny-by-default, authz, 403/404, dual-control | GV-D2/§14/§22/§24 | ✅ |
| `12 §19` | authz 403/404 model | §22 (neutral denial, probing-resistant) | ✅ |
| `15 §G.1`/§J.3 | Security-critical module, mutation ≥90% | §44/§45 GV-A15 | ✅ |
| `16 §E.2/§I.1/§D-057` | governance canary; baselines; flags can't gate invariants | §31/§28/§12; GV-D7 | ✅ |
| `19 §31/§PR-D8/D9` | Router consumes C4-resolved policy | §36 (resolved context handoff; no re-eval) | ✅ |
| `20 §17/§30.1` | rate-limit authored C4/enforced C2; coordination model | §20/§41; §28.1 (mirror coordination, no new model) | ✅ |
| `06 §376` / AD-022 | shared in-memory tier, cached snapshots | §28.1 (quota counter on frozen tier, no new store) | ✅ |
| AD-014 / `16` D-009 | residency / regional independence | §28.1 GC-12 (regional quota coordination, no cross-region hot-path) | ✅ |
| `12 §19` / `13` | 403/404 authz, probing resistance | §14.1/§22/§25 (truth table, external minimal reasons) | ✅ |
| `16 §I.1` | operational baselines | §29.1 (all numerics), §12.1, §28.2, §36.1 TTL | ✅ |

**No contradictions found** against `00`–`20` and AD-001…AD-023. The corrective pass (§28.1/§28.2/§28.3, §36.1, §14.1, §12.1, §25, §20, §29.1, Appendix A, GV-A16…A20) is **additive only**: **no new ADR, no new service, no new module, no new store** (§28.1 uses the frozen shared in-memory tier `06 §376`), **no new consistency model** (reuses the frozen `20 §30.1` bounded-staleness model), **no ownership/event/data-ownership change, no responsibility moved, no consistency/security/deployment/observability/testing-model change, no provider coupling, and no invariant weakened** (GV-INV, non-bypass, deny-overrides, PEP/PDP separation, residency/compliance are *reinforced*). The coordination contract (§28.1) **specifies** the existing tier mechanism honestly; the `ResolvedGovernanceContext` (§36.1) **restates** the `19 §31` seam without moving responsibility; the RBAC+ABAC truth table (§14.1) **formalizes** existing deny-overrides semantics.

### 2. Architecture Validation
- **Non-bypassable admission (AD-018/019):** governance runs first; no PERMIT ⇒ no routing/invocation; deny-by-default (GV-D1/D2/§9). ✅
- **PEP/PDP separation (AD-019):** the Engine enforces; the control plane authors — no policy authored here (§4/§39/GV-A12). ✅
- **Deny-overrides safety:** any hard-domain DENY denies; a permit never masks a compliance/security/residency denial (GV-D2/GV-A3). ✅
- **Isolation (AD-021):** tenant-scoped policy/usage; (tenant,key)-keyed shared counter; no cross-tenant leakage (§15/§28). ✅
- **Residency/compliance (AD-014):** resolved + admitted at governance, filtered at Router, confined at data/deploy — defense-in-depth (GV-D8/D9/§36). ✅
- **Fail-closed (GV-INV):** every uncertainty ⇒ DENY; never silent permit/bypass/downgrade (§42). ✅
- **Provider neutrality (AD-007):** governance is provider-agnostic (GV-D11). ✅
- **Modular monolith (AD-020):** stateless module, no store, credential-free (§34/§7). ✅
- **Quota/budget coordination honesty (§28.1):** shared counter on the frozen tier with bounded-staleness eventual consistency; states what is and is NOT guaranteed; staleness/partition only make Governance *stricter* (deny-safe); C5 owns reconciliation. ✅
- **Governance→Router seam (§36.1):** single resolution authority (Engine); immutable, TTL'd `ResolvedGovernanceContext`; Router narrows-only, never re-governs — signed, testable. ✅
- **Authorization completeness (§14.1):** normative RBAC+ABAC truth table; every uncertainty fails closed. ✅
- Introduces **no new architecture decision**; every GV-Dx is an implementation choice inside frozen C4.

### 3. Adversarial Review (re-run after corrective pass)
*Board: Principal Enterprise Architect · Security Architect · Enterprise Governance Architect · Compliance Auditor · Distributed Systems Architect · AI Infrastructure Architect · Performance Engineer · CTO.*

**Accepted findings — resolution**
- **GVH-1 → RESOLVED (§28.1/§28.2/§28.3).** Quota/budget coordination contract GC-1…GC-14 on the frozen shared in-memory tier: authoritative ownership (C5 ledger / C8 limits; Governance enforces, owns neither), shared atomic per-(tenant,key) counter, bounded-staleness eventual consistency (NOT linearizable), **deny-safe** (staleness only stricter), stale/partition handling, fail-closed for hard caps, over-admit prevention (atomic CAS) + honest bounded over-admit reconciled by C5, under-admit (bounded false-deny) stated, regional (no cross-region hot-path), quota/budget exhaustion. **States what IS and IS NOT guaranteed** — no distributed-consistency overclaim. **Soft vs hard** classification (§28.3) prevents unnecessary mass-denial. Build-enforced (GV-A16/A19).
- **GVH-2 → RESOLVED (§36.1).** Signed `ResolvedGovernanceContext` contract RGC-1…RGC-8: immutable fields, ownership (Governance emits / Router consumes), lifecycle (PERMIT-only), TTL/`validUntil`, consumption rules (narrow-only, never widen/re-govern), refresh (re-govern on expiry), failure (fail closed on missing/expired), unknown-field (fail closed most-restrictive). **Router never re-evaluates governance.** Build-enforced (GV-A17).
- **GVH-3 → RESOLVED (§14.1).** Normative RBAC+ABAC contract: evaluation order, complete truth table, missing/unknown/stale attribute ⇒ DENY, deny precedence, deny-by-default, conflicting-rule resolution, unknown identity ⇒ DENY, policy error ⇒ DENY. **All uncertainty fails closed.** Build-enforced (GV-A18).
- **GVM-1 → RESOLVED (§12.1):** propagation ownership (C4 CP authors/propagates, Config distributes), bounded eventual-consistency window (baseline), tightening exposure window audited + max-bounded, hard-domain propagation confirmation referenced to control plane.
- **GVM-2 → RESOLVED (§25):** explicit external (neutral binding code only) vs internal (full, content-free, tenant-scoped) split with why; no cross-tenant/enumeration leak (GV-A20).
- **GVM-3 → RESOLVED (§20):** three-way ownership (C4 authors; Governance enforces quota/budget at admission; Reliability executes rate-limit at invocation); sequential independent gates; surfacing order pinned.
- **GVM-4 → RESOLVED (§29.1):** all numeric thresholds are operational-baseline entries; no immutable constants; never below frozen floors.
- **GVM-5 → RESOLVED (§28.3):** soft vs hard quota/budget behavior, ordering, failure mode, audit — hard = fail-closed DENY; soft = bounded audited grace/degrade.

**New findings from the re-run**
- **🔴 Critical:** none.
- **🟠 High:** none.
- **🟡 Medium:** none blocking. (All numeric baselines are governed operational-baseline entries per `16 §I.1`; the C4 control-plane policy-authoring/propagation-confirmation and the C5 usage-ledger *implementations* are cross-team deliverables referenced here and contract-/chaos-tested (`15` T-018/T-019/T-031) — noted as Low deferrals, not open design gaps.)
- **🟢 Low (deferred, non-blocking):**
  - **GVL-1** — governance event names/topics → reconcile with `06 §23` registry (Appendix A).
  - **GVL-2** — JMH benchmark set → `benchmarks/` (Appendix A).
  - **GVL-3** — policy-rule DSL / compiled-policy format → C4 control-plane authoring standard.
  - **GVL-4** — shared quota-counter data structures → detailed design (Appendix A).

### Architecture Readiness Score: **97 / 100**
All three High findings and all five Medium findings resolved additively, with **no new architecture, no new module/service/store, no new consistency model, no ownership/event/data-ownership change, no provider coupling, and no invariant weakened** (GV-INV/non-bypass/deny-overrides/PEP-PDP/residency/compliance reinforced). The quota/budget coordination contract, `ResolvedGovernanceContext`, and RBAC+ABAC truth table — the crux of governance correctness at scale — are now signed, testable, honest about their limits, and machine-enforceable (GV-A1…A20, Security-critical mutation ≥90%). Residual 3 points are honest cross-team-deliverable/calibration items (Low, deferred).

### Documentation Health: **96 / 100** · Implementation Readiness: **94 / 100**
Contradictions/gaps closed; the coordination model states exactly what it guarantees and what it does not; every load-bearing seam (quota counter, Router handoff, authorization, propagation, explainability, quota/rate-limit) is specified precisely enough to implement without inventing a distributed-consistency guarantee the design cannot keep.

### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`20` or AD-001…AD-023; no new architecture; no ownership/module/service/boundary/event/data/deployment/security/observability/testing change; no new consistency model; no provider coupling; no invariant weakened.

---

*End of document — 21-GovernanceEngine.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
