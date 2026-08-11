# 24A — Architecture Reconciliation: Token Optimization Within the Frozen Architecture

**Document:** Architecture Reconciliation & Recommendation
**Project:** Reliability-First AI Gateway
**Status:** **RECONCILIATION (advisory)** — not a component spec, not a redesign, introduces no architecture
**Subject:** Whether a **standalone Token Optimization Engine** (as drafted in `24-TokenOptimizationEngine.md`, DRAFT) is architecturally valid within the frozen model
**Reviews against:** `00`–`23`, **AD-001…AD-023**, bounded contexts **C1–C16**, pipeline ownership, runtime ordering, module ownership, non-bypass invariants, provider neutrality, and existing ownership of prompts/prompt-assets/routing/governance/request-preprocessing
**Classification:** Internal — Architecture Decision Input

> **Mandate (strict).** This document **only reconciles**. It does **not** redesign, does **not** introduce new architecture, and does **not** modify any frozen document or `24-TokenOptimizationEngine.md`. It answers **seven** questions and issues **one** recommendation. It changes nothing until an ADR acts on it.

---

## 0. Scope & Method
The board reviewed the frozen sources bearing on where — if anywhere — token optimization may live:

- **Context catalogue** — `05 §5` (C1–C16), `06 §6` (context→plane partition table).
- **Pipeline ownership & runtime ordering** — `06 §1/§8/§9` (the co-located data-plane pipeline and its module list), AD-020, AD-017.
- **Module ownership** — `06 §5/§16` (bounded context ≠ service; one owner per module; Platform owns pipeline assembly).
- **Non-bypass invariants** — AD-018 (`17`/`18`/`21`), AD-016.
- **Extensibility mechanism** — AD-004 (sandboxed, additive-only plugins at defined extension points), `06 §9` extension points (**pre-routing**, classification, validation, telemetry, attribution).
- **Provider neutrality** — AD-007.
- **Existing ownership of the things optimization touches** — prompts/prompt-assets (**C11**, `05 §5`/`06 §9.11`), routing/pre-routing (**C1**, `19`), governance/budget (**C4**, `21`), request preprocessing (pre-routing extension point / C1), token accounting (**C5**, `22`/`23`), caching (**C2**).

The method: locate every architectural slot optimization would need, and test the standalone-engine hypothesis (Doc 24's proposed "C6 — Optimization" context) against each. **No new slot is proposed** — the board only reports which *existing* frozen slots do or do not accommodate the capability.

---

## 1. Findings from the frozen architecture (evidence, not decisions)

**F-1 — There is no Optimization bounded context.** `05 §5` freezes exactly C1–C16; **C6 = Identity & Access**. No context — core, supporting, or generic — is "Optimization." Doc 24's "C6 — Optimization" both **collides** with the frozen C6 and **invents a 17th context**.

**F-2 — The hot path is one co-located pipeline, not a set of services (AD-020, `06 §1/§8`).** The Data Plane is a **modular monolith**: a single deployable hosting in-process modules — *Ingress, Identity/AuthN, Authorization/PEP, Governance-enforcement, Router, Reliability Engine, Provider Adapters, Correctness Engines, Accounting Emitter, Telemetry/Audit Emitter, + sandboxed Plugin Runtime.* **No "optimization" or "preprocessing" module exists in that list.** A *standalone engine* on the hot path would be a new hot-path unit — the exact thing AD-020 forbids (latency budget `NFR-LAT-001`, P99 ≤ 50 ms added).

**F-3 — A frozen mechanism already exists for exactly this kind of additive behavior (AD-004, `06 §9`).** The pipeline defines **additive-only plugin extension points: `pre-routing`, classification, validation, telemetry, attribution**, executed by the **sandboxed Plugin Runtime**. AD-004 plugins "add behavior … but **cannot bypass** authentication, authorization, governance, correctness, accounting, isolation, or audit." Request-side, meaning-preserving token optimization is a textbook **pre-routing, additive-only** behavior.

**F-4 — Request-path work is owned by C1 at pre-routing.** Pre-routing request preparation on the hot path sits within the **Router (C1, `19`)** boundary / the pre-routing extension point. There is no separate "request preprocessing" owner; it is C1/pre-routing.

**F-5 — C11 owns prompt *assets*, not runtime prompt transformation.** `06 §9.11`: Prompt Asset Service is **control-plane only, Tier-2**, governing templates/versions/approvals as auditable assets, with **no conversation state (OOS-2/3)**. So conversation compaction / runtime prompt rewriting is **not** C11's — but *authoring* the rules for prompt shaping is asset/policy governance, adjacent to C11/C4/C15.

**F-6 — Enforcement vs management splits across planes for every context (`06 §4.3`).** A context's *enforcement/emission* is a DP module; its *management* is a CP service on cached snapshots (AD-022). Optimization, if it exists, must follow the same split — runtime application in the DP, policy authoring in the CP.

**F-7 — Non-bypass is absolute (AD-018).** Correctness (`17`/`18`) and governance (`21`) are mandatory, non-bypassable stages. Anything that mutates a request must leave those stages intact and their validated/governed envelope honored.

**F-8 — Provider neutrality is absolute (AD-007).** Provider specifics live only in the adapter layer. Token counting is model/tokenizer-specific and therefore cannot import a provider SDK on the hot path; savings are estimates unless computed from a neutral, control-plane-owned token-accounting descriptor.

---

## 2. The seven questions

### Q1 — Does a standalone Token Optimization Engine belong in the current architecture?
**No — not as a standalone bounded context or a standalone hot-path service/module-context.**
- As a **new context** ("C6 — Optimization"): **invalid** — collides with frozen C6 and adds a 17th context (F-1), contradicting `05`/`06` and the "no new architecture" constraint.
- As a **standalone hot-path service**: **invalid** — AD-020 forbids new network services on the latency-budgeted hot path (F-2).
- The *capability* is legitimate; the *standalone-engine packaging* is not. The capability must be **absorbed into the existing pipeline as additive behavior**, not stood up as its own architectural entity.

### Q2 — If yes, exactly where?
It "belongs" **only** in one of two frozen slots, both inside the existing co-located Data-Plane pipeline — **never a new context/service**:
- **(Preferred) An additive-only plugin at the `pre-routing` extension point (AD-004, `06 §9`)**, executed by the sandboxed Plugin Runtime (C12), governed by control-plane policy snapshots. This is the frozen, intended mechanism for exactly this behavior (F-3).
- **(Alternative) An in-process capability within the C1 Router module's pre-routing responsibility (`19`)** — request-side, provider-neutral request shaping before route selection (F-4).
Both run **after** identity/authz/governance-admission and correctness request-validation, **before** routing/dispatch, and are subject to every mandatory downstream stage (AD-018). Neither adds a service, a store, or a context.

### Q3 — If not standalone, which existing bounded context should own optimization?
Ownership splits along the frozen enforcement/management line (F-6):
- **Runtime application (DP):** **C1 (Provider & Routing)** as a pre-routing responsibility, **or** **C12 (Extensibility)** as a sandboxed pre-routing plugin. Recommended primary owner: **C1 pre-routing** (it already owns hot-path request preparation), with the plugin route (C12) as the extensibility-friendly alternative.
- **Policy authoring (CP):** **C15 (Configuration/Feature Mgmt)** distributes the optimization-policy snapshot (AD-022); **C4 (Governance)** owns any policy that constrains *what may be dropped/trimmed* (budget/context-trim is governance-adjacent).
- **Token-accounting descriptor:** a neutral descriptor referenced from **C5 (Metering & Cost)** / **C1 capability registry** — never a provider SDK (F-8).
- **Not C11:** C11 owns prompt *assets*, not runtime transformation (F-5); optimization must not be lodged there.

### Q4 — Can optimization exist as a runtime engine without violating existing ADRs?
**Yes — but only in the constrained form above, and *not* as Doc 24 currently frames it.**
- **Compatible when** it is an **additive-only, sandboxed, non-bypassing** pre-routing plugin/capability (AD-004), owns **no store** (AD-006), makes **no synchronous CP call** on the hot path (AD-022), imports **no provider SDK** (AD-007), stays within the **latency budget** (NFR-LAT-001 / budgeted plugin overhead NFR-PERF-003), and preserves **AD-018** (mutating only within the validated/governed envelope, or emitting advisory hints).
- **Violates ADRs when** packaged as Doc 24 drafts it: a **new "C6 — Optimization" context** (violates `05`/`06`), a **standalone hot-path engine** (AD-020), an actor with an **unresolved re-validation/ordering contract** that can mutate a validated request (latent AD-018 bypass), or one that reintroduces **provider-specific tokenization** on the hot path (AD-007). Doc 24's own review (§S) already flags these as Critical/High.

### Q5 — Is optimization runtime or control-plane?
**Both, split along the frozen pattern (F-6) — it is not "one or the other":**
- **Control-plane:** the **decision of what may be optimized** — policy, safety thresholds, the token-accounting descriptor — authored and versioned as **cached snapshots** (AD-022) by C15/C4/C5. This is where the *intelligence and governance* of optimization live.
- **Data-plane (runtime):** the **deterministic application** of already-decided, lossless, meaning-preserving transforms at pre-routing, on cached snapshots, fail-closed to pass-through.
This mirrors every other context (enforcement/emission in the DP; management in the CP) and is the only split consistent with AD-017/AD-020/AD-022.

### Q6 — What enterprise products implement this capability, and how?
The consistent industry pattern is **optimization as data-plane middleware/plugin, never its own bounded context or service** — which corroborates Q2/Q3:
- **AI gateways (middleware/plugin model):** Kong AI Gateway (AI plugins), Portkey, Cloudflare AI Gateway, LiteLLM Proxy, TrueFoundry AI Gateway, Apigee-style gateways — all implement prompt/token handling as **request middleware or plugins in the gateway data plane**, configured by control-plane policy. None model "optimization" as a separate domain/service.
- **Prompt/context compression:** Microsoft **LLMLingua / LLMLingua-2** — note these are **lossy, semantic** compressors (they *do* change token content by learned importance). That is precisely the behavior Doc 24's TOE-INV **forbids**; adopting such a technique would violate the meaning-preservation invariant. Enterprises that use them accept a quality/eval trade-off outside a strict correctness envelope.
- **Semantic / prompt caching:** GPTCache, Portkey semantic cache, and **provider-native prompt caching** (Anthropic prompt caching, OpenAI prompt caching) — these reduce token cost via **caching**, which in this architecture is **C2's** concern and an explicit **non-responsibility** of any optimizer here. Provider-native caching lives at the adapter/provider, not as a gateway rewrite.
- **Takeaway:** the market implements token economy as (a) a **gateway plugin/middleware** (structural, config-driven) and/or (b) **caching** and/or (c) **lossy compression accepted outside a hard correctness envelope**. The frozen architecture already has the first as **AD-004 plugins**, the second as **C2**, and forbids the third. **No mainstream product introduces an "Optimization" bounded context** — validating that Doc 24's standalone framing is an outlier.

### Q7 — Which option minimizes architectural complexity while preserving correctness?
**The additive-only `pre-routing` plugin under AD-004, inside the existing co-located Data-Plane pipeline, with policy authored as control-plane snapshots.**
- **Minimizes complexity:** adds **zero** new services, **zero** new stores, **zero** new bounded contexts; reuses two frozen mechanisms (the pre-routing extension point + AD-022 snapshots).
- **Preserves correctness:** AD-004 plugins are **structurally non-bypassing** (AD-018 intact); fail-closed pass-through makes the plugin **removable with no behavioral change**; provider neutrality is kept by construction (no SDK in a sandboxed plugin); the latency budget is enforced as budgeted plugin overhead.
- **Ranking (least → most complexity, all correctness-preserving only in the first two):**
  1. **Pre-routing plugin (C12 runtime) + CP policy** — *lowest complexity, recommended.*
  2. **C1 pre-routing in-process capability + CP policy** — low complexity, if tighter Router coupling is preferred over a plugin.
  3. **Standalone engine as a re-scoped C1/C12 module (no new context)** — higher coupling/ownership cost; only if plugin/pre-routing prove insufficient.
  4. **Standalone "C6 — Optimization" context/service (Doc 24 as drafted)** — **highest complexity and violates the frozen model; not correctness-preserving as specified. Rejected.**

---

## 3. Reconciliation summary

| Dimension | Doc 24 standalone engine (drafted) | Reconciled placement (this document) |
|---|---|---|
| Bounded context | New "C6 — Optimization" (collides with C6, invents C17) ❌ | None new; behavior under C1/C12, policy under C15/C4/C5 ✅ |
| Hot-path packaging | Standalone engine/component ❌ (AD-020) | Additive-only `pre-routing` plugin/capability in the co-located pipeline ✅ |
| Runtime vs CP | Ambiguous (runtime component) ⚠ | Split: application in DP, policy in CP (AD-017/022) ✅ |
| Non-bypass (AD-018) | Unresolved re-validation seam (latent bypass) ❌ | AD-004 plugin is structurally non-bypassing ✅ |
| Store | None (good) ✅ | None (AD-006) ✅ |
| Provider neutrality | Token counting model-specific ⚠ | Structural + neutral CP token descriptor; no SDK ✅ |
| Ownership of prompts | Treads on C11 ⚠ | C11 keeps asset authoring; runtime shaping is C1/C12 ✅ |
| New architecture introduced | Yes (context) ❌ | None ✅ |

---

## 4. Recommendation

**A standalone Token Optimization Engine is NOT architecturally valid in the frozen model.** The capability is legitimate and worth having, but it must be **re-scoped from a standalone bounded context/engine (`24`) into an additive-only `pre-routing` plugin (AD-004) — or an in-process C1 pre-routing capability — within the existing co-located Data-Plane pipeline, with its policy and token-accounting descriptor authored as versioned control-plane snapshots (AD-022) owned by C15/C4/C5.** This placement introduces **no new context, no new service, no new store, and no new architecture**; preserves **AD-016/017/018/020/022 and AD-007**; and matches how enterprise AI gateways actually implement token economy (Q6).

Concretely, the board recommends that a future ADR:
1. **Reject** the "C6 — Optimization" context and any standalone hot-path optimization service (F-1/F-2).
2. **Locate** runtime optimization at the frozen **`pre-routing` extension point (AD-004)** under **C1/C12**, non-bypassing and fail-closed (F-3/F-7).
3. **Author** optimization policy + the neutral token-accounting descriptor in the **control plane** (C15/C4/C5), delivered as AD-022 snapshots (F-6/F-8).
4. **Confirm** C11 retains prompt-**asset** ownership; runtime prompt shaping is **not** lodged in C11 (F-5).
5. **Require** `24-TokenOptimizationEngine.md` to be **re-scoped to this placement before any freeze** (it remains a DRAFT; this document does not modify it).

**This is a reconciliation and recommendation only. No frozen document and no ADR is modified. `24-TokenOptimizationEngine.md` is left unchanged and unfrozen. No new architecture is introduced. Any change requires a future ADR.**

---

*End of document — 24A-Architecture-Reconciliation.md · Status: RECONCILIATION (advisory) · Version: 1.0 (2026-07-21)*
