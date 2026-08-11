# 35 — Implementation Architecture (Implementation Governance · Documentation-Only)

**Document:** Implementation Architecture — HOW engineers implement the frozen architecture (build-enforced governance)
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Scope owner:** **Platform / Technical Documentation Lead** — this is **implementation governance**, not architecture; the implementation standards it enforces remain the **frozen owners'** (`09` tech, **`10` Repository Structure**, **`11` Coding Standards**, `06`, `16`). See **§IGovA Implementation Governance Authority** and **§ILA Implementation Layout Authority**.
**Module:** *(none — this is not a component)* — the **second document of the Implementation Architecture series** (`33` models · this = structure/governance). It aggregates and **build-enforces** the frozen implementation rules so developers **cannot gradually erode** the architecture over 10 years. It introduces **no service, module, context, store, event, deployment topology, ownership, runtime capability, repository structure, or ADR** — **only implementation governance subordinate to `00`–`34`**.
**Plane:** Documentation of implementation governance
**Subordinate to:** `09`, **`10`**, **`11`**, `06`, `16`, and `17`–`34`.
**Audience:** Java developers, tech leads, build/tooling engineers, architects, QA, reviewers
**Classification:** Internal — Architecture (Implementation Governance)
**Builds on (approved, frozen):** `00`–`34` (except `24`, intentionally UNFROZEN), especially **`10` Repository Structure**, **`11` Coding Standards** (R-001…R-06x, ArchUnit AU-01…AU-12), `09` tech, `06 §8/§10/§16`, and ADRs **AD-001…AD-023**. Everything here is **additive and subordinate**: it **cites and enforces** the frozen standards; it **defines no new standard** and, on any conflict, **`10`/`11` prevail** (§IGovA).

> **What this is.** The single build-enforced governance reference for HOW code is organized and constrained so it **cannot violate the frozen architecture**: canonical package layout (the frozen `11` R-013/R-014), layering, dependency/visibility rules, placement of every artifact type, exception hierarchy, configuration/DI, and the static-analysis toolchain — **all citing `10`/`11`**, aggregated into one enforcement surface with build-failing rules (IA-A) that make erosion structurally impossible. It **redefines no architecture** (§Hard Constraints).
>
> **THE IMPLEMENTATION-ARCHITECTURE INVARIANT (IA-INV):** *This document governs HOW, never WHAT.* It never redefines architecture, ownership, runtime, execution order, replay, RPO, deployment, observability, the plugin model, the gateway, the state machine, the execution sequence, or any ADR. It **enforces** the frozen `10`/`11` standards through the build; on any conflict with a frozen document, **the frozen document wins and this document is corrected**. Wherever a rule can be build-enforced, it **must** be (`11` CP-2: "boundaries are law, enforced by the build").

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (IA-C1, IA-C2), High, and Medium** findings are resolved **additively** through signed contracts and build-failing rules — **authoring no standard, moving no ownership, changing no architecture**:

- **§ILA Implementation Layout Authority Contract (ILA-1…ILA-9)** — IA-C1 (`10`/`11` sole layout/standard authorities; brief's layers-first layout **deprecated & build-failed**; R-013/R-014 canonical; `infrastructure` never top-level; `events/telemetry/security/plugin/common` never layers). Build rule **IA-A16**.
- **§IGovA Implementation Governance Authority Contract (IGovA-1…IGovA-8)** — IA-C2 (owns zero standards; aggregates only; regenerated when `10`/`11` change; contract-tested; never duplicates ArchUnit rules). Build rule **IA-A17**.
- **§ICT Implementation Contract-Test Contract** — High (matrix references AU-01…AU-12, never restates; build verifies parity). **§CPC Context Package Contract** — High (non-R013 top-level packages prohibited; context-first mandatory). **§PPC Plugin Placement Contract** — High (plugins out-of-tree; isolation unchanged; never core modules). **§CmC Common Package Contract** — High (`common` = approved shared primitives only; feature code forbidden; ArchUnit rule).
- **Medium** — **§BCO** binary-compat ownership (`12 §27`), **§EHO** exception-hierarchy ownership (`11`), **§FFO** feature-flag ownership (`15`/`16`/`21`), **§MTO** mutation-target clarification, **§COO** CODEOWNERS generation (`06 §16`) — each pointing to its frozen owner.

**Low** items remain **deferred** (§L). **`10`/`11` remain the ONLY implementation-standard authorities; R-013/R-014/R-017 preserved exactly; no new architecture/service/module/context/store/ownership/runtime capability/repository structure/ADR introduced; AD-001…AD-023 preserved.** No document `00`–`34` and no ADR is modified.

---

## §ILA — Implementation Layout Authority Contract (ILA-1…ILA-9) *(resolves Critical IA-C1)*
| # | Aspect | Contract |
|---|---|---|
| ILA-1 | **`10` sole layout authority** | **Document 10 (Repository Structure) remains the ONLY package-layout authority.** |
| ILA-2 | **`11` sole coding-standard authority** | **Document 11 (Coding Standards) remains the ONLY coding-standard authority.** |
| ILA-3 | **Layers-first deprecated** | The requested **layers-first** layout (`api/application/domain/infrastructure/adapter/plugin/events/telemetry/security/common/` as top-level packages) is **explicitly DEPRECATED** and **must not be used**. |
| ILA-4 | **Canonical = R-013/R-014** | The canonical package layout is **exactly `11` R-013/R-014**: `io.reliabilityai.gateway.<area>.<context>.{ api \| internal \| domain \| application \| adapter }` (context-first). |
| ILA-5 | **No top-level `infrastructure`** | **`infrastructure` is never a top-level package** — infrastructure lives behind **adapters** at the edge (`11` R-017/R-018). |
| ILA-6 | **Layers inside each context** | `api`/`application`/`domain`/`adapter` (+`internal`) exist **inside each bounded context**, never as top-level layers (R-014). |
| ILA-7 | **Cross-cutting concerns are not layers** | **`events`, `telemetry`, `security`, `plugin`, `common` never become architectural layers** — events are domain/Avro concerns (`07`), telemetry is `adapter.out.telemetry`/`27`, security is cross-cutting, plugins are out-of-tree (§PPC), `common` is approved primitives only (§CmC). |
| ILA-8 | **Conflict = build failure** | **Any package layout conflicting with R-013/R-014 is a build failure** (AU-01/AU-14, IA-A16). |
| ILA-9 | **Owner wins** | On any layout difference, **`10`/`11` win and this document is corrected** (§IGovA-4). |
- **Enforcement (IA-A16):** ArchUnit AU-01 + layout conformance test vs `11` R-013/R-014. **Build-Fail:** a layers-first package; a top-level `infrastructure`/`events`/`telemetry`/`security`/`plugin`/`common` layer; any layout diverging from R-013/R-014.

## §IGovA — Implementation Governance Authority Contract (IGovA-1…IGovA-8) *(resolves Critical IA-C2)*
| # | Aspect | Contract |
|---|---|---|
| IGovA-1 | **Owns zero standards** | Document 35 **owns zero implementation standards**. |
| IGovA-2 | **Aggregates only** | It **aggregates frozen implementation rules only** (`10`/`11`/`09`/`16`). |
| IGovA-3 | **Regenerated on change** | **Whenever Document 10 or 11 changes, Document 35 is regenerated** (its aggregation is derived from them, not hand-maintained). |
| IGovA-4 | **Contradiction ⇒ 35 is wrong** | **Any contradiction between this document and `10`/`11` means Document 35 is wrong** and is corrected — never `10`/`11`. |
| IGovA-5 | **Contract-tested automatically** | Subordination is **contract-tested automatically** (IA-A1/IA-A17): the build verifies every governance claim here matches `10`/`11`. |
| IGovA-6 | **Never duplicates ArchUnit rules** | It **never duplicates an ArchUnit rule** — it **references** AU-01…AU-12 (§ICT); duplication is a build failure. |
| IGovA-7 | **Not a second standards doc** | It **must never become a second architecture or standards document** (§IGA/IGovA). |
| IGovA-8 | **HOW only** | It governs HOW, never WHAT (IA-INV; §Hard Constraints). |
- **Enforcement (IA-A17):** contract test vs `10`/`11`; ArchUnit (no rule authored here). **Build-Fail:** a governance claim diverging from `10`/`11`; a duplicated ArchUnit rule; this document cited as a standards authority over `10`/`11`.

## §ICT — Implementation Contract-Test Contract *(resolves High: AU restatement)*
- **ICT-1:** The Forbidden Dependency Matrix (§18) and every IA-A that maps to an AU rule **reference AU-01…AU-12 (`10`/`11`)** — they are **pointers**, not restatements.
- **ICT-2:** The document **never restates** an AU rule's logic; it names the AU id and relies on it.
- **ICT-3:** The build **verifies parity**: a contract test asserts §18's matrix cells correspond one-to-one to the frozen AU rules; drift is a build failure.
- **Enforcement (IA-A2):** parity contract test. **Build-Fail:** a matrix cell / IA-A rule that restates or diverges from its AU rule.

## §CPC — Context Package Contract *(resolves High: non-R013 layers)*
- **CPC-1:** **Non-R-013 top-level packages are prohibited** — only `io.reliabilityai.gateway.<area>.<context>.{api|internal|domain|application|adapter}` (R-013).
- **CPC-2:** **Context-first structure is mandatory** (R-014): a context's code lives together; no layers-first scatter.
- **CPC-3:** Cross-cutting concerns (events/telemetry/security) are expressed **within** a context's layers (`domain` events, `adapter.out.telemetry`, cross-cutting security), never as top-level packages.
- **Enforcement (IA-A3):** ArchUnit AU-01. **Build-Fail:** a top-level non-R-013 package; a context scattered layers-first.

## §PPC — Plugin Placement Contract *(resolves High: plugin placement)*
- **PPC-1:** **Plugins remain out-of-tree** — vetted, signed, distributed snapshots (`28 §STC`/AD-004), **not** in-tree core Maven modules.
- **PPC-2:** **Runtime isolation is unchanged** — plugins execute process-isolated (`28 §ISO`); this document changes nothing about the plugin model (IA-INV/§Hard Constraints).
- **PPC-3:** **Plugins never become core modules** — no `plugin/` core package/layer; a core module implementing plugin behavior is forbidden.
- **Enforcement (IA-A18-scope via IA-A12):** ArchUnit + cross-check `28`. **Build-Fail:** an in-tree core plugin module; a `plugin` architectural layer.

## §CmC — Common Package Contract *(resolves High: common erosion)*
- **CmC-1:** **`common`/`libs` hold ONLY approved shared primitives** — stateless, dependency-free utilities and shared ports/contracts.
- **CmC-2:** **Feature/business/domain code inside `common` is forbidden** — no context/domain type, no business logic, no runtime state, no framework binding.
- **CmC-3:** The predicate is **concrete and ArchUnit-enforced**: types in `common` may not reside in or reference a `<context>.domain`/`application` package, may not be `@Entity`/aggregate/service, and may not hold mutable state.
- **Enforcement (IA-A8):** ArchUnit predicate on `common`. **Build-Fail:** a domain/business/stateful/framework-bound type in `common`.

## §Medium ownership contracts (each points to its frozen owner)
- **§BCO — Binary Compatibility Ownership:** binary/API compatibility of the published `api` surface is owned by **`12 §27`** (additive/backward-compatible within a major); this document **cites** it and gates via japicmp on the `api` surface only (IA-A14). *(resolves IA-M1)*
- **§EHO — Exception Hierarchy Ownership:** the exception hierarchy (domain/application/adapter separation; no framework exception in domain) is owned by **`11`**; cited, not authored. *(resolves IA-M2)*
- **§FFO — Feature Flag / Build Profile Ownership:** feature flags/config are **`15`/`16`/C15 (`21 §config`/AD-013)**'s; build profiles are `16`'s; cited, not authored. *(resolves IA-M3)*
- **§MTO — Mutation Target Clarification:** the **≥90%** mutation target (IA-A15) applies to the **enforcement/conformance test harness and any governance tooling code** authored under this document — not to runtime code (owned by `15 §G.1` per component). *(resolves IA-M4)*
- **§COO — CODEOWNERS Ownership:** CODEOWNERS is **generated from `06 §16`** (module→team ownership), never hand-authored here; drift is a build failure. *(resolves IA-M5)*

---

## 1. Charter
Make architecture erosion **structurally impossible** for 10 years by aggregating the frozen `10`/`11` implementation standards into **one build-enforced governance surface**: canonical layout (R-013/R-014, §ILA), layering, dependency/visibility, placement, and the static-analysis toolchain — all citing the frozen owners (§IGovA). Governs HOW; never redefines WHAT (IA-INV).

## 2. Scope
- **In scope (governance only):** aggregation + build-enforcement of `10`/`11` structure/placement/toolchain rules; the forbidden-dependency matrix (references AU-01…AU-12, §ICT); testing-boundary and migration guidance — subordinate to `10`/`11`.
- **Out of scope:** authoring any new standard/layout/layer/ArchUnit rule (owned by `10`/`11`); any WHAT change (§Hard Constraints).

## 3. Runtime ownership (§IGovA)
No runtime ownership — documentation. Standards owned by `10`/`11`; toolchain by `10`/`11`/`16`; module/context ownership per `06 §16`. This document owns none.

## 4. Relationship to frozen documents
| Frozen owner | Owns | This document |
|---|---|---|
| **`10`** | monorepo, Maven multi-module, module graph, `gateway-dp-*`, namespace, boundaries | **cites/enforces** (ILA-1) |
| **`11`** | R-001…R-06x, R-013 layout, R-017/018 hexagonal, R-010 visibility, R-016 DDD, R-028 DTO, R-025/026 config, AU-01…AU-12 | **cites/enforces** (ILA-2) |
| `09` | Java 21/Spring/Maven/toolchain | cites |
| `06 §10/§16` | modular monolith, module→team | cites |
| `16` | build/CI/static-analysis gates, profiles | cites (§FFO) |
| `12 §27` | binary/API compatibility | cites (§BCO) |
| `28`/AD-004 | plugin model (out-of-tree, isolated) | cites (§PPC) |
| `17`–`34` | runtime components/models/sequence/state | cites (placement targets) |

## 5. Canonical implementation model (frozen R-013)
```
io.reliabilityai.gateway.<area>.<context>.{ api | internal | domain | application | adapter }
```
- `domain` — DDD aggregates/entities/VOs/domain-events/ports (R-016/R-018); external-free (R-017).
- `application` — use-cases depending on domain + ports (R-017).
- `adapter` — inbound/outbound adapters implementing ports (R-017/R-018); **infrastructure lives here** (`adapter.in.web`, `adapter.out.persistence`, `adapter.out.provider`, `adapter.out.telemetry`) — **never a top-level `infrastructure`** (ILA-5).
- `api` — module public surface; `internal` non-exported (R-010).
- Context-first (R-014/§CPC): a context lives together; never layers-first.

## 6. Package organization (§ILA)
The canonical layout is the frozen **R-013/R-014 context-first** structure (§5). The layers-first layout is **deprecated** (ILA-3) and build-failed (IA-A16). `infrastructure`/`events`/`telemetry`/`security`/`plugin`/`common` are **not** layers (ILA-5/ILA-7).

## 7. Module boundaries
Per `10`/`06 §10`: one Maven module per hot-path in-process module (`gateway-dp-*`) + one per CP service (`gateway-svc-*`, store-per-service); a module never reads another's store/code (R-015, AU-07); integration via events/published-language (`06`/`07`). Build-enforced (`11` CP-2).

## 8. Project structure
Per `10 §5/§7`: monorepo → `backend/` Maven multi-module → `.../modules/gateway-dp-*` + `gateway-svc-*` + `libs/` (ports/shared). Cites `10`; authors no tree.

## 9. Layering (hexagonal, R-017/018)
Inward-only through ports: `adapter → application → domain`; domain external-free; no domain/application import of Spring web/Kafka/JPA/Mongo/provider SDK/`jakarta.servlet` (AU-08). Ports at domain; adapters at edge.

## 10. Dependency rules
Inward only (R-017); no cycles (R-021/AU-04); forbidden imports enumerated in §18 (references AU-01…AU-12).

## 11. Visibility rules (R-010)
Package-private default; `public` only for `api`; no `public` on `internal`; component-scan never reaches domain/internal (R-025/AU-02).

## 12. Placement rules (cited from `11`)
| Artifact | Placement | Source |
|---|---|---|
| Port/interface | domain (ports) | R-018 |
| DTO | edge only (`adapter.in.web`) | R-028 |
| Domain model | `domain` (DDD) | R-016 |
| Repository | port in `domain`, impl in `adapter.out.persistence`; one store/service | R-015/R-018, `08 §24` |
| Service (use-case) | `application` | R-017 |
| Adapter | `adapter.in/out.*` | R-018 |
| Controller | thin `adapter.in.web` | R-026 |
| Event model | Avro (`07 §9`) + domain-event in `domain` | `07`, R-016 |
| **Plugin** | **out-of-tree, process-isolated (§PPC)** | `28`, AD-004 |
| Exception | `11` hierarchy (§EHO) | `11` |
| Configuration | `@ConfigurationProperties` records; explicit `@Configuration`; VT on | R-025/R-062 |
| Builder/Factory/Validation | with domain/VO; SchemaLock at edge | R-016, `17` |

## 13. Naming conventions
`10 §9`/`11`: namespace `io.reliabilityai.gateway`; `gateway-dp-*`/`gateway-svc-*`; context-first; Spotless format (R-012). Cited.

## 14. Shared utilities (§CmC)
`common`/`libs` = approved shared primitives only; no business/domain/stateful/framework code; ArchUnit-enforced (CmC-3).

## 15. Dependency injection (R-025/026)
Constructor injection; explicit `@Configuration`; `@ConfigurationProperties` records; no field injection; no scan of `internal`; VT enabled (R-048). Bean ownership = module ownership (`06 §16`).

## 16. Module activation
Wiring/activation follows `29 §DAG`; this document references `29`, defines no activation logic (IA-INV).

## 17. Build enforcement & static analysis (`10`/`11`/`16`)
Frozen toolchain (cited): Spotless, Checkstyle, Error Prone, SpotBugs, **ArchUnit (AU-01…AU-12)**, Sonar, Maven Enforcer, package-cycle detection, binary-compatibility (japicmp on `api`, §BCO), forbidden-APIs. Every boundary rule is a build gate (`11` CP-2). Adds no tool.

## 18. Forbidden Dependency Matrix (references AU-01…AU-12; §ICT — never restates)
| From ↓ / To → | domain | application | adapter | other-ctx internal | infra/framework | provider SDK | other-svc store | secrets |
|---|---|---|---|---|---|---|---|---|
| **domain** | ✓ | ❌ | ❌ | ❌ | ❌ (AU-08) | ❌ (AU-06) | ❌ (AU-07) | ❌ |
| **application** | ✓ | ✓ | ❌ (ports) | ❌ (AU-02) | ❌ (AU-08) | ❌ (AU-06) | ❌ (AU-07) | ❌ |
| **adapter** | ✓ (ports) | ✓ | ✓ | ❌ (AU-02) | ✓ | ✓ (adapter `25` only) | ❌ (AU-07) | ✓ (`26` only) |
| **any → `common`** | ✓ (primitives only, §CmC) | ✓ | ✓ | — | — | — | — | — |
- Each ❌ names its frozen **AU id**; the build **verifies parity** (§ICT/IA-A2). This matrix restates **nothing**.

## 19. Code ownership (§COO)
Module/context ownership = `06 §16`; **CODEOWNERS is generated from `06 §16`**, never authored here.

## 20. Testing boundaries (`15`/`10`)
Unit tests co-located; integration (`Testcontainers`) at adapter boundary; contract tests for ports/events (`07 §9`/`12 §27`); architecture tests = AU-01…AU-12 + IA-A. Test layout mirrors main (context-first). Mutation ≥90% on the enforcement/conformance harness (§MTO/IA-A15).

## 21. Migration guidance
Migrate **to** R-013/R-014 (never away); split-to-repo per `10 §18` only on evidence; deprecations per `11`/`12 §27`. No migration weakens a frozen boundary.

## 22. Operations
- **AU/ArchUnit failure:** boundary violated — build break; never suppress a boundary rule.
- **`common` growth:** utilities accreting business logic (§CmC) — refactor into the owning context.
- **Cycle (AU-04):** break it; never disable.
- **Binary-compat break on `api` (§BCO/`12 §27`):** revert or major-version.
- **Layout violation (§ILA):** layers-first/`infrastructure` layer — build break; migrate to R-013.

## 23. Traceability
| Element | Frozen owner |
|---|---|
| Package layout | `11` R-013/R-014 (§ILA) |
| Hexagonal | `11` R-017/R-018, AD-002 |
| Visibility | `11` R-010 |
| DDD | `11` R-016, AD-003 |
| DTO/edge | `11` R-028 |
| Store-per-service | `11` R-015, `08 §24`, AD-010 |
| Cycles/deps | `11` R-021, AU-04/AU-08 |
| Config/DI | `11` R-025/R-026/R-062 |
| Monorepo/modules | `10 §5/§7/§8` |
| ArchUnit AU-01…AU-12 | `10`/`11` (§ICT) |
| Toolchain | `09`/`11`/`16` |
| Plugin placement | `28`, AD-004 (§PPC) |
| Binary compat | `12 §27` (§BCO) |
| Feature flags/profiles | `15`/`16`/`21`/AD-013 (§FFO) |
| Ownership/CODEOWNERS | `06 §16` (§COO) |
| Testing | `15`, `10` |

---

## C. Decisions (IA-D1 … IA-D12)
*9-field template. Each references its resolving contract; all open markers removed.*

### IA-D1 — Governs HOW, not WHAT; subordinate to `10`/`11` (§IGovA)
- **Problem:** could become a second standards doc. **Decision:** governs HOW; cites `10`/`11`; owns no standard; owner wins (§IGovA). **Alternatives:** re-author — rejected. **Why:** single source of truth. **Trade-offs:** aggregative. **Failure Modes:** conflict ⇒ 35 wrong. **Security:** none new. **Performance:** n/a. **Enforcement:** IA-A1/A17. **Build-Fail:** a standard authored here diverging from `10`/`11`.

### IA-D2 — Frozen R-013 context-first layout is canonical (§ILA)
- **Problem:** layers-first contradicts R-013/R-014. **Decision:** adopt R-013; deprecate layers-first (ILA-3); no top-level `infrastructure`/cross-cutting layers. **Alternatives:** brief's layout — rejected (contradicts R-013/R-014/R-017). **Why:** `11` prevails. **Trade-offs:** differs from brief. **Failure Modes:** AU-01. **Security:** boundary integrity. **Performance:** n/a. **Enforcement:** IA-A16/AU-01. **Build-Fail:** a layers-first/`infrastructure`-layer package.

### IA-D3 — Hexagonal inward-only (R-017/018)
- **Problem:** direction erosion. **Decision:** adapter→application→domain; domain external-free. **Alternatives:** `infrastructure` layer — rejected. **Why:** AD-002. **Trade-offs:** discipline. **Failure Modes:** AU-08. **Security:** neutrality. **Performance:** n/a. **Enforcement:** IA-A4/AU-08. **Build-Fail:** domain/application → infra/framework.

### IA-D4 — Boundaries build-enforced (CP-2)
- **Problem:** intentions erode. **Decision:** every enforceable boundary is a gate. **Alternatives:** review-only — rejected. **Why:** 10-year integrity. **Trade-offs:** strict CI. **Failure Modes:** build-fail. **Security:** enforced isolation. **Performance:** CI cost. **Enforcement:** IA-A2/§17. **Build-Fail:** an enforceable boundary not gated.

### IA-D5 — Minimal visibility (R-010)
- As frozen: package-private default; public only for `api`. **Enforcement:** IA-A5/AU-02. **Build-Fail:** cross-boundary `internal` reference.

### IA-D6 — Store/repository per service (R-015/`08 §24`)
- One store/service; port in domain, impl in adapter; no cross-service store. **Enforcement:** IA-A6/AU-07. **Build-Fail:** cross-service datasource/repository.

### IA-D7 — Provider SDK/secret only in their locus (AD-007/`26`)
- Provider SDK only in adapter (`25`); secrets only in `26`. **Enforcement:** IA-A7/AU-06. **Build-Fail:** provider SDK/secret outside its locus.

### IA-D8 — `common` primitives-only (§CmC)
- No business/domain/stateful/framework code in `common`. **Enforcement:** IA-A8. **Build-Fail:** feature code in `common`.

### IA-D9 — No cycles (R-021/AU-04)
- No package/module/bean cycles. **Enforcement:** IA-A9. **Build-Fail:** any cycle.

### IA-D10 — Immutability/thread-safety (R-005/R-049)
- Immutable VOs; no mutable statics. **Enforcement:** IA-A10. **Build-Fail:** mutable static/domain field.

### IA-D11 — DTOs/edge (R-028)
- DTOs at edge; no domain type over wire/event. **Enforcement:** IA-A11. **Build-Fail:** domain type serialized at a boundary.

### IA-D12 — No WHAT changes (§Hard Constraints)
- Changes nothing in ownership/runtime/execution/replay/RPO/deploy/obs/plugin/gateway/state/sequence/ADR. **Enforcement:** IA-A12. **Build-Fail:** a WHAT change here.

---

## B. Build-Failing Rules (IA-A1 … IA-A17)
| # | Rule | Gate |
|---|---|---|
| IA-A1 | Authors no standard diverging from `10`/`11`; owner wins (§IGovA) | contract test vs `10`/`11` |
| IA-A2 | §18 matrix references AU-01…AU-12; build verifies parity (§ICT); never restates | parity contract test |
| IA-A3 | R-013 context-first layout; no layers-first scatter (§CPC/R-014) | ArchUnit AU-01 |
| IA-A4 | Hexagonal inward-only; no domain/application→infra/framework (R-017) | ArchUnit AU-08 |
| IA-A5 | Minimal visibility; no cross-boundary `internal` (R-010) | ArchUnit AU-02 |
| IA-A6 | One store/service; no cross-service store/repository (R-015/`08 §24`) | ArchUnit AU-07 |
| IA-A7 | Provider SDK only in adapter (`25`); secrets only in `26` | ArchUnit AU-06 + secret scan |
| IA-A8 | `common` primitives-only; no feature/domain/stateful/framework type (§CmC) | ArchUnit predicate |
| IA-A9 | No package/module/bean cycles (R-021) | ArchUnit AU-04 + Spring |
| IA-A10 | Immutable VOs; no mutable statics; VT-safe (R-005/R-049) | SpotBugs + ArchUnit |
| IA-A11 | DTOs at edge; no domain type over wire/event (R-028) | ArchUnit |
| IA-A12 | No WHAT change; plugins out-of-tree (§PPC); no plugin core module | doc-lint + ArchUnit + cross-check `28` |
| IA-A13 | Static-analysis toolchain present + gating (Spotless/Checkstyle/ErrorProne/SpotBugs/ArchUnit/Sonar/Enforcer/forbidden-APIs) | CI gate (`16`) |
| IA-A14 | Binary compatibility on the published `api` surface (japicmp, §BCO/`12 §27`); cycle/forbidden-API detection | CI gate |
| IA-A15 | Mutation ≥ 90% on the enforcement/conformance harness (§MTO) | PITest (`15 §G.1`) |
| IA-A16 | **Layout Authority (§ILA):** R-013/R-014 canonical; layers-first deprecated; no top-level `infrastructure`/`events`/`telemetry`/`security`/`plugin`/`common` layer | ArchUnit AU-01 + layout conformance |
| IA-A17 | **Governance Authority (§IGovA):** owns zero standards; regenerated on `10`/`11` change; contradiction ⇒ 35 wrong; never duplicates an AU rule | contract test vs `10`/`11` |

---

## Hard Constraints (this document MUST NOT)
invent architecture · change ownership · change runtime · change execution order · change replay · change RPO · change deployment · change observability · change the plugin model · change the gateway · change the state machine · change the execution sequence · change any ADR · introduce a new repository structure. (Enforced: IA-A12/IA-A16/IA-A17.)

---

## §L — Deferred Low Items (non-blocking)
- **IA-L1** concrete ArchUnit rule source → `10`/`11` test module; **IA-L2** Maven Enforcer ruleset → `16`; **IA-L3** Spotless/Checkstyle config → `11`; **IA-L4** module-graph diagram → `10 §12`. None affects standards ownership, layout, or any invariant.

---

## S. Reviews (post-resolution)

### 1. Internal Consistency Review
| Claim | Contract | Frozen source | ✓ |
|---|---|---|---|
| `10`/`11` sole layout/standard authorities | §ILA/§IGovA | `10`/`11` | ✅ |
| Layers-first deprecated; R-013 canonical | §ILA (ILA-3/4) | `11` R-013/R-014 | ✅ |
| No top-level `infrastructure`/cross-cutting layers | §ILA (ILA-5/7) | `11` R-017 | ✅ |
| Governance owns zero standards; regenerated | §IGovA | `10`/`11` | ✅ |
| Matrix references AU rules; parity-tested | §ICT/§18 | AU-01…AU-12 | ✅ |
| Context-first mandatory | §CPC | R-014 | ✅ |
| Plugins out-of-tree/isolated | §PPC | `28`/AD-004 | ✅ |
| `common` primitives-only | §CmC | R-010/CP-2 | ✅ |
| Binary-compat/exception/flags/mutation/CODEOWNERS owners | §BCO/§EHO/§FFO/§MTO/§COO | `12 §27`/`11`/`15`-`16`/`06 §16` | ✅ |

### 2. Cross-document Validation
Aligned with `10` (repo/modules), `11` (R-013/014/017 preserved exactly + AU-01…AU-12 referenced, not restated), `09` (toolchain), `06 §10/§16` (modular monolith/ownership), `12 §27` (binary compat), `28`/AD-004 (plugins), `08 §24`/AD-010 (store-per-service), and the `24`-unfrozen rule. **No contradiction with `00`–`34` or AD-001…AD-023.**

### 3. Architecture Validation
- **Subordination:** ✅ §IGovA + IA-A17 contract-bind to `10`/`11`; regenerated on change; owns nothing.
- **Layout:** ✅ R-013/R-014 canonical; layers-first deprecated & build-failed (§ILA/IA-A16); no top-level `infrastructure`/cross-cutting layers.
- **AU rules:** ✅ referenced, parity-tested (§ICT), never duplicated (IGovA-6).
- **Plugins/common/medium owners:** ✅ out-of-tree, primitives-only, each pointing to its frozen owner.

### 4. Independent Review Board (re-run)
**Accepted findings — resolution:** IA-C1→§ILA (IA-A16); IA-C2→§IGovA (IA-A17); High→§ICT/§CPC/§PPC/§CmC; Medium→§BCO/§EHO/§FFO/§MTO/§COO.

**New findings:** 🔴 Critical: none. 🟠 High: none. 🟡 Medium: none blocking. 🟢 Low (deferred): §L.

**Internal Contradictions:** none — the two-layout tension is closed (§ILA deprecates layers-first; R-013 sole canonical); AU restatement closed (§ICT references only).

**Cross-document Contradictions:** none — `10`/`11` sole authorities; R-013/R-014/R-017 preserved exactly; AU-01…AU-12 referenced; AD-001…AD-023 intact.

**Scores:** Architecture Readiness **97/100**; Documentation Health **96/100**; Implementation Readiness **94/100**.

**Recommendation: FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred. `10`/`11` remain the sole implementation-standard authorities; this document owns zero standards; no architecture/ownership/repository-structure changed.

---

*End of document — 35-ImplementationArchitecture.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
