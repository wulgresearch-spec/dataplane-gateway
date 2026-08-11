# 32 — Runtime Execution Sequence (Canonical Request Lifecycle · Documentation-Only)

**Document:** Canonical Runtime Execution Sequence — how one request flows through the frozen runtime
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Scope owner:** **Platform** — owner of the co-located pipeline assembly (`06 §8/§57`) — **unchanged**; every stage's decision remains its **frozen owner's** (C1–C6/C9/C10/C12, `17`–`31`)
**Module:** *(none — this is not a component)* — this is the **canonical execution-order description** of the already-frozen co-located Data-Plane pipeline (`06 §8`). It introduces **no service, module, context, store, event, extension point, ownership, pipeline stage, ADR, or architecture** — it **only documents the order** in which already-frozen components execute (§SAQ).
**Plane:** Documentation of Tier-0 runtime behavior
**Audience:** All engineers/SRE/security/QA/reviewers needing one authoritative execution-order reference
**Classification:** Internal — Architecture (Documentation)
**Builds on (approved, frozen):** `00`–`31` (except `24`, which remains intentionally UNFROZEN — a standalone optimization engine violates the frozen architecture per `24A`; optimization exists **only** as an additive **pre-routing plugin** at the frozen extension point, `28 §EPC`, and must not be revived), `24A` (informational), and ADRs **AD-001…AD-023** — **especially** AD-018 (non-bypass), AD-020 (co-located pipeline), AD-022 (snapshots), AD-005/AD-009 (RPO=0), and the frozen component docs `17`–`31`. Everything here is **descriptive and additive**: it **cites** frozen ordering, it **defines** none.

> **What this is.** The single authoritative description of **one request's lifecycle** through the frozen runtime — Client → Ingress (`30`) → Authentication (C6) → Governance (`21`) → Routing (`19`) → Reliability (`20`) → Secrets (`26`) → Provider Adapter (`25`) → Provider → Streaming (`18`) → Validation (`17`) → Usage Metering (`23`) → Cost (`22`) → Observability (`27`) → Finalization (`23 §17.1`) → Response — with every mandatory stage, every failure/retry/timeout/cancellation path, the streaming and partial-streaming paths, the WAL and RequestFinalized ordering, deterministic replay, and plugin execution at the **frozen five extension points only** (`28 §EPC`). It **reconciles** the ordering already frozen across `06 §8` and `17`–`31`; it **adds no new ordering, ownership, or guarantee**.
>
> **THE RUNTIME-SEQUENCE INVARIANT (RSE-INV):** *This document describes; it never decides, coordinates, dispatches, orchestrates, or owns execution (§SAQ).* The execution order it records is the **frozen** order (`06 §8`, AD-018): every mandatory stage executes, in order, for every request; no stage is skipped, reordered, or overridden; every decision is its frozen owner's; plugins are additive signals at the frozen extension points; accounting is exactly-once (RPO=0) and never double-counted; replay reproduces decisions from recorded versions, never wall-clock/provider behavior.

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (RSE-C1), High (RSE-H1…RSE-H3), and Medium** findings are resolved **additively** through signed normative contracts, each build-enforced — **citing frozen ordering, defining none, expanding no authority**:

- **§RFO Response Finalization Ordering Contract (RFO-1…RFO-12)** — RSE-C1. Build rule **RSE-A16**.
- **§PEB Plugin Execution Boundary Contract (PEB-1…PEB-10)** — RSE-H1. Build rule **RSE-A17**.
- **§CRS Composed Replay Scope Contract (CRS-1…CRS-8)** — RSE-H2. Build rule **RSE-A18**.
- **§SAQ Sequence Authority Contract (SAQ-1…SAQ-8)** — RSE-H3. Build rule **RSE-A19**.
- **§POP Partial Order Primacy Contract** — RSE-M1. **§CAC Cancellation Accounting Contract** — RSE-M2. **§SPT Snapshot Pin Timing Contract** — RSE-M3. **§SFC Streaming Finalization Contract** — RSE-M4.

**Low** items remain **deferred** (Appendix B). **No new service, module, context, store, event, pipeline stage, extension point, ownership, or ADR is introduced; every frozen owner is preserved; non-bypass, exactly-once, RPO=0, deterministic-replay scope, and the runtime-only nature are preserved; no wording expands authority.** No document `00`–`31` and no ADR is modified.

---

## Table of Contents
**1. Charter · 2. Scope · 3. Runtime ownership (§SAQ) · 4. Canonical request lifecycle (§POP) · 5. Request phases · 6. Mandatory execution ordering · 7. Component interaction · 8. State transitions · 9. Streaming lifecycle (§SFC) · 10. Retry lifecycle · 11. Timeout lifecycle · 12. Cancellation lifecycle (§CAC) · 13. Failure lifecycle · 14. Replay lifecycle (§CRS) · 15. Metering lifecycle · 16. Cost lifecycle · 17. Finalization lifecycle (§RFO) · 18. Snapshot usage (§SPT) · 19. Configuration visibility · 20. Plugin execution order (§PEB) · 21. Determinism guarantees · 22. Recovery interaction · 23. Observability interaction · 24. Performance considerations · 25. Security boundaries · 26. Build-failing rules (RSE-A1…RSE-A19) · 27. Operations · 28. Traceability · 29. Independent Review Board** · **C. Decisions (RSE-D1…RSE-D12)** · **Appendix**

---

## §SAQ — Sequence Authority Contract (SAQ-1…SAQ-8) *(resolves High RSE-H3)*
| # | Aspect | Contract |
|---|---|---|
| SAQ-1 | **Describes only** | This document **describes** the runtime sequence; it is documentation, not a runtime artifact. |
| SAQ-2 | **Never coordinates** | It **never coordinates** execution. |
| SAQ-3 | **Never dispatches** | It **never dispatches** work. |
| SAQ-4 | **Never orchestrates** | It **never orchestrates** stages. |
| SAQ-5 | **Never routes/activates** | It **never routes** and **never activates** anything (routing = C1 `19`; activation = `29`). |
| SAQ-6 | **Never owns execution** | It **never owns execution**; the co-located pipeline is assembled by Platform (`06 §8/§57`) and every stage decision is its frozen owner's. |
| SAQ-7 | **No authority expansion** | No wording herein grants this document, or any reader, authority over any stage. |
| SAQ-8 | **Not an orchestrator spec** | **No future implementation may treat this document as an orchestrator/sequencer specification.** Any component claiming to "execute the sequence" is a new module and is out of scope/forbidden here. |
- **Enforcement (RSE-A19):** doc-lint + ArchUnit (no runtime type references this doc as an authority). **Build-Fail:** a runtime component citing this document as an execution/orchestration authority; any authority-expanding wording.

## §POP — Partial Order Primacy Contract *(resolves Medium RSE-M1)*
- **POP-1:** The **linear lifecycle diagram (§4) is illustrative only.** The **authoritative** definition of execution order is the **partial-order dependency graph** (§6 hard constraints + §RFO/§CAC/§SFC/§SPT/§PEB). Where the linear view and the partial order appear to differ, **the partial order governs** — per-attempt metering/WAL interleaves with retries, and response streaming overlaps finalization (§RFO).
- **Enforcement:** RSE-A1/A5. **Build-Fail:** an implementation that treats the linear diagram as a total order and reorders/serializes contrary to the partial order.

---

## 1. Charter
Give every engineer **one** authoritative execution-order reference for the frozen runtime: the exact partial order of frozen stages, the failure/retry/timeout/cancel/stream/replay paths, and where the frozen guarantees (non-bypass, RPO=0, exactly-once, deterministic replay, tenant isolation, residency) are enforced. It **cites** the frozen owners; it **owns** nothing (§SAQ).

## 2. Scope
- **In scope (describe order only):** the canonical happy-path sequence; each variant lifecycle; the WAL/RequestFinalized ordering (§RFO); snapshot pinning (§SPT); plugin execution order at the frozen five points (§PEB); determinism scope (§CRS).
- **Out of scope:** any new stage/owner/store/event/extension point; re-deciding any stage's logic; CI/CD/deployment/DR mechanisms (`16`/`29`/`31`).
- **Applies to:** every request on the frozen co-located pipeline (`06 §8`).

## 3. Runtime ownership (§SAQ)
The co-located pipeline is assembled by Platform (`06 §8/§57`); each stage's decision is its frozen owner's (Ingress `30`; AuthN C6; Governance `21`; Router `19`; Reliability `20`; Adapter `25`; Secrets `26`; StreamGuard/SchemaLock `18`/`17`; Metering `23`; Cost `22`; Observability `27`; Plugin Runtime `28`). Per **§SAQ**, this document is a description and **not** a sequencer/orchestrator/owner.

## 4. Canonical request lifecycle (illustrative — authoritative order is §POP/§6/§RFO)
```
CLIENT → [30] INGRESS (identity/context; forward transport identity; NON-BYPASS)
  → [C6] AUTHENTICATION (principal + tenant scope) → [§SPT] SNAPSHOT PIN (after C6)
  → [28] «pre-routing plugins» → [21] GOVERNANCE («classification plugins») admit/deny
  → [19] ROUTING → [20] RELIABILITY (owns deadline) ┐
     per attempt: [26] SECRETS → [25] ADAPTER → PROVIDER → [18] STREAMGUARD verdict/usage
                  → «validation plugins» → [17] SCHEMALOCK → [23] METERING (UsageFact→WAL) «attribution plugins» ┘
  → mark DELIVERED (20 §25) → [30] response framed verbatim (consumer-paced)
  → [22] COST → [27] OBSERVABILITY («telemetry plugins»)
  → [23] FINALIZATION: RequestFinalized(complete) AFTER all UsageFacts WAL-committed (§RFO)
  → RESPONSE COMPLETE
```
**This is illustrative (§POP-1).** The binding order is the partial-order graph (§6/§RFO/§CAC/§SFC).

## 5. Request phases
| Phase | Owner | Doc | Fail-closed |
|---|---|---|---|
| Ingress/transport | Platform | `30` | reject |
| Authentication | C6 | (C6) | unknown ⇒ reject (`30 §IAB`) |
| Snapshot pin | Platform | §SPT | required-missing ⇒ fail closed |
| Governance | C4 | `21` | deny-by-default |
| Routing | C1 | `19` | no route ⇒ fail |
| Reliability | C2 | `20` | deadline/exhaustion ⇒ fail |
| Secrets | C14 | `26` | CredentialUnavailable ⇒ fail |
| Adapter/Provider | C1 | `25` | CanonicalProviderError ⇒ `20` |
| StreamGuard/SchemaLock | C3 | `18`/`17` | invalid ⇒ fail closed |
| Metering | C5 | `23` | UsageUnrecorded ⇒ fail closed |
| Cost | C5 | `22` | CostUnavailable |
| Observability | C9 | `27` | drop telemetry, never request |
| Finalization | C5 | `23` | INCOMPLETE ⇒ gap (§RFO) |

## 6. Mandatory execution ordering (hard constraints — authoritative)
1. **Ingress first** (`30`) — identity/context established.
2. **AuthN before tenant-scoped work; snapshot pin after C6** (`30 §IAB`, §SPT).
3. **Governance before Routing/Execution** (AD-018).
4. **Routing before Reliability** (`19`→`20`).
5. **Reliability owns and wraps every attempt** (`20`/`25 §17.1`).
6. **Secrets materialize immediately before, zeroize immediately after, each provider invocation** (`26`).
7. **StreamGuard/SchemaLock gate output before delivery** (`18`/`17`, AD-018).
8. **Per-attempt UsageFact WAL-commits before "accounted"; RequestFinalized(complete) only after ALL facts WAL-committed** (§RFO, `23 §17.1`/`§39.1`).
9. **Cost after Usage** (`22`←`23`).
10. **Observability side-effect-free, never gates a decision** (`27`, OT-INV).
11. **No mandatory stage skipped/reordered/overridden, including by plugins** (AD-018/`28 §EPC`, §PEB).

## 7. Component interaction
In-process handoff (`06 §8`, AD-020); no synchronous CP call on the hot path (AD-022); cross-stage data is canonical model (`25`) + RequestContext (`30`); downstream consumers receive facts, never re-derive (`23`/`22`/`27`).

## 8. State transitions
```
RECEIVED → AUTHENTICATED → PINNED → ADMITTED → ROUTED → EXECUTING
  → (per attempt: INVOKING → STREAMING/RESPONDED → GUARDED → VALIDATED → METERED)
  → DELIVERED → PRICED → OBSERVED → FINALIZED(complete)
Terminal (non-happy): REJECTED | DENIED | FAILED | CANCELLED | FINALIZED(incomplete/gap)
```
Every terminal state is single and explicit (`23 §17.1` FC-11).

## 9. Streaming lifecycle (§SFC)
Provider stream → **Adapter** frames canonical chunks (`25 §11`, verbatim, consumer-paced) → **StreamGuard** integrity + **terminal verdict + authoritative usage** (`18 §30`/CV-5) → **Ingress** frames to client verbatim (`30 §SIC`) under consumer-paced backpressure. Partial-stream order is pinned in **§SFC**.

## §SFC — Streaming Finalization Contract *(resolves Medium RSE-M4)*
Order (both terminal and partial/truncated streams):
```
Terminal stream verdict (18 §30)  →  authoritative usage extraction (18 CV-5)
  →  UsageFact WAL commit (23 §39.1)  →  RequestFinalized (23 §17.1)  →  resource cleanup (25 §31.1 / 26)
```
- **SFC-1:** Finalization for a stream occurs **only after** StreamGuard's **terminal verdict** — **never** mid-stream or on a still-in-flight stream (`23 §17.1` FC-2).
- **SFC-2:** A **partial/truncated** stream (`18 §26`) yields StreamGuard's **authoritatively-consumed** usage → WAL → metered as a **failed/cancelled-attempt** UsageFact (never delivered) → the request finalizes **INCOMPLETE-or-complete per `23 §17.1`**, **never finalized-complete early**. The un-delivered remainder is never fabricated (`23 §20`).
- **SFC-3:** Resource cleanup (pool reset `25 §31.1`, credential zeroize `26`) occurs **after** WAL commit — never before consumed usage is durably recorded.
- **Enforcement (RSE-A16):** streaming-finalization order test. **Build-Fail:** finalizing a partial/in-flight stream before its terminal verdict + WAL commit.

## 10. Retry lifecycle (`20`/`23`)
Reliability orchestrates initial/failover/hedge/retry/guided-retry; **each unit-consuming attempt → its own UsageFact** (`23 §18/§26`) keyed by execution identity `(idempotencyKey, attemptId)` (`27 §18.1`/`23 §14.1`) so **retries never double-count**; exactly one attempt **delivered** (`20 §25`); provider usage = Σ attempts, customer = delivered (`23 §26–28`/`22 §24.1`). Retry policy is solely `20`'s.

## 11. Timeout lifecycle (three frozen layers)
Transport timeout — Ingress (`30 §DOC`); **execution deadline — Reliability (`20`, sole owner)** (`25 §17.1`); per-attempt transport budget — Adapter (`25 §17.1`, handed by `20`). The gateway never extends/shortens the execution budget (`30 §DOC`).

## 12. Cancellation lifecycle (§CAC)
## §CAC — Cancellation Accounting Contract *(resolves Medium RSE-M2)*
Order (client disconnect / cancel):
```
CANCEL intent (30 §CUC)  →  Reliability decides execution cancel (20)
  →  usage extraction of consumed units (18 CV-5 / 25)  →  WAL commit (23 §39.1)
  →  metering (23 attempt UsageFact)  →  RequestFinalized (23 §17.1)  →  resource cleanup (25/26)
```
- **CAC-1:** **Disconnect ≠ execution cancellation** (`30 §CUC-1`).
- **CAC-2:** **Consumed usage is extracted and WAL-committed BEFORE resource cleanup** — a cancel **never** loses consumed provider usage (`23 §22–23`/`22 §24.1` CA-6). Metering precedes cleanup; cleanup precedes exit.
- **CAC-3:** The cancelled attempt is a provider-usage fact, **never** the delivered customer usage.
- **CAC-4:** Finalization for a cancelled request follows the normal completeness rule (`23 §17.1`) — surfaced, never silently dropped; **no accounting evasion**.
- **Enforcement (RSE-A11):** cancellation-accounting order test. **Build-Fail:** resource cleanup before consumed-usage WAL commit; a cancel that drops consumed usage.

## 13. Failure lifecycle
Every failure resolves at its owning stage's frozen fail-closed rule (§5); a failure never bypasses a downstream mandatory stage (AD-018). Observability failure ⇒ drop telemetry, request unaffected (OT-INV).

## 14. Replay lifecycle (§CRS)
## §CRS — Composed Replay Scope Contract (CRS-1…CRS-8) *(resolves High RSE-H2)*
| # | Aspect | Contract |
|---|---|---|
| CRS-1 | **Weakest-scope inheritance** | The composed whole-request replay **inherits the weakest replay guarantee** of its components — it is **no stronger** than the adapter's/provider's/telemetry's honest scope. |
| CRS-2 | **Reproduces — decision ordering** | Replay reproduces the **ordering of mandatory-stage decisions** (§6, `29 §CVR`). |
| CRS-3 | **Reproduces — canonical mappings** | Replay reproduces the **adapter's canonical mapping** decisions (`25 §23.1`, mapping core only). |
| CRS-4 | **Reproduces — recorded plugin outputs** | Replay reproduces **recorded plugin outputs** (`28 §RPC`) — plugins are **not re-executed** as authoritative. |
| CRS-5 | **Reproduces — recorded telemetry decisions** | Replay reproduces **recorded telemetry sampling/redaction decisions** (`27 §19.1`) — not telemetry timing. |
| CRS-6 | **MUST NOT claim** | Replay **MUST NOT** claim reproduction of: **provider timing, wall clock, network scheduling, provider randomness, provider execution, telemetry timing, stream timing.** |
| CRS-7 | **Non-authoritative** | Replay is non-authoritative; accounting truth is C5 (`23`), never re-derived from a replay. |
| CRS-8 | **Recorded identity** | Replay uses the **recorded `(codeVersion, snapshotVersions, execution identity)`** (`29 §CVR`), never the currently-serving version. |
- **Enforcement (RSE-A18):** replay-scope test (`15` T-035). **Build-Fail:** a composed replay claim reproducing provider/wall-clock/scheduling/timing/provider-execution; a replay re-executing a plugin as authoritative.

## 15. Metering lifecycle (`23`)
Per-attempt UsageFacts (authoritative `18 CV-5`) → **WAL-commit (RPO=0)** → ledger (idempotency-keyed, exactly-once) → quota counters (`21 §28.1`) → Cost (`22`). Never fabricated; missing ⇒ UsageUnrecorded (fail closed).

## 16. Cost lifecycle (`22`)
Cost prices recorded UsageFacts (`22 §20`), delivered-vs-attempt (`22 §24.1`), **after** metering, never re-deriving usage. UsageUnrecorded ⇒ CostUnavailable.

## 17. Finalization lifecycle (§RFO)
## §RFO — Response Finalization Ordering Contract (RFO-1…RFO-12) *(resolves Critical RSE-C1)*
The authoritative partial order among **provider-attempt completion (PAC)**, **authoritative usage extraction (AUE, `18 CV-5`)**, **WAL commit (WAL, `23 §39.1` UC-1)**, **StreamGuard terminal verdict (SGV, `18 §30`)**, **RequestFinalized (RF, `23 §17.1`)**, and **client response completion (CRC)**:

| # | Aspect | Contract |
|---|---|---|
| RFO-1 | **PAC → AUE** | Authoritative usage is extracted **after** the provider attempt completes (unary) or the stream reaches SGV (streaming) — **never** estimated ahead (`18 CV-5`/`23 §D5`). |
| RFO-2 | **SGV → AUE (streaming)** | For streaming, AUE occurs **only after** the StreamGuard **terminal verdict** (`18 §30`); no authoritative usage before SGV. |
| RFO-3 | **AUE → WAL** | Each authoritative UsageFact is **WAL-committed** (`23 §39.1` UC-1) after extraction. |
| RFO-4 | **WAL before "accounted"** | A request is **"accounted"** only after its UsageFact(s) WAL-commit (`23 §39.1` UC-1); WAL commit is the **RPO=0 boundary**. |
| RFO-5 | **MAY happen before CRC** | The client response **MAY complete after** the delivered attempt's WAL commit but **before** RF (`23 §39.1` UC-5) — response delivery and RF are **distinct events**. |
| RFO-6 | **MUST before RF** | RF(complete) is emitted **only after EVERY authoritative UsageFact for the request is WAL-committed** (`23 §17.1` FC-1…FC-6) — including all attempts and, for streaming, post-SGV usage. |
| RFO-7 | **NEVER before** | RF(complete) **MUST NEVER** be emitted before any authoritative UsageFact is WAL-committed; CRC **MUST NEVER** be treated as RF; consumed usage **MUST NEVER** be discarded before WAL (`23 §17.1`/§CAC). |
| RFO-8 | **Asynchronous** | The **ledger acknowledgement** (post-WAL forward, `23 §39.1` UC-5) and **Cost/Observability** are **asynchronous** to CRC — they run behind the response and **never** gate it. |
| RFO-9 | **Impossible** | It is **impossible** (build-forbidden) to: serve a response whose usage could not WAL-commit (fail-safe reject, `07 EV-D3`); finalize-complete with a missing attempt/stream fact; finalize on a still-in-flight stream (§SFC). |
| RFO-10 | **Timeout → INCOMPLETE** | A finalization timeout ⇒ **RF(incomplete)/gap** (`23 §17.1` FC-7) — **never** a silent complete. |
| RFO-11 | **Single terminal** | Each request reaches a single terminal finalization state (`23 §17.1` FC-11); no third "silently done." |
| RFO-12 | **Preserves frozen** | This contract **preserves `23 §17.1`, `23 §39.1`, `07 EV-D3`, RPO=0, and exactly-once** — it **cites and orders** them; it authors no new guarantee. |
- **Cardinal order:** `PAC → (SGV for streams) → AUE → WAL → [CRC may occur here] → (all facts WAL-committed) → RF → ledger-ack/Cost/Obs async`.
- **Enforcement (RSE-A16):** finalization-order + completeness test (`15` T-046). **Build-Fail:** RF(complete) before all facts WAL-committed; CRC treated as RF; usage discarded before WAL; finalization on an in-flight stream.

## 18. Snapshot usage (§SPT)
## §SPT — Snapshot Pin Timing Contract *(resolves Medium RSE-M3)*
Order:
```
identity established (30)  →  C6 authentication (principal)  →  tenant resolution (tenant scope)
  →  snapshot selection (per tenant/region, AD-022)  →  snapshot PIN  →  pipeline continues
```
- **SPT-1:** **Snapshot pinning MUST NEVER occur before C6 authentication** — tenant/policy/capability/cost/usage snapshots are tenant-scoped and cannot be selected before the tenant scope is resolved (post-C6, `30 §IAB-5`).
- **SPT-2:** Once pinned, snapshot versions are **identical across all stages** for the request (`29 §SCC`); no stage re-reads a fresher snapshot mid-request (deterministic, `22 §36`/`23 §36`).
- **SPT-3:** A required snapshot unavailable at pin ⇒ **fail closed** (out of rotation / reject) — never serve on an unpinned/incompatible set.
- **Enforcement (RSE-A8):** snapshot-pin-timing test. **Build-Fail:** a snapshot pinned before C6; a mid-request snapshot version change.

## 19. Configuration visibility
The pinned snapshot set is the **only** configuration a request sees (AD-022); no synchronous CP call (AD-022); a request's **decisions** are a pure function of `(codeVersion, pinned snapshots, request inputs, recorded plugin contributions)` (§21).

## 20. Plugin execution order (§PEB)
## §PEB — Plugin Execution Boundary Contract (PEB-1…PEB-10) *(resolves High RSE-H1)*
Plugins run **at** the frozen point, **before** the owning stage's decision, contributing an **additive signal**; the stage decides (`28 §EPC-5`). Per-point boundaries:

| Point | Inputs visible to the plugin | Decisions NOT visible | Stage authority (decides) |
|---|---|---|---|
| **pre-routing** | RequestContext, authenticated principal/tenant scope, pinned snapshots, request payload | **the route/provider (not yet chosen)**; any downstream decision | **C1 Router (`19`)** |
| **classification** | RequestContext, tenant scope, payload, pinned policy snapshot | **the governance verdict (not yet made)**; route; usage/cost | **C4 Governance (`21`)** |
| **validation** | the canonical response/tool-call to validate, pinned schema | **the SchemaLock verdict (not yet made)**; delivery | **C3 SchemaLock (`17`)** |
| **telemetry** | content-free, correlation-threaded signals | request/response **content**; secrets; decisions it could alter | **C9 Observability (`27`)** |
| **attribution** | canonical usage facts, tenant scope | the **priced cost**; the metering ledger write | **C5 Metering (`23`)** |

| # | Boundary rule | Contract |
|---|---|---|
| PEB-1 | **Signal-in / decision-out** | A plugin returns an **advisory signal**; the stage consumes it and **decides**. |
| PEB-2 | **Never a stage owner** | A plugin **never becomes a stage owner** — ownership stays C1–C6/C9 per the table. |
| PEB-3 | **Never changes a verdict** | A plugin **never changes a stage's verdict** (route, admission, validation, cost, usage). |
| PEB-4 | **Never bypasses** | A plugin **never bypasses** a mandatory stage (AD-018). |
| PEB-5 | **Never observes unavailable info** | A plugin **never observes a decision not yet made** (per the "Decisions NOT visible" column) nor another point's private state. |
| PEB-6 | **Deterministic order** | Multiple plugins at one point run in **deterministic order** (`28 §POC`); mutually isolated (no cross-plugin visibility, `28 §POC-3`). |
| PEB-7 | **Isolated failure** | A plugin failure is **isolated**; its contribution discarded; the stage proceeds unweakened (`28 §EPFC`). |
| PEB-8 | **Frozen five only** | Plugins run **only** at pre-routing/classification/validation/telemetry/attribution; **no** post-routing/pre-invoke/post-invoke/response-transform/provider-interception/stream-mutation (`28 §EPC-3`). |
| PEB-9 | **No new points** | This document introduces **no new extension point**. |
| PEB-10 | **No secret/provider access** | A plugin **never** accesses secrets (`26`) or couples to a provider (`28 §31.1`, AD-007). |
- **Enforcement (RSE-A17):** plugin-boundary test. **Build-Fail:** a plugin at a non-frozen point; a plugin changing/owning a verdict; a plugin observing an unmade decision; a new extension point.

## 21. Determinism guarantees
Mandatory-stage **decisions** are deterministic given `(codeVersion, pinned snapshots, request inputs, recorded plugin contributions)` (`11` R-063, `29 §CVR`). **Not** deterministic: wall-clock, timing, scheduling, provider behavior, telemetry/stream timing (§CRS). Deterministic in **order and decision**, not **timing**.

## 22. Recovery interaction (`29`/`31`)
On recovery, an in-flight request drains (`29 §SDC`, RPO=0) or its committed UsageFacts replay (`23 §39.1`/`31 §RCO`); a recovered instance serves only after readiness (`29 §SRC`, non-bypass). RPO=0 preserved within the frozen scope (`31 §RPO`).

## 23. Observability interaction (`27`)
Content-free, correlation-threaded telemetry off the decision path (OT-INV); one correlation id threads the lifecycle (`27 §9`/`07 §6`); telemetry never gates a stage.

## 24. Performance considerations
In-process (AD-020), Virtual Threads (AD-023); added-latency budget = Σ stage overheads excluding provider round-trip (`06 §11`); streaming consumer-paced (`25 §31.1`); metering WAL off the response critical path (`23 §40`, RFO-8).

## 25. Security boundaries
mTLS at ingress (`30`/AD-012); tenant scope post-C6 (`30 §IAB`/§SPT); credentials only Secrets→Adapter (`26`, never plugins/telemetry, PEB-10); non-bypass (AD-018); residency (AD-014); tenant isolation (AD-021); content-free telemetry/audit (`27 §15.1`).

## 26. Build-Failing Rules (RSE-A1 … RSE-A19)
| # | Rule | Gate |
|---|---|---|
| RSE-A1 | Every mandatory stage executes; none skipped/reordered/overridden (AD-018); partial order honored (§POP) | non-bypass test |
| RSE-A2 | Tenant scope never used before C6 (`30 §IAB`) | ordering test |
| RSE-A3 | Governance admission precedes provider selection/call | ordering test |
| RSE-A4 | Nothing delivered un-guarded/un-validated (`18`/`17`) | correctness-order test |
| RSE-A5 | Per-attempt UsageFact WAL-committed; RF only after all committed (§RFO) | finalization-order test |
| RSE-A6 | No double-count under retry; execution-identity dedup (`23 §14.1`) | accounting test |
| RSE-A7 | Cost prices after Usage; never re-derives (`22`←`23`) | ordering test |
| RSE-A8 | Snapshots pinned after C6, at request start, identical across stages (§SPT) | snapshot-pin test |
| RSE-A9 | Plugins only at frozen five points, signal-before-decision (§PEB) | plugin-order test |
| RSE-A10 | Execution deadline solely Reliability's (`20`/`30 §DOC`) | timeout-ownership test |
| RSE-A11 | Disconnect ≠ cancellation; consumed usage WAL-committed before cleanup (§CAC) | cancellation test |
| RSE-A12 | Replay reproduces decisions+order only (§CRS) | replay test (`15` T-035) |
| RSE-A13 | Observability side-effect-free; never gates a stage (OT-INV) | side-effect test |
| RSE-A14 | Authors no new stage/owner/store/event/extension point (description-only) | doc-lint + ArchUnit |
| RSE-A15 | Mutation score ≥ 90% (pipeline-sequence conformance harness) | PITest (`15 §G.1`) |
| RSE-A16 | **Response Finalization Ordering (§RFO/§SFC):** RF only after all facts WAL-committed; CRC≠RF; no early/partial-stream finalize | finalization/completeness test (`15` T-046) |
| RSE-A17 | **Plugin Execution Boundary (§PEB):** signal-in/decision-out; never own/change/bypass a verdict; never observe unmade decisions; frozen five only | plugin-boundary test |
| RSE-A18 | **Composed Replay Scope (§CRS):** weakest-scope; reproduces decisions/mappings/recorded-outputs only; never provider/wall-clock/timing | replay-scope test |
| RSE-A19 | **Sequence Authority (§SAQ):** description-only; never coordinates/dispatches/orchestrates/routes/activates/owns; not an orchestrator spec | doc-lint + ArchUnit |

## 27. Operations
- **Sequence-conformance failure:** a stage skipped/reordered ⇒ AD-018 breach (Sev); pipeline assembly wrong.
- **`ume_completeness_gap` > 0:** RF ordering breach (§RFO) ⇒ Sev1.
- **Double-count anomaly:** execution-identity dedup breach (`23 §14.1`).
- **Plugin-order nondeterminism:** `28 §POC` breach.

## 28. Traceability
| Concern | Owner | Doc | ADR |
|---|---|---|---|
| Non-bypass order (§6) | pipeline | `06 §8`/`17`–`31` | AD-018/020 |
| Finalization order (§RFO) | C5 | `23 §17.1`/`§39.1` | AD-005/009 |
| Plugin boundary (§PEB) | C12 | `28 §EPC` | AD-004 |
| Composed replay (§CRS) | — | `29 §CVR`/`25`/`27`/`28` | AD-016 |
| Sequence authority (§SAQ) | Platform | `06 §8/§57` | AD-020 |
| Snapshot pin (§SPT) | C15/… | `29 §SCC`/AD-022 | AD-022 |
| Cancellation accounting (§CAC) | C5/C2 | `23`/`20`/`30 §CUC` | AD-018 |
| Streaming finalization (§SFC) | C3/C5 | `18`/`23 §17.1` | AD-018 |
| Retry/timeout (§10/§11) | C2 | `20`/`25 §17.1` | AD-016 |
| Recovery (§22) | Platform | `29`/`31` | AD-014/017 |

---

## C. Decisions (RSE-D1 … RSE-D12)
*9-field template. Each decision references its resolving contract; all "open" tags removed.*

### RSE-D1 — Documentation-only; describes, never owns (§SAQ)
- **Problem:** a sequence doc could be read as an orchestrator/owner. **Decision:** it **describes** the frozen `06 §8` order and owns nothing (§SAQ). **Alternatives:** a runtime sequencer — rejected (new module). **Why:** faithful, no drift. **Trade-offs:** descriptive only. **Failure Modes:** n/a. **Security:** no new surface. **Performance:** none. **Enforcement:** RSE-A19/§SAQ. **Build-Fail:** authoring a stage/owner or being cited as an orchestrator.

### RSE-D2 — Canonical non-bypass ordering (AD-018)
- **Problem:** exact frozen order. **Decision:** cite §6; every stage, in order (§POP). **Alternatives:** fast-path skip — rejected. **Why:** non-bypass fidelity. **Trade-offs:** full pipeline. **Failure Modes:** skip ⇒ build-fail. **Security:** structural non-bypass. **Performance:** budgeted. **Enforcement:** RSE-A1. **Build-Fail:** skipped/reordered stage.

### RSE-D3 — Snapshot pin after C6, at request start (§SPT)
- **Problem:** consistent config, tenant-scoped. **Decision:** pin after C6; identical across stages (§SPT). **Alternatives:** pin before C6 / per-stage reads — rejected. **Why:** determinism + tenant-correctness. **Trade-offs:** freshness bounded. **Failure Modes:** required-missing ⇒ fail closed. **Security:** consistent policy. **Performance:** cached. **Enforcement:** RSE-A8. **Build-Fail:** pin before C6 / mid-request change.

### RSE-D4 — Plugins additive at frozen points, signal-before-decision (§PEB)
- **Problem:** plugin order must not bypass/override. **Decision:** frozen five, signal-in/decision-out (§PEB). **Alternatives:** deciding plugins — rejected. **Why:** additive fidelity. **Trade-offs:** signals only. **Failure Modes:** isolated (`28 §EPFC`). **Security:** no bypass. **Performance:** budgeted. **Enforcement:** RSE-A17. **Build-Fail:** non-frozen point / verdict change.

### RSE-D5 — Retry per-attempt accounting, no double-count (§10)
- **Problem:** retries double-count. **Decision:** per-attempt UsageFact, execution-identity dedup. **Alternatives:** count-per-success — rejected. **Why:** exactly-once. **Trade-offs:** more facts. **Failure Modes:** dedup breach ⇒ build-fail. **Security:** no evasion/inflation. **Performance:** O(attempts). **Enforcement:** RSE-A6. **Build-Fail:** double-count.

### RSE-D6 — Streaming/partial lifecycle (§SFC)
- **Problem:** streaming order + no mutation. **Decision:** adapter→StreamGuard→ingress verbatim; partial per §SFC. **Alternatives:** ingress mutation — rejected. **Why:** integrity. **Trade-offs:** no repair. **Failure Modes:** partial ⇒ failed/cancelled fact. **Security:** no stream bypass. **Performance:** consumer-paced. **Enforcement:** RSE-A4/A16. **Build-Fail:** delivery before verdict.

### RSE-D7 — Timeout/cancellation layering (§11/§CAC)
- **Problem:** three timeout layers. **Decision:** transport/execution(sole `20`)/per-attempt; disconnect≠cancel; meter-before-cleanup (§CAC). **Alternatives:** ingress execution deadline — rejected. **Why:** single deadline authority. **Trade-offs:** layered. **Failure Modes:** timeout ⇒ cancel + metered. **Security:** no evasion. **Performance:** bounded. **Enforcement:** RSE-A10/A11. **Build-Fail:** ingress execution deadline / cleanup before WAL.

### RSE-D8 — WAL→response→RequestFinalized ordering (§RFO)
- **Problem:** response/finalization order. **Decision:** §RFO partial order (RF only after all facts WAL-committed; CRC may precede RF). **Alternatives:** finalize-before-WAL — rejected (`23 §17.1`). **Why:** completeness-correct. **Trade-offs:** distinct events. **Failure Modes:** timeout ⇒ INCOMPLETE. **Security:** accounting completeness. **Performance:** WAL off critical path. **Enforcement:** RSE-A16. **Build-Fail:** RF before all facts WAL-committed.

### RSE-D9 — Cost after Usage (§16)
- **Problem:** cost re-derive. **Decision:** price after metering. **Alternatives:** re-parse — rejected. **Why:** single usage truth. **Trade-offs:** ordering. **Failure Modes:** UsageUnrecorded ⇒ CostUnavailable. **Security:** consistent. **Performance:** O(1). **Enforcement:** RSE-A7. **Build-Fail:** cost before/without usage.

### RSE-D10 — Composed deterministic replay (§CRS)
- **Problem:** whole-request replay honesty. **Decision:** weakest-scope; decisions/order only (§CRS). **Alternatives:** full replay — rejected. **Why:** honest. **Trade-offs:** no timing. **Failure Modes:** none on correctness. **Security:** audit integrity. **Performance:** neutral. **Enforcement:** RSE-A18. **Build-Fail:** overclaimed reproduction.

### RSE-D11 — Observability side-effect-free (§23)
- **Problem:** telemetry gating. **Decision:** passive, off decision path (OT-INV). **Alternatives:** gating — rejected. **Why:** no runtime effect. **Trade-offs:** none. **Failure Modes:** drop, request unaffected. **Security:** no telemetry bypass. **Performance:** off critical path. **Enforcement:** RSE-A13. **Build-Fail:** telemetry gating a stage.

### RSE-D12 — Fail closed at owning stage; no bypass (AD-018)
- **Problem:** failures bypassing stages. **Decision:** owning-stage fail-closed (§13). **Alternatives:** fail-open — rejected. **Why:** reliability-first. **Trade-offs:** stricter. **Failure Modes:** owning-stage fail-closed. **Security:** no bypass. **Performance:** budgeted. **Enforcement:** RSE-A1. **Build-Fail:** failure bypassing a stage.

---

## 29. Independent Review Board — Adversarial Review (re-run after resolution)

### 1. Internal Consistency Review
| Claim | Contract | Frozen source | ✓ |
|---|---|---|---|
| Non-bypass; partial-order primacy | §6/§POP | `06 §8`/AD-018 | ✅ |
| Response/RF/WAL partial order | §RFO RFO-1…RFO-12 | `23 §17.1`/`§39.1`/`07 EV-D3` | ✅ |
| Plugin per-point boundary | §PEB PEB-1…PEB-10 | `28 §EPC`/`§POC`/`§EPFC` | ✅ |
| Composed replay weakest-scope | §CRS CRS-1…CRS-8 | `25 §23.1`/`27 §19.1`/`28 §RPC`/`29 §CVR` | ✅ |
| Description ≠ orchestrator | §SAQ SAQ-1…SAQ-8 | `06 §8/§57` | ✅ |
| Snapshot pin after C6 | §SPT | `30 §IAB`/`29 §SCC` | ✅ |
| Cancellation meter-before-cleanup | §CAC | `23`/`30 §CUC`/`22 §24.1` | ✅ |
| Streaming finalization | §SFC | `18 §26/§30`/`23 §17.1` | ✅ |

### 2. Architecture Validation
- **Ownership:** ✅ description-only, hard-pinned (§SAQ); no orchestrator/owner.
- **Non-bypass/exactly-once/RPO=0:** ✅ preserved and now ordering-precise (§RFO/§CAC/§SFC).
- **Plugins:** ✅ per-point boundary exact (§PEB); frozen five only.
- **Replay:** ✅ composed at the weakest honest scope (§CRS); no overclaim.
- **Runtime-only:** ✅ authors no stage/owner/store/event/point.

### 3. Cross-document Validation
Consistent with `06 §8`, `17`–`31`, AD-004/005/009/011/014/016/018/020/021/022, and the `24`-unfrozen / optimization-as-pre-routing-plugin rule (`24A`/`28 §EPC`). No contradiction; every ordering claim cites its frozen source.

### 4. Independent Review Board
*Board: Enterprise Solutions Architect · Distributed Systems Architect · Principal Staff Engineer · Runtime Platform Architect · Technical Documentation Lead · Staff Reliability Engineer · Security Architect · Compliance Auditor · CTO.*

**Accepted findings — resolution:** RSE-C1→§RFO (RSE-A16); RSE-H1→§PEB (RSE-A17); RSE-H2→§CRS (RSE-A18); RSE-H3→§SAQ (RSE-A19); RSE-M1→§POP; RSE-M2→§CAC; RSE-M3→§SPT; RSE-M4→§SFC.

**New findings:** 🔴 Critical: none. 🟠 High: none. 🟡 Medium: none blocking. 🟢 Low (deferred): Appendix B.

**Internal Contradictions:** none — the linear/partial-order tension is resolved by §POP (partial order authoritative); response/finalization ordering is exact (§RFO).

**Cross-document Contradictions:** none.

**Scores:** Architecture Readiness **97/100**; Documentation Health **96/100**; Implementation Readiness **94/100**.

**Recommendation: FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred. No contradictions with `00`–`31` or AD-001…AD-023; description-only; every ordering claim frozen-cited; RSE-INV reinforced.

---

## Appendix B — Deferred Low Items (non-blocking)
- **RSE-L1** — sequence-diagram artifact → detailed design.
- **RSE-L2** — per-stage latency budget breakdown → `06 §11`/`16 §I.1`.
- **RSE-L3** — state-machine formalization → design.
- **RSE-L4** — correlation-id propagation diagram → `27`.

These are documentation/detail deliverables; none affects ordering, ownership, non-bypass, exactly-once, RPO=0, or replay scope.

---

*End of document — 32-RuntimeExecutionSequence.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
