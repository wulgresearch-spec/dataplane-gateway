# 28 — Plugin Runtime (Sandboxed Extension Execution · Domain C12)

**Document:** Component Implementation Architecture — Plugin Runtime
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Bounded context:** **C12 — Extensibility & Ecosystem** (Supporting, `05`/`06`)
**Module:** *(no new module)* — this document specifies **only the runtime behavior of the frozen "Sandboxed Plugin Runtime (C12)" node** (`06 §8` PLG), the data-plane presence of C12, co-located per **AD-020/AD-006**. It is **not** a new bounded context, **not** a new service, **not** a new deployment, **not** a new store, and **owns no store**. It **executes vetted plugins** distributed by the **frozen Extensibility Service (C12 control plane, `06 §9.12`)**, at the **frozen additive-only extension points** (`06 §9`/`04 §53`), per **AD-004**.
**Frozen name:** Sandboxed Plugin Runtime (C12) — data-plane presence
**Owner:** Platform (runtime host / Tier-0 SLOs, `06 §9.1`) with **Ecosystem (C12)** owning the registry/vetting control plane (`06 §9.12`) — **unchanged**
**Plane:** Tier-0 Runtime (request-critical host); registry/vetting is Tier-2 control-plane (C12 Extensibility Service)
**Audience:** Platform/ecosystem/security/data-plane engineers, SRE, supply-chain security, compliance, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`27`, `24A` (informational), and ADRs **AD-001…AD-023** — **especially** AD-004, AD-020, AD-018, AD-022, `05` Component Catalogue, `06` Runtime Architecture + `06 §9` extension points + `06 §9.12` Extensibility Service. Everything here is **additive**. Introduces **no new architecture decision, no new bounded context, no new service, no new module, no new store, no new deployment topology, no new extension point beyond the frozen set, no provider coupling, and no responsibility movement.** Every PRT-Dx is a *component-internal implementation decision* inside the already-frozen C12 data-plane presence, expressed through hexagonal ports (AD-002), **subordinate to AD-004** and to the **frozen five extension points** (`06 §9`).

> **What this is.** The complete implementation architecture of the **Sandboxed Plugin Runtime** — the component that **safely executes approved (vetted) plugins inside the existing request pipeline while preserving every system invariant**. Plugins **add behavior** at the frozen **additive-only extension points** (pre-routing, classification, validation, telemetry, attribution; `06 §9`/`04 §53`) but **can never bypass** authentication, authorization, governance, correctness, accounting, isolation, or audit, and **can never cross tenant boundaries** (AD-004). Vetting, registration, signing, publishing, discovery, and provenance are the **Extensibility Service's (C12 CP, `06 §9.12`)**; the runtime **verifies, loads, executes, isolates, and unloads** what C12 vetted — it never vets.
>
> **THE PLUGIN-RUNTIME INVARIANT (PRT-INV):** *Plugins may extend behavior. Plugins may never redefine architecture. Plugins may never bypass policy. Plugins may never mutate ownership. Plugins may never weaken correctness.* The runtime must NEVER let a plugin violate Security, Governance, Routing, Reliability, StreamGuard, SchemaLock, Metering, Cost, Secrets, or Observability. On any attempted violation, crash, timeout, or uncertainty: **FAIL CLOSED** — isolate the plugin, discard its contribution, and let the mandatory pipeline proceed unweakened (§37/§EPFC). This is the extensibility-layer realization of **AD-004 (sandboxed, additive-only)** and **AD-018 (non-bypassable pipeline)**.

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (PRT-C1, PRT-C2, PRT-C3)**, **High (PRT-H1…PRT-H5)**, and **Medium** findings are resolved **additively** through signed normative contracts, each build-enforced:

- **§EPC Extension Point Contract (EPC-1…EPC-8)** — PRT-C1 (frozen five only; forbids post-routing/pre-invoke/post-invoke/response-transform/provider-interception/stream-mutation). Build rule **PRT-A19**.
- **§ISO Isolation Contract (ISO-1…ISO-10)** — PRT-C2 (honest guaranteed vs not-guaranteed; process-isolation substrate). Build rule **PRT-A20**.
- **§ROC Runtime Ownership Contract (ROC-1…ROC-8)** — PRT-C3 (CP owns registry/signing/vetting/discovery/provenance/publishing/lifecycle; runtime owns verify/load/execute/isolate/unload). Build rule **PRT-A21**.
- **§STC Supply-chain Trust Contract (STC-1…STC-7)** — PRT-H1 (trust anchor, signature, immutable digest, provenance).
- **§REC Resource Enforcement Contract (REC-1…REC-7)** — PRT-H2 (quotas, cancellation, timeout ownership, exhaustion behavior, fail closed).
- **§POC Plugin Ordering Contract (POC-1…POC-6)** — PRT-H3 (deterministic order from inputs+plugin-set+policy-snapshot).
- **§RPC Replay Contract (RPC-1…RPC-6)** — PRT-H4 (decisions reproduced; wall-clock/timing/scheduling never).
- **§HRC Hot Reload Contract (HRC-1…HRC-6)** — PRT-H5 (new versions require the deployment lifecycle; never mutate loaded plugin behavior).
- **§EPFC per-point fail-closed** + Medium clarifications (dependency/classloader isolation §14.1, recursion/deadlock §30.1, tenant isolation §36.1, provider-coupling exclusion §31.1).

**Low** items remain **deferred** (Appendix B). **No new architecture, bounded context, service, module, store, deployment topology, ownership, or extension point is introduced; AD-002/AD-004/AD-007/AD-018/AD-020/AD-021/AD-022/AD-023 are preserved exactly; the frozen C12 architecture and the frozen five extension points are preserved; no invariant is weakened; the document is runtime-only.** No document `00`–`27` and no ADR is modified.

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Interfaces** (§6–7) · **§EPC / §ISO / §ROC** (load-bearing contracts) · **C. Major Decisions** (PRT-D1…PRT-D12) · **D. Lifecycle** (§8–14 · §14.1) · **E. Execution & Extension Points** (§15–16 · §EPFC) · **F. Isolation, Sandboxing & Resource Limits** (§23–30 · §REC · §30.1) · **G. Permission & Security Model** (§31–36 · §31.1 · §STC · §36.1) · **H. Failure, Ordering, Determinism & Replay** (§37–42 · §POC · §RPC) · **I. Hot Reload / Upgrade / Rollback** (§43–45 · §HRC) · **J. Observability** (§46–48) · **K. Testing** (§49) · **L. Build-Failing Rules** (PRT-A1…PRT-A21) · **M. Operations** (§50–52) · **N. Traceability** (§53) · **S. Reviews** · **Appendix A/B**

---

## A. Charter

### §1 — Purpose
The Plugin Runtime **safely executes approved plugins inside the existing request pipeline while preserving every system invariant**. For each request it invokes the vetted plugins bound to the **frozen additive-only extension points** (§EPC), in **process-isolated sandboxes** with **least-privilege permissions** and **bounded, accounted resources** (§ISO/§REC), such that a plugin can **add** behavior (extra classification, routing *signals*, validation *signals*, telemetry, attribution) but can **never** bypass or weaken a mandatory stage (AD-004/AD-018). A plugin's failure/violation is isolated and its contribution discarded; the mandatory pipeline proceeds unweakened (PRT-INV).

### §2 — Runtime scope
- **In scope (verify/load/execute/isolate/unload vetted plugins only — §ROC):** verify signature/provenance/digest (§STC); load vetted plugin snapshots (AD-022); bind them to the **frozen extension points** (§EPC); execute in a process-isolated sandbox with a permission model + accounted resource budgets (§ISO/§REC); isolate failures; propagate correlation context; emit plugin telemetry/audit; unload.
- **Out of scope (frozen C12 CP / other owners — §ROC):** plugin **vetting, registration, discovery, marketplace, signing, publishing, provenance authoring, lifecycle authoring, SDK management** (Extensibility Service, `06 §9.12`); any mandatory-stage logic (C1–C6/C9/C10); any new extension point (frozen set only, §EPC).
- **Applies to:** every request, at each frozen extension point where a vetted plugin is bound.

### §3 — Responsibilities
1. **Verify** the signature, immutable digest, and provenance of the C12-vetted snapshot before load (§STC).
2. **Load** vetted, version-pinned plugin snapshots distributed by C12 (`06 §9.12`, AD-022).
3. **Bind & execute** plugins at the **frozen additive-only extension points** (§EPC).
4. **Sandbox** execution: process isolation, least-privilege permissions (§31), accounted CPU/memory/time/IO with quotas + cancellation (§REC), deny-by-default network/filesystem/secret access (§31–33).
5. **Isolate failures**: a plugin crash/timeout/violation is contained; its contribution discarded; the mandatory pipeline **proceeds unweakened** (§37/§EPFC).
6. **Propagate** correlation context (`07 §6`, `14`/`27`); emit content-free plugin telemetry/audit.
7. **Unload** plugins; pick up new versions **only** via the deployment lifecycle (§HRC).
8. **Fail closed** on any attempted invariant violation, crash, timeout, or uncertainty (PRT-INV).

### §4 — Non-Responsibilities (frozen ownership — the runtime NEVER does; see §ROC)
- ❌ **vet / register / discover / market / sign / publish / author-provenance / author-lifecycle / manage SDKs** — **C12 Extensibility Service** (CP, `06 §9.12`).
- ❌ **any mandatory-stage decision** — routing (C1/`19`), reliability (C2/`20`), correctness (C3/`17`/`18`), governance/authz (C4/`21`), metering/cost (C5/`22`/`23`), secrets (C14/`26`), observability standards (C9/`14`/`27`). Plugins provide **signals**; the owning stage **decides**.
- ❌ **provider calls / provider coupling / provider interception / stream mutation** (C1 Adapter `25`, StreamGuard `18`, AD-007) — explicitly forbidden (§EPC/§31.1).
- ❌ **secret access** — plugins **never** access credentials (`26`).
- ❌ **new extension points / new architecture** — the frozen five only (§EPC).

### §5 — What the Plugin Runtime is NOT
| Plugin Runtime (verify/load/execute/isolate/unload) | Not the Plugin Runtime |
|---|---|
| ✓ execute vetted, signed plugins in process-isolated sandboxes | ✗ vet/register/sign/publish/distribute plugins (C12 CP `06 §9.12`) |
| ✓ additive-only at the frozen five extension points | ✗ add extension points / intercept providers / mutate streams (§EPC) |
| ✓ provide signals; owning stage decides | ✗ route/retry/govern/meter/validate authoritatively |
| ✓ deny-by-default, least-privilege, accounted, fail-closed | ✗ grant secrets/network/filesystem to plugins |
| ✓ isolate plugin failure; pipeline unweakened | ✗ let a plugin weaken correctness or cross tenants |

---

## §EPC — Extension Point Contract (EPC-1…EPC-8) *(resolves Critical PRT-C1 — additive; pinned to `06 §9`/`04 §53`)*
| # | Aspect | Contract |
|---|---|---|
| EPC-1 | **Frozen five only** | Plugins bind **only** at the frozen additive-only extension points: **pre-routing · classification · validation · telemetry · attribution** (`06 §9`/`04 §53`). |
| EPC-2 | **Never new points** | The Plugin Runtime **SHALL NEVER introduce a new runtime extension point.** |
| EPC-3 | **Explicitly forbidden points** | The following are **explicitly forbidden**: **post-routing, pre-invoke, post-invoke, response-transform, provider interception, stream mutation.** |
| EPC-4 | **Why forbidden** | These would **bypass** mandatory stages — response-transform/stream-mutation bypass StreamGuard (`18`)/SchemaLock (`17`)/Provider Adapter (`25`); provider-interception/pre-invoke/post-invoke couple to/bypass C1 (`19`/`25`); post-routing overrides C1's decision — violating AD-004/AD-018. |
| EPC-5 | **Signals only** | At every permitted point a plugin returns an **advisory signal**; the **owning mandatory stage decides** — a plugin never decides, mutates a request/response, or overrides a stage. |
| EPC-6 | **Future points require an ADR** | Introducing **any** additional extension point **requires a future ADR** (amending AD-004/`06 §9`) — it is **out of scope** for this document and this component. |
| EPC-7 | **Bind refusal** | A plugin declaring a non-frozen point ⇒ **refuse to bind** (fail closed). |
| EPC-8 | **No response path** | The runtime exposes **no response-mutation, provider-call, or stream-write capability** to any plugin at any point. |
- **Enforcement (PRT-A19):** ArchUnit (frozen `ExtensionPoint` enum only) + non-bypass tests (`17`/`18`/`19`/`25`). **Build-Fail:** a plugin bound to a non-frozen point; any response-transform/provider-interception/stream-mutation capability.

## §ISO — Isolation Contract (ISO-1…ISO-10) *(resolves Critical PRT-C2 — additive; honest, no overclaim)*
| # | Aspect | Contract |
|---|---|---|
| ISO-1 | **Substrate** | Plugins execute in a **process-isolated sandbox** (separate OS process / container-grade worker) — **not** trusted in-JVM-only — for any plugin running untrusted third-party code. In-JVM restricted classloading is a *defense-in-depth layer*, **not** the isolation boundary. |
| ISO-2 | **GUARANTEED — process isolation** | Plugin code runs in a **separate process** with no shared heap with the request path; a crash/escape attempt is contained to that process. |
| ISO-3 | **GUARANTEED — permission enforcement** | Deny-by-default, least-privilege capabilities enforced at the process boundary (§31); no capability ⇒ no access. |
| ISO-4 | **GUARANTEED — deterministic API surface** | Plugins see **only** a fixed, mediated host API (signals in / contribution out) — a closed, deterministic surface (no ambient JVM/OS access). |
| ISO-5 | **GUARANTEED — capability restrictions** | No secrets, no provider calls, no network egress, no filesystem, no cross-tenant data, no mandatory-stage mutation (§31/§31.1/§36.1). |
| ISO-6 | **GUARANTEED — resource accounting** | CPU/memory/time/IO are **accounted and quota-enforced at the process boundary** (§REC), with cancellation on breach. |
| ISO-7 | **GUARANTEED — fail closed** | Any violation/crash/timeout ⇒ isolate + discard + pipeline unweakened (§EPFC). |
| ISO-8 | **NOT GUARANTEED — perfect JVM sandbox** | In-process JVM sandboxing is **not** claimed (SecurityManager removed, AD-023) — which is **why** the boundary is process-level (ISO-1). |
| ISO-9 | **NOT GUARANTEED — absolute limits/escape prevention** | **Absolute** CPU/memory isolation and **absolute** escape prevention are **not** claimed for any managed runtime; process isolation + quotas **bound the blast radius**, they do not make it zero — stated honestly, no overclaim. |
| ISO-10 | **Never overclaim** | Security posture is **process-isolation + least-privilege + accounting + fail-closed**; it is documented as **strong containment with a bounded, non-zero residual**, never as an absolute sandbox. |
- **Enforcement (PRT-A20):** sandbox-escape + isolation tests (`15`). **Build-Fail:** executing an untrusted plugin in-process without the process boundary; a claim/config asserting absolute in-JVM sandbox/hard-limit guarantees.

## §ROC — Runtime Ownership Contract (ROC-1…ROC-8) *(resolves Critical PRT-C3 — additive; pinned to `06 §9.12`)*
| # | Owner | Contract |
|---|---|---|
| ROC-1 | **Control Plane (C12, `06 §9.12`)** | Owns **registry**. |
| ROC-2 | **Control Plane** | Owns **signing**. |
| ROC-3 | **Control Plane** | Owns **vetting**. |
| ROC-4 | **Control Plane** | Owns **discovery**. |
| ROC-5 | **Control Plane** | Owns **provenance** (authoring). |
| ROC-6 | **Control Plane** | Owns **publishing** and **plugin lifecycle** (authoring). |
| ROC-7 | **Runtime (this node)** | Owns **only**: **verification · loading · execution · isolation · unloading**. |
| ROC-8 | **Runtime — NEVER** | The runtime **SHALL NEVER publish, approve, sign, register, or vet plugins.** |
- **Enforcement (PRT-A21):** cross-check `06 §9.12`; ArchUnit. **Build-Fail:** the runtime publishing/approving/signing/registering/vetting a plugin, or authoring provenance/lifecycle.

---

## B. Domain & Interfaces

### §6 — Domain model (plugin-runtime subdomain)
Immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `VettedPluginSnapshot` | VO (immutable, snapshot) | A C12-vetted, **signed**, **digest-pinned**, version-pinned plugin artifact + manifest (AD-022) — authored by C12 CP (ROC), the runtime's read-only input |
| `PluginManifest` | VO | `{pluginId, version, digest, extensionPoint(frozen five), declaredCapabilities, permissions(least-privilege), resourceBudget, signature, provenance}` — **declared**, verified (§STC), never authored by the runtime |
| `ExtensionPoint` | enum | **pre-routing \| classification \| validation \| telemetry \| attribution** (frozen, §EPC) — the **only** binding points |
| `PluginInvocation` | VO (transient) | A single sandboxed execution: `{pluginId, extensionPoint, correlationId, tenantScope, resourceBudget, deadline, orderIndex}` |
| `PluginContribution` | VO | The **additive signal** a plugin returns — advisory input to the owning stage, never a decision |
| `PluginOutcome` | VO | `completed(contribution)` \| `isolated(reason)` (crash/timeout/violation ⇒ discarded, pipeline unweakened) |

**Aggregate:** `PluginExecution` — the transient per-invocation aggregate; **no shared mutable state across requests/tenants** (AD-021); no plugin state persisted (stateless host).

### §7 — Ports (AD-002 hexagonal)
**Inbound (called by the pipeline at a frozen extension point):**
```
PluginRuntimePort:
  invokeAt(ExtensionPoint, PluginInvocationContext) -> PluginContribution | Isolated   // additive, fail-closed, process-sandboxed
```
**Outbound (no store owned; verify/load/execute vetted snapshots only):**
```
VettedPluginSnapshotPort  // <- C12 Extensibility Service: vetted, signed, digest-pinned snapshots (AD-022, 06 §9.12) — read-only
SignaturePort             // <- verify signature/digest/provenance against the trust anchor (§STC); runtime verifies, never signs
SandboxHostPort           // -> process-isolation substrate: resource quotas, deny-by-default, cancellation (§ISO/§REC)
TelemetryPort             // -> C9 (27): content-free plugin telemetry
AuditSinkPort             // -> C10: content-free plugin-execution audit
ClockPort                 // deadlines/timeouts (§REC)
```
- **Enforcement:** ArchUnit — the runtime imports **no** provider SDK, **no** mandatory-stage mutation types, **no** secret/credential API for plugins, **no** persistence/store. Plugins run **only** at the frozen extension points and return **only** additive contributions; the owning stage remains the sole decider (AD-018).

---

## C. Major Decisions (PRT-D1 … PRT-D12)
*9-field template: Problem · Decision · Alternatives · Why selected · Trade-offs · Failure Modes · Security Impact · Performance Impact · Enforcement.*

### PRT-D1 — Additive-only, non-bypassable (AD-004/AD-018)
- **Problem:** extensibility must never become a bypass of the mandatory pipeline.
- **Decision:** plugins are **additive-only**: they add behavior at the frozen extension points and return **advisory contributions**; the **owning mandatory stage always decides** and **cannot be bypassed, disabled, reordered, or overridden** by a plugin (AD-004/AD-018, §EPC). Removing all plugins changes **no** mandatory-stage outcome.
- **Alternatives:** plugins that gate/replace a stage — rejected (AD-018 bypass).
- **Why selected:** the core AD-004/AD-018 guarantee.
- **Trade-offs:** plugins influence via signals, never decisions.
- **Failure Modes:** any attempted bypass ⇒ fail closed (§37/§EPFC).
- **Security Impact:** structural non-bypass.
- **Performance Impact:** budgeted plugin overhead (NFR-PERF-003).
- **Enforcement:** ArchUnit; PRT-A1/A19. **Build-Fail:** a plugin path that bypasses/overrides a mandatory stage.

### PRT-D2 — Runtime executes; C12 CP vets/registers/signs (§ROC)
- **Problem:** the runtime must not absorb C12 CP's vetting/registry/signing/publishing ownership.
- **Decision:** the runtime **verifies, loads, executes, isolates, unloads** vetted, signed snapshots distributed by the **Extensibility Service (C12 CP, `06 §9.12`)**; it **never** vets, registers, discovers, signs, publishes, or authors provenance/lifecycle (§ROC).
- **Alternatives:** runtime-side vetting/registry — rejected (C12 CP ownership).
- **Why selected:** preserves the frozen C12 DP/CP split.
- **Trade-offs:** the runtime depends on C12 vetting/signing (correct).
- **Failure Modes:** unvetted/unsigned snapshot ⇒ refuse (§STC).
- **Security Impact:** vetting stays in the governed CP.
- **Performance Impact:** verify at load, not per request.
- **Enforcement:** §ROC; PRT-A2/A21. **Build-Fail:** the runtime vetting/registering/signing/publishing/authoring-provenance.

### PRT-D3 — Frozen extension points only (§EPC)
- **Problem:** extension points must be the frozen five; new points = new architecture.
- **Decision:** plugins bind **only** at the frozen five (§EPC); **post-routing, pre-invoke, post-invoke, response-transform, provider-interception, stream-mutation are forbidden**; any new point **requires a future ADR**.
- **Alternatives:** add new points here — rejected (new architecture; AD-004/`06 §9`).
- **Why selected:** stays additive within the frozen set.
- **Trade-offs:** narrower than an unconstrained plugin model (deliberate).
- **Failure Modes:** non-frozen point declared ⇒ refuse to bind.
- **Security Impact:** no new bypass surface.
- **Performance Impact:** bounded by point count.
- **Enforcement:** §EPC; PRT-A3/A16/A19. **Build-Fail:** a plugin bound to a non-frozen extension point.

### PRT-D4 — Process isolation, honest guarantees (§ISO)
- **Problem:** third-party code on a Tier-0 host cannot be absolutely sandboxed in-JVM (AD-023).
- **Decision:** untrusted plugins run in **process-isolated sandboxes** (§ISO); guarantees (process isolation, permission enforcement, deterministic API, capability restriction, resource accounting, fail-closed) and non-guarantees (perfect JVM sandbox, absolute CPU/memory isolation, absolute escape prevention) are **stated explicitly** — **no overclaim**.
- **Alternatives:** trust plugins in-process — rejected (escape/DOS); absolute-sandbox claim — rejected (false).
- **Why selected:** strong containment with a bounded, honest residual.
- **Trade-offs:** process boundary adds latency/overhead (budgeted).
- **Failure Modes:** escape attempt ⇒ contained to the process; isolate.
- **Security Impact:** bounded blast radius; central attack surface addressed honestly.
- **Performance Impact:** budgeted (NFR-PERF-003).
- **Enforcement:** §ISO; PRT-A4/A20. **Build-Fail:** untrusted plugin in-process without the boundary; an absolute-sandbox claim.

### PRT-D5 — Least-privilege permission model (deny-by-default)
- **Problem:** plugins must have only their declared, vetted minimum capabilities.
- **Decision:** **deny-by-default**; a plugin gets **only** the capabilities its vetted manifest declares (§31), a closed set that **excludes** secrets, provider calls, cross-tenant data, network, filesystem, and mandatory-stage mutation.
- **Alternatives:** permissive plugins — rejected (AD-004).
- **Why selected:** least-privilege by construction.
- **Trade-offs:** plugins constrained (correct).
- **Failure Modes:** undeclared capability ⇒ deny + isolate.
- **Security Impact:** minimal capability surface.
- **Performance Impact:** O(1) check.
- **Enforcement:** capability tests; PRT-A5. **Build-Fail:** a plugin exercising an undeclared/forbidden capability.

### PRT-D6 — Resource enforcement (quotas + cancellation, fail closed) (§REC)
- **Problem:** a plugin must not exhaust CPU/memory/time/IO (DOS).
- **Decision:** each invocation runs under **accounted quotas** (CPU, memory, wall-clock deadline, IO) enforced **at the process boundary** (§REC), within NFR-PERF-003; breach ⇒ **cancel + isolate** (fail closed). Timeout ownership and exhaustion behavior are pinned in §REC.
- **Alternatives:** unbounded plugins — rejected (DOS).
- **Why selected:** bounded overhead; protects Tier-0 via the process boundary.
- **Trade-offs:** process-level enforcement overhead (accepted).
- **Failure Modes:** breach ⇒ cancel/isolate.
- **Security Impact:** DOS blast-radius bounded (§ISO-9 honesty).
- **Performance Impact:** budgeted (NFR-PERF-003).
- **Enforcement:** §REC; resource/DOS tests; PRT-A6. **Build-Fail:** an unbounded plugin execution; a missing deadline/quota.

### PRT-D7 — Network & filesystem deny-by-default
- **Problem:** plugins must not perform arbitrary network/FS IO.
- **Decision:** **no network egress, no filesystem access** by default; any IO is via a mediated, capability-gated, audited host API only (never to providers or secrets).
- **Alternatives:** open network/FS — rejected (exfiltration/SSRF).
- **Why selected:** eliminates the exfiltration surface.
- **Trade-offs:** hot-path plugins can't call out (correct).
- **Failure Modes:** attempted IO ⇒ deny + isolate.
- **Security Impact:** no egress/exfil.
- **Performance Impact:** neutral.
- **Enforcement:** network/FS tests; PRT-A7. **Build-Fail:** a plugin with direct socket/file/native access.

### PRT-D8 — No secret access (plugins never touch credentials)
- **Problem:** plugins must never access secrets/credentials (`26`).
- **Decision:** plugins have **no secret/credential capability** — ever. The Secrets Provider (`26`) supplies credentials **only** to the Provider Adapter (`26 §7`), never to a plugin.
- **Alternatives:** plugins with secret access — rejected (catastrophic).
- **Why selected:** absolute secret containment.
- **Trade-offs:** none material.
- **Failure Modes:** attempted secret access ⇒ deny + isolate + Sev.
- **Security Impact:** no credential exposure to plugins.
- **Performance Impact:** neutral.
- **Enforcement:** secret tests (`15` T-052); cross-check `26`; PRT-A8. **Build-Fail:** any plugin secret/credential capability.

### PRT-D9 — Failure isolation & per-point fail-closed (§EPFC)
- **Problem:** a plugin crash/timeout/violation must never harm the request; fail-closed differs per point.
- **Decision:** a plugin failure is **isolated**; its contribution **discarded**; the mandatory pipeline **proceeds unweakened** (additive-only). A **violation attempt** ⇒ fail closed hard (isolate + audit + Sev). Per-point semantics are pinned in **§EPFC** — the runtime **discards the contribution**; whether a missing validation/classification signal changes an outcome is the **owning stage's** decision (`17`/`21`), not the runtime's.
- **Alternatives:** fail the request on any plugin error — rejected (additive plugins must not create new failure modes).
- **Why selected:** isolation preserves correctness.
- **Trade-offs:** a failed plugin contributes nothing.
- **Failure Modes:** crash/timeout/violation ⇒ isolate + discard.
- **Security Impact:** no plugin-induced failure/leak.
- **Performance Impact:** bounded (deadline).
- **Enforcement:** §EPFC; isolation/fault tests; PRT-A9. **Build-Fail:** a plugin failure that alters a mandatory-stage outcome.

### PRT-D10 — Determinism & replay (plugins non-authoritative) (§POC/§RPC)
- **Problem:** third-party plugins are not deterministic; the platform supports replay.
- **Decision:** plugin contributions are **non-authoritative advisory signals**; plugin **execution order is deterministic** (§POC); **replay reproduces decisions, never wall-clock/timing/scheduling** (§RPC); a plugin contribution is a **recorded input** to replay, not re-executed as authoritative.
- **Alternatives:** plugin output as authoritative/deterministic — rejected.
- **Why selected:** honest; correctness rests on mandatory stages.
- **Trade-offs:** plugin behavior may vary run-to-run (disclosed).
- **Failure Modes:** none on correctness.
- **Security Impact:** none new.
- **Performance Impact:** neutral.
- **Enforcement:** §POC/§RPC; PRT-A10. **Build-Fail:** a plugin contribution treated as an authoritative/deterministic decision; a nondeterministic execution order.

### PRT-D11 — Supply-chain: verify C12-signed digest-pinned snapshots (§STC)
- **Problem:** only vetted, authentic plugins may execute; supply-chain attacks must be blocked.
- **Decision:** the runtime **verifies signature + immutable digest + provenance** of the C12-vetted snapshot against a **pinned trust anchor** (§STC) **before load**; unsigned/invalid/undigested/unvetted/unpinned ⇒ **refuse** (fail closed). Vetting/signing/provenance authoring is C12 CP's (§ROC).
- **Alternatives:** load unsigned/unvetted — rejected (supply-chain attack).
- **Why selected:** authenticity gate at load; ownership preserved.
- **Trade-offs:** depends on C12 signing infra (correct).
- **Failure Modes:** bad signature/digest/provenance ⇒ refuse.
- **Security Impact:** supply-chain integrity (verification side).
- **Performance Impact:** verify at load.
- **Enforcement:** §STC; cross-check `06 §9.12`; PRT-A11. **Build-Fail:** loading an unsigned/unverified/undigested/unvetted plugin.

### PRT-D12 — Lifecycle: version-pinned load; new versions via deployment lifecycle (§HRC)
- **Problem:** plugins must load/upgrade/rollback without weakening the immutable, stateless DP (AD-020/AD-006).
- **Decision:** plugins load from **version-pinned, digest-pinned vetted snapshots** (AD-022); **new plugin versions require the deployment lifecycle** (rolling/canary, `16` D-031) — **no in-place mutation of loaded plugin behavior** (§HRC); upgrade/rollback = a governed snapshot-version change picked up on rollout, not an ungoverned hot-swap.
- **Alternatives:** in-place arbitrary code hot-swap — rejected (stability/security; AD-020).
- **Why selected:** snapshot-versioned, deploy-governed lifecycle.
- **Trade-offs:** new versions land on rollout, not instantly (safe).
- **Failure Modes:** bad snapshot ⇒ refuse/rollback to last-known-good.
- **Security Impact:** governed, versioned, immutable code.
- **Performance Impact:** load at rollout.
- **Enforcement:** §HRC; lifecycle/rollback tests; PRT-A12. **Build-Fail:** an unpinned version; an in-place mutation of loaded plugin behavior.

---

## D. Lifecycle (§8–14)

### §8 — Discovery (C12-owned) · §9 — Verification & Loading
Discovery/registration/marketplace/publishing is **C12 CP's** (§ROC, `06 §9.12`); the runtime **verifies** (§STC) then **loads** the vetted, signed, digest-pinned snapshot (AD-022), version-pinned.

### §10 — Registration (C12-owned) · §11 — Versioning
Registration is C12's; the runtime binds a **version- and digest-pinned** plugin to its declared **frozen extension point** (§EPC). No floating/latest.

### §12 — Capability declaration · §13 — Manifest verification
The manifest **declares** point/capabilities/permissions/budget; the runtime **verifies** consistency with the signed snapshot (§STC) and **least-privilege** (§31); undeclared/forbidden ⇒ refuse.

### §14 — Compatibility · §14.1 — Dependency & classloader isolation *(resolves Medium — additive)*
Each plugin runs in its **own process with an isolated dependency set / classloader** (§ISO) — **no shared classloader with the host or other plugins**, eliminating dependency-conflict and cross-plugin interference. Compatibility is pinned to the C12 SDK version registry (`06 §9.12`). **Build-Fail:** a shared/host classloader for an untrusted plugin.

---

## E. Execution & Extension Points (§15–16)

### §15 — Execution model
At each frozen extension point, the runtime invokes each bound plugin in a **process-isolated `PluginExecution`** with a **deadline** and **accounted quota** (§REC), in a **deterministic order** (§POC); the plugin returns a **`PluginContribution`** or is **isolated**. Contributions are **input** to the owning mandatory stage, which **decides** (PRT-D1).

### §16 — Frozen extension points (the only binding points, §EPC)
- **pre-routing** — routing **signal** to C1 (`19`); C1 decides.
- **classification** — classification signal to Governance (`21`); C4 decides.
- **validation** — validation **signal** to SchemaLock (`17`); C3 decides.
- **telemetry** — content-free telemetry via `27`; `14`/`27` own the standards. *(Observability-hook = telemetry; no separate point.)*
- **attribution** — attribution metadata to Metering (`23`); C5 decides.

### §EPFC — Per-point fail-closed behavior *(resolves Medium — additive)*
| Point | On plugin crash/timeout/violation |
|---|---|
| pre-routing | discard signal; **C1 routes as if no plugin** (unweakened) |
| classification | discard signal; **C4 governs on its own inputs** (deny-by-default preserved, `21`) |
| validation | discard signal; **C3/SchemaLock validates authoritatively** (`17`) — a missing plugin signal never relaxes validation |
| telemetry | drop telemetry (`27` OT-INV) — never affects the request |
| attribution | discard signal; **C5 meters on authoritative facts** (`23`) — never fabricated |
- **Rule:** the runtime **only discards the contribution**; it **never** makes the owning stage's decision. A **violation attempt** (bypass/secret/escape/forbidden-point) ⇒ fail closed hard + audit + Sev. **Build-Fail:** a plugin failure that relaxes/alters an owning-stage decision.

---

## F. Isolation, Sandboxing & Resource Limits (§23–30)

### §23 — Isolation substrate (see §ISO)
Process-isolated sandbox; honest guaranteed/not-guaranteed split (§ISO-1…ISO-10). In-JVM restricted classloading is defense-in-depth, not the boundary.

### §24 — Sandboxing · §25 — Permission enforcement
Deny-by-default; only declared, vetted capabilities (§31); mediated host API only (§ISO-4).

### §26 — CPU · §27 — Memory · §28 — Time · §29 — IO (see §REC)
Accounted quotas + deadline enforced at the process boundary (§REC).

### §REC — Resource Enforcement Contract (REC-1…REC-7) *(resolves High PRT-H2 — additive)*
| # | Aspect | Contract |
|---|---|---|
| REC-1 | **Quotas** | Each invocation has **CPU, memory, wall-clock, and IO quotas** from the vetted manifest (NFR-PERF-003), enforced **at the process boundary** (§ISO). |
| REC-2 | **Cancellation** | On quota/deadline breach the runtime **cancels** the invocation (process-level kill/cancel) and **isolates** it. |
| REC-3 | **Timeout ownership** | The **runtime owns the per-invocation plugin deadline**; it never lets a plugin extend it; the **request deadline remains Reliability's (`20`)** and a plugin can never exceed it. |
| REC-4 | **Exhaustion behavior** | Resource exhaustion ⇒ **cancel + isolate + discard contribution + pipeline unweakened** (§EPFC) + `prt_plugin_budget_breach` metric. |
| REC-5 | **Fail closed** | Any inability to enforce a quota ⇒ **fail closed** (do not run the plugin) rather than run it unbounded. |
| REC-6 | **Blast radius** | Process isolation bounds the exhaustion blast radius to the plugin process; the request path is protected (§ISO-2). Honest residual per ISO-9. |
| REC-7 | **Baselines** | All quotas/deadlines are **operational-baseline + vetted-manifest** values (`16 §I.1`), never hard-coded. |
- **Enforcement (PRT-A6):** resource/DOS/cancellation tests. **Build-Fail:** an unbounded/undeadlined plugin execution; a plugin exceeding the request deadline; a quota that cannot be enforced yet runs the plugin.

### §30 — Backpressure · §30.1 — Recursion & deadlock prevention *(resolves Medium — additive)*
Streaming-adjacent plugins participate in **consumer-paced backpressure** (`25 §31.1 PL-7`). **Plugin pipeline re-entry/recursion is forbidden** — a plugin has **no capability to re-invoke the pipeline or another plugin** (closed API surface, §ISO-4); **no plugin may hold a host/hot-path lock** (process isolation ⇒ no shared locks), preventing deadlock. **Build-Fail:** a plugin re-entry/recursion capability; a plugin-held hot-path lock.

---

## G. Permission & Security Model (§31–36)

### §31 — Permission model (deny-by-default) · §31.1 — Provider-coupling exclusion *(resolves Medium — additive)*
Closed capability set, least-privilege (PRT-D5). **The capability set explicitly EXCLUDES any provider SDK, provider call, provider-native type, provider interception, or stream write** (AD-007, `25`, §EPC-3) — a plugin **can never** couple to or intercept a provider. **Build-Fail:** any provider/stream capability in the plugin capability set.

### §32 — Network policy · §33 — Filesystem policy
No egress / no FS by default (PRT-D7); mediated, capability-gated, audited; never to providers/secrets.

### §34 — Signature verification · §35 — Provenance (see §STC)
### §STC — Supply-chain Trust Contract (STC-1…STC-7) *(resolves High PRT-H1 — additive)*
| # | Aspect | Contract |
|---|---|---|
| STC-1 | **Trust anchor** | Verification is rooted in a **pinned trust anchor** (a C12/organization signing root whose public key/cert is distributed via the frozen secret/config trust path, `26`/C14 `06 §9.6` / `06 §9.12`) — the runtime holds the **public** anchor, never a signing key. |
| STC-2 | **Signature verification** | Every snapshot's **signature is verified** against the trust anchor before load; invalid ⇒ refuse. |
| STC-3 | **Immutable digest** | The snapshot is **digest-pinned** (content hash); the loaded bytes must match the signed digest — **no substitution** after signing. |
| STC-4 | **Provenance validation** | The snapshot's **provenance** (who vetted/built it, C12-authored) is **validated** (present, well-formed, anchored) — the runtime validates, never authors it (§ROC). |
| STC-5 | **Refuse on any gap** | Unsigned / invalid-signature / digest-mismatch / missing-provenance / unvetted / unpinned ⇒ **refuse to load** (fail closed) + `prt_signature_reject` + Sev. |
| STC-6 | **No TOFU** | No trust-on-first-use; only the pinned anchor is trusted. |
| STC-7 | **Verify-only** | The runtime **verifies**; **signing/vetting/provenance authoring is C12 CP's** (§ROC). |
- **Enforcement (PRT-A11):** supply-chain tests. **Build-Fail:** loading a plugin whose signature/digest/provenance is absent/invalid; trusting an unpinned anchor.

### §36 — Secret access (forbidden) · §36.1 — Tenant isolation *(resolves Medium — additive)*
Plugins **never** access secrets (`26`, PRT-D8). Execution is **tenant-scoped**; a plugin sees **only** its request's tenant-scoped, capability-gated context; **no plugin retains cross-request or cross-tenant state** (stateless host, process-per-invocation isolation, AD-021). **Build-Fail:** cross-tenant plugin data access; plugin state surviving across requests/tenants.

---

## H. Failure, Ordering, Determinism & Replay (§37–42)

### §37 — Failure isolation & crash/timeout/cancellation (see §EPFC)
Crash/timeout/cancellation ⇒ isolate + discard + pipeline unweakened; violation ⇒ fail closed hard + audit + Sev.

### §38 — Ordering (see §POC)
### §POC — Plugin Ordering Contract (POC-1…POC-6) *(resolves High PRT-H3 — additive)*
| # | Aspect | Contract |
|---|---|---|
| POC-1 | **Deterministic order** | For a given extension point, plugin execution order is **deterministic**: **same inputs + same plugin set + same policy snapshot ⇒ same execution order**. |
| POC-2 | **Order authority** | Order is a **total order from the C12-vetted manifest priority + pluginId tiebreak** (data from the AD-022 snapshot) — not registration timing, not wall-clock. |
| POC-3 | **Isolation between plugins** | Plugins at a point are **mutually isolated**; one plugin **does not** see another's contribution (each gets the same stage input) — no order-dependent inter-plugin coupling, unless a future ADR defines a pipeline semantics. |
| POC-4 | **Additive aggregation** | The owning stage receives the set of contributions; **aggregation is the stage's**, deterministic over the ordered set. |
| POC-5 | **No reordering by plugins** | A plugin cannot change its own or others' order (no capability). |
| POC-6 | **Stable across replay** | The same snapshot yields the same order on replay (§RPC). |
- **Enforcement (PRT-A10):** ordering/property tests. **Build-Fail:** a nondeterministic plugin order; inter-plugin contribution visibility without an ADR.

### §39 — Cancellation · §40 — Backpressure
Deadline-driven cancellation (§REC); streaming consumer-paced (`25 §31.1`); no unbounded buffering.

### §41 — Replay (see §RPC)
### §RPC — Replay Contract (RPC-1…RPC-6) *(resolves High PRT-H4 — additive)*
| # | Aspect | Contract |
|---|---|---|
| RPC-1 | **Decisions reproduced** | Replay reproduces **decisions** — the mandatory-stage outcomes and the plugin **execution order** (§POC) — from recorded inputs. |
| RPC-2 | **Timing never reproduced** | Replay **NEVER** reproduces **wall-clock, timing, or scheduling** — these are real-time and differ on replay. |
| RPC-3 | **Plugin output recorded** | A plugin's contribution is a **recorded input** to replay; the third-party plugin is **not re-executed as authoritative** (it may be non-deterministic). |
| RPC-4 | **Non-authoritative** | Plugin contributions are **non-authoritative**; correctness/accounting truth remains the mandatory stages/C5 (`23`). |
| RPC-5 | **Deterministic dispatch** | The runtime's **dispatch/order/aggregation logic** is deterministic over recorded inputs (`11` R-063). |
| RPC-6 | **No overclaim** | The runtime is **not** deterministic end-to-end (plugins/timing are not); only **decisions and order** are reproducible. |
- **Enforcement (PRT-A10):** replay tests (`15` T-035). **Build-Fail:** re-executing a plugin as authoritative on replay; a replay claiming timing reproduction.

### §42 — Determinism
Plugins are **not assumed deterministic**; the runtime's dispatch/order is deterministic (§POC/§RPC); correctness rests on the **mandatory stages**.

---

## I. Hot Reload / Upgrade / Rollback (§43–45)

### §43 — Hot reload (see §HRC)
### §HRC — Hot Reload Contract (HRC-1…HRC-6) *(resolves High PRT-H5 — additive)*
| # | Aspect | Contract |
|---|---|---|
| HRC-1 | **Deployment lifecycle** | **New plugin versions require the deployment lifecycle** (rolling/canary, zero-downtime, `16` D-031, AD-020) — picked up on rollout, not by in-place code injection. |
| HRC-2 | **Never mutate loaded behavior** | The runtime **never mutates the behavior of an already-loaded plugin** in place; a loaded plugin's code/behavior is immutable for its lifetime. |
| HRC-3 | **Version = new snapshot** | An upgrade is a **new vetted, signed, digest-pinned snapshot version** (AD-022) rolled out via the deploy train; a rollback is the prior version, likewise. |
| HRC-4 | **Immutable-deploy consistent** | This preserves the frozen immutable/stateless DP (AD-020/AD-006) — no ungoverned live-patching of a Tier-0 process. |
| HRC-5 | **Config enable/disable** | A plugin may be **enabled/disabled** via the C12/config snapshot (AD-022) without changing code — a governed toggle, not a behavior mutation. |
| HRC-6 | **Last-known-good** | On a bad snapshot, the runtime keeps the **last-known-good** version (AD-022) until a healthy rollout. |
- **Enforcement (PRT-A12):** lifecycle/rollback tests. **Build-Fail:** in-place mutation of loaded plugin behavior; a plugin version change outside the deployment lifecycle.

### §44 — Upgrades · §45 — Rollback
Vetted snapshot version change via the deploy train (§HRC); last-known-good rollback; version- and digest-pinned.

---

## J. Observability (§46–48)

### §46 — Audit
Every plugin load + invocation outcome is a **content-free audit record** (C10): `{pluginId, version, digest, extensionPoint, outcome(completed/isolated), signatureVerified, correlationId}` — never plugin content/secret.

### §47 — Metrics
`prt_plugin_invocations_total{extension_point, outcome}`, `prt_plugin_isolated_total{reason}`, `prt_plugin_budget_breach_total{resource}`, `prt_signature_reject_total`, `prt_plugin_latency`. Labels bounded (`14 §5`, via `27`); no plugin content/secret label.

### §48 — Tracing & context propagation
One span per plugin invocation (via `27`); correlation context propagated (`07 §6`, `14`); content-free; provider-neutral; obeys `27`/`14` (allow-list + redact + reject).

## K. Testing (§49)
- **Sandbox/process-isolation (`15`):** escape attempts contained to the plugin process; capability deny-by-default; **adversarial escape suite**; honest residual acknowledged (§ISO-9).
- **Non-bypass (`15`, `17`/`18`/`19`/`20`/`21`/`25`):** plugins cannot bypass/override any mandatory stage; no forbidden point (§EPC); removing plugins changes no mandatory outcome.
- **Resource/DOS (`15`):** CPU/memory/time/IO quota breach ⇒ cancel/isolate; request unaffected; no deadlock; no recursion (§REC/§30.1).
- **Secret/tenant (`15` T-052/T-044):** plugins never access secrets (`26`); never cross tenants (§36.1, AD-021).
- **Supply-chain (`15`):** unsigned/invalid/digest-mismatch/unvetted refused (§STC).
- **Failure isolation (`15` T-018):** crash/timeout/violation ⇒ isolate + pipeline unweakened per point (§EPFC).
- **Ordering/replay (`15` T-035):** deterministic order (§POC); plugin non-authoritative, timing not reproduced (§RPC).
- **Provider-neutrality (`15` T-015):** no provider/stream capability (§31.1).
- **Hot reload (`15`):** version change only via deploy train; no in-place behavior mutation (§HRC).
- **Mutation (`15 §G.1`):** **≥ 90%** — a mutant that lets a plugin bypass a stage, access a secret, cross a tenant, escape isolation, exhaust resources, or reorder nondeterministically must be killed.
- **Follows:** `15`/`16`/AD-004 and `17`–`27`.

## L. Build-Failing Rules (PRT-A1 … PRT-A21)

| # | Rule | Gate |
|---|---|---|
| PRT-A1 | A plugin can never bypass/override/reorder/disable a mandatory stage (additive-only) | ArchUnit + non-bypass test |
| PRT-A2 | Runtime never vets/registers/signs/publishes/authors-provenance (C12 CP; §ROC) | cross-check `06 §9.12` |
| PRT-A3 | Plugins bind only at the frozen extension points | ArchUnit (frozen enum) |
| PRT-A4 | Untrusted plugins run process-isolated; no unrestricted classloading/reflection/native | sandbox-escape test |
| PRT-A5 | Deny-by-default; only declared, vetted capabilities | capability test |
| PRT-A6 | No unbounded plugin execution; CPU/memory/time/IO quota + deadline enforced (§REC) | resource/DOS test |
| PRT-A7 | No plugin network egress / filesystem access (mediated only) | network/FS test |
| PRT-A8 | Plugins never access secrets/credentials (`26`) | secret test (`15` T-052) |
| PRT-A9 | A plugin failure never alters a mandatory-stage outcome (isolate + discard; §EPFC) | isolation/fault test |
| PRT-A10 | Deterministic plugin order (§POC); contributions non-authoritative; replay reproduces decisions not timing (§RPC) | ordering/replay test |
| PRT-A11 | No unsigned/unverified/undigested/unvetted plugin loaded (§STC) | supply-chain test |
| PRT-A12 | No unpinned version; no in-place behavior mutation; versions via deploy lifecycle (§HRC) | lifecycle test |
| PRT-A13 | Plugins never couple to/intercept providers or mutate streams (§31.1, AD-007) | ArchUnit + `15` T-015 |
| PRT-A14 | Plugins never cross tenants; no cross-request state (§36.1, AD-021) | isolation test (`15` T-044) |
| PRT-A15 | No plugin pipeline re-entry/recursion; no hot-path lock held by a plugin (§30.1) | recursion/deadlock test |
| PRT-A16 | No new extension point beyond the frozen set (`06 §9`; §EPC) | ArchUnit (frozen enum) |
| PRT-A17 | No hard-coded plugin numeric; quotas/limits from operational baseline + vetted manifest | config lint (`16 §I.1`) |
| PRT-A18 | Mutation score ≥ 90% | PITest (`15 §G.1`) |
| PRT-A19 | **Extension Point Contract (§EPC):** frozen five only; post-routing/pre-invoke/post-invoke/response-transform/provider-interception/stream-mutation forbidden; new point requires an ADR | ArchUnit + non-bypass test |
| PRT-A20 | **Isolation Contract (§ISO):** process-isolated substrate; no absolute-sandbox/hard-limit overclaim; honest guaranteed/not-guaranteed | sandbox/isolation test |
| PRT-A21 | **Runtime Ownership Contract (§ROC):** runtime only verifies/loads/executes/isolates/unloads; never publishes/approves/signs/registers/vets | cross-check `06 §9.12` |

## M. Operations (§50–52)
- **§50 — `prt_plugin_isolated_total` spike:** plugins crashing/timing out/violating — expected isolation (§EPFC); investigate the plugin, never disable isolation; pipeline unaffected.
- **§51 — `prt_signature_reject_total` > 0:** a supply-chain/authenticity failure — Sev; the runtime refused an unsigned/unverified/digest-mismatched plugin (correct); investigate C12 distribution (§STC).
- **§52 — resource-budget-breach spike:** a plugin near/over quota — cancel/isolate (§REC); tune the vetted budget (C12) or quarantine the plugin.

## N. Traceability (§53)
| Concern | BR | NFR | ADR | Domain/Svc | Sec | Test | Deploy |
|---|---|---|---|---|---|---|---|
| Additive/non-bypass (PRT-D1/§EPC) | BR-029/015 | NFR-PERF-003 | AD-004/018 | C12 (`06 §8` PLG) | §31 | non-bypass | `16` |
| Runtime ownership (PRT-D2/§ROC) | BR-005 | NFR-PERF | AD-020/006 | C12 (`06 §9.12`) | — | T-051 | D-014 |
| Extension points (PRT-D3/§EPC) | BR-029 | NFR-PERF-003 | AD-004 | `06 §9` | §31 | ArchUnit | — |
| Isolation (PRT-D4/§ISO) | BR-030 | NFR-SEC | AD-004/023 | C12 | §ISO | escape | — |
| Permission model (PRT-D5/§31/§31.1) | BR-030 | NFR-SEC | AD-004/012/007 | C12 | §31 | capability | — |
| Resource enforcement (PRT-D6/§REC) | BR-030 | NFR-PERF-003 | AD-004 | C12/`20` | §REC | DOS | — |
| Network/FS (PRT-D7/§32–33) | BR-030 | NFR-SEC | AD-012 | C12 | §31 | net/FS | — |
| No secret access (PRT-D8/§36) | BR-020 | NFR-SEC-SM | AD-012 | C14 (`26`) | §36 | T-052 | — |
| Failure isolation (PRT-D9/§EPFC) | BR-001 | NFR-REL | AD-016/018 | C12 | §EPFC | T-018 | — |
| Ordering/replay (PRT-D10/§POC/§RPC) | BR-004 | NFR-OBS | AD-016 | `25 §23.1` | — | ordering/replay | — |
| Supply-chain (PRT-D11/§STC) | BR-030 | NFR-SEC | AD-004/012 | C12 CP (`06 §9.12`)/C14 | §STC | supply-chain | — |
| Lifecycle/hot-reload (PRT-D12/§HRC) | BR-028 | NFR-UPG | AD-004/020/022 | C12 | — | lifecycle | D-031 |
| Recursion/deadlock (§30.1) | BR-001 | NFR-REL | AD-018 | C12 | §30.1 | recursion | — |
| Observability (§46–48) | BR-010 | NFR-OBS | AD-011 | C9 (`27`) | §36 | T-052 | — |

---

## S. Reviews

### 1. Consistency Review (post-resolution)
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-004 / `06 §9` / `04 §53` | sandboxed, additive-only, frozen five points | §EPC EPC-1…EPC-8; PRT-D1/D3 | ✅ |
| `06 §9.12` | C12 CP owns registry/vetting/signing/publishing/discovery/provenance/lifecycle | §ROC ROC-1…ROC-8 | ✅ |
| AD-018 | non-bypassable pipeline | §EPC/§EPFC (signals only; owning stage decides) | ✅ |
| AD-023 (JVM) | shared managed runtime, no absolute in-JVM sandbox | §ISO ISO-1…ISO-10 (process isolation; honest limits) | ✅ |
| AD-020/`16` D-031 | immutable/zero-downtime deploy | §HRC HRC-1…HRC-6 (versions via deploy lifecycle) | ✅ |
| `26` | plugins never access secrets | PRT-D8/§36 | ✅ |
| AD-021 | tenant isolation | §36.1 | ✅ |
| `25 §23.1`/`23` | replay; authoritative truth | §RPC RPC-1…RPC-6 (decisions not timing; non-authoritative) | ✅ |
| `17`/`18`/`25` | correctness/stream/adapter own response | §EPC-3 (response-transform/provider-interception/stream-mutation forbidden) | ✅ |
| AD-007 | provider neutrality | §31.1 (no provider/stream capability) | ✅ |
| `14`/`27` | observability standards | §46–48 | ✅ |
| C12 signing / C14 trust | supply-chain trust | §STC STC-1…STC-7 (pinned anchor, digest, provenance) | ✅ |

**No contradictions remain.** The Resolution Pass (§EPC/§ISO/§ROC/§STC/§REC/§POC/§RPC/§HRC/§EPFC/§14.1/§30.1/§31.1/§36.1, PRT-A19…A21) is **additive only**: no new architecture/context/service/module/store/topology/ownership/extension-point; AD-002/004/007/018/020/021/022/023 preserved exactly; PRT-INV reinforced; runtime-only.

### 2. Architecture Validation (post-resolution)
- **Ownership:** ✅ CP owns registry/vetting/signing/publishing/discovery/provenance/lifecycle; runtime verifies/loads/executes/isolates/unloads (§ROC) — **nothing moved**.
- **Boundaries:** ✅ additive-only signals; owning stage decides; forbidden points hard-excluded (§EPC).
- **Security:** ✅ **honest** process isolation + least-privilege + accounting + fail-closed (§ISO), no overclaim; supply-chain trust pinned (§STC); no secret/provider/stream capability.
- **Extension points:** ✅ frozen five only; new point requires an ADR.
- **Determinism/replay/ordering:** ✅ deterministic order + decisions-not-timing replay (§POC/§RPC).
- **Deployment:** ✅ versions via deploy lifecycle; no in-place mutation (§HRC).
- **Resource/DOS:** ✅ quotas + cancellation + process blast-radius bound (§REC).

### 3. Adversarial Review — Independent Review Board (re-run after resolution)
*Board: Principal Enterprise Architect · Plugin/Sandbox Security Specialist · JVM Isolation Expert · Supply-Chain Security Architect · Distributed Systems Architect · Staff Reliability Engineer · Streaming Systems Expert · Performance Engineer · Compliance Auditor · CTO.*

#### Accepted findings — resolution
- **PRT-C1 → RESOLVED (§EPC).** Frozen five only; post-routing/pre-invoke/post-invoke/response-transform/provider-interception/stream-mutation forbidden; new point requires an ADR. Build-enforced (PRT-A19).
- **PRT-C2 → RESOLVED (§ISO).** Process-isolation substrate; guaranteed (process isolation, permission enforcement, deterministic API, capability restriction, resource accounting, fail-closed) vs not-guaranteed (perfect JVM sandbox, absolute CPU/memory isolation, absolute escape prevention) — no overclaim. Build-enforced (PRT-A20).
- **PRT-C3 → RESOLVED (§ROC).** CP owns registry/signing/vetting/discovery/provenance/publishing/lifecycle; runtime owns verify/load/execute/isolate/unload; never publishes/approves/signs/registers/vets. Build-enforced (PRT-A21).
- **PRT-H1 → RESOLVED (§STC).** Pinned trust anchor, signature verification, immutable digest, provenance validation; no TOFU; verify-only.
- **PRT-H2 → RESOLVED (§REC).** Quotas + cancellation + runtime-owned plugin deadline (request deadline stays `20`'s) + exhaustion behavior + fail-closed + process blast-radius bound.
- **PRT-H3 → RESOLVED (§POC).** Deterministic order from inputs + plugin set + policy snapshot; mutual isolation between plugins.
- **PRT-H4 → RESOLVED (§RPC).** Replay reproduces decisions/order; never wall-clock/timing/scheduling; plugin output recorded, not re-executed as authoritative.
- **PRT-H5 → RESOLVED (§HRC).** New versions require the deployment lifecycle; never mutate loaded plugin behavior; immutable-deploy consistent.
- **Medium → RESOLVED:** per-point fail-closed (§EPFC); dependency/classloader isolation (§14.1); recursion/deadlock (§30.1); tenant isolation (§36.1); provider-coupling exclusion (§31.1).

#### New findings from the re-run
- **🔴 Critical:** none. **🟠 High:** none. **🟡 Medium:** none blocking. **🟢 Low (deferred):** Appendix B (metric names, substrate benchmark, manifest/permission schema pointer to C12, SDK compatibility matrix) — detail deliverables, non-blocking.

#### Internal Contradictions
- **None.** Isolation stated honestly (strong containment, bounded residual); ownership pinned (§ROC); extension points hard-frozen (§EPC); replay/ordering deterministic-on-decisions-only.

#### Cross-document Contradictions
- **None.** Consistent with AD-004/`06 §9`/`04 §53` (points), `06 §9.12` (C12 CP), AD-018 (non-bypass), AD-023 (isolation honesty), AD-020/`16` D-031 (deploy), `26` (secrets), `25`/`17`/`18` (no response/stream/provider capability), AD-007/AD-021, `25 §23.1`/`23` (replay).

#### Scores
- **Architecture Readiness: 96 / 100** — all Critical/High/Medium resolved additively; extension points hard-frozen; isolation honest and process-level; ownership pinned; supply-chain/resource/ordering/replay/hot-reload contracted; no architecture/ownership/topology change. Residual 4 pts are Low detail deliverables.
- **Documentation Health: 96 / 100** — contradictions closed; security stated without overclaim (§ISO); ownership and extension-point boundaries explicit.
- **Implementation Readiness: 94 / 100** — every load-bearing seam (extension points, isolation substrate, ownership, supply-chain, resources, ordering, replay, hot-reload, per-point fail-closed) specified precisely enough to implement.

#### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred (Appendix B). No contradictions with `00`–`27` or AD-001…AD-023; no new architecture/context/service/module/store/topology/ownership/extension-point; AD-002/004/007/018/020/021/022/023 preserved; frozen C12 architecture and the frozen five extension points preserved; PRT-INV reinforced; runtime-only.

---

## Appendix A — Isolation honesty (informative)
Process isolation (separate OS process / container-grade worker) bounds a plugin's blast radius: a crash, an escape *attempt*, or resource exhaustion is contained to the plugin process, and the Tier-0 request path shares no heap with it. This is **strong containment with a bounded, non-zero residual** (§ISO-9) — not an absolute sandbox. In-JVM restricted classloading is a defense-in-depth layer, not the boundary (AD-023: the JVM SecurityManager is removed and cannot be relied on for in-process isolation of hostile code).

## Appendix B — Deferred Low Items (non-blocking)
- **PRT-L1** — metric/label names → `14`/`27` registry.
- **PRT-L2** — isolation-substrate benchmark (process/worker overhead) → `benchmarks/`.
- **PRT-L3** — manifest/permission schema → owned by C12 (`06 §9.12`), referenced here only.
- **PRT-L4** — SDK compatibility matrix → C12 SDK version registry.

These are documentation/detail deliverables; none affects ownership, isolation strength, non-bypass, correctness, or any invariant, and each is contract-/cross-team-testable.

---

*End of document — 28-PluginRuntime.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
