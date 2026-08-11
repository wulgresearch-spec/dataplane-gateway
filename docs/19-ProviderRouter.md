# 19 — Provider Router (Capability-Based Routing Engine · Domain C1)

**Document:** Component Implementation Architecture — Provider Router
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Version:** 1.0 (frozen 2026-07-21)
**Bounded context:** **C1 — Provider & Routing** (Core Domain, `05`/`06`)
**Module:** `dp-provider-router` — a **module inside the Data Plane** deployable, co-located per **AD-020/AD-006** — **not** a separate service, **owns no store**.
**Audience:** Routing/data-plane engineers, SRE, security, compliance, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`18` and ADRs **AD-001…AD-023**. Everything here is **additive**. Introduces **no new architecture decision, no new service, no new module, no new store, no ownership/event/data-ownership change, no consistency/security/deployment/observability/testing-model change, and no provider coupling.** Every PR-Dx is a *component-internal implementation decision* inside the already-frozen C1, expressed through hexagonal ports (AD-002).

> **What this is.** The complete implementation architecture of the **Provider Router** — the engine that, for every request, **selects where the request may go** and emits an **immutable, policy-safe routing decision** that C2 Reliability then executes (`06`: C1 → C2 → C3). The Router decides *which providers/models are eligible and in what order*; it **never** invokes providers, holds credentials, or authors policy. It is the concrete realization of C1's routing responsibility, kept **fully provider-neutral** (AD-007). Every major decision carries **Problem · Decision · Alternatives · Why selected · Trade-offs · Failure modes · Security impact · Performance impact · Enforcement**.
>
> **THE PROVIDER-ROUTER INVARIANT (PR-INV):** *The Provider Router must never route a request to a destination that violates tenant policy, residency, compliance, capability requirements, cost policy, availability policy, or reliability policy. On any uncertainty it fails closed and surfaces — it never guesses, never silently downgrades, never silently changes compliance, never silently changes region.* This is the routing-layer realization of **AD-016 (reliability first)**, **AD-018 (non-bypassable governance)**, **AD-021 (isolation)**, and **AD-014 (residency)**.

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Interfaces** (§6–7) · **C. Major Decisions** (PR-D1…PR-D12) · **D. Routing Algorithm** (§8–15) · **E. Determinism & Snapshot Trust** (§16–17) · **F. Failure Handling** (§18) · **G. Performance** (§19–22) · **H. Security** (§23) · **I. Observability** (§24–27) · **J. Data & Integration** (§28–34) · **K. Testing** (§35) · **L. Build-Failing Rules** (§36) · **M. Operations** (§37–39) · **N. Traceability** (§40) · **O. Reviews**

---

## A. Charter

### §1 — Purpose
The Provider Router **selects the eligible destination(s)** for every request and emits an **immutable routing decision** — an ordered, fully policy-filtered candidate set — for C2 Reliability to execute (`06`). It guarantees that **no request is ever routed anywhere that violates a hard constraint** (PR-INV), across all providers (AD-007) and deployment targets (`16`).

### §2 — Scope
- **In scope:** normalize request requirements; resolve applicable policy/compliance/residency/cost constraints (from cached snapshots, AD-022); generate, **hard-filter**, and **score** provider/model candidates by **canonical capability**; produce a **deterministic, immutable routing decision** (primary + ranked, pre-filtered failover candidates); emit routing telemetry + the frozen `RequestRouted` event (`06 §23`).
- **Applies to:** every request on the hot path, before C2 Reliability executes invocation.

### §3 — Responsibilities
1. Normalize the request's **requirements** into canonical capability + constraint requirements (§9).
2. Resolve the applicable **routing policy, compliance, residency, cost, availability, reliability** constraints from cached snapshots (§28–33, AD-022/AD-019).
3. **Filter** the candidate set against **hard constraints** in strict policy-hierarchy order (PR-D2, §11).
4. **Score** survivors against **soft preferences** and select a winner **deterministically** (§12–13).
5. Emit an **immutable routing decision** (ordered eligible candidates, all hard-filtered so C2 failover is always safe) and the `RequestRouted` event (§14/§30).
6. Fail closed and surface on any uncertainty (§18).

### §4 — Out of Scope (what the Router never does)
- **Invoking providers / making network calls** — the **Provider Adapters** (C1) invoke; **C2 Reliability** executes/retries/fails-over (`06`). The Router only *decides*.
- **Authoring policy / compliance / residency rules** — owned by **C4 Governance** + **Configuration** (`06`, AD-019). The Router **enforces** resolved constraints; it does not author them.
- **Owning provider health** — owned by **Provider Registry** (C1 control plane, `06 §23` `ProviderHealthChanged`). The Router **consumes** health snapshots (PR-D6).
- **Computing cost / billing** — owned by **Metering & Cost (C5)** / **Billing (C8)**. The Router **consumes** normalized cost + entitlement/budget descriptors; it never estimates a bill (PR-D7).
- **Holding credentials / secrets / API keys** — owned by **Secrets (C14)**, used by adapters. The Router is **credential-free** (PR-D12).
- **Correctness / streaming / retry** — owned by C3 (SchemaLock `17`, StreamGuard `18`) and C2 Reliability.

### §5 — What the Router is NOT
| Provider Router (C1 — decision) | Not the Router |
|---|---|
| ✓ decide *which* provider/model is eligible, in what order | ✗ invoke the provider (C2 + Adapters) |
| ✓ enforce resolved policy/compliance/residency as hard filters | ✗ author policy/compliance rules (C4/Config) |
| ✓ consume canonical capability/health/cost snapshots | ✗ own health/cost/billing (Provider Registry / C5 / C8) |
| ✓ emit an immutable routing decision + `RequestRouted` | ✗ retry/failover execution (C2) |
| ✓ credential-free routing | ✗ hold secrets or call HTTP (Adapters + Secrets C14) |

---

## B. Domain & Interfaces

### §6 — Domain model (routing subdomain)
Pure domain types (no infrastructure, `11` hexagonal); immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `RoutingRequest` | VO | Neutral request context: tenant/correlation ids (`07 §6`), required capabilities, declared constraints (residency scope, compliance regime), soft preferences |
| `CapabilityRequirement` | VO | Canonical required capabilities (e.g. `structured_output`, `tool_calling`, `vision`, `streaming`, `context_length≥N`) — **provider-neutral** (PR-D1/D3) |
| `Constraint` | VO | A resolved hard constraint (tenant-policy allow/deny, compliance regime, residency scope, availability floor, reliability floor, cost ceiling) |
| `CapabilityDescriptor` | VO (immutable, snapshot) | A provider/model's **canonical** published capabilities + attributes (residency support, compliance attestations, normalized cost, context length) — from the registry snapshot |
| `Candidate` | VO | An eligible provider/model route under evaluation |
| `RoutingPolicy` | VO | The resolved policy hierarchy + weights, from cached snapshot (authored by C4/Config) |
| `RoutingDecision` | VO (immutable) | Ordered, hard-filtered candidate set (primary + ranked failover) + decision rationale + decision id (§14) |
| `RoutingFailure` | VO | Neutral failure with the **binding constraint** that eliminated all candidates (§18) |
| `Snapshots` | VO (injected) | Cached last-known-good: capability, health, policy, compliance, residency, cost, entitlement (AD-022) |

**Aggregate:** `RoutingEvaluation` — the transient per-request aggregate coordinating normalize → resolve → filter → score → decide; holds **no shared mutable state across requests** (AD-021).

### §7 — Public interfaces (ports — AD-002 hexagonal)
**Inbound (called by the data-plane request pipeline, after auth/governance PEP, before C2):**
```
ProviderRouterPort:
  route(RoutingRequest) -> RoutingDecision | RoutingFailure   // deterministic, fail-closed
```
**Outbound (implemented by adapters elsewhere; all reads are cached snapshots, no hot-path network):**
```
CapabilityRegistryPort   // <- C1 Provider Registry / adapters: canonical CapabilityDescriptors (snapshot, AD-022)
PolicySnapshotPort       // <- C4 Governance / Configuration: resolved routing/compliance/residency policy (snapshot, AD-019/022)
HealthSnapshotPort       // <- C1 Provider Registry: provider availability/health (snapshot)
CostSnapshotPort         // <- C5 Metering / C8 Billing: normalized cost + entitlement/budget (snapshot)
RoutingDecisionSinkPort  // -> C2 Reliability (in-proc handoff of the immutable decision)
AuditSinkPort            // -> Audit (C10): RequestRouted + routing-failure events (content-free)
MeteringSinkPort         // -> Metering (C5): routing counters (content-free)
TelemetryPort            // -> Observability (C9): routing metrics/traces (content-free)
ClockPort / IdPort       // deterministic time / ids
```
- **Enforcement:** ArchUnit — the Router imports **no** provider SDK (AU-06), **no** HTTP client, **no** persistence driver (AU-07), **no** secrets/credential API, **no** correctness/streaming/retry types. All inputs are **cached snapshots**; the only output is a `RoutingDecision`/`RoutingFailure`. **Build-Fail:** any such forbidden import; any hot-path network call in the Router.

---

## C. Major Decisions

### PR-D1 — Capability-based routing (never by provider identity)
- **Problem:** routing by provider name hard-codes provider knowledge, breaks neutrality (AD-007), and rots as providers change.
- **Decision:** the Router routes **only by normalized, canonical capabilities** — e.g. `structured_output`, `tool_calling` / `function_calling`, `vision`, `audio`, `reasoning`, `streaming`, `json_mode`, `embeddings`, `context_length`, `max_output_tokens`, etc. A request declares **required capabilities**; candidates are eligible iff their `CapabilityDescriptor` satisfies them. **No routing logic ever branches on a provider name.**
- **Alternatives:** (a) provider-name routing tables — rejected (coupling, AD-007 violation, `06`); (b) hybrid name+capability — rejected (name path still couples).
- **Why selected:** neutral, future-proof (new provider = new descriptor, §PR-D11), auditable, testable.
- **Trade-offs:** requires a **rigorous canonical capability taxonomy** (governed, §PR-D3/§9.1); coarse capabilities risk under-constraining (adversarially flagged).
- **Failure modes:** no candidate satisfies a required capability ⇒ fail closed (`CAPABILITY_UNSATISFIED`, §18).
- **Security impact:** no provider identity in routing logic reduces coupling-driven leakage.
- **Performance impact:** capability match is O(required × candidates), cheap over bounded candidate sets (§21).
- **Enforcement:** ArchUnit — no provider-name literal / switch in routing logic (PR-A5); conformance tests (`15` T-015). **Build-Fail:** a provider-name branch/switch/table in the Router.

### PR-D2 — Routing policy hierarchy (strict priority, higher never yields to lower)
- **Problem:** conflicting objectives (compliance vs cost vs latency) need an unambiguous, non-negotiable precedence.
- **Decision:** a **strict, ordered hierarchy** governs routing; a **higher priority is never violated to satisfy a lower one**:
  ```
  1. Tenant Policy      (hard: allow/deny, contractual routing rules)
  2. Compliance         (hard: HIPAA/GDPR/SOC2/… attestation required)
  3. Residency          (hard: region-permitted only)
  4. Capability         (hard: required capabilities satisfied)
  5. Availability       (hard: health/circuit acceptable)
  6. Reliability policy (hard: reliability floor)
  ───────────── hard filters above · soft scoring below ─────────────
  7. Cost               (soft: within ceiling, then minimize)
  8. Latency            (soft: minimize expected added latency)
  9. Preferred Provider (soft: tenant *preference* hint — lowest)
  ```
  Levels **1–6 are eliminative hard filters** (a candidate failing any is removed); **7–9 are soft scoring** among survivors. **Tenant *policy*** (level 1, hard allow/deny/contract) is distinct from **tenant *preferred provider*** (level 9, a soft hint) — the former is inviolable, the latter is a tiebreak.
- **Alternatives:** (a) weighted blend of all objectives — rejected (a high enough cost weight could override compliance — unacceptable); (b) configurable order — rejected (compliance/residency must never be demotable; the hard tiers are fixed by invariant).
- **Why selected:** compliance/residency/tenant-policy can **never** be traded away for cost/latency; the ordering is a direct encoding of PR-INV.
- **Trade-offs:** cost/latency optimize **only among equally-compliant-capable-available** candidates — cost is never a reason to cross a hard tier. This is intended (and adversarially flagged: cost has limited leverage).
- **Failure modes:** hard filters empty the set ⇒ fail closed with the **binding tier** named (§18).
- **Security/compliance impact:** the hierarchy is the enforcement of residency/compliance/isolation invariants.
- **Performance impact:** filters short-circuit (cheapest, most-eliminative hard filters can run first internally without changing the *outcome* ordering, §11.1).
- **Enforcement:** the hard/soft split is fixed in code; hard tiers are non-configurable. **Build-Fail:** any code path where a soft objective (cost/latency/preference) can override a hard tier (PR-A6/A7/A8).

### PR-D3 — Provider-neutral capability registry (adapters publish; Router consumes canonical descriptors)
- **Problem:** the Router must know provider capabilities without knowing providers.
- **Decision:** **Provider Adapters publish** their capabilities to the **Capability Registry** (C1, `06 D-4`); the Registry produces **canonical `CapabilityDescriptor` snapshots** (AD-022); the Router **consumes only canonical descriptors** and **never branches on provider names**. The canonical capability taxonomy is a **governed, versioned vocabulary** (§9.1) — adapters map their provider's features *into* it; the Router reads *only* the canonical form.
- **Alternatives:** Router queries providers live — rejected (hot-path network, coupling); Router holds provider feature tables — rejected (coupling, staleness).
- **Why selected:** neutral, extensible, snapshot-fast, single source of canonical truth.
- **Trade-offs:** the taxonomy must be expressive enough (governed evolution, §PR-D10); adapters bear the mapping burden.
- **Failure modes:** a descriptor missing a required attribute ⇒ that candidate treated as **not satisfying** (fail-safe conservative) ⇒ eliminated (never assumed-capable).
- **Security impact:** descriptors carry **no secrets** (capabilities/attributes only).
- **Performance impact:** O(1) snapshot read.
- **Enforcement:** Router reads only `CapabilityDescriptor` (canonical); ArchUnit forbids provider-name access. **Build-Fail:** the Router consuming a non-canonical/provider-specific capability field.

### PR-D4 — Routing decision lifecycle (documented end to end)
- **Problem:** the decision path must be explicit, auditable, and non-bypassable.
- **Decision:** the fixed lifecycle (detailed in §8–15):
  ```
  Receive request → Normalize requirements → Resolve policy →
  Filter eligible providers (hard tiers) → Score candidates (soft) →
  Select winner → Emit immutable routing decision → Pass to C2 Reliability
  ```
  Each step is pure/deterministic and produces inputs for the next; the **immutable decision** (with rationale) is emitted once and handed to C2. There is **no path** that reaches C2 without a completed decision (non-bypass, AD-018).
- **Alternatives:** interleave invocation with decision — rejected (Router must not invoke, §4).
- **Why selected:** clean separation, auditable, testable per step.
- **Trade-offs:** decision is computed fully before handoff (no partial decisions) — correct for integrity.
- **Failure modes:** any step failing ⇒ fail closed (§18).
- **Security/Performance impact:** one bounded pass; decision immutable + audited (§30).
- **Enforcement:** ArchUnit — no invocation/HTTP in the Router; decision emitted before C2 handoff. **Build-Fail:** a C2 handoff without a completed `RoutingDecision`.

### PR-D5 — Deterministic routing
- **Problem:** routing must be **reproducible** (audit, replay, testing, incident forensics); nondeterminism defeats all four.
- **Decision:** **identical `RoutingRequest` + identical resolved policy + identical provider-state snapshots ⇒ identical `RoutingDecision`.** The Router is a **pure function** of its inputs; no wall-clock/random in the decision core (`11` R-063) — time/ids via `ClockPort`/`IdPort`; scoring and tie-breaking are **deterministic** (§13). Load distribution across requests is achieved by a **deterministic tiebreak keyed on the request's stable id** (same request → same choice; different requests spread) — *not* randomness (§13.1).
- **Alternatives:** random load-balancing — rejected (non-reproducible, un-auditable); round-robin with mutable counter — rejected (shared mutable state, AD-021; non-deterministic under concurrency).
- **Why selected:** reproducible for audit/replay/test while still distributing load.
- **Trade-offs:** determinism is over the **recorded inputs**; because health/cost/load snapshots change continuously, two *wall-clock-separated* requests rarely have "identical state" — determinism here means **reproducible from the recorded snapshot set**, which is exactly what audit/replay need (stated honestly, §16).
- **Failure modes:** none from determinism; it is a guardrail.
- **Security/Performance impact:** cache-friendly; enables property/mutation testing (§35).
- **Enforcement:** ArchUnit forbids wall-clock/random in the decision core; replay tests (`15` T-035). **Build-Fail:** wall-clock/random in the routing decision core; a mutable shared load-balancer counter.

### PR-D6 — Provider health integration (consumes, never owns)
- **Problem:** availability filtering needs health, but health ownership must not drift into the Router.
- **Decision:** the Router **consumes** provider health/availability from **snapshots** (owned by **Provider Registry**, `06 §23` `ProviderHealthChanged`, AD-022); it **never owns, computes, or mutates health**. Health feeds the **Availability** hard filter (tier 5) and the reliability score. On health-snapshot staleness beyond tolerance, availability is treated **conservatively** (§17).
- **Alternatives:** Router probes providers — rejected (hot-path network, ownership drift); Router infers health from its own errors — rejected (it doesn't invoke).
- **Why selected:** clean ownership, snapshot-fast, no drift.
- **Trade-offs:** health is as fresh as the snapshot (staleness handled in §17).
- **Failure modes:** all candidates unhealthy ⇒ fail closed (`NO_AVAILABLE_PROVIDER`, §18).
- **Security/Performance impact:** O(1) snapshot read; no probing surface.
- **Enforcement:** ArchUnit — no health mutation/computation in the Router. **Build-Fail:** the Router writing/owning health state.

### PR-D7 — Cost awareness (consumes normalized cost; never estimates billing)
- **Problem:** cost-aware routing must respect budgets/ceilings/contracts without the Router becoming a billing engine.
- **Decision:** the Router **consumes normalized cost descriptors + tenant entitlement/budget/ceiling** from snapshots (owned by **C5 Metering** / **C8 Billing**, AD-022); it applies **budget/cost-ceiling as a hard tier when a ceiling is set** (a candidate over the tenant's hard ceiling is eliminated) and **minimizes cost as a soft objective** (tier 7) among survivors. It **never estimates a bill, never computes charges** — it reads normalized comparative cost only.
- **Alternatives:** Router computes provider pricing — rejected (billing is C5's; duplication/drift); ignore cost — rejected (BR cost goals).
- **Why selected:** cost-aware without cost-ownership; respects enterprise contracts via entitlement snapshots.
- **Trade-offs:** depends on someone (C5) providing **normalized** cost across heterogeneous provider pricing models — an explicit upstream dependency (adversarially flagged).
- **Failure modes:** budget exceeded for all candidates ⇒ fail closed (`BUDGET_EXCEEDED`, §18) — **never** silently route over budget.
- **Security/Performance impact:** O(1) snapshot read.
- **Enforcement:** ArchUnit — no billing/charge computation in the Router. **Build-Fail:** the Router computing a monetary charge/bill.

### PR-D8 — Residency (never routes outside allowed regions; no cross-residency fallback)
- **Problem:** residency is an absolute invariant (AD-014); a routing or **failover** slip is a compliance breach.
- **Decision:** residency is a **hard filter (tier 3)**: only providers/models attested to serve the request's **permitted region(s)** survive. **All candidates in the emitted decision — including every failover candidate — are residency-permitted**, so **any C2 failover is residency-safe by construction** (§30). There is **no cross-residency fallback, ever.** A request whose residency scope has no permitted provider ⇒ **fail closed** (`RESIDENCY_CONFLICT`), never a cross-region route.
- **Alternatives:** allow a "best-effort" cross-region fallback under load — rejected (residency invariant, non-negotiable, AD-014).
- **Why selected:** residency confinement is inviolable and is enforced *before* the candidate set is handed to C2 (so failover cannot breach it).
- **Trade-offs:** a residency-constrained tenant with a single in-region provider has no failover diversity — correct (surfaced availability, not a residency breach).
- **Failure modes:** empty after residency filter ⇒ fail closed.
- **Security/compliance impact:** primary residency-confinement control at the routing layer.
- **Performance impact:** O(candidates) attribute check.
- **Enforcement:** residency-conflict tests (`15 §I.1`, T-045); every decision candidate is residency-checked. **Build-Fail:** a decision containing a non-permitted-region candidate; any cross-residency fallback path.

### PR-D9 — Compliance (HIPAA/GDPR/SOC2/…; filtered before routing)
- **Problem:** regulated tenants require providers holding the right attestations; compliance cannot be an afterthought.
- **Decision:** compliance is a **hard filter (tier 2)** applied **before** capability/availability/cost scoring: only providers/models whose `CapabilityDescriptor` attests the **required compliance regime(s)** (HIPAA, GDPR, SOC2, etc., resolved from the tenant's policy snapshot) survive. Compliance requirements come from **C4 Governance/Config** (the Router enforces, does not author). A compliance-empty set ⇒ **fail closed** (`COMPLIANCE_CONFLICT`), never a downgrade.
- **Alternatives:** post-hoc compliance check after routing — rejected (could route then reject, wasteful and risky); Router authoring compliance rules — rejected (ownership, C4).
- **Why selected:** compliance is enforced up front and can never be traded for cost/latency (tier 2 > 7/8).
- **Trade-offs:** requires accurate provider compliance attestations in descriptors (governed).
- **Failure modes:** empty after compliance filter ⇒ fail closed.
- **Security/compliance impact:** core regulated-routing control.
- **Performance impact:** O(candidates).
- **Enforcement:** compliance-filter tests; audit records the applied regime (§30). **Build-Fail:** a decision candidate lacking a required compliance attestation.

### PR-D10 — Version neutrality (providers change; capability contracts stay stable)
- **Problem:** providers add/rename/deprecate models and features constantly; the Router must not churn.
- **Decision:** the Router depends on the **stable canonical capability contract**, not on provider versions. A provider model change updates its **adapter-published descriptor** (§PR-D3); the Router's logic is **unchanged**. The capability taxonomy evolves **additively** (new capability keys are backward-compatible, `12 §27`); a removed provider model simply disappears from the snapshot.
- **Alternatives:** version-pinned routing tables — rejected (churn, coupling).
- **Why selected:** stable Router across a 10-year provider landscape.
- **Trade-offs:** taxonomy governance is required (§9.1).
- **Failure modes:** a descriptor referencing an unknown capability key ⇒ that key ignored for matching (conservative) / flagged for taxonomy review.
- **Security/Performance impact:** neutral.
- **Enforcement:** capability-taxonomy is versioned + backward-compatible (`12 §27`). **Build-Fail:** a Router change required solely to onboard a provider (should be adapter-only, §PR-D11).

### PR-D11 — Extensibility (new provider = new adapter only; no Router change)
- **Problem:** onboarding a provider must not touch core routing.
- **Decision:** adding a provider requires **only a new Provider Adapter** (C1) that (a) publishes a canonical `CapabilityDescriptor` and (b) implements invocation behind the frozen adapter port (AD-002/AD-007). **The Router is not modified.** This is the neutrality dividend of PR-D1/D3.
- **Alternatives:** per-provider routing config in the Router — rejected (coupling).
- **Why selected:** open-closed; the Router is closed to modification, open to new providers via adapters.
- **Trade-offs:** the adapter contract must be expressive enough (owned by C1 adapter spec, referenced not redefined here).
- **Failure modes:** an adapter publishing an incomplete descriptor ⇒ conservative elimination (never assumed-capable).
- **Security/Performance impact:** neutral.
- **Enforcement:** conformance suite adds a provider with **zero Router diff** (`15` T-014/T-015). **Build-Fail:** a Router code change whose sole purpose is provider onboarding.

### PR-D12 — Security (credential-free, SDK-free, HTTP-free; ports only)
- **Problem:** the Router sits on the hot path with tenant context; it must present **zero** credential/SDK/network surface.
- **Decision:** the Router holds **no secrets, no credentials, no API keys, no provider SDKs, and makes no direct HTTP**. All inputs are **cached snapshots via ports**; the only output is a `RoutingDecision`. Credentials live in **Secrets (C14)** and are used only by adapters at invocation time (C2/adapters), never by the Router.
- **Alternatives:** Router validates provider auth — rejected (credential surface, ownership drift).
- **Why selected:** minimal attack surface; the routing decision names a *route*, not a *credential*.
- **Trade-offs:** none material.
- **Failure modes:** n/a (no credential path exists to fail).
- **Security impact:** eliminates credential leakage from the routing layer.
- **Performance impact:** neutral.
- **Enforcement:** ArchUnit — no secrets API, no HTTP client, no provider SDK in the Router (PR-A1/A2/A3/A4). **Build-Fail:** any credential/HTTP/SDK reference in the Router.

---

## D. Routing Algorithm

### §8 — Overview
The algorithm is a **deterministic, single-pass pipeline over a bounded candidate set**, using only cached snapshots (no hot-path network): **normalize → resolve → hard-filter (tiers 1–6) → soft-score (tiers 7–9) → select → emit immutable decision.**

### §9 — Candidate generation
- The initial candidate set is **every provider/model with a `CapabilityDescriptor` in the current snapshot** (bounded set, §21). No provider is special-cased.

### §9.1 — Requirement normalization (canonical taxonomy)
- The request's stated needs are normalized into **`CapabilityRequirement`** over the **governed canonical capability taxonomy** (PR-D3, formalized in §9.2 + Appendix A) — e.g. required `{structured_output, tool_calling, streaming, context_length≥N}` plus constraint requirements (`residency=EU`, `compliance⊇{HIPAA}`, `cost_ceiling=X`). The taxonomy is **versioned** (`12 §27`) and provider-neutral. **Granularity note (honest):** a capability is matched at the taxonomy's granularity; where a capability has *modes* (e.g. native-schema vs prompt-constrained structured output, `17 §14`), the descriptor exposes the **mode attribute** (a canonical parameter, §9.2) so the Router can match the *required* mode — it does not conflate "supports structured output somehow" with "supports native structured-output enforcement" (§35 conformance-tested against SchemaLock's strategy needs).

### §9.2 — Canonical Capability Model (closed, versioned taxonomy) *(resolves High PRH-2 — additive; formalizes PR-D1/D3, no provider coupling)*

The taxonomy is a **closed, governed, versioned vocabulary**. Provider adapters **map provider-native features into it**; the Provider Router **consumes ONLY canonical `CapabilityDescriptor`s** and **never provider-native fields** and **never branches on provider names** (PR-A5). Adding a capability is a governed, backward-compatible vocabulary change (`12 §27`, §PR-D10); it is **not** a Router code change.

**Every canonical capability carries this shape:**
| Field | Meaning |
|---|---|
| **canonical identifier** | stable canonical key (e.g. `structured_output`) — the only thing the Router matches on |
| **version** | capability-schema version (additive/backward-compatible, `12 §27`) |
| **optional parameters** | canonical, provider-neutral parameters/modes (e.g. `mode ∈ {native_schema, json_mode, prompt_constrained}`, `max_context`, `max_output`, `formats`) |
| **capability state** | `supported` / `unsupported` / `preview` / `deprecated` (a `preview`/`deprecated` state is honored per policy; **`unsupported` or absent ⇒ not eligible**) |
| **compatibility rules** | canonical constraints between capabilities (e.g. `structured_output` requires `json_mode` or `tool_calling`; `streaming` compatibility with `tool_calling`) — expressed in canonical terms only |

**The closed capability categories (canonical identifiers — full vocabulary in Appendix A):**
`structured_output` · `tool_calling` · `function_calling` · `streaming` · `json_mode` · `vision` · `image_generation` · `audio_input` · `audio_output` · `embeddings` · `reasoning` · `long_context` · `batch` · `fine_tuning` · `vector_search` · `safety` · `moderation` · `prompt_caching` · `computer_use` · `mcp` · **+ governed future extensions** (added additively under the same shape).

- **Consumption rule (normative):** the Router reads **canonical identifiers + parameters + state + compatibility rules** only. It **MUST NOT** read any provider-native capability field, provider model name, or provider-specific attribute.
- **Unknown-capability rule (normative, fail-closed):** a **required** capability the taxonomy does not know, or a candidate descriptor with a **required capability absent/`unsupported`**, ⇒ that candidate is **not eligible** (never assumed-capable); if **no** candidate satisfies a required capability ⇒ **fail closed** (`CAPABILITY_UNSATISFIED`, §18). Unknown capabilities **fail closed**, never route-through.
- **Enforcement (PR-A5 extended + PR-A16):** ArchUnit — the Router references only `CapabilityDescriptor` canonical fields; no provider-native field/name. Conformance tests (`15` T-014/T-015) prove capability-mode granularity matches `17`'s strategy needs and that adding a provider is adapter-only. **Build-Fail:** the Router reading a provider-native capability field or model name; an unknown/absent required capability treated as satisfied.

### §10 — Policy resolution
- The Router resolves the applicable **`RoutingPolicy`** (hierarchy weights + tenant allow/deny/contract), **compliance regime(s)**, **residency scope**, **cost ceiling/budget/entitlement**, **availability floor**, and **reliability floor** from **cached snapshots** (AD-022; authored by C4 Governance/Config, C5/C8 for cost). Resolution is **read-only**; the Router enforces, never authors (§4).

### §11 — Candidate filtering (hard tiers 1–6, eliminative)
Applied so that **the outcome respects the strict hierarchy (PR-D2)**; a candidate failing **any** hard tier is removed:
1. **Tenant Policy** — deny-listed / contract-excluded routes removed.
2. **Compliance** — candidates lacking a required attestation removed (PR-D9).
3. **Residency** — candidates not permitted for the region(s) removed (PR-D8).
4. **Capability** — candidates not satisfying **all** required capabilities (at required mode/granularity, §9.1) removed (PR-D1).
5. **Availability** — candidates below the health/availability floor (from snapshot, PR-D6) removed.
6. **Reliability policy** — candidates below the reliability floor / with an open circuit (reliability snapshot) removed.
- **If the set becomes empty at any tier, the Router fails closed and names the *binding tier*** (§18) — it never relaxes a hard tier to keep a candidate.

### §11.1 — Filter ordering (optimization that never changes the outcome)
- Internally the Router **may** run the most-eliminative/cheapest hard filters first for speed, because **hard filters are commutative for the eligibility outcome** (a candidate must pass *all* of tiers 1–6 to be eligible; order of elimination doesn't change the surviving set). The **failure attribution** (which binding tier) is reported per the **canonical hierarchy order** (the highest-priority tier that the request fundamentally conflicts with), so the surfaced reason is stable and deterministic regardless of internal filter order.

### §12 — Candidate scoring (soft tiers 7–9, among survivors)
- Survivors are scored by a **deterministic composite**: (7) **cost** (within ceiling, lower is better, from normalized cost snapshot, §33.1), (8) **latency** (lower expected added latency is better, from the latency/health snapshot), (9) **preferred-provider** (tenant soft hint bonus). Scoring **cannot** reorder across the hard/soft boundary — it only ranks **already-eligible** candidates.
- **Cost contributes only after all hard tiers succeed (resolves PRM-2):** soft scoring — including cost — runs **exclusively over the survivors of hard tiers 1–6**. **Compliance, residency, and tenant policy can NEVER be traded for cost savings** (or latency, or preference): a cheaper non-compliant/non-permitted candidate was already eliminated at tiers 2–3 before scoring ever ran. Cost has leverage **only among equally-compliant-capable-available-reliable** candidates.
- **Weight ownership (resolves PRM-4):** the composite **weights come from the resolved `RoutingPolicy`** (authored by **C4 Governance / Configuration**, AD-019/022). The **Provider Router consumes these weights; it does not own or author routing policy** (§4/§31). Changing routing weights is a governance/config change at C4, not a Router change.

### §13 — Selection & tie-breaking (deterministic)
- The highest-scoring survivor is the **primary**; the remainder are ranked as **failover candidates** (all already hard-filtered, so failover is always policy-safe, §30). **Ties** are broken **deterministically** (§13.1) — never randomly.

### §13.1 — Deterministic tiebreak with load distribution
- Among candidates with **equal composite score**, selection uses a **deterministic function of the request's stable id** (e.g. a stable hash of `requestId` mapped over the tied set, optionally weighted by a policy-provided distribution). This is **reproducible** (same request ⇒ same choice, PR-D5) **and** distributes load across the request population (different `requestId`s spread over the tied set) — **without randomness or shared mutable state** (AD-021). Weighted distribution (e.g. 70/30 across two equal-score providers) is expressed as deterministic bucketing of the request-id hash.

### §14 — Routing decision emission (immutable)
- The Router emits an **immutable `RoutingDecision`**:
```
RoutingDecision {
  decisionId, correlationId, tenantScope          // ids only (07 §6)
  candidates: [ordered: primary, failover…]        // ALL hard-filtered (tiers 1–6 passed)
  appliedConstraints: {compliance, residency, capability, availability, reliability, costCeiling}
  scoringRationale: {per-candidate soft scores}    // provider-neutral, no secrets
  snapshotVersions: {capability, health, policy, cost}  // for reproducibility/audit
  validUntil / ttl                                 // decision TTL (operational baseline, §17/§30.1 RC-5)
}
```
- The decision is **handed to C2 Reliability** (§30) and the frozen **`RequestRouted`** event (`06 §23`, ZL, owned by C1) is emitted (content-free, §27). The decision **names routes, never credentials** (PR-D12).

### §15 — Complexity (stated honestly)
- Let **P** = number of provider/model candidates in the snapshot, **R** = required capabilities, **F** = hard filters (6):
  - Candidate generation: **O(P)** (snapshot iteration).
  - Hard filtering: **O(P × (F + R))** — a bounded constant per candidate; short-circuits on first failed filter.
  - Scoring: **O(P′)** to score survivors + **O(P′ log P′)** to rank (P′ ≤ P).
  - All snapshot lookups are **O(1)** (in-memory, AD-022); **no network calls on the hot path.**
- **Honest statement:** this is **linear in the candidate count P**, dominated by memory reads. P is bounded (tens to low hundreds of provider/model combinations); at that scale the whole routing decision is **sub-millisecond CPU work** and fits comfortably within the added-latency budget (`06 §11`). The Router does **not** claim O(1) routing — it claims **bounded linear** over a small, bounded P. If P ever grew unbounded, pre-indexing by capability/region would be required (noted, §21) — but P is bounded by the provider landscape, not by traffic.

---

## E. Determinism & Snapshot Trust

### §16 — Determinism scope (honest)
- The routing decision is a **pure, deterministic function** of `(RoutingRequest, resolved policy, provider-state snapshots)` (PR-D5). Because provider-state snapshots (health/cost/load) change continuously, determinism is **"reproducible from the recorded input set,"** not "stable across wall-clock time." This is exactly the property needed for **audit, replay, incident forensics, and testing** — given the recorded snapshot versions (§14), the decision can be **exactly reproduced**. The Router records `snapshotVersions` in the decision precisely to make this reproducibility concrete.

### §17 — Snapshot trust & staleness (reconciling AD-022 last-known-good with PR-INV fail-closed)
- **Problem:** AD-022 says serve on last-known-good; PR-INV says fail closed on compliance/residency uncertainty. These are reconciled by **classifying inputs by criticality**:
  - **Hard-constraint inputs (tenant policy, compliance, residency):** have a **strict freshness requirement**. If the snapshot is **missing or stale beyond its tolerance**, the Router **fails closed** (`STALE_CONSTRAINT` → surface) — it **never** routes without current knowledge of compliance/residency/tenant-policy. (Last-known-good is honored only within the strict freshness window; beyond it, uncertainty ⇒ fail closed, honoring PR-INV over convenience.)
  - **Soft-preference inputs (health, cost, latency):** tolerate **more staleness** and **degrade gracefully** (a stale health snapshot routes conservatively; a stale cost snapshot may pick a slightly sub-optimal but still-eligible candidate). These never affect a hard constraint, so degrading them cannot breach an invariant.
- Freshness tolerances per input class are **operational-baseline entries** (`16 §I.1`), never hard-coded. This is the precise reconciliation: **AD-022 last-known-good applies to soft inputs; hard-constraint inputs fail closed on staleness.** No invariant is weakened.
- **Enforcement:** staleness fault tests (`15` T-018) assert fail-closed on stale hard-constraint snapshots. **Build-Fail:** routing on a stale/missing hard-constraint snapshot beyond tolerance.

---

## F. Failure Handling

### §18 — Failure handling (every failure fails closed)
Every failure resolves to **fail closed + surface a neutral error naming the binding constraint** — never guess, never downgrade, never cross a hard tier.

| Failure | Detection | Neutral outcome |
|---|---|---|
| **Provider unavailable** (all candidates down) | availability filter empties (tier 5) | `NO_AVAILABLE_PROVIDER` ⇒ surface |
| **No provider satisfies capability** | capability filter empties (tier 4) | `CAPABILITY_UNSATISFIED` ⇒ surface |
| **Residency conflict** | residency filter empties (tier 3) | `RESIDENCY_CONFLICT` ⇒ surface (never cross-region) |
| **Compliance conflict** | compliance filter empties (tier 2) | `COMPLIANCE_CONFLICT` ⇒ surface (never downgrade) |
| **Policy conflict** | tenant-policy filter empties (tier 1) | `POLICY_CONFLICT` ⇒ surface |
| **Budget exceeded** | cost ceiling eliminates all | `BUDGET_EXCEEDED` ⇒ surface (never route over budget) |
| **Unsupported feature** | required capability unknown/unsatisfiable | `CAPABILITY_UNSATISFIED` ⇒ surface |
| **Stale/missing hard-constraint snapshot** | freshness check (§17) | `STALE_CONSTRAINT` ⇒ fail closed |
| **Empty candidate set (post-generation)** | no descriptors in snapshot | `NO_ELIGIBLE_PROVIDER` ⇒ fail closed |
- Failures map to the neutral error model (`12 §12`, RFC 9457); the surfaced reason names the **binding tier** per the canonical hierarchy (§11.1). Every routing failure is **audited** (§30). Default on any unclassified condition = **fail closed**.

---

## G. Performance

### §19 — Latency budget
- Routing is **sub-millisecond CPU work** over cached snapshots (§15), well within the added-latency budget (`06 §11`: P50 ≤ 5ms/P95 ≤ 20ms/P99 ≤ 50ms added). **No hot-path network calls** (all inputs cached, AD-022). JMH microbenchmarks baseline the decision hot path (`15` T-026, `16 §J.2`).

### §20 — Memory model & allocation
- Per-request state lives in the transient `RoutingEvaluation` aggregate; snapshots are **shared immutable** (read-only). Allocation on the hot path is minimized (reuse of candidate buffers where safe, no per-request snapshot copies). Sized within container limits (`16` D-044, ZGC AD-023). No unbounded structures.

### §21 — Complexity & scale (indexing strategy) *(resolves PRM-3 — additive; documents, does not redesign)*
- **Current model — linear scan is acceptable while P is bounded:** routing is bounded-linear in candidate count **P** (§15). While **P remains small (tens to low hundreds of provider/model/region combinations)**, a **plain linear scan is the correct, simplest implementation** — the whole decision is sub-millisecond CPU over cached snapshots, comfortably within the added-latency budget (`06 §11`). No indexing is needed or claimed at this scale.
- **Future extension — snapshot indexing (optional, additive, ownership-neutral):** if the catalog grew large (e.g. **P beyond a governed threshold**, an operational-baseline value `16 §I.1`), the Registry snapshot **may** carry **pre-built indexes** (by residency region, by compliance regime, by canonical capability) so filtering drops from O(P) to O(eligible). This is an **internal data-structure optimization on the snapshot**; it **does not change ownership, routing outcome, determinism, or the hierarchy** — the same candidates survive, just found faster. It is stated as a **future option**, not a current claim, and would be introduced additively without a Router redesign. P is bounded by the provider landscape, not by traffic (which scales horizontally via stateless replicas, AD-020).

### §22 — Concurrency & Virtual Threads
- Runs on **Virtual Threads** (AD-023). Snapshots are immutable/shared; per-request state is isolated (**no shared mutable state**, AD-021 — no round-robin counters, §13.1). No blocking in `synchronized` on the hot path (`11` R-049). **Build-Fail:** shared mutable routing state; a mutable load-balancer counter; blocking-in-`synchronized` on the hot path.

---

## H. Security

### §23 — Security considerations
- **Tenant isolation (AD-021):** routing uses per-request tenant scope; **no cross-tenant state** (no shared counters/caches keyed by tenant identity that could leak); a tenant's policy/entitlement never affects another's decision.
- **Provider isolation:** the decision names routes; it carries **no provider credentials/secrets** (PR-D12); provider identity in the decision is internal routing data, **never exposed on external/caller-facing surfaces** as a provider-native leak (`12 §16.10`, AD-007) — external responses remain provider-neutral.
- **Decision integrity & auditability:** the `RoutingDecision` is **immutable**; the `RequestRouted` event is **ZL, content-free, tamper-evident** into Audit (C10, WORM+Merkle, `08 §10`, `13 §19`) — every route (and every routing failure) is auditable with the applied constraints + snapshot versions (§30).
- **Routing trace:** the decision rationale (applied constraints, per-candidate scores, snapshot versions) is recorded for forensics — **provider-neutral, no secrets, no prompt/completion content** (`13 §20`, `14 §7.1`).
- **No secret exposure:** enforced structurally (PR-D12/§7).
- **Enforcement:** content/secret-leak scanner on telemetry/events (`14 §7.1`, `15` T-052); isolation tests (`15` T-044). **Build-Fail:** any secret/credential in a decision/event/log; cross-tenant routing state.

---

## I. Observability

### §24 — Posture
- Full instrumentation per `14`, **content-free**: spans per lifecycle step (§8), metrics (§25), structured logs (§26), events (§27). Two-tier (`14 §5.1`): operational counters → Plane A; high-cardinality routing diagnostics → Plane B. Telemetry privacy (`14 §7.1`): **never** prompt/completion/secret content.

### §25 — Metrics (SLI-bearing)
- `router_decision_latency`, `router_decisions_total`, `router_eligible_candidates` (distribution).
- `router_routing_failure_total{binding_tier}` — routing failures by binding tier (capability/residency/compliance/policy/budget/availability/stale) — a **reliability/compliance canary signal** (`16 §E.2`).
- `router_hardfilter_eliminations_total{tier}`, `router_stale_constraint_total`, `router_failover_candidates` (distribution).
- **Provider dimension & cardinality (resolves PRM-5):** a `provider`/`model` label MAY appear on routing metrics **strictly as internal operational telemetry** (needed for capacity/routing-distribution analysis and drift, `15 §C.1`); it is **NEVER customer-facing / never exposed on any external or caller-facing surface** (`12 §16.10`, AD-007). Provider identity is not tenant content. **Cardinality is bounded:** as the model catalog grows, `provider`/`model` labels on Plane A metrics are **bounded (top-N by volume + an `other` bucket)** to protect Prometheus cardinality (`14 §5.1`); unbounded high-cardinality routing detail lives on **Plane B** (`14 §5.1`), not Plane A. All labels low-cardinality on Plane A; **no prompt/completion/secret**. This keeps internal operability and external AD-007 neutrality both satisfied.

### §26 — Tracing & Logging
- One span per lifecycle step with neutral attributes (eligible count, binding tier, primary chosen, snapshot versions) — **never** prompt/completion/secret. Structured, content-free logs: ids, applied constraints, binding tier, counts. Correlation/causation ids propagated (`07 §6`, `14`).

### §27 — Events
- Off the hot path (async, AD-005), **Avro** via Apicurio (`07 §9`), standard envelope (`07 §6`), **content-free**. The Router emits the **frozen `RequestRouted`** event (`06 §23`, topic `rfaig.routing.request.v1`, **ZL**, owned by **C1 Provider&Routing**, produced by the Data Plane Router) and a routing-failure event within C1's **already-owned** event space (no new topic owner; consistent with `06 §23`). Consumed by Reliability (in-proc), Audit (C10), Observability (C9), Metering (C5). **No provider-native events** (`12 §16.10`). Event evolution follows `16` D-033. **Build-Fail:** a provider-native event exposed; content in an event.

---

## J. Data & Integration

### §28 — Data ownership
- The Router is **stateless** (AD-020) and owns **no persistent store** (no ownership/data-model change). It holds only **ephemeral in-memory** per-request evaluation state + read-only shared snapshots (AD-022). Durable routing records are owned by Audit (C10) / Metering (C5) via events (§27), consistent with store-per-service (`08`, `06 §24`). **Build-Fail:** the Router acquiring a private persistent store.

### §29 — Integration with Provider Adapters & Capability Registry (C1)
- Via `CapabilityRegistryPort`: adapters publish → Registry produces canonical `CapabilityDescriptor` snapshots (PR-D3); the Router **reads only canonical descriptors**. Adapters (not the Router) perform invocation with credentials (C2/adapters). No provider SDK in the Router (AU-06).

### §30 — Integration with C2 Reliability (the decision handoff — failover safety)
- The Router hands the **immutable `RoutingDecision`** (ordered, **fully hard-filtered** candidate set) to **C2 Reliability** via `RoutingDecisionSinkPort`. **Critical property:** because **every** candidate (primary + all failover) passed hard tiers 1–6, **any failover C2 performs is guaranteed policy/compliance/residency-safe** — C2 can fail over across the provided candidates **without ever consulting the Router again on the hot path and without any risk of a boundary-crossing route.** If C2 **exhausts** the candidate set, C2 does **not** invent new routes; it either surfaces the failure or requests a **fresh routing decision** from the Router under updated state (a *new* decision, re-filtered — never a boundary bypass). **Staleness note (honest):** a candidate healthy at decision time may degrade before C2's failover attempt; C2 owns invocation-time health handling (retry/next-candidate), and every remaining candidate is still hard-filtered, so degradation affects *availability*, never *compliance/residency*. The full normative contract is **§30.1**. This seam is contract-tested (`15` T-008).

### §30.1 — Normative Router ↔ C2 Reliability Contract *(resolves High PRH-1 — additive; specifies responsibilities only, no ownership move, no architecture change)*

This is the **signed, testable division of responsibility** across the frozen `06` C1→C2 seam. **Each responsibility has exactly one owner; neither side duplicates the other.**

| # | Concern | **Owner** | Contract at the seam |
|---|---|---|---|
| RC-1 | **Immutable routing decision** | **Provider Router** | The `RoutingDecision` (§14) is **immutable once emitted**; the Router **never mutates or re-orders it after emission**. C2 treats it as read-only. |
| RC-2 | **Candidate-list lifecycle** | **Router produces; C2 consumes** | Router builds the ordered candidate list (primary + ranked failover), all hard-filtered (tiers 1–6). C2 **traverses** it in order during execution/failover; C2 never adds, removes, re-filters, or re-orders candidates. |
| RC-3 | **Candidate ordering** | **Provider Router** | Ordering is fixed at emission (soft-scored, §12/§13). C2 fails over **strictly in the given order**; it does not re-rank. |
| RC-4 | **Decision snapshot** | **Provider Router** | The decision records `snapshotVersions` (§14) so the decision is reproducible/auditable; C2 executes against that recorded basis. |
| RC-5 | **Routing-decision TTL** | **Provider Router sets; C2 honors** | Each decision carries a **`validUntil` / TTL** (an operational-baseline value, `16 §I.1`, §17). Within TTL, C2 may execute and fail over across the candidate list. **On TTL expiry**, the decision is **stale** (RC-6). |
| RC-6 | **Stale-decision handling** | **C2 detects; Router refreshes** | On TTL expiry **or** exhaustion of all candidates, C2 **does not invent routes and does not extend the decision** — it **requests a fresh `RoutingDecision`** from the Router under current snapshots (a **new** decision, re-hard-filtered). Compliance/residency can never drift because a refresh is a full re-filter; an expired decision is **never** executed. |
| RC-7 | **Refresh ownership** | **Provider Router** | Only the Router produces (or refreshes) a routing decision. C2 **requests** a refresh; it never constructs or edits routing decisions itself. |
| RC-8 | **Failover ownership** | **C2 Reliability** | C2 **executes** invocation and **performs failover** across the Router's candidate list (retry/next-candidate, circuit-breaking) — all within `06`/`15` T-017 retry budget. Because every candidate is hard-filtered, failover is policy-safe by construction. |
| RC-9 | **Retry ownership** | **C2 Reliability (not the Router)** | The **Provider Router NEVER performs retries** — it makes a decision, once. Retry/failover is exclusively C2's. |
| RC-10 | **Post-emission immutability** | **Provider Router** | The **Provider Router NEVER changes routing after decision emission.** Any change of route is a **new** decision (RC-6), never an edit of an emitted one. |
| RC-11 | **Stream-lifetime interaction** | **split** | For a streaming request, the decision selects the route **once**; the stream then belongs to StreamGuard (`18`) transport + C2 execution. A **mid-stream** provider failure **after first downstream emission** is **not** re-routable (would violate `18` effectively-once/ordering, `18 §26/§47.1`) — it surfaces; a **pre-first-emission** failure may fail over across the (still-valid, in-TTL) candidate list (C2), consistent with `18 §47.1` point-of-no-return. |
| RC-12 | **Cancellation interaction** | **C2/StreamGuard execute; Router uninvolved** | Cancellation is handled at execution/transport (`18 §27`, C2); the Router holds no session state and has nothing to cancel (its work completed at decision emission). |

- **Ownership boundary (normative, explicit):** **The Provider Router owns routing *decisions*. C2 Reliability owns *execution and failover*. The Provider Router never performs retries and never changes routing after emission.** These statements are inviolable and build-enforced (PR-A15).
- **Enforcement (PR-A15):** ArchUnit + contract tests (`15` T-008/T-018) fail the build on: a routing decision mutated after emission; the Router performing a retry/failover; C2 constructing/editing a routing decision; execution of an expired (TTL-lapsed) decision without refresh. **No overlap, no ambiguity, no ownership drift.**

### §31 — Integration with Governance / Configuration (C4)
- Via `PolicySnapshotPort`: the Router consumes **resolved** routing/compliance/residency policy from cached snapshots (authored by C4 Governance + Configuration, AD-019/022). It **enforces** as hard filters; it **never authors** policy. Governance remains **non-bypassable** (AD-018) — the Router is a routing PEP applying PDP-resolved constraints.

### §32 — Integration with Provider Registry (health)
- Via `HealthSnapshotPort`: consumes provider health/availability snapshots (owned by Provider Registry, `06 §23`). Never owns/mutates health (PR-D6).

### §33 — Integration with Metering (C5) / Billing (C8)
- Via `CostSnapshotPort`: consumes normalized cost + entitlement/budget/ceiling snapshots (owned by C5/C8). Never computes billing (PR-D7). Via `MeteringSinkPort`: emits content-free routing counters as inputs to C5's accounting (C5 owns cost attribution). Full contract: **§33.1**.

### §33.1 — Cost Normalization Contract *(resolves High PRH-3 — additive; ownership referenced, not moved)*

**Ownership (normative):** the **Provider Router NEVER calculates cost, NEVER estimates pricing, and NEVER owns billing.** Cost/billing is owned by **Metering & Cost (C5)** and **Billing & Commerce (C8)** (`05`/`06`). The Router **consumes** a **normalized cost descriptor** produced upstream and applies it only as (a) a **hard ceiling filter** when a tenant ceiling is set (over-ceiling candidate eliminated) and (b) a **soft minimization objective** (tier 7) among already-eligible candidates. It reads comparative cost; it computes no money.

| Aspect | Contract |
|---|---|
| **Ownership** | Normalized cost descriptors are **produced by C5** (with entitlement/contract inputs from C8); the Router is a **read-only consumer** via `CostSnapshotPort` (AD-022 snapshot). |
| **Freshness** | Cost is a **soft-preference input** (§17): it tolerates staleness and **degrades gracefully**; a stale cost snapshot may yield a slightly sub-optimal but still-**eligible** candidate. Freshness tolerance is an operational-baseline value (§17/PRM-1). |
| **Versioning** | The cost-descriptor schema is versioned + backward-compatible (`12 §27`); the Router matches by canonical fields only. |
| **Currency normalization** | Performed **upstream (C5/C8)**; the Router consumes a **single canonical comparative unit** — it never converts currencies. |
| **Token normalization** | Performed **upstream**; heterogeneous pricing (per-token, per-request, tiered, cached-input discounts) is normalized by C5 into a canonical comparative cost — the Router never reconstructs provider pricing models. |
| **Enterprise pricing** | Reflected in the C8 entitlement/contract inputs feeding C5's normalized descriptor; the Router sees only the resulting normalized cost + ceiling. |
| **Negotiated contracts** | Same — captured upstream (C8) and surfaced as normalized cost/ceiling; the Router applies, never negotiates or computes. |
| **Missing cost behavior** | If normalized cost for a candidate is **absent**, cost cannot be used to *prefer* it, but its absence **never eliminates** an otherwise-eligible candidate and **never violates a higher tier** — the candidate remains eligible; cost simply does not contribute to its soft score (fail-safe toward eligibility, never toward a hard-tier breach). |
| **Stale cost behavior** | Degrade gracefully (soft input, §17); **never** fail the request on stale cost alone, and **never** let stale cost override a higher tier. |

- **Subordination rule (normative, ties PRM-2):** **cost is always subordinate to Tenant Policy, Compliance, Residency, Capability, Availability, and Reliability** (PR-D2 tiers 1–6). **Missing or stale cost must never violate a higher-priority routing tier**, and **compliance/residency/policy can never be traded for cost savings** (§11.1). Cost contributes **only after** all hard tiers succeed (§12).
- **Enforcement (PR-A17):** ArchUnit — no monetary/charge/pricing computation in the Router; property test — a missing/stale cost input never eliminates an eligible candidate and never reorders across the hard/soft boundary. **Build-Fail:** the Router computing a charge/price; cost affecting a hard-tier outcome.

### §34 — Integration with Audit (C10)
- Via `AuditSinkPort`: `RequestRouted` + routing-failure events are **content-free, tamper-evident** audit records (WORM+Merkle, `08 §10`, `13 §19`); audit completeness invariant (RPO=0) preserved.

---

## K. Testing

### §35 — Testing strategy (Provider Router is a `15 §G.1` critical module)
- **Unit (JUnit5/Mockito):** normalization, hard-filter tiers, scoring, tie-break, failure classification, decision assembly.
- **Property-based (jqwik, `15` T-035):** *for any candidate set + policy, the decision never contains a candidate failing a hard tier* (safety invariant); *identical inputs ⇒ identical decision* (determinism); *the surfaced binding tier is the highest-priority conflicting tier* (stable attribution).
- **Mutation (`15 §G.1`):** **≥ 85%** on the Router (named critical module); a mutant that relaxes a hard filter or crosses the hard/soft boundary must be killed.
- **Integration (Testcontainers where snapshots are backed):** snapshot-driven routing over real snapshot sources.
- **Provider conformance (`15` T-014/T-015):** adding a provider = **zero Router diff**; identical neutral routing behavior across providers; capability-mode granularity matches SchemaLock's strategy needs (§9.1).
- **Failure injection (`15` T-018):** empty-at-each-tier, stale hard-constraint snapshot, all-unhealthy, over-budget ⇒ **fail closed, correct binding tier, zero boundary-crossing route**.
- **Deterministic routing tests:** replay from recorded snapshot versions ⇒ identical decision; weighted tiebreak distribution is reproducible.
- **Concurrency/isolation (`15` T-027/T-044):** massive concurrent routing, no cross-tenant/cross-request state leakage; VT no-pinning (`15` T-028).
- **Performance (`15` T-021/T-026, `16 §J.2`):** decision hot path within budget; perf-smoke gate.

---

## L. Build-Failing Rules

### §36 — Build-failing architectural rules
| # | Rule | Gate |
|---|---|---|
| PR-A1 | No provider SDK in the Router | ArchUnit AU-06 |
| PR-A2 | No direct HTTP / network client in the Router | ArchUnit |
| PR-A3 | No credential/secret/API-key access in the Router | ArchUnit + secret scanner (`13 §20`) |
| PR-A4 | No persistence / private store (stateless, `06 §24`) | ArchUnit AU-07 |
| PR-A5 | No provider-name branch / switch / lookup table (route by capability only) | ArchUnit + conformance (`15` T-015) |
| PR-A6 | No path where a soft objective (cost/latency/preference) overrides a hard tier | ArchUnit + property test (§35) |
| PR-A7 | No cross-residency fallback; every decision candidate is region-permitted | fault test (`15` T-045) |
| PR-A8 | No compliance bypass; every candidate holds required attestations | fault test (§35) |
| PR-A9 | No policy bypass; governance-resolved constraints always applied (non-bypass, AD-018) | ArchUnit + `15` T-031 |
| PR-A10 | No routing on a stale/missing hard-constraint snapshot (fail closed, §17) | fault test (`15` T-018) |
| PR-A11 | No C2 handoff without a completed immutable `RoutingDecision` | contract test (`15` T-008) |
| PR-A12 | No wall-clock/random in the decision core; no shared mutable routing state | ArchUnit (`11` R-063/R-049) |
| PR-A13 | No provider-native field/event exposed externally; no secret/content in telemetry/events | `14 §7.1` scanner, `15` T-052 |
| PR-A14 | Mutation score ≥ 85% (critical module) | PITest (`15 §G.1`) |
| PR-A15 | Router↔C2 contract: decision immutable post-emission; Router never retries; C2 never edits a decision; no execution of a TTL-expired decision without refresh | ArchUnit + contract test (`15` T-008/T-018, §30.1) |
| PR-A16 | Router reads only canonical `CapabilityDescriptor` fields; unknown/absent required capability fails closed (never assumed-capable) | ArchUnit + fault test (§9.2) |
| PR-A17 | No monetary/charge/pricing computation in the Router; missing/stale cost never affects a hard-tier outcome or eliminates an eligible candidate | ArchUnit + property test (§33.1) |

---

## M. Operations

### §37 — Operational runbook
- **Routing-failure spike (`router_routing_failure_total{binding_tier}`):** identify the binding tier — capability (provider/model gap), residency/compliance (attestation/region gap → coverage), availability (provider outage → Provider Registry), budget (entitlement exhaustion → C8), stale-constraint (snapshot pipeline health → C4/Config, AD-022). Canary auto-aborts on threshold (`16 §E.2`).
- **Eligible-candidate collapse:** a policy/compliance/residency snapshot change over-constrained routing → verify the C4/Config snapshot; never relax hard tiers to "unblock" (that would breach PR-INV — instead widen provider coverage or correct the policy at C4).
- **Stale-constraint spike:** snapshot distribution health (AD-022 pipeline); Router correctly fails closed — fix the snapshot source, do not disable the freshness gate.
- **Decision-latency spike:** check candidate-set size P and snapshot read path; consider snapshot indexing (§21).

### §38 — Upgrade strategy
- Ships within the **data-plane release train** (`16` D-014/D-055): rolling/canary, zero-downtime (`16` D-031), routing-signal-gated (`16 §E.2`). Capability-taxonomy changes are additive/backward-compatible (`12 §27`) and conformance-tested (`15` T-014/T-015).

### §39 — Backward compatibility
- The **routing contract** (`RoutingRequest`, `RoutingDecision`, `RoutingFailure`, canonical capability taxonomy) is a stable internal API (`12 §27`): changes are additive/backward-compatible within a major. A **new provider** is additive via adapter (PR-D11). Emitted events evolve under Avro compat (`07 §9`, `16` D-033). Deterministic core (§16) guarantees identical decisions for identical recorded inputs across versions.

---

## Appendix A — Canonical Capability Vocabulary *(reference for §9.2; governed, versioned, additive)*

The closed capability set the Router consumes (canonical identifiers). Adapters map provider-native features into these; the Router **never** reads provider-native fields. Each entry has the §9.2 shape (identifier · version · optional parameters · state · compatibility rules). This appendix is a **reference vocabulary**, governed and extended additively (`12 §27`); it introduces no architecture.

| Canonical identifier | Example canonical parameters | Notes |
|---|---|---|
| `structured_output` | `mode ∈ {native_schema, json_mode, prompt_constrained}` | mode granularity matches `17 §14` strategy selection |
| `tool_calling` | `parallel`, `max_tools` | |
| `function_calling` | `parallel` | legacy alias family of tool_calling per taxonomy compat rules |
| `streaming` | `transport-agnostic` | transport integrity owned by `18` StreamGuard |
| `json_mode` | — | |
| `vision` | `formats`, `max_images` | input modality |
| `image_generation` | `formats`, `sizes` | output modality |
| `audio_input` | `formats` | |
| `audio_output` | `formats`, `voices` | |
| `embeddings` | `dimensions`, `max_input` | |
| `reasoning` | `effort_levels` | |
| `long_context` | `max_context` | numeric threshold matching |
| `batch` | `max_batch` | |
| `fine_tuning` | `methods` | typically management-plane, routing-relevant as attribute |
| `vector_search` | — | |
| `safety` | `categories` | |
| `moderation` | `categories` | |
| `prompt_caching` | `ttl`, `min_tokens` | |
| `computer_use` | — | |
| `mcp` | `transports` | |
| *future extensions* | governed, additive | added under the same §9.2 shape, backward-compatible |

- **State values:** `supported` / `preview` / `deprecated` / `unsupported`. Absent or `unsupported` for a **required** capability ⇒ candidate not eligible; unknown required capability ⇒ **fail closed** (§9.2/§18).

## Appendix B — Implementation notes *(non-normative)*
- **Benchmarks:** the decision hot path (normalize → filter → score) has a JMH microbenchmark set in `benchmarks/` (`15` T-026, `16 §J.2`); the perf-smoke gate (`16 §J.2`) guards P99 regression.
- **Event naming:** the Router emits the frozen `RequestRouted` (`06 §23`, `rfaig.routing.request.v1`); the routing-failure event name/topic sits in C1's already-owned space and is reconciled with the `06 §23` registry (deferred Low, PRL-1) — no new topic owner.
- **Snapshot indexing:** the optional index structures (§21) are a snapshot-side data-structure concern; the Router's logic is index-agnostic (same outcome with or without).

## N. Traceability

### §40 — Traceability matrix
| Router concern | BR | NFR | ADR | Domain/Svc | API/Event | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|---|
| Capability routing / neutrality (PR-D1/D3/D11) | BR-006 | NFR-IF-001 | AD-007 | C1 (`06`) | `12 §16.10` | — | — | T-014/T-015 | — |
| Policy hierarchy / non-bypass (PR-D2/§11/§31) | BR-013 | NFR-GOV | AD-018/019 | C1↔C4 (`06`) | — | §7 | — | T-031 | — |
| Residency (PR-D8/§11) | BR-023 | NFR-MR | AD-014 | — | — | §15/§18 | §18.1 | T-045 | D-051 |
| Compliance (PR-D9/§11) | BR-021/024 | NFR-SEC/COMPL | AD-018 | C1↔C4 | — | §26 | — | T-031 | — |
| Deterministic routing (PR-D5/§16) | BR-004 | NFR-RTY-001 | AD-016 | `11` R-063 | `12 §14` | — | — | T-035 | — |
| Health integration (PR-D6/§32) | BR-003 | NFR-AV | AD-016/017 | Provider Registry | `07 §23` | — | §17 | T-018 | — |
| Cost awareness (PR-D7/§33) | BR-005/012 | NFR-COST | AD-010 | C5/C8 | — | — | §10 | T-018 | — |
| Immutable decision → C2 (PR-D4/§14/§30) | BR-003 | NFR-AV/RTY | AD-017 | C1→C2 (`06`) | `06 §23` RequestRouted | — | — | T-008 | — |
| Router↔C2 normative contract (§30.1) | BR-003/004 | NFR-AV/RTY-001 | AD-017/016 | C1↔C2 (`06`) | `12 §16` | — | — | T-008/T-018 | — |
| Canonical capability taxonomy (§9.2/App.A) | BR-006 | NFR-IF-001/VER | AD-007/002 | C1 | `12 §16.10/§27` | — | — | T-014/T-015 | — |
| Cost normalization contract (§33.1) | BR-005/012 | NFR-COST | AD-010/022 | C5/C8 | `12 §27` | — | §10 | T-018 | §I.1 |
| Module in data plane, stateless (§28) | BR-005 | NFR-PERF | AD-020/006 | `06 §10/§24` | — | — | — | T-051 | D-014 |
| Hexagonal ports, credential-free (PR-D12/§7) | BR-019 | NFR-SEC | AD-002/012 | `05`/`11` | — | §20/§21 | — | T-008 | — |
| Snapshot trust / staleness (§17) | BR-010 | NFR-AV/COMPL | AD-022 | — | `10 §7` | §18 | §19 | T-018 | §I.1 |
| Performance / VT / isolation (§19–22) | BR-005 | NFR-PERF/CONC | AD-020/021/023 | `06 §11` | — | §15 | §15 | T-021/T-027/T-044 | D-044 |
| Security / decision integrity (§23) | BR-018/024 | NFR-SEC/AUD | AD-021/012/009 | — | `12 §16.10` | §19/§20 | §8 | T-044/T-052 | — |
| Observability (§24–27) | BR-010 | NFR-OBS | AD-011 | C9/C10/C5 | `07 §6` | §7.1 | §5/§19 | T-052 | — |
| Testing (critical module) (§35/§36) | BR-006 | NFR-TEST-001 | AD-016 | — | — | §27 | §19 | §G.1/T-018/T-035 | §E.2 |
| Upgrade/back-compat (§38/§39) | BR-028 | NFR-UPG/VER | AD-015 | — | `12 §27` | — | — | T-049 | D-014/D-055 |

---

## O. Reviews

### 1. Internal Consistency Review
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-007 / `12 §16.10` | provider abstraction, no leaks | PR-D1/D3/D11 (capability-only), §23/§27 (no external provider leak) | ✅ |
| AD-018 | non-bypassable governance | §11/§31 (governance-resolved hard filters always applied) | ✅ |
| AD-019 / AD-022 | PEP/PDP, cached snapshots | §10/§17/§31 (enforce resolved policy; last-known-good with fail-closed on hard-constraint staleness) | ✅ |
| AD-021 | tenant isolation | §22 (no shared mutable state), §23 | ✅ |
| AD-014 | residency confinement | PR-D8/§11/§30 (all candidates incl. failover region-permitted) | ✅ |
| AD-020/AD-006 | data plane co-located stateless module | §28 (no store), §22 (stateless) | ✅ |
| AD-002 | hexagonal replaceable adapters | §7 ports; snapshots via ports | ✅ |
| AD-016 | reliability first | PR-INV fail-closed (§18) | ✅ |
| AD-017 | C1→C2 flow | §30 (immutable decision handoff; safe failover) | ✅ |
| AD-023 | Java 21 + VT + ZGC | §22 (VT, no pinning), §20 | ✅ |
| `06` (C1 = Provider&Routing) | Router + Adapters modules, C1 | §1/§5 (Router module in C1; adapters separate) | ✅ |
| `06 §23` | RequestRouted owned by C1, ZL | §27 (frozen event reused, no new owner) | ✅ |
| `06 §24` | store-per-service | §28 (no store) | ✅ |
| `06 D-4` | Provider Mgmt + Capability Registry + Routing merged | PR-D3 (registry in C1, Router consumes) | ✅ |
| `08` DA-D2 | residency by classification | PR-D8/§30 | ✅ |
| `13 §20/§7.1` | no secret/content in telemetry | §23/§25/§26 | ✅ |
| `14 §5.1/§7.1` | two-tier, privacy | §24–27 | ✅ |
| `15 §G.1` | Provider Router critical module, mutation ≥85% | §35/§36 PR-A14 | ✅ |
| `16 §E.2` | routing/reliability canary gating | §25/§37 | ✅ |
| `17`/`18` | SchemaLock/StreamGuard seams | §9.1/§9.2 (capability mode matches `17` strategy); §30.1 RC-11 (stream lifetime per `18`); Router decides route only, C3 owns correctness | ✅ |
| `06` C1→C2 (frozen) | routing decision → reliability | §30.1 (RC-1…RC-12: Router owns decisions, C2 owns execution/failover/retry) | ✅ |
| `12 §27` | additive/backward-compatible contracts | §9.2 (taxonomy versioned/additive), §33.1 (cost schema versioned) | ✅ |
| `16 §I.1` | operational baselines | §17/§30.1 RC-5 (TTL), §21 (P-threshold), §33.1 (cost freshness) | ✅ |

**No contradictions found** against `00`–`18` and AD-001…AD-023. The corrective pass (§30.1, §9.2, §33.1, §12/§21/§25 clarifications, Appendices A/B, PR-A15…A17) is **additive only**: **no new ADR, no new service, no new module, no new store, no ownership/event/data-ownership change, no consistency/security/deployment/observability/testing-model change, no provider coupling, and no invariant weakened** (residency/compliance/isolation/non-bypass are *reinforced*). The Router↔C2 contract (§30.1) **restates** the frozen `06` C1→C2 seam precisely without moving any responsibility; the capability taxonomy (§9.2) **strengthens** AD-007 neutrality; the cost contract (§33.1) **references** C5/C8 ownership without claiming it.

### 2. Architecture Validation
- **Provider neutrality (AD-007):** routes by canonical capability only; no provider-name branching; new provider = adapter only; no external provider leak (PR-D1/D3/D11, PR-A5). ✅
- **Non-bypass governance (AD-018/AD-019):** governance-resolved compliance/residency/policy are hard filters always applied; the Router is a routing PEP, never a policy author (§11/§31). ✅
- **Residency invariant (AD-014):** every candidate — including failover — is region-permitted, so C2 failover is residency-safe by construction; no cross-residency fallback (PR-D8/§30). ✅
- **Isolation (AD-021):** no shared mutable routing state; deterministic tiebreak instead of a shared counter (§13.1/§22). ✅
- **Fail-closed (AD-016/PR-INV):** every failure surfaces with the binding tier; last-known-good reconciled with fail-closed for hard-constraint staleness (§17/§18). ✅
- **Modular monolith (AD-020):** in-process stateless module, no store, credential-free (§28/PR-D12). ✅
- **Router↔C2 seam (§30.1):** Router owns decisions (immutable, TTL'd), C2 owns execution/failover/retry — signed, testable, no drift (RC-1…RC-12, PR-A15). ✅
- **Capability neutrality (§9.2):** closed canonical taxonomy; Router reads canonical fields only; unknown fails closed — AD-007 reinforced (PR-A16). ✅
- **Cost subordination (§33.1):** cost never owned, never overrides a hard tier; missing/stale cost never breaches an invariant (PR-A17). ✅
- Introduces **no new architecture decision**; every PR-Dx is an implementation choice inside frozen C1.

### 3. Adversarial Review (re-run after corrective pass)
*Board: Principal Enterprise Architect · Distributed Systems Architect · AI Infrastructure Architect · Reliability Engineer · Security Architect · Performance Engineer · Compliance Auditor · CTO.*

**Accepted findings — resolution**
- **PRH-1 → RESOLVED (§30.1).** Normative Router↔C2 contract RC-1…RC-12: immutable decision, candidate lifecycle/ordering, decision snapshot, **routing-decision TTL** (`validUntil`, §14), stale-decision handling (C2 requests refresh, never extends/edits), refresh ownership (Router only), failover ownership (C2 only), retry ownership (C2 only — **Router never retries**), post-emission immutability (**Router never changes routing after emission**), stream-lifetime + cancellation interactions. Signed, testable, no overlap/ambiguity/drift (PR-A15).
- **PRH-2 → RESOLVED (§9.2 + Appendix A).** Closed, versioned canonical capability model (identifier · version · optional parameters · state · compatibility rules) covering all requested categories + governed future extensions; Router consumes canonical fields only; **unknown capabilities fail closed**; no provider-native fields; no provider-name branching (PR-A16).
- **PRH-3 → RESOLVED (§33.1).** Cost-normalization contract: ownership (C5/C8 produce; Router read-only, **never computes cost/pricing/billing**), freshness (soft input, degrade), versioning, currency/token normalization (upstream), enterprise/negotiated pricing (upstream), missing-cost + stale-cost behavior (never eliminate an eligible candidate, never override a hard tier). Cost strictly subordinate to tiers 1–6.
- **PRM-1 → RESOLVED (§17/§30.1/§33.1):** all freshness tolerances + TTL are operational-baseline entries (`16 §I.1`), no hard-coded numbers.
- **PRM-2 → RESOLVED (§12/§33.1):** cost contributes only after hard tiers succeed; compliance/residency/policy never traded for savings.
- **PRM-3 → RESOLVED (§21):** linear scan documented as acceptable while P bounded; snapshot indexing documented as an additive, ownership-neutral future extension past a governed P-threshold — no redesign.
- **PRM-4 → RESOLVED (§12):** routing weights owned by C4 Governance/Config; Router consumes, does not author.
- **PRM-5 → RESOLVED (§25):** provider identifiers internal-operational-telemetry only, never customer-facing (AD-007); Plane A cardinality bounded (top-N + `other`), high-cardinality detail on Plane B.

**New findings from the re-run**
- **🔴 Critical:** none.
- **🟠 High:** none.
- **🟡 Medium:** none blocking. (Numeric baselines — TTL, freshness tolerances, P-threshold, cardinality-N — are governed operational-baseline entries per `16 §I.1`, a stated design choice, not open defects. The upstream C5 cost-normalization *implementation* and the governed capability *vocabulary document* are cross-team deliverables referenced here; their correctness is contract-tested (`15` T-014/T-015) — noted as Low deferrals, not open design gaps.)
- **🟢 Low (deferred, non-blocking):**
  - **PRL-1** — exact routing-failure event name/topic → reconcile with `06 §23` registry (Appendix B).
  - **PRL-2** — JMH benchmark set for the decision hot path → `benchmarks/` (Appendix B).
  - **PRL-3** — governed capability-vocabulary reference document (beyond Appendix A) → tooling/standard.
  - **PRL-4** — snapshot index data structures → detailed design (§21).
  - **PRL-5** — cost-descriptor field schema (owned by C5) → C5 contract/standard.

### Architecture Readiness Score: **97 / 100**
All three High findings and all five Medium findings resolved additively, with **no new architecture, no new module, no ownership/event/data-ownership change, no provider coupling, and no invariant weakened** (residency/compliance/isolation/non-bypass reinforced; AD-007 strengthened by the closed taxonomy). The Router↔C2 contract, canonical capability model, and cost-normalization contract are now signed, testable, and machine-enforceable (PR-A1…A17). Residual 3 points are honest calibration/cross-team-deliverable items (Low, deferred).

### Documentation Health: **96 / 100** · Implementation Readiness: **94 / 100**
Contradictions/gaps removed; every load-bearing seam (C2 handoff, capability taxonomy, cost) now specified precisely enough to implement, with honest complexity/determinism scoping and a reference capability vocabulary (Appendix A).

### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`18` or AD-001…AD-023; no new architecture; no ownership/event/data-ownership/consistency/security/deployment/observability/testing-model change; no provider coupling; no invariant weakened.

---

*End of document — 19-ProviderRouter.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
