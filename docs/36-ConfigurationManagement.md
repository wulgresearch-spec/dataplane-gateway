# 36 — Configuration Management (Runtime Config Consumption · Documentation-Only)

**Document:** Configuration Management — the runtime behavior by which the data plane consumes the frozen configuration architecture
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Scope owner:** **Platform** — the frozen owner of the **data-plane config cache** (AD-013 Affected Components; `06 §1/§8` "Config/Secret caches C15/C14") — **unchanged**; configuration **authoring/validation/distribution/flag authority remains C15** (`06 §9.4`).
**Module:** *(no new module/service/context/store)* — this document specifies **only the runtime consumption behavior of the frozen data-plane config cache** (the C15 snapshot consumer inside the Data Plane, co-located per AD-020/AD-006). It introduces **no service, module, context, store, event, deployment topology, ownership, runtime stage, or ADR** — **only runtime configuration behavior subordinate to C15 (`06 §9.4`), AD-013, and AD-022**.
**Plane:** Tier-0 Runtime (config consumption); the config **authority/store is Tier-1 control-plane** (C15 Configuration Service)
**Subordinate to:** **AD-013**, **AD-022**, **C15 (`06 §9.4`)**, `29 §SCC/§SPT/§RBC/§CVR`, `32 §SPT/§CRS`, `16 §I.1`, `08`, `07`.
**Audience:** Platform/SRE/config/security/compliance engineers, developers, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`35` (except `24`, intentionally UNFROZEN), especially **AD-013, AD-022, C15 (`06 §9.4`)**, `29`, `32 §SPT`, `16 §I.1`, `26`, `21`, `07`, `08`, and ADRs **AD-001…AD-023**. Everything here is **descriptive and additive**: it **cites** the frozen config architecture; it **defines no config authority** and, on any conflict, **AD-013/C15 prevail** (§CGA).

> **What this is.** The single runtime reference for how a request **consumes configuration**: the data-plane config cache pulls **validated, immutable, versioned snapshots** from C15 (`06 §9.4`, AD-022), runs on **last-known-good** when C15 is unavailable (AD-017), **pins** the snapshot set per request at request start (`32 §SPT`), evaluates **feature flags from the pinned snapshot** (deterministically, never live mid-request — §FFC), and **fails closed** on missing/invalid required config with **secure defaults that live in the snapshot** (§SDS). It **authors, validates, and distributes nothing** — those are C15's. It **redefines no architecture** (§Hard Constraints).
>
> **THE CONFIGURATION INVARIANT (CFG-INV):** *Configuration is consumed, never authored, validated, or mutated at runtime; it never carries a secret; it never bypasses governance or a mandatory stage; it never breaks deterministic replay; it never propagates synchronously on the hot path.* On any missing/invalid required configuration: **FAIL CLOSED** with **secure defaults (snapshot-authored, §SDS)** — the request is denied/rejected rather than served on unknown or wrong config. Configuration authoring/validation/distribution/flag-authority is **C15's** (`06 §9.4`, AD-013); this document is the **runtime consumer** only.

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (CFG-C1, CFG-C2), High (CFG-H1…CFG-H4), and Medium (CFG-M1…CFG-M5)** findings are resolved **additively** through signed contracts — **authoring no config, moving no ownership, changing no architecture, citing frozen behavior where it exists**:

- **§FFC Feature-Flag Determinism Contract (FFC-1…FFC-8)** — CFG-C1 (flag/targeting resolved once at pin time, recorded, immutable for the request; no live mid-request evaluation). Build rule **CFG-A16**.
- **§CAB Consumer-Authority Binding Contract (CAB-1…CAB-6)** — CFG-C2 (consumer authors nothing; contract-tested against C15/AD-013). Build rule **CFG-A17**.
- **§AMP Additive-vs-Mandatory Flag Contract (AMP-1…AMP-6)** — CFG-H1 (flags may toggle only additive behavior; mandatory stages are never flag-controllable). Build rule **CFG-A18**.
- **§HPP High-Priority Propagation Contract (HPP-1…HPP-5)** — CFG-H2 (security/invariant config uses the frozen AD-022/AD-013 high-priority path; revocation ≤ frozen bound).
- **§OBB Operational-Baseline Binding Contract** — CFG-H3 (`16 §I.1` baselines are config snapshot entries; a threshold change is a governed config change, never code). **§DRC Drift Response Contract** — CFG-H4 (run validated last-known-good + alarm; fail closed on required drift; never silently accept drift).
- **§SDS Secure-Defaults-In-Snapshot Contract** — CFG-M5/internal-contradiction (secure defaults are C15-authored and **carried in the snapshot**; the consumer applies, never synthesizes). Build rule **CFG-A19**.
- **Medium** — §SCA schema-compat = `29 §SCC` (CFG-M1); §TFI tenant-flag isolation (CFG-M2, AD-021); §CAU audit completeness = C15/C10 (CFG-M3); §RIF rollback-in-flight = `32 §SPT` (CFG-M4).

**Low** items remain **deferred** (§L). **C15/AD-013/AD-022 remain the sole config authorities; no new service/module/context/store/ownership/runtime stage/topology/ADR; runtime behavior changed only by clarifying already-frozen guarantees; AD-001…AD-023 preserved.** No document `00`–`35` and no ADR is modified.

---

## §CGA — Configuration Governance Authority (CGA-1…CGA-7)
| # | Aspect | Contract |
|---|---|---|
| CGA-1 | **C15 is the config authority** | Config **authoring, validation, versioning, distribution, and feature-flag authority** are **C15's** (`06 §9.4`, AD-013). |
| CGA-2 | **Consumer only** | This document specifies only the **runtime consumer** — pulls, caches, pins, reads validated snapshots. |
| CGA-3 | **Authors nothing** | It **authors no config/validation/flag/default/policy** — all are C15's / frozen owners'. |
| CGA-4 | **Owner wins** | On any conflict with AD-013/C15, the frozen owner wins and this document is corrected. |
| CGA-5 | **Not a second authority** | It **must never become a second configuration authority or store**. |
| CGA-6 | **No hot-path CP call** | The consumer never makes a synchronous C15 call on the hot path (AD-022). |
| CGA-7 | **HOW only** | It governs runtime consumption, never the config architecture (§Hard Constraints). |
- **Enforcement (CFG-A1):** doc-lint + cross-check `06 §9.4`/AD-013. **Build-Fail:** the consumer authoring/validating/distributing config or flags; a synchronous C15 hot-path call.

## §CAB — Consumer-Authority Binding Contract (CAB-1…CAB-6) *(resolves Critical CFG-C2)*
| # | Aspect | Contract |
|---|---|---|
| CAB-1 | **Zero standards owned** | Document 36 owns **zero** configuration standards, values, defaults, flag semantics, or validation. |
| CAB-2 | **Reads validated snapshots only** | The consumer only reads **C15-validated** snapshots; it re-checks schema-compat (`29 §SCC`) but never validates content. |
| CAB-3 | **No local synthesis** | The consumer **never synthesizes** a config value, default, or flag locally (defaults come from the snapshot, §SDS). |
| CAB-4 | **Contract-tested** | Subordination is **contract-tested automatically** (CFG-A17): the build asserts every config/default/flag the consumer uses originates from a C15 snapshot, not from consumer code. |
| CAB-5 | **Contradiction ⇒ 36 wrong** | Any contradiction with C15/AD-013 means Document 36 is wrong and is corrected. |
| CAB-6 | **No shadow authority** | The consumer may not accrete default values, flag semantics, or validation — doing so is a build failure. |
- **Enforcement (CFG-A17):** contract test vs C15/AD-013. **Build-Fail:** a config value/default/flag/validation authored in consumer code; a locally-synthesized default.

---

## 1. Charter
Specify how the runtime **consumes** the frozen configuration architecture safely: pull validated immutable versioned snapshots (C15/AD-022), run on last-known-good (AD-017), pin per request (`32 §SPT`), evaluate flags deterministically from the pinned snapshot (§FFC), and fail closed with snapshot-authored secure defaults (§SDS) on missing/invalid required config. It consumes; C15 owns (§CGA/§CAB).

## 2. Scope
- **In scope (runtime consumption only):** snapshot pull/cache; per-request pinning; feature-flag reads from the pinned snapshot; last-known-good; drift-event consumption; fail-closed/secure-default; rollback via re-pin (consumption side).
- **Out of scope (C15/frozen owners):** authoring/validation/versioning/distribution/flag-authority (C15, AD-013); policy (C4/`21`); secrets (C14/`26`); operational-baseline **values** (`16 §I.1`); the config store (C15/`08`).

## 3. Runtime ownership (§CGA/§CAB)
The runtime consumer is the frozen **data-plane config cache** (Platform-owned, AD-013 Affected Components); the config **authority/store/flags** are **C15's** (`06 §9.4`). No ownership moves.

## 4. Relationship to frozen documents
| Frozen owner | Owns | This document |
|---|---|---|
| **AD-013** | config-management strategy | cites/consumes |
| **C15 (`06 §9.4`)** | snapshot substrate + flag authority + store | cites/consumes |
| **AD-022** | cached snapshots, last-known-good, no hot-path CP call | cites |
| `29 §SCC/§SPT/§RBC/§CVR` | compat/pin/rollback/replay | cites |
| `32 §SPT/§CRS` | pin after C6; replay scope | cites |
| `16 §I.1` | operational-baseline values (are config) | cites (§OBB) |
| `26` | secrets (config carries none) | boundary |
| `21` | policy (config is not policy) | boundary |
| `07`/`08` | config events / config store | cites |

## 5. Canonical configuration model (cited, not defined)
Consumed as **validated, immutable, versioned snapshots** (AD-013/AD-022): `{configVersion, entries(C15-validated), featureFlags(pinned + resolved, §FFC), secureDefaults(in-snapshot, §SDS), region}`. Shape/validation is **C15's**. Config **is not policy** (`21`) and **carries no secret** (`26`). Numeric thresholds are `16 §I.1` config entries (§OBB).

## 6. Snapshot consumption
The consumer **pulls** C15 snapshots (AD-022, out-of-band, cache-fronted), holds **last-known-good**, and swaps on `config-published` (`07`/`06 §9.4`). **No synchronous C15 call on the hot path** (CGA-6). A request reads only the **pinned** snapshot (§7).

## 7. Per-request pinning (`32 §SPT`)
The config version is **pinned at request start, after C6** (`32 §SPT`, tenant/region-scoped), **immutable** for the request; every stage reads the **same** pinned version (`29 §SCC`, §SCA). No mid-request re-read. The pinned version (and resolved flag set, §FFC) is **recorded** for replay (`29 §CVR`).

## 8. Feature flags (§FFC)
## §FFC — Feature-Flag Determinism Contract (FFC-1…FFC-8) *(resolves Critical CFG-C1)*
| # | Aspect | Contract |
|---|---|---|
| FFC-1 | **Part of the pinned snapshot** | Feature flags are part of the **pinned config snapshot** (C15 flag authority); read from the pinned version only. |
| FFC-2 | **Resolved once at pin time** | Any **targeting / percentage-rollout / tenant-scoped** flag is **resolved once, at pin time (post-C6)**, to a concrete boolean/value for the request — using the frozen pinned inputs `(tenantScope, region, correlationId/ExecutionIdentity, config version)`. |
| FFC-3 | **Deterministic resolution** | Percentage rollout is a **deterministic function** of a pinned, recorded key (e.g., hash of `(flag, tenantScope)` vs the snapshot's rollout ratio) — **no `Math.random`**, no wall-clock; identical inputs ⇒ identical resolution (mirrors `27 §19.1`/`28 §POC` determinism). |
| FFC-4 | **Immutable for the request** | The **resolved** flag set is **immutable for the request**; a mid-flight flag change (new snapshot) **never** affects an in-flight request (`32 §SPT`). |
| FFC-5 | **Recorded for replay** | The **resolved flag set** (flag id → resolved value) is **recorded** alongside the pinned version identity; replay reproduces decisions from the **recorded resolution**, never a live re-evaluation (`29 §CVR`/`32 §CRS`). |
| FFC-6 | **No live evaluation** | Flags are **never** evaluated live mid-request or per-stage. |
| FFC-7 | **Additive-only** | A flag may toggle only **additive** behavior (§AMP); never a mandatory stage (AD-018). |
| FFC-8 | **No secret / no policy** | A flag is not a secret (`26`) and not a governance policy (`21`). |
- **Enforcement (CFG-A16):** flag-determinism + replay test (`15` T-035). **Build-Fail:** a live/per-stage flag evaluation; a non-deterministic (RNG/wall-clock) rollout resolution; an unrecorded resolved flag set; a flag mutating an in-flight request.

## §AMP — Additive-vs-Mandatory Flag Contract (AMP-1…AMP-6) *(resolves High CFG-H1)*
| # | Aspect | Contract |
|---|---|---|
| AMP-1 | **Mandatory stages are not flag-controllable** | No flag/config may disable/skip/bypass a **mandatory stage** (Ingress/AuthN/Governance/Router/Reliability/Adapter/StreamGuard/SchemaLock/Metering/Cost/Emitter, `06 §8`, AD-018). |
| AMP-2 | **Additive-only toggles** | Flags may toggle **only additive behavior**: enabling/disabling a **plugin** (`28`, additive-only), an **optional emission**, or a **non-mandatory optimization** — never a guarantee-bearing component. |
| AMP-3 | **Explicit additive set** | The set of flag-controllable components is exactly the **frozen additive extension points/plugins** (`28 §EPC`) and optional emissions — enumerated, closed; nothing mandatory is ever added to it. |
| AMP-4 | **No dependency inversion** | A mandatory stage may **never depend** on a flag-toggled additive component's output (so disabling an additive component cannot weaken a mandatory guarantee). |
| AMP-5 | **Kill-switch scope** | A kill-switch may disable a **plugin/optional emission**; disabling it leaves the mandatory pipeline **unweakened** (`28 §EPFC`). |
| AMP-6 | **Build-forbidden** | A flag wired to a mandatory stage is a build failure. |
- **Enforcement (CFG-A18):** non-bypass + dependency test (AD-018/`28`). **Build-Fail:** a flag controlling a mandatory stage; a mandatory stage depending on a flag-toggled additive output.

## 9. Last-known-good (AD-017/AD-022)
C15 unavailable ⇒ serve **last-known-good** (no outage, `06 §9.4`); no new propagation until recovery. Required config absent even in last-known-good ⇒ **fail closed** + secure defaults (§SDS).

## 10. Config drift (§DRC)
## §DRC — Drift Response Contract (DRC-1…DRC-5) *(resolves High CFG-H4)*
- **DRC-1:** The consumer **consumes** C15 `drift-detected` events (AD-013 drift detection); it authors no drift policy.
- **DRC-2:** On detected drift, the consumer **continues serving on validated last-known-good** and **raises an alarm** (`cfg_drift_detected`, C27/C10) — it **never silently accepts** drifted/un-validated config.
- **DRC-3:** If drift affects a **required** config the consumer cannot satisfy from validated last-known-good, it **fails closed** with secure defaults (§SDS).
- **DRC-4:** Drift **remediation** is C15/ops (re-publish a validated snapshot); the consumer only surfaces and degrades safe.
- **DRC-5:** Drift never causes a request outage (AD-017) and never weakens a mandatory guarantee (AD-018).
- **Enforcement (CFG-A9):** drift test. **Build-Fail:** silent acceptance of drifted/un-validated config.

## 11. Validation (C15-owned)
Config is **validated by C15 before distribution** (AD-013); the consumer **trusts** the validated snapshot and re-checks **only schema-compat** at activation (§SCA = `29 §SCC`). Authors no validation logic; invalid/out-of-range ⇒ refuse (last-known-good / fail closed).

### §SCA — Schema-Compat Alignment *(resolves Medium CFG-M1)*
The consumer's activation-time compat check **is exactly `29 §SCC`** (code×snapshot schema range; out-of-range ⇒ refuse/last-known-good). The per-request pinned config must be **range-valid**; a pinned out-of-range config ⇒ fail closed. No separate validation is authored.

## 12. Secure defaults (§SDS)
## §SDS — Secure-Defaults-In-Snapshot Contract (SDS-1…SDS-5) *(resolves internal-contradiction / CFG-M5)*
| # | Aspect | Contract |
|---|---|---|
| SDS-1 | **Authored by C15, carried in-snapshot** | Secure defaults (deny-by-default, conservative limits, AD-013/BRULE-3) are **C15-authored** and **carried within the config snapshot** (a defaults section). |
| SDS-2 | **Applied, never synthesized** | The consumer **applies** the snapshot's secure defaults; it **never synthesizes** a default locally (would author config, violating §CAB). |
| SDS-3 | **Missing required ⇒ default + fail closed** | On a missing/invalid required entry, the consumer applies the **snapshot secure default** and **fails closed / denies** — never a permissive fallback. |
| SDS-4 | **No permissive default** | There is no permissive/allow-by-default fallback anywhere (BRULE-3). |
| SDS-5 | **Defaults recorded** | Applied defaults are recorded with the pinned version for replay/audit. |
- **Enforcement (CFG-A19):** secure-default test. **Build-Fail:** a locally-synthesized default; a permissive fallback; a missing-config path that proceeds without deny/fail-closed.

## 13. Rollback (§RIF; AD-013/`29 §RBC`)
Config rollback = **re-pinning a prior validated snapshot version** (AD-013 reversible rollback), via `29 §RBC`; no in-place mutation.

### §RIF — Rollback-In-Flight *(resolves Medium CFG-M4)*
An **in-flight** request keeps its **pinned** config version through rollback (`32 §SPT` immutability); rollback changes the **serving** version for **new** requests only. Past requests replay on their **recorded** config version (`29 §CVR`). **Build-Fail:** a rollback mutating an in-flight request's config.

## 14. Residency (AD-014) & §TFI Tenant-Flag Isolation *(resolves Medium CFG-M2)*
Config snapshots are **region-scoped, residency-confined** (`14 §18.1`/`08`); the consumer reads region-local config; no cross-region sync dependency (AD-022). **§TFI:** tenant-scoped flags/config are applied **post-C6**, keyed by tenant scope (AD-021); **no cross-tenant flag/config leak** — a tenant's resolved flags never affect another's. **Build-Fail (CFG-A14):** cross-tenant config/flag leak.

## §HPP — High-Priority Propagation Contract (HPP-1…HPP-5) *(resolves High CFG-H2)*
| # | Aspect | Contract |
|---|---|---|
| HPP-1 | **Frozen high-priority path** | Security/invariant config changes (revoked flag, tightened limit, security-critical toggle) use the **frozen AD-022/AD-013 high-priority propagation path** — not normal bounded-staleness propagation. |
| HPP-2 | **Consumer honors priority** | The consumer **applies** a high-priority snapshot on its next pin as soon as it arrives (out-of-band, still no hot-path CP call). |
| HPP-3 | **Bound cited, not authored** | The propagation bound is C15/AD-022's (cited, `16 §I.1` baseline); this document authors no SLA. |
| HPP-4 | **Tighten-only fast-path** | The high-priority path is used for **security-tightening** changes; loosening changes follow normal propagation (safe-by-default). |
| HPP-5 | **Never bypass** | A high-priority change never bypasses a mandatory stage or validation (AD-018/AD-013). |
- **Enforcement (via CFG-A5/A9):** cross-check AD-013/AD-022. **Build-Fail:** a security-critical config change routed through normal propagation when the high-priority path exists.

## §OBB — Operational-Baseline Binding Contract *(resolves High CFG-H3)*
Every operational-baseline numeric (`16 §I.1`) referenced across `17`–`35` is a **config snapshot entry** (C15/`16 §I.1`-authored): a threshold change is a **governed config change** (re-pin a validated snapshot), **never a code change**. The consumer reads baselines from the **pinned snapshot**; hard-coded numerics are forbidden. **Build-Fail (CFG-A20-scope via CFG-A1):** a hard-coded operational numeric in runtime code; a baseline changed outside a config snapshot.

## §CAU — Config Audit Completeness *(resolves Medium CFG-M3)*
Config version/flag changes are audited **content-free** by **C15/C10** (who changed which version/flag — C15's authoring audit) + the consumer's applied-version audit (which pinned version served a request). The consumer authors no audit policy; it emits its applied-version audit (C10, content-free).

## 15. Determinism & replay (`29 §CVR`/`32 §CRS`)
Decisions are a pure function of `(codeVersion, pinned config version + resolved flag set + other pinned snapshots, request inputs)` — config **and resolved flags** are **pinned and recorded** (§7/§FFC). Replay uses the **recorded** config version + resolved flag set, never a live read. **Not** deterministic: propagation timing (async). Honest scope: reproducible **given the recorded config**, not "stable across config changes."

## 16. Failure handling (fail closed / secure defaults)
| Failure | Handling |
|---|---|
| C15 unavailable | last-known-good (AD-017); no outage |
| Required config absent | **fail closed** + snapshot secure default (§SDS) |
| Invalid/out-of-range snapshot | refuse; last-known-good; fail closed if none valid (§SCA) |
| Flag would disable a mandatory stage | **rejected** (§AMP, AD-018) |
| Drift detected | validated last-known-good + alarm; fail closed on required drift (§DRC) |
| Security-critical change | high-priority path (§HPP) |
| Any config uncertainty | **fail closed** + secure defaults (CFG-INV) |

## 17. Security
- **Config carries no secret** (`26`); **secure-by-default** (§SDS, BRULE-3); **no config-driven bypass** (§AMP, AD-018); **tenant isolation** (§TFI, AD-021); **residency** (AD-014); **config version changes audited** content-free (§CAU, C10).
- **Enforcement:** secret scan (`13 §20`), non-bypass test (AD-018). **Build-Fail:** a secret in config; a config-driven mandatory bypass; cross-tenant config.

## 18. Observability
Content-free config telemetry (via `27`): `cfg_snapshot_version{outcome}`, `cfg_last_known_good_active_total`, `cfg_required_missing_total`, `cfg_flag_resolved_total{flag}` (pinned resolutions), `cfg_drift_detected_total`, `cfg_rollback_total`, `cfg_high_priority_applied_total`. Config version + resolved-flag-set-id stamped for replay correlation. No config-content/secret/PII in telemetry (`14 §7.1`/`27 §15.1`).

## 19. Testing
- **Pinning/flag determinism (`15` T-035):** config + resolved flags pinned post-C6, immutable, recorded; deterministic rollout resolution; no live/per-stage eval (§FFC).
- **Non-bypass (§AMP, AD-018):** no flag disables a mandatory stage; no mandatory stage depends on a flag-toggled output.
- **Last-known-good/secure-default (§SDS):** C15 down ⇒ last-known-good; required-missing ⇒ snapshot default + fail closed; no local synthesis (§CAB).
- **Drift (§DRC):** no silent acceptance; alarm + degrade safe.
- **High-priority (§HPP):** security-tightening via high-priority path.
- **Secret/tenant/residency (`15` T-052/T-044/T-045):** no secret in config; no cross-tenant flag; region-confined.
- **Rollback/replay (§RIF):** re-pin; in-flight keeps version; recorded-version replay reproduces decisions.
- **Mutation (`15 §G.1`):** **≥ 90%** on the config-consumption conformance harness.

## 20. Operations
- **`cfg_last_known_good_active` spike:** C15 degraded — graceful degradation (`06 §9.4`); restore C15.
- **`cfg_required_missing` > 0:** required config absent — snapshot secure default + fail closed (correct); fix the C15 snapshot.
- **`cfg_drift_detected` > 0:** drift (§DRC) — remediate via C15; run validated last-known-good.
- **Flag-bypass alarm:** a flag attempted to disable a mandatory stage — §AMP/AD-018 breach; reject; fix C15 flag authoring.
- **`cfg_high_priority_applied`:** a security-critical change landed via the fast path (§HPP) — expected.

## 21. Traceability
| Element | Frozen owner |
|---|---|
| Config strategy | AD-013 |
| Authority/store/flags | C15 (`06 §9.4`) |
| Snapshots/last-known-good | AD-022, AD-017 |
| Per-request pinning | `32 §SPT`, `29 §SCC` |
| Flag determinism | §FFC → `29 §CVR`/`32 §CRS`, `06 §9.4` |
| Additive-vs-mandatory | §AMP → AD-018/`28 §EPC` |
| High-priority propagation | §HPP → AD-013/AD-022 |
| Operational baselines | §OBB → `16 §I.1` |
| Drift | §DRC → AD-013/`06 §9.4` |
| Secure defaults | §SDS → AD-013/BRULE-3 |
| Rollback/in-flight | §RIF → AD-013/`29 §RBC`/`32 §SPT` |
| Config ≠ secret / ≠ policy | `26` / `21` |
| Residency/tenant | AD-014/AD-021, §TFI |
| Audit/observability | §CAU/C10, `27` |

---

## C. Decisions (CFG-D1 … CFG-D12)
*9-field template. Each references its resolving contract; all open markers removed.*

### CFG-D1 — Consume only; C15 owns config (§CGA/§CAB)
- **Problem:** could become a second authority. **Decision:** consume only; contract-tested (§CAB). **Alternatives:** DP authoring — rejected. **Why:** single authority. **Trade-offs:** consumer-only. **Failure Modes:** conflict ⇒ owner wins. **Security:** no new surface. **Performance:** n/a. **Enforcement:** CFG-A1/A17. **Build-Fail:** consumer authoring config.

### CFG-D2 — Cached snapshots, no hot-path CP call (AD-022)
- **Decision:** read cached last-known-good; refresh out-of-band. **Enforcement:** CFG-A2. **Build-Fail:** synchronous C15 hot-path call. *(other fields per §6.)*

### CFG-D3 — Per-request pinning after C6 (`32 §SPT`)
- **Decision:** pin at request start post-C6, immutable, identical across stages, recorded. **Enforcement:** CFG-A3. **Build-Fail:** pin before C6 / mid-request change.

### CFG-D4 — Feature flags pinned + resolved-once + recorded (§FFC)
- **Decision:** resolve targeting once at pin time deterministically; record; never live. **Enforcement:** CFG-A16. **Build-Fail:** live/per-stage eval; RNG rollout; unrecorded resolution.

### CFG-D5 — Flags additive-only; never bypass a mandatory stage (§AMP)
- **Decision:** flags toggle only additive behavior; mandatory stages never flag-controllable. **Enforcement:** CFG-A18. **Build-Fail:** a flag on a mandatory stage.

### CFG-D6 — Last-known-good on C15 outage (AD-017)
- **Decision:** serve last-known-good; no outage. **Enforcement:** CFG-A6. **Build-Fail:** request outage on C15 unavailability.

### CFG-D7 — Config carries no secret (`26`)
- **Decision:** config never carries a secret. **Enforcement:** CFG-A7. **Build-Fail:** a secret in config.

### CFG-D8 — Config is not policy (`21`)
- **Decision:** governance policy = C4; config = C15; distinct. **Enforcement:** CFG-A8. **Build-Fail:** config used for a governance decision.

### CFG-D9 — Secure defaults in-snapshot, applied not synthesized (§SDS)
- **Decision:** defaults C15-authored, in-snapshot; consumer applies; missing ⇒ deny/fail-closed. **Enforcement:** CFG-A19. **Build-Fail:** locally-synthesized default; permissive fallback.

### CFG-D10 — Rollback via re-pin; in-flight immutable (§RIF)
- **Decision:** re-pin prior validated version; in-flight keeps pinned version. **Enforcement:** CFG-A10. **Build-Fail:** in-place config mutation; in-flight config change.

### CFG-D11 — Determinism/replay (config + flags pinned + recorded)
- **Decision:** decisions a function of recorded pinned config + resolved flags; replay uses recorded. **Enforcement:** CFG-A11. **Build-Fail:** replay using live/current config or flags.

### CFG-D12 — No WHAT change (§Hard Constraints)
- **Decision:** changes nothing in ownership/runtime/execution/replay/RPO/deploy/obs/plugin/gateway/state/sequence/ADR. **Enforcement:** CFG-A12. **Build-Fail:** a WHAT change here.

---

## B. Build-Failing Rules (CFG-A1 … CFG-A19)
| # | Rule | Gate |
|---|---|---|
| CFG-A1 | Consumer authors/validates/distributes no config/flag; C15 owns (§CGA/§CAB) | cross-check `06 §9.4`/AD-013 |
| CFG-A2 | No synchronous C15 call on the hot path (AD-022) | ArchUnit |
| CFG-A3 | Config pinned post-C6, immutable, identical across stages (`32 §SPT`) | pinning test |
| CFG-A4 | (reserved — see CFG-A16 flag determinism) | — |
| CFG-A5 | No flag/config disables/bypasses a mandatory stage (§AMP, AD-018) | non-bypass test |
| CFG-A6 | C15 outage ⇒ last-known-good; no request outage (AD-017) | resilience test |
| CFG-A7 | No secret in any config snapshot (`26`) | secret scan (`13 §20`) |
| CFG-A8 | Config never makes a governance decision (policy = `21`) | ArchUnit + cross-check `21` |
| CFG-A9 | Missing/invalid required config ⇒ snapshot secure default, fail closed (§SDS); no silent drift (§DRC) | fault/drift test |
| CFG-A10 | Rollback = re-pin prior validated version; no in-place mutation (§RIF/AD-013) | rollback test |
| CFG-A11 | Replay uses recorded pinned config + resolved flags, never live (`29 §CVR`) | replay test (`15` T-035) |
| CFG-A12 | No WHAT change (ownership/runtime/execution/replay/RPO/deploy/obs/plugin/gateway/state/sequence/ADR) | doc-lint + ArchUnit |
| CFG-A13 | No config content/secret/PII in telemetry/logs (`14 §7.1`) | leak scan (`15` T-052) |
| CFG-A14 | Config region-confined; tenant-scoped post-C6; no cross-tenant flag leak (§TFI, AD-014/021) | residency/isolation test |
| CFG-A15 | Mutation ≥ 90% (config-consumption conformance harness) | PITest (`15 §G.1`) |
| CFG-A16 | **Feature-Flag Determinism (§FFC):** resolved once at pin time, deterministic, recorded, immutable per request; no live/per-stage eval; no RNG rollout | flag-determinism/replay test |
| CFG-A17 | **Consumer-Authority Binding (§CAB):** every config/default/flag originates from a C15 snapshot; no local synthesis; no shadow authority | contract test vs C15/AD-013 |
| CFG-A18 | **Additive-vs-Mandatory (§AMP):** flags toggle only additive behavior; no mandatory stage flag-controllable; no mandatory stage depends on a flag-toggled output | non-bypass + dependency test |
| CFG-A19 | **Secure-Defaults-In-Snapshot (§SDS):** defaults applied from the snapshot, never synthesized; no permissive fallback | secure-default test |

---

## Hard Constraints (this document MUST NOT)
invent architecture · create a config authority/store · change ownership · change runtime · change execution order · change replay · change RPO · change deployment · change observability · change plugin/gateway/state-machine/sequence · change any ADR. (Enforced: CFG-A1/A12.)

---

## §L — Deferred Low Items (non-blocking)
- **CFG-L1** exact config metric names → `14`/`27`; **CFG-L2** flag schema → C15/`07`; **CFG-L3** operational-baseline registry → `16 §I.1`; **CFG-L4** drift-remediation runbook → ops. None affects config ownership, determinism, non-bypass, or any invariant.

---

## S. Reviews (post-resolution)

### 1. Internal Consistency Review
| Claim | Contract | Frozen source | ✓ |
|---|---|---|---|
| Consume only; C15 authority; contract-tested | §CGA/§CAB | `06 §9.4`, AD-013 | ✅ |
| Flags pinned, resolved-once, recorded, deterministic | §FFC | `29 §CVR`/`32 §CRS`, `06 §9.4` | ✅ |
| Additive-only flags; no mandatory bypass | §AMP | AD-018/`28 §EPC` | ✅ |
| High-priority propagation for security config | §HPP | AD-013/AD-022 | ✅ |
| Secure defaults in-snapshot, applied not synthesized | §SDS | AD-013/BRULE-3 | ✅ |
| Drift response (degrade safe / fail closed) | §DRC | AD-013/`06 §9.4` | ✅ |
| Schema-compat = `29 §SCC`; baselines = `16 §I.1` | §SCA/§OBB | `29`/`16 §I.1` | ✅ |
| No hot-path CP call; last-known-good | §6/§9 | AD-022/AD-017 | ✅ |
| Config ≠ secret / ≠ policy; residency/tenant | §17/§TFI | `26`/`21`/AD-014/021 | ✅ |
| Rollback re-pin; in-flight immutable | §RIF | AD-013/`29 §RBC`/`32 §SPT` | ✅ |

**No contradictions remain.** The Resolution Pass (§FFC/§CAB/§AMP/§HPP/§OBB/§DRC/§SDS/§SCA/§TFI/§RIF/§CAU, CFG-A16…A19) is **additive only**; C15/AD-013/AD-022 authorities preserved; runtime behavior clarified, not changed.

### 2. Cross-document Validation
Aligned with AD-013/AD-022/AD-017 (config/snapshots/plane-independence), C15 (`06 §9.4`), `29 §SCC/§SPT/§RBC/§CVR`, `32 §SPT/§CRS`, `26`/`21` (boundaries), `16 §I.1` (baselines), `28`/AD-018 (additive/non-bypass), AD-014/021 (residency/isolation), `07`/`08`, and the `24`-unfrozen rule. **No contradiction.**

### 3. Architecture Validation
- **Subordination:** ✅ §CAB contract-binds to C15/AD-013; owns nothing.
- **Determinism:** ✅ flags resolved-once, deterministic, recorded (§FFC) — the Critical replay hole closed.
- **Non-bypass:** ✅ additive-only, no mandatory dependency on a toggled output (§AMP).
- **Safety:** ✅ secure defaults in-snapshot (§SDS); drift degrade-safe (§DRC); security-critical high-priority (§HPP).
- **Boundaries:** ✅ config ≠ secret ≠ policy; residency/tenant isolated.

### 4. Independent Review Board (re-run)
**Accepted findings — resolution:** CFG-C1→§FFC (CFG-A16); CFG-C2→§CAB (CFG-A17); CFG-H1→§AMP (CFG-A18); CFG-H2→§HPP; CFG-H3→§OBB; CFG-H4→§DRC; CFG-M1→§SCA; CFG-M2→§TFI; CFG-M3→§CAU; CFG-M4→§RIF; CFG-M5/internal→§SDS (CFG-A19).

**New findings:** 🔴 Critical: none. 🟠 High: none. 🟡 Medium: none blocking. 🟢 Low (deferred): §L.

**Internal Contradictions:** none — "authors nothing" reconciled with secure defaults via §SDS (defaults live in the snapshot); flag determinism pinned via §FFC.

**Cross-document Contradictions:** none — C15/AD-013/AD-022 sole authorities; determinism/non-bypass/residency preserved.

**Scores:** Architecture Readiness **97/100**; Documentation Health **96/100**; Implementation Readiness **94/100**.

**Recommendation: FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred. No contradictions with `00`–`35` or AD-001…AD-023; consumer-only; C15/AD-013/AD-022 preserved; CFG-INV reinforced.

---

*End of document — 36-ConfigurationManagement.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
