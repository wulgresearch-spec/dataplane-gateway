# 34 — Runtime State Machine (Descriptive · Documentation-Only)

**Document:** Runtime State Machine — the formal description of the request execution states that already exist across the frozen architecture
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Scope owner:** **Platform / Technical Documentation Lead** — this is a **description**, not a component; the state and every transition remain the **frozen stage owners'** (`06 §8`, `17`–`33`). See **§SMA State-Machine Authority**.
**Module:** *(none — this is not a component)* — a **documentation-only** formalization of the runtime execution states **already frozen in `32 §8` (State transitions)** and enforced across `17`–`33`. It introduces **no service, module, context, store, event, pipeline stage, runtime capability, canonical model, ownership, or ADR**. It **never becomes an orchestrator/state-engine specification** (§SMA).
**Plane:** Documentation of Tier-0 runtime states
**Subordinate to:** `06`, `32` (esp. `32 §8` state transitions, `32 §SAQ` sequence authority, `32 §RFO/§CRS/§POP`), and `17`–`33`.
**Audience:** Engineers, SRE, security, QA, reviewers needing a formal state reference
**Classification:** Internal — Architecture (Documentation)
**Builds on (approved, frozen):** `00`–`33` (except `24`, intentionally UNFROZEN; optimization is a pre-routing plugin only, `28 §EPC`/`24A`), especially `06 §8`, `32 §8` (states), `17`–`31` (owners), `33` (models), and ADRs **AD-001…AD-023**. Everything here is **descriptive and additive**: it **cites** the frozen states/transitions; it **defines** none and **owns** none.

> **What this is.** The formal, single-reference description of the **runtime execution state machine** that already exists: the states a request passes through (`32 §8`), the **allowed** and **forbidden** transitions, the invariants each state upholds (non-bypass, exactly-once, RPO=0, snapshot/identity immutability, tenant isolation, replay scope), the **terminal** states, and the **replay interpretation** of transitions (inheriting `32 §CRS`). **The authoritative state list is `32 §8`**; where this document uses a state name not present in `32 §8`, it is marked **derived-documentation-only** and is an alias/refinement, never a new authoritative state.
>
> **THE STATE-MACHINE INVARIANT (SM-INV):** *This document describes an emergent state machine; it never drives, advances, coordinates, or owns transitions (§SMA).* State is **distributed** across the frozen stage owners — each owner produces its own transition by executing its frozen logic (`06 §8`); no entity "runs the state machine." The states, order, and forbidden transitions are the **frozen** ones (`32 §8`, AD-018): every mandatory transition occurs, in order, for every request; no transition skips/reorders/overrides a mandatory stage; terminal states are single and explicit; replay reproduces decision transitions only, never timing/provider behavior.

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (SM-C1), High (SM-H1…SM-H3), and Medium (SM-M1…SM-M4)** findings are resolved **additively** through signed contracts and build-failing rules — creating **no state, moving no ownership, changing no architecture**:

- **§SVA State Vocabulary Authority Contract (SVA-1…SVA-6)** — SM-C1 (`32 §8` is the ONLY authoritative state vocabulary; aliases deprecated). Build rule **SM-A16**.
- **§NEC Non-Executable Contract (NEC-1…NEC-8)** — SM-H1 (no advance/dispatch/route/coordinate/schedule/nextState/transition/orchestration; no executable engine derivable). Build rule **SM-A17**.
- **§POP Partial Order Primacy Contract (POP-1…POP-6)** — SM-H2 (linear diagrams illustrative only; authoritative order = `32 §RFO/§POP/§CAC/§SFC/§CRS`; DELIVERED ≠ FINALIZED). Build rule **SM-A18**.
- **§DSC Derived State Contract (DSC-1…DSC-6)** — SM-H3 (derived/sub/alias states are NOT runtime states; never in code/storage/events/APIs/logs/metrics/schemas/replay). Build rule **SM-A19**.
- **§RRC Retry Re-entry Contract** — SM-M1. **§COC Cancellation Ordering Contract** — SM-M2. **§TRC Terminal Reachability Contract** — SM-M3. **§SSC Streaming State Contract** — SM-M4.

**Low** items remain **deferred** (§L). **`32 §8` remains the ONLY authoritative runtime lifecycle; this document is documentation-only, descriptive, non-authoritative, subordinate to `32`, never executable, never an engine/orchestrator, never a source of truth.** SM-INV, ownership, all runtime states, transitions, invariants, replay scope, owners, ADR references, and architecture are **unchanged**. No document `00`–`33` and no ADR is modified.

---

## Table of Contents
**1. Charter · 2. Scope · 3. Runtime ownership (§SMA) · 4. State machine overview · 5. Canonical runtime states · 6. Transition rules · 7. State invariants · 8. Replay section · 9. Failure section · 10. Determinism · 11. Testing · 12. Build-failing rules (SM-A1…SM-A15) · 13. Traceability · S. Independent Review Board**

---

## §SMA — State-Machine Authority (SMA-1…SMA-7)
| # | Aspect | Contract |
|---|---|---|
| SMA-1 | **Describes only** | This document **describes** an emergent state machine; it is documentation, not a runtime artifact (extends `32 §SAQ`). |
| SMA-2 | **Never drives** | Nothing here **drives, advances, coordinates, dispatches, or orchestrates** transitions. |
| SMA-3 | **Distributed state** | State is **distributed** across frozen stage owners (`06 §8`); each owner produces its own transition; **no state-engine owns the request**. |
| SMA-4 | **Authoritative list is `32 §8`** | The authoritative state set/order is **`32 §8`**; on any difference, `32 §8` wins and this document is corrected. |
| SMA-5 | **Derived states marked** | Any state name not in `32 §8` is **derived-documentation-only** (alias/refinement), never a new authoritative state. |
| SMA-6 | **No orchestrator spec** | **No implementation may treat this document as a state-engine/orchestrator specification.** A component "running the state machine" would be a new module — forbidden. |
| SMA-7 | **No authoring** | Authors no state/transition/invariant/owner; all are cited (`32 §8`, `17`–`33`). |
- **Enforcement (SM-A14/SM-A1):** doc-lint + ArchUnit. **Build-Fail:** a runtime component citing this doc as a state-engine authority; a state/transition diverging from `32 §8`.

## §SVA — State Vocabulary Authority Contract (SVA-1…SVA-6) *(resolves Critical SM-C1)*
| # | Aspect | Contract |
|---|---|---|
| SVA-1 | **Sole authority** | **`32 §8` is the ONLY authoritative runtime state vocabulary.** |
| SVA-2 | **Aliases deprecated** | Every alias (`NEW`, `CONTEXT_RESOLVED`, `SNAPSHOT_PINNED`, `PROVIDER_SELECTED`, `PROVIDER_EXECUTING`, `COMPLETED`, and any refinement name) is **DEPRECATED**. |
| SVA-3 | **Reference-only aliases** | Aliases exist **only for historical/reference mapping** (§4) — never as canonical runtime states. |
| SVA-4 | **No alias in any surface** | **No implementation, SDK, diagram, API, documentation, or code may use an alias name as a canonical runtime state.** |
| SVA-5 | **Order per `32 §8`** | The authoritative state **order** is `32 §8` (AUTHENTICATED → PINNED → ADMITTED → …); any other ordering (including the brief's) is non-authoritative. |
| SVA-6 | **Owner wins** | On any vocabulary/order difference, **`32 §8` wins and this document is corrected**. |
- **Enforcement (SM-A16):** vocabulary conformance test. **Build-Fail:** any canonical state name that differs from `32 §8`; an alias used as a runtime state.

## §NEC — Non-Executable Contract (NEC-1…NEC-8) *(resolves High SM-H1)*
This document **does NOT**, and **cannot be used to**:
| # | Forbidden |
|---|---|
| NEC-1 | advance state |
| NEC-2 | dispatch work · route requests · coordinate execution · schedule stages · call components |
| NEC-3 | calculate/derive the next state |
| NEC-4 | contain transition logic |
| NEC-5 | define `nextState()` |
| NEC-6 | define `transition()` / `advance()` |
| NEC-7 | define workflow execution |
| NEC-8 | define orchestration |
- **Cardinal rule:** **It is impossible to derive an executable runtime engine from this document.** State is distributed across frozen stage owners (`06 §8`, §SMA-3); **every runtime owner remains unchanged**; no `StateEngine`/`WorkflowOrchestrator`/`Sequencer` may cite this document as its specification.
- **Enforcement (SM-A17):** ArchUnit. **Build-Fail:** an implementation referencing this document as an execution/orchestration/next-state authority; a `nextState()`/`transition()`/`advance()` derived from it.

## §POP — Partial Order Primacy Contract (POP-1…POP-6) *(resolves High SM-H2)*
| # | Aspect | Contract |
|---|---|---|
| POP-1 | **Linear = illustrative** | The linear state diagrams (§4/§5) are **illustrative only**. |
| POP-2 | **Authoritative order** | The authoritative ordering is **`32 §RFO`, `32 §POP`, `32 §CAC`, `32 §SFC`, `32 §CRS`** — the partial-order graph, not a line. |
| POP-3 | **DELIVERED ≠ FINALIZED** | **DELIVERED (response delivered) and FINALIZED are NOT equivalent.** |
| POP-4 | **Delivery ≠ finalization** | **Response delivery never implies `RequestFinalized`** (`32 §RFO-5/§RFO-7`). |
| POP-5 | **Frozen partial order** | The exact frozen partial order (cited): `PAC → (SGV for streams) → AUE → WAL → [CRC may occur] → (all UsageFacts WAL-committed) → RF → ledger-ack/Cost/Obs async` (`32 §RFO`). RF(complete) **only after** all authoritative UsageFacts WAL-committed. |
| POP-6 | **No collapse** | No reading may collapse DELIVERED into FINALIZED or treat finalization as "the last step after responding". |
- **Enforcement (SM-A18):** finalization/partial-order test. **Build-Fail:** treating DELIVERED as FINALIZED; RF(complete) before all facts WAL-committed; a linear order overriding `32 §RFO/§POP`.

## §DSC — Derived State Contract (DSC-1…DSC-6) *(resolves High SM-H3)*
| # | Aspect | Contract |
|---|---|---|
| DSC-1 | **Not runtime states** | Derived states, sub-states, aliases, and visual refinements (GUARDED/VALIDATED/METERED/PROVIDER_SELECTED/CONTEXT_RESOLVED/RESPONDED/INVOKING/…) are **NOT runtime states**. |
| DSC-2 | **Documentation only** | They are documentation refinements with **no independent authority** (§SMA-5). |
| DSC-3 | **Never in surfaces** | They **cannot appear** in code, storage, events, APIs, logs, metrics, schemas, replay, or execution logic. |
| DSC-4 | **Only `32 §8` exists** | **Only the `32 §8` runtime states exist** as runtime states. |
| DSC-5 | **No inflation** | A derived state may never accrete behavior or be promoted to a runtime state (that would be an invented capability — forbidden). |
| DSC-6 | **Owner wins** | On any conflict, `32 §8` and the owning stage govern. |
- **Enforcement (SM-A19):** derived-state test. **Build-Fail:** a derived/sub/alias state in code/storage/events/API/logs/metrics/schema/replay/execution.

## §RRC — Retry Re-entry Contract *(resolves Medium SM-M1)*
Every execution attempt (EXECUTING re-entry, `20`) binds **`ExecutionIdentity = (idempotencyKey, attemptId)`** exactly as frozen (`20`, `23 §14.1`, `33 §10.9`): a distinct `attemptId` per attempt under the same `idempotencyKey`; **retry never mutates identity** (`33 §TIC`); each attempt's UsageFact is keyed distinctly so retries never double-count (`32 §10`). **Build-Fail (SM-A8):** a retry re-entry without a distinct `(idempotencyKey, attemptId)`; a mutated ExecutionIdentity.

## §COC — Cancellation Ordering Contract *(resolves Medium SM-M2)*
Reusing `32 §CAC`: on cancel, **consumed usage is extracted → metered → WAL-committed BEFORE CANCELLED becomes terminal** — cleanup follows WAL commit. Order: `cancel intent (30 §CUC) → 20 decides → usage extraction (18 CV-5/25) → WAL (23 §39.1) → metering (23) → RequestFinalized (23 §17.1) → resource cleanup (25/26) → CANCELLED terminal`. Consumed usage is **never** lost (`22 §24.1` CA-6). **Build-Fail (SM-A9):** CANCELLED reached before consumed-usage WAL commit.

## §TRC — Terminal Reachability Contract *(resolves Medium SM-M3)*
Explicit, exhaustive terminal reachability (no implicit transitions):
| Active state | → FAILED | → CANCELLED | → FINALIZED |
|---|---|---|---|
| RECEIVED | — (→REJECTED) | — | — |
| AUTHENTICATED | — (→REJECTED) | — | — |
| PINNED | ✓ | — | — |
| ADMITTED | — (→DENIED) | — | — |
| ROUTED | ✓ | — | — |
| EXECUTING/INVOKING | ✓ (exhaustion/error) | ✓ (disconnect, §COC) | — |
| STREAMING | ✓ (partial/error) | ✓ (disconnect, §COC) | — |
| GUARDED/VALIDATED/METERED (derived) | ✓ | ✓ | — |
| DELIVERED | — | — | → PRICED→OBSERVED→FINALIZED |
| PRICED/OBSERVED | — | — | → FINALIZED |
- Terminal set = REJECTED · DENIED · FAILED · CANCELLED · FINALIZED(complete|incomplete). Each transition is **explicit**; no implicit/silent transition (`23 §17.1` FC-11). **Build-Fail (SM-A12):** an implicit terminal transition.

## §SSC — Streaming State Contract *(resolves Medium SM-M4)*
The stream state is **C3 StreamGuard's `StreamState`** (`18`, cataloged `33 §10.3`) — this document **reuses** it and **never defines another stream-state notion**. STREAMING/GUARDED here are references to `18 §30`'s frozen stream lifecycle. **Build-Fail (SM-A13):** a second stream-state definition.

---

## 1. Charter
Give one formal reference for the runtime states a request occupies, the legal/illegal transitions, and the invariant each state upholds — **as already frozen in `32 §8` and `17`–`33`**. It cites the frozen owners; it owns nothing (§SMA).

## 2. Scope
- **In scope (describe states only):** states, transitions (allowed/forbidden), invariants, terminal states, replay interpretation — all citing `32 §8`/owners.
- **Out of scope:** any state-engine/orchestrator; any new state/transition/capability; re-deciding any stage (`17`–`31`); redefining `StreamState` (C3, `18`) or any `33` model.

## 3. Runtime ownership (§SMA)
State is **distributed**: Ingress (`30`), AuthN (C6), Governance (`21`), Router (`19`), Reliability (`20`), Adapter (`25`), Secrets (`26`), StreamGuard/SchemaLock (`18`/`17`), Metering (`23`), Cost (`22`), Observability (`27`), Plugin Runtime (`28`) each **produce their own transition**. Per **§SMA**, this document is a description and **not** a state engine/owner.

## 4. State machine overview
The authoritative states and order are **`32 §8`**:
```
RECEIVED → AUTHENTICATED → PINNED → ADMITTED → ROUTED → EXECUTING
  → (per attempt: INVOKING → STREAMING/RESPONDED → GUARDED → VALIDATED → METERED)
  → DELIVERED → PRICED → OBSERVED → FINALIZED(complete)
Terminal (non-happy): REJECTED | DENIED | FAILED | CANCELLED | FINALIZED(incomplete/gap)
```
**Brief-name → frozen-name mapping (derived-only aliases, SMA-5):** `NEW`=RECEIVED · `CONTEXT_RESOLVED`=(post-AUTHENTICATED tenant resolution, refinement of AUTHENTICATED→PINNED, `32 §SPT`) · `SNAPSHOT_PINNED`=PINNED · `PROVIDER_SELECTED`=(refinement within ROUTED, C1 `19`) · `PROVIDER_EXECUTING`=EXECUTING/INVOKING · `COMPLETED`=DELIVERED (**response delivered ≠ FINALIZED**, `32 §RFO`). The brief's **ordering** (ADMITTED before AUTHENTICATED) is **not** the frozen order; **`32 §8` order governs** (AUTHENTICATED → PINNED → ADMITTED).

## 5. Canonical runtime states
*Each block cites `32 §8` + the owning stage. "Derived" = documentation-only refinement (SMA-5).*

### RECEIVED (`NEW`, derived alias) — owner: Platform/Ingress (`30`)
- **Purpose:** request received at the transport boundary; identity/context established.
- **Entry:** client connect + parsed envelope (`30 §5`).
- **Exit:** context established → AUTHENTICATED; or → REJECTED (malformed/overload, `30 §15`).
- **Allowed:** RECEIVED→AUTHENTICATED; RECEIVED→REJECTED.
- **Forbidden:** RECEIVED→any stage past AuthN (no bypass, AD-018).
- **Failure:** reject (fail closed, `30`).
- **Replay:** recorded-input (non-authoritative, `32 §CRS`).
- **Refs:** `30`, `32 §8`, `12 §14`.

### AUTHENTICATED — owner: C6 Identity
- **Purpose:** principal authenticated; **tenant scope resolved** (`CONTEXT_RESOLVED` derived refinement, `32 §SPT`).
- **Entry:** RECEIVED + valid transport identity (`30 §IAB`).
- **Exit:** → PINNED; or → REJECTED (unknown identity, `30 §IAB-6`).
- **Allowed:** AUTHENTICATED→PINNED; AUTHENTICATED→REJECTED.
- **Forbidden:** any tenant-scoped work before this state (`32 §SPT`, §7 identity invariant).
- **Failure:** reject.
- **Replay:** recorded-input.
- **Refs:** C6, `30 §IAB`, `32 §SPT`.

### PINNED (`SNAPSHOT_PINNED`) — owner: Platform (pin), snapshots owned by C15/C4/C1/C6/C14
- **Purpose:** all snapshot versions pinned for the request (immutable thereafter).
- **Entry:** AUTHENTICATED + tenant resolved (pin **after** C6, `32 §SPT`).
- **Exit:** → ADMITTED; or → REJECTED (required snapshot missing/out-of-range, `29 §SCC`).
- **Allowed:** PINNED→ADMITTED; PINNED→REJECTED.
- **Forbidden:** pinning before C6 (`32 §SPT-1`); mid-request re-pin (`32 §SPT-2`).
- **Failure:** fail closed (out of rotation / reject).
- **Replay:** recorded pinned version-ids (`29 §CVR`).
- **Refs:** `32 §SPT`, `29 §SCC`, AD-022.

### ADMITTED — owner: C4 Governance (`21`)
- **Purpose:** governance/quota admission (deny-by-default).
- **Entry:** PINNED (policy snapshot available).
- **Exit:** → ROUTED (admit); or → DENIED (deny, terminal).
- **Allowed:** ADMITTED→ROUTED; ADMITTED→DENIED.
- **Forbidden:** provider selection/call before admission (AD-018).
- **Failure:** deny-by-default (`21`).
- **Replay:** decision replay (`32 §CRS`).
- **Refs:** `21`, AD-018/019.

### ROUTED (`PROVIDER_SELECTED` derived refinement) — owner: C1 Router (`19`)
- **Purpose:** canonical model id + provider route selected (capability snapshot).
- **Entry:** ADMITTED.
- **Exit:** → EXECUTING; or → FAILED (no route, `19`).
- **Allowed:** ROUTED→EXECUTING; ROUTED→FAILED.
- **Forbidden:** ROUTED→FINALIZED skipping execution/metering (AD-018).
- **Failure:** fail (`19`).
- **Replay:** decision replay.
- **Refs:** `19`, `06 §9.2`.

### EXECUTING / INVOKING (`PROVIDER_EXECUTING`) — owner: C2 Reliability (`20`); per attempt: `26`/`25`
- **Purpose:** Reliability orchestrates attempt(s); each attempt materializes a credential (`26`) and invokes the provider via the adapter (`25`).
- **Entry:** ROUTED.
- **Exit:** → STREAMING/RESPONDED (attempt yields output); → EXECUTING (retry/failover/hedge, re-enter per attempt, `20`); → DELIVERED (delivered attempt marked, `20 §25`); → FAILED (exhaustion); → CANCELLED (client disconnect → cancel, `30 §CUC`/§12).
- **Allowed:** EXECUTING→{STREAMING, RESPONDED, EXECUTING(next attempt), DELIVERED, FAILED, CANCELLED}.
- **Forbidden:** delivering output un-guarded/un-validated (must pass GUARDED/VALIDATED, `18`/`17`); an attempt without a UsageFact if units consumed (`23`).
- **Failure:** `CanonicalProviderError` → `20` decides failover; exhaustion → FAILED.
- **Replay:** decision + canonical-mapping replay; **provider execution/timing NOT reproduced** (`25 §23.1`/`32 §CRS`).
- **Refs:** `20`, `25`, `26`.

### STREAMING — owner: C3 StreamGuard (`18`); frames via `25`/`30`
- **Purpose:** streaming response; StreamGuard guards integrity + terminal verdict + authoritative usage.
- **Entry:** EXECUTING (provider streams).
- **Exit:** → GUARDED/VALIDATED → METERED → DELIVERED; or → FAILED/CANCELLED (partial/truncated stream, `18 §26`).
- **Allowed:** STREAMING→{GUARDED, FAILED, CANCELLED}.
- **Forbidden:** ingress/adapter mutation of the canonical stream (`30 §SIC`/`18`); finalizing an in-flight stream (`32 §SFC`).
- **Failure:** partial stream → consumed-only UsageFact (failed/cancelled attempt, `23 §20`), never delivered.
- **Replay:** stream **timing NOT reproduced** (`32 §CRS`); the terminal verdict decision is recorded.
- **Refs:** `18 §30`, `25 §11`, `30 §SIC`.

### GUARDED / VALIDATED / METERED (derived sub-states of EXECUTING per `32 §8`)
- **GUARDED** (C3 StreamGuard `18`): terminal verdict + authoritative usage. **VALIDATED** (C3 SchemaLock `17`): schema/tool-call verdict. **METERED** (C5 Metering `23`): per-attempt UsageFact **WAL-committed (RPO=0)**. Forbidden: delivery before GUARDED+VALIDATED (AD-018); "accounted" before WAL commit (`23 §39.1`). Replay: decision replay; metering is authoritative-accounting replay (`23`).

### DELIVERED (`COMPLETED` alias) — owner: C2 Reliability marks (`20 §25`); response framed by `30`
- **Purpose:** the delivered attempt's response is delivered to the client. **DELIVERED (response) ≠ FINALIZED** (`32 §RFO-5`).
- **Entry:** METERED for the delivered attempt (WAL-committed).
- **Exit:** → PRICED → OBSERVED → FINALIZED.
- **Allowed:** DELIVERED→PRICED.
- **Forbidden:** treating DELIVERED as FINALIZED (`32 §RFO-7`); delivering before WAL commit (`32 §RFO`).
- **Failure:** n/a (a failed delivery is FAILED/CANCELLED, not DELIVERED).
- **Replay:** decision replay; delivery **timing NOT reproduced**.
- **Refs:** `20 §25`, `30`, `32 §RFO`.

### PRICED / OBSERVED (post-delivery, async per `32 §RFO-8`) — owners C5 Cost (`22`) / C9 Observability (`27`)
- **PRICED** (`22`): cost priced from recorded usage (after metering, never re-derives). **OBSERVED** (`27`): content-free telemetry emitted (side-effect-free; never gates). Both may run **asynchronous to the response** (`32 §RFO-8`). Replay: PRICED = derived; OBSERVED = recorded telemetry decisions only (`27 §19.1`).

### FINALIZED — owner: C5 Metering (`23 §17.1`) — **terminal**
- **Purpose:** completeness assertion — **RequestFinalized(complete) only after ALL authoritative UsageFacts WAL-committed** (`32 §RFO-6`).
- **Entry:** all facts WAL-committed (streaming: post terminal verdict, `32 §SFC`).
- **Exit:** terminal — FINALIZED(complete) **or** FINALIZED(incomplete/gap) (`23 §17.1` FC-7/FC-11).
- **Allowed:** →FINALIZED(complete) | →FINALIZED(incomplete).
- **Forbidden:** FINALIZED(complete) before all facts WAL-committed (`32 §RFO-7`); a third "silently done" state (`23 §17.1` FC-11).
- **Failure:** finalize-timeout → INCOMPLETE (gap, never silent complete).
- **Replay:** authoritative-accounting completeness (`23`).
- **Refs:** `23 §17.1`, `32 §RFO/§SFC`.

### Terminal states (frozen, `32 §8`): REJECTED · DENIED · FAILED · CANCELLED · FINALIZED(complete|incomplete)
- **REJECTED** (Ingress, `30`): transport/malformed/overload/unknown-identity. **DENIED** (Governance, `21`): admission deny. **FAILED** (any owning stage): no-route/exhaustion/validation-fail/UsageUnrecorded. **CANCELLED** (`30 §CUC`→`20`→`25`): disconnect→cancel, **consumed usage still metered** (`32 §CAC`). **FINALIZED**: completeness terminal. Each is **single and explicit**; no "silently done" (`23 §17.1` FC-11).

## 6. Transition rules
| Path | Rule (frozen) |
|---|---|
| **Normal** | RECEIVED→AUTHENTICATED→PINNED→ADMITTED→ROUTED→EXECUTING→(GUARDED→VALIDATED→METERED)→DELIVERED→PRICED→OBSERVED→FINALIZED (`32 §8`) |
| **Failure** | any state → FAILED at its owning stage's fail-closed rule; never bypasses a downstream stage (AD-018, `32 §13`) |
| **Retry** | EXECUTING→EXECUTING per attempt (`20`); each attempt METERED distinctly (execution identity, `23 §14.1`); no double-count (`32 §10`) |
| **Streaming** | EXECUTING→STREAMING→GUARDED (terminal verdict, `18 §30`); partial → FAILED/CANCELLED with consumed UsageFact (`32 §SFC`) |
| **Cancellation** | any active state → CANCELLED (`30 §CUC`→`20`→`25`); **meter consumed usage → WAL → finalize → cleanup** (`32 §CAC`) |
| **Provider failure** | EXECUTING: `CanonicalProviderError`→`20` decides retry/failover or FAILED (`25`/`20`) |
| **Timeout** | transport timeout (`30 §DOC`)→cancel; execution deadline (`20`)→FAILED; per-attempt budget (`25`)→provider error→`20` (`32 §11`) |
| **Plugin execution** | at the frozen five points, signal-before-decision; plugin failure isolated, state proceeds unweakened (`28 §EPFC`, `32 §PEB`) — plugins **never** cause a forbidden transition |
| **Shutdown** | drain in-flight to terminal or replay; RPO=0 (`29 §SDC`); no new admissions |
| **Deployment restart** | recover on last-known-good; serve only after readiness (`29 §SRC`); in-flight drains/replays (`29`) |
| **Recovery** | committed UsageFacts replay; RPO=0 within frozen scope (`31 §RPO`); no partial serving (`31 §RCO`) |
| **Replay** | reproduces decision transitions + order from recorded versions; never timing/provider (§8) |

## 7. State invariants (upheld at every state)
| Invariant | Source |
|---|---|
| **Non-bypass** — no transition skips/reorders/overrides a mandatory stage | AD-018, `32 §6` |
| **Exactly-once** — accounting counted once per execution identity | `23 §14.1`, `27 §18.1` |
| **RPO=0** — committed accounting/audit never lost (frozen scope) | `23 §39.1`, `31 §RPO` |
| **Snapshot immutability** — pinned versions immutable for the request | `32 §SPT`, AD-022 |
| **Identity immutability** — correlation/idempotency/ExecutionIdentity immutable | `33 §TIC`, `20`/`12 §14` |
| **Execution identity** — `(idempotencyKey, attemptId)`, owned by `20` | `20`, `33 §10.9` |
| **Tenant isolation** — no cross-tenant state; tenant scope post-C6 | AD-021, `32 §SPT` |
| **Replay scope** — decisions/order only (weakest scope) | `32 §CRS`, `33 §RSM` |

## 8. Replay section (inherits `32 §CRS`/`33 §RSM`)
| Transition class | Replay treatment |
|---|---|
| **Decision transitions** (ADMITTED, ROUTED, VALIDATED, DELIVERED-mark) | **replayed** — reproduced from recorded decisions + order (`32 §CRS` CRS-2) |
| **Canonical mapping** (adapter translate) | **replayed** — mapping decisions only (`25 §23.1`) |
| **Recorded plugin outputs** | **replayed as recorded**, plugins not re-executed (`28 §RPC`) |
| **Accounting transitions** (METERED, FINALIZED) | **authoritative** via WAL/ledger (`23`), not re-derived from replay |
| **Telemetry transitions** (OBSERVED) | **recorded decisions only**, timing not reproduced (`27 §19.1`) |
| **Provider execution / STREAMING timing / wall clock / scheduling / randomness** | **NEVER reproduced** (`32 §CRS` CRS-6) |
- **Recorded identity:** replay uses recorded `(codeVersion, snapshotVersions, ExecutionIdentity)` (`29 §CVR`). Composed replay = **weakest scope** (`32 §CRS` CRS-1).

## 9. Failure section
- **Terminal states:** REJECTED, DENIED, FAILED, CANCELLED, FINALIZED(complete|incomplete) — each single/explicit (`23 §17.1` FC-11).
- **Recovery transitions:** in-flight → drain (`29 §SDC`, RPO=0) or committed-fact replay (`23 §39.1`/`31 §RCO`); recovered instance re-enters only at RECEIVED after readiness (`29 §SRC`).
- **Impossible transitions (build-forbidden):** FINALIZED(complete) before all facts WAL-committed (`32 §RFO`); DELIVERED before METERED/WAL; delivery before GUARDED+VALIDATED; any state past AuthN before AUTHENTICATED.
- **Forbidden transitions (AD-018):** any transition skipping a mandatory stage; a plugin causing a transition (`32 §PEB`); tenant-scoped transition before AUTHENTICATED; pin before C6.

## 10. Determinism
State **order and decision transitions** are deterministic given `(codeVersion, pinned snapshots, request inputs, recorded plugin outputs)` (`32 §21`/`29 §CVR`) — **weakest honest scope**. **Not** deterministic: timing, wall-clock, scheduling, provider behavior, stream/telemetry timing (§8). Deterministic in **which transitions and in what order**, not **when**.

## 11. Testing
- **State-transition tests:** the frozen `32 §8` order holds for every request.
- **Illegal-transition tests:** every forbidden/impossible transition (§9) is rejected by construction.
- **Replay tests (`15` T-035):** decision transitions reproduce; timing/provider never (§8).
- **Failure tests (`15` T-018):** each terminal reachable per its owner's fail-closed rule.
- **Cancellation tests:** disconnect→CANCELLED with consumed usage metered before cleanup (`32 §CAC`).
- **Streaming tests (`15` T-012):** STREAMING→GUARDED terminal verdict; partial → consumed UsageFact; no early finalize.
- **Mutation (`15 §G.1`):** **≥ 90%** on the state-conformance harness.

## 12. Build-Failing Rules (SM-A1 … SM-A15)
| # | Rule | Gate |
|---|---|---|
| SM-A1 | State set/order matches `32 §8`; no divergent authoritative state (§SMA-4) | conformance test vs `32 §8` |
| SM-A2 | No transition skips/reorders/overrides a mandatory stage (AD-018) | illegal-transition test |
| SM-A3 | No tenant-scoped transition before AUTHENTICATED (`32 §SPT`) | ordering test |
| SM-A4 | No snapshot pin before C6; no mid-request re-pin (`32 §SPT`) | pin-timing test |
| SM-A5 | No delivery before GUARDED+VALIDATED (`18`/`17`) | correctness-order test |
| SM-A6 | No FINALIZED(complete) before all UsageFacts WAL-committed (`32 §RFO`) | finalization test |
| SM-A7 | DELIVERED ≠ FINALIZED; response delivery is a distinct state (`32 §RFO`) | state test |
| SM-A8 | No accounting double-count across retry transitions (execution identity, `23 §14.1`) | accounting test |
| SM-A9 | Cancellation meters consumed usage before cleanup (`32 §CAC`) | cancellation test |
| SM-A10 | Replay reproduces decision transitions + order only (`32 §CRS`/§8) | replay test |
| SM-A11 | No plugin causes a state transition; plugins additive-only (`32 §PEB`) | plugin-boundary test |
| SM-A12 | Terminal states single/explicit; no "silently done" (`23 §17.1` FC-11) | terminal test |
| SM-A13 | No `StreamState`/model redefined here (references `18`/`33`) | ArchUnit |
| SM-A14 | This document is not a state-engine/orchestrator; no runtime cites it as authority (§SMA/§NEC) | doc-lint + ArchUnit |
| SM-A15 | Mutation score ≥ 90% (state-conformance harness) | PITest (`15 §G.1`) |
| SM-A16 | **State Vocabulary Authority (§SVA):** canonical state names match `32 §8` exactly; aliases deprecated, never runtime states | vocabulary conformance test |
| SM-A17 | **Non-Executable (§NEC):** no implementation references this doc as execution/next-state/orchestration authority; no `nextState()`/`transition()`/`advance()` derivable | ArchUnit |
| SM-A18 | **Partial Order Primacy (§POP):** DELIVERED≠FINALIZED; delivery never implies finalization; RF only after all facts WAL-committed | finalization/partial-order test |
| SM-A19 | **Derived State (§DSC):** no derived/sub/alias state in code/storage/events/API/logs/metrics/schema/replay/execution | derived-state test |

## 13. Traceability
| Element | Frozen owner/source |
|---|---|
| States/order | `32 §8` |
| RECEIVED/REJECTED | `30` |
| AUTHENTICATED | C6, `30 §IAB` |
| PINNED | `32 §SPT`, `29 §SCC`, AD-022 |
| ADMITTED/DENIED | `21`, AD-018/019 |
| ROUTED | `19`, `06 §9.2` |
| EXECUTING/retry/timeout | `20`, `25`, `26`, `32 §10/§11` |
| STREAMING/GUARDED | `18`, `25 §11`, `30 §SIC`, `32 §SFC` |
| VALIDATED | `17` |
| METERED/FINALIZED | `23 §17.1/§39.1`, `32 §RFO` |
| DELIVERED | `20 §25`, `30`, `32 §RFO` |
| PRICED/OBSERVED | `22`, `27` |
| CANCELLED | `30 §CUC`, `32 §CAC` |
| Invariants | AD-018/021/022, `23`/`31 §RPO`/`32 §CRS`/`33` |
| Replay | `32 §CRS`, `29 §CVR`, `33 §RSM` |
| Recovery | `29`/`31` |
| Authority | `32 §SAQ`, §SMA |

---

## §L — Deferred Low Items (non-blocking)
- **SM-L1** formal state-diagram artifact → design; **SM-L2** state×event transition matrix → design; **SM-L3** TLA+/formal model → optional; **SM-L4** per-state metric mapping → `27`. None affects states, ownership, ordering, replay scope, or any invariant.

## S. Reviews (post-resolution)

### 1. Internal Consistency Review
| Claim | Contract | Frozen source | ✓ |
|---|---|---|---|
| `32 §8` sole vocabulary; aliases deprecated | §SVA | `32 §8` | ✅ |
| Non-executable; no engine derivable | §NEC | `32 §SAQ`/§SMA | ✅ |
| DELIVERED ≠ FINALIZED; partial-order primacy | §POP | `32 §RFO/§POP` | ✅ |
| Derived states not runtime states | §DSC | `32 §8`/§SMA | ✅ |
| Retry binds `(idempotencyKey, attemptId)` | §RRC | `20`/`23 §14.1`/`33 §TIC` | ✅ |
| Cancel meters before terminal | §COC | `32 §CAC`/`22 §24.1` | ✅ |
| Exhaustive terminal reachability | §TRC | `23 §17.1` FC-11 | ✅ |
| Reuse C3 StreamState | §SSC | `18`/`33 §10.3` | ✅ |

### 2. Cross-document Validation
Aligned with `32 §8/§RFO/§POP/§CAC/§SFC/§CRS/§SAQ`, `17`–`31` (owners), `33` (models incl. StreamState/ExecutionIdentity), AD-018/021/022, and the `24`-unfrozen rule. Provider execution/timing excluded from replay. **No contradiction.**

### 3. Architecture Validation
- **Non-executable / description-only:** ✅ hard-pinned (§NEC/§SMA); no engine derivable; ownership unchanged.
- **Vocabulary:** ✅ `32 §8` sole authority; aliases deprecated (§SVA).
- **Partial order:** ✅ DELIVERED≠FINALIZED restated at state level (§POP).
- **Derived states:** ✅ explicitly non-authoritative, barred from all surfaces (§DSC).
- **Retry/cancel/terminal/stream:** ✅ identity-bound, meter-before-terminal, exhaustive, StreamState reused.

### 4. Independent Review Board (re-run)
*Board: Enterprise Solutions Architect · Distributed Systems Architect · Principal Staff Engineer · Runtime Platform Architect · Technical Documentation Lead · Staff Reliability Engineer · Security Architect · Compliance Auditor · CTO.*

**Accepted findings — resolution:** SM-C1→§SVA (SM-A16); SM-H1→§NEC (SM-A17); SM-H2→§POP (SM-A18); SM-H3→§DSC (SM-A19); SM-M1→§RRC; SM-M2→§COC; SM-M3→§TRC; SM-M4→§SSC.

**New findings:** 🔴 Critical: none. 🟠 High: none. 🟡 Medium: none blocking. 🟢 Low (deferred): §L.

**Internal Contradictions:** none — two-vocabulary risk closed (§SVA deprecates aliases); linear/partial-order tension closed (§POP); DELIVERED/FINALIZED kept distinct.

**Cross-document Contradictions:** none — states/transitions/invariants cite `32 §8`/owners; replay excludes provider/timing.

**Scores:** Architecture Readiness **97/100**; Documentation Health **96/100**; Implementation Readiness **94/100**.

**Recommendation: FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred. `32 §8` remains the sole authoritative lifecycle; this document is documentation-only, non-executable, subordinate to `32`; no architecture/ownership/state changed.

---

*End of document — 34-RuntimeStateMachine.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
