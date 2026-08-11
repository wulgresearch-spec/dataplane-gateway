# 15 — Testing Standards (The Testing Constitution)

**Document:** Testing Standards & Governance
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Version:** 1.0 (frozen 2026-07-21)
**Audience:** Every engineer, SDET, SRE, security engineer, release manager, auditor
**Classification:** Internal — Engineering Standard
**Builds on (approved, frozen):** `00`–`14` and ADRs **AD-001…AD-023**. Nothing here may contradict them.
**Stack:** Java 21 + Virtual Threads · JUnit 5 · Mockito · **Testcontainers** (PostgreSQL/MongoDB/Valkey/Kafka) · **ArchUnit** · Pact (CDC) · WireMock/recorded fixtures (provider stubs) · Gatling/k6 (load) · JMH (benchmark) · PITest (mutation) · jqwik (property-based) · OWASP ZAP (DAST) · Chaos Mesh/Litmus (chaos) · Spectral (OpenAPI) · JaCoCo · GitHub Actions.

> **What this is.** The permanent law for how the platform is tested — the evidence that its reliability, correctness, security, isolation, and compliance guarantees actually hold. In a *reliability-first* product, testing is not a phase; it is the proof. Every rule carries **Problem · Rule · Why · Good · Bad · Exceptions · Enforcement · Build-Fail**; examples are tiny illustrations of the standard, never product code. Where a rule adds ceremony without buying confidence, it is cut.
>
> **The three testing principles (everything derives from these):**
> - **TP-1 — Test the guarantees, not just the code.** Every promised guarantee (SLO, invariant, correctness escape rate, isolation) has a test that fails if it regresses (AD-016, `03`).
> - **TP-2 — Prove correctness under failure, concurrency, and scale — not just the happy path.** Fault injection, chaos, concurrency, and load are first-class, not optional.
> - **TP-3 — A test that cannot fail the build proves nothing.** Confidence comes from gates, not intentions; flaky, skipped, or coverage-gaming tests are defects.

---

## Table of Contents
**A. Philosophy & Structure** (T-001…T-004) · **B. Functional Levels** (T-005…T-013) · **C. Provider Testing** (T-014…T-016) · **D. Reliability & Resilience** (T-017…T-020) · **E. Performance & Concurrency** (T-021…T-028) · **F. Security Testing** (T-029…T-034) · **G. Advanced Techniques** (T-035…T-039) · **H. E2E & Clients** (T-040…T-043) · **I. Platform, Data & Ops** (T-044…T-052) · **J. Quality Gates & Process** (T-053…T-064) · **K. Reviews** (Traceability · Consistency · Architecture Validation · Adversarial Review · Score · Findings · Recommendation)

---

## A. Philosophy & Structure

### T-001 — Testing philosophy
- **Problem:** without a philosophy, testing becomes coverage-theater. **Rule:** testing exists to **prove the platform's promises** (reliability, correctness, security, isolation, compliance) and to make regressions **impossible to merge**. Tests are treated as production code (reviewed, owned, maintained). **Why:** the product *is* reliability; untested reliability is a claim, not a fact (AD-016). **Enforcement:** review + gates (§J). **Build-Fail:** required gates red.

### T-002 — Reliability-first testing principles
- **Rule:** for every guarantee we make, there is a test that fails when it breaks — **SLOs** (`03 §61`), **invariants** (isolation, no-silent-delivery, audit integrity, residency, non-exposure — zero-budget), **correctness escape rates** (`03` NFR-SO/STRM), **failover/retry**, **cost/quota**. Failure, concurrency, and scale are tested, not assumed (TP-2). **Why:** reliability-first means proving it (CP-1). **Enforcement:** the guarantee→test map (§K traceability) is complete. **Build-Fail:** a P0 NFR/invariant without a verifying test (`03 §50`, NFR-TEST-001).

### T-003 — The testing pyramid (`11` R-080)
- **Rule:** **many fast unit tests** (domain/application, no Spring) → **fewer integration/component tests** (Testcontainers, real backends) → **fewest e2e** (`testing/`). Push logic tests down; keep the top thin and stable. **Why:** confidence without brittleness/slowness. **Good:** a pure domain rule tested by a millisecond JUnit test. **Bad:** `@SpringBootTest` to test a value-object invariant. **Enforcement:** ArchUnit test-classification + suite-time budgets. **Build-Fail:** unit-suite time budget exceeded; a domain rule tested only via e2e.

### T-004 — Test ownership
- **Rule:** the team that owns the code (`06 §16` CODEOWNERS, `10 §16`) owns its tests, coverage, flake budget, and the runbook for failures. Cross-cutting suites (isolation, security, chaos, contract) are co-owned by Platform/SRE/Security + the context team. **Why:** ownership prevents rot. **Enforcement:** CODEOWNERS maps test paths (`10`). **Build-Fail:** test change without owner review.

---

## B. Functional Levels

### T-005 — Unit testing (JUnit 5 + Mockito, no Spring) (`11` R-081)
- **Problem:** slow, coupled unit tests. **Rule:** domain/application unit tests use **JUnit 5 + Mockito**, **no Spring context**, deterministic (injected `Clock`/`RandomGenerator`, `11` R-063). **Mock ports, never concrete infrastructure.** One behavior per test; AAA (arrange-act-assert). **Why:** speed + isolation (TP-3). **Good:** `mock(ProviderPort.class)`. **Bad:** mocking a Kafka client in a domain test. **Exceptions:** none in domain/application. **Enforcement:** ArchUnit (no Spring in `*.domain`/`*.application` tests). **Build-Fail:** Spring context in a unit test; non-deterministic time/random.

### T-006 — Integration testing (Testcontainers, real backends) (`11` R-082)
- **Problem:** in-memory fakes hide real-backend behavior. **Rule:** persistence/messaging/adapter tests run against **Testcontainers** — real **PostgreSQL/MongoDB/Valkey/Kafka** — never H2 or in-memory substitutes. **Why:** fidelity to `09` production tech. **Bad:** H2 standing in for PostgreSQL RLS/JSONB. **Enforcement:** convention + CI. **Build-Fail:** failing integration test; in-memory DB used for a real-backend behavior.

### T-007 — Component testing (a service in isolation)
- **Rule:** each service/module is tested as a **component** — its API/ports exercised with real backends (Testcontainers) but **collaborator services mocked/stubbed** at their contracts (Pact/WireMock). **Why:** verifies a service's behavior without a full environment. **Enforcement:** CI per service. **Build-Fail:** component suite red.

### T-008 — API testing (`12 §30`)
- **Rule:** every operation is tested against its **OpenAPI 3.1** contract (request/response/error envelope §11, status codes, headers, pagination, idempotency); negative and boundary cases mandatory. **Why:** the spec is the contract (`10 §7`, AD-015). **Enforcement:** contract tests + Spectral. **Build-Fail:** controller ↔ OpenAPI mismatch; missing error-model/security in spec (`12 §33`).

### T-009 — Contract testing
- **Rule:** provider/consumer contracts (REST + events) are **verified both sides** in CI; a change that breaks a published contract without a major bump fails (`12 §27`, AD-015). **Why:** no silent drift. **Enforcement:** spec-diff + Pact. **Build-Fail:** backward-incompatible contract change without a major.

### T-010 — Consumer-driven contract testing (CDC, Pact)
- **Rule:** SDK/consumer expectations are captured as **Pact** contracts and verified against providers; both consumer and provider pipelines are gated. **Why:** the platform never breaks a real consumer (`12 §26`, BR-028). **Enforcement:** Pact broker + CI verification. **Build-Fail:** provider fails a published consumer contract.

### T-011 — Event contract testing (`07 §9`)
- **Rule:** every event is validated against its **Avro** schema via **Apicurio** compatibility gates; **backward/forward compatibility** within a major is enforced; producers and consumers are contract-tested; **envelope completeness** (correlation/causation/tenant/classification, `07 §6`) is asserted. **Why:** zero-loss, versioned events. **Enforcement:** registry compat gate + consumer tests. **Build-Fail:** incompatible schema; event off-envelope.

### T-012 — Streaming testing (`12 §16`, `07`)
- **Problem:** streaming corruption is easy to miss. **Rule:** streaming is tested for **ordering/exactly-once per sequence**, **tool-call reconstruction + validation before `tool_call.done`**, **partial-JSON never emitted as valid**, **explicit terminal event** (`stream.done`/`stream.error`), **heartbeats**, **cancellation**, **backpressure**, and **no silent truncation** (mid-stream failure surfaced). Provider-native streams are tested to map to the canonical model with **no provider leaks** (`12 §16.10`). **Why:** streaming integrity is a core guarantee (`03` NFR-STRM-001, BRULE-1). **Enforcement:** fault-injection suite (T-018) + contract tests. **Build-Fail:** silent truncation, invalid `tool_call.done`, ambiguous close, or provider field leak detected.

### T-013 — Schema validation testing (`10 §7`, `12 §22`)
- **Rule:** REST payloads tested against **JSON Schema** (OpenAPI 3.1); **AI structured output** tested against declared **JSON Schema** — conforming, non-conforming (surfaced, `03` NFR-SO), and malformed cases; events against **Avro**. **Why:** correctness enforcement is validated, not assumed. **Enforcement:** validation tests + fault injection. **Build-Fail:** a validated surface without conformance + non-conformance tests.

---

## C. Provider Testing

### T-014 — Provider compatibility testing
- **Problem:** provider APIs drift; adapters break silently. **Rule:** each provider adapter (AD-007) has a **compatibility suite** verifying request/response/streaming/tool/error mapping to the neutral model, run against **recorded fixtures** (deterministic CI) **and** periodically against **live providers** (scheduled, isolated credentials). **No provider-native field leaks** (`12 §16.10`). **Why:** provider drift is a top real-world failure (`01` PRB-007/022). **Good:** recorded-fixture replay in PR CI + nightly live-smoke. **Bad:** only mocking the adapter's own output. **Enforcement:** fixture replay (PR) + scheduled live suite. **Build-Fail:** neutralization-leak or mapping regression on fixtures.

### T-015 — Multi-provider conformance testing
- **Rule:** a **shared conformance suite** runs the **same neutral requests across all providers** and asserts **identical neutral behavior** (semantics, streaming contract, error neutralization) — proving provider independence (`BR-006`). **Why:** the neutral contract must hold across providers, not just format. **Enforcement:** conformance matrix in CI (fixtures) + scheduled (live). **Build-Fail:** a provider diverges from the neutral conformance contract.

### T-016 — Golden datasets (correctness & conformance)
- **Rule:** **versioned golden datasets** (inputs + expected neutral outputs/structures) drive correctness and conformance tests; datasets are **synthetic, PII-free, tenant-neutral, reviewed, and versioned** (`08`/`13`). Drift is managed (dataset changes reviewed like code). **Why:** stable, auditable correctness baselines. **Enforcement:** golden-dataset suite; dataset changes CODEOWNER-gated. **Build-Fail:** golden-suite regression; a dataset containing PII (scanner). *(Lifecycle & approval workflow: §C.2.)*

### §C.1 — Provider Conformance Framework *(resolves TH-1; expands T-014/T-015 — additive, no architecture change)*

This section fully specifies the framework that verifies every provider adapter (AD-007) against the **provider-neutral contract** (`12 §16`/`16.10`). It has **two independent tracks that never substitute for each other**, plus the artifacts and workflows that keep them honest.

**§C.1.1 — Two tracks (deterministic CI vs. scheduled live) — the boundary is law**

| | **Track 1 — Deterministic CI (fixtures)** | **Track 2 — Scheduled Live Validation** |
|---|---|---|
| **Runs on** | Every PR + merge queue (gating) | Nightly (smoke) + weekly (full matrix); on adapter change | 
| **Data source** | Recorded fixtures (captured provider I/O) replayed via WireMock | Real provider endpoints, isolated non-prod credentials, capped spend budget |
| **Determinism** | Fully deterministic, offline, zero external cost | Non-deterministic; tolerant assertions (schema/shape/semantics, not byte-equality) |
| **Purpose** | Prove **our mapping/neutralization logic** is correct and unchanged | Prove **the provider still behaves as the fixtures claim** (reality check) |
| **Failure = ** | **Blocks merge** (our regression) | **Raises a drift ticket + fails the scheduled gate** (provider drift), never blocks unrelated PRs |
| **Isolation** | No network, no credentials | Dedicated test tenant, dedicated keys, rate-limited, cost-budgeted, region-pinned |

> **C-INV (conformance invariant): fixtures NEVER replace live validation.** Fixtures prove *our code* is right against a *recorded reality*; only Track 2 proves that recorded reality is *still true*. A provider adapter is **not** considered conformant on fixtures alone — a live-validation run within the **freshness window (§C.1.4)** is a prerequisite for release (T-053). Disabling Track 2 for an active provider is a release-blocking exception (`13 §29`), never silent.

**§C.1.2 — What is validated (the conformance contract — both tracks assert all eight)**

| # | Dimension | Asserted behavior (neutral contract) | Source |
|---|---|---|---|
| CV-1 | **Canonical request** | Neutral request → provider request mapping is complete and lossless; unsupported params are surfaced, never silently dropped | `12 §16`, AD-007 |
| CV-2 | **Canonical streaming** | Provider stream → canonical events; ordering/exactly-once per sequence; explicit terminal (`stream.done`/`stream.error`); heartbeats; **no silent truncation** | `12 §16`, `03` NFR-STRM-001, T-012 |
| CV-3 | **Tool calls** | Reconstructed + schema-validated **before** `tool_call.done`; partial/corrupt tool JSON never emitted valid; multi-tool ordering preserved | `12 §16`, T-012/T-035 |
| CV-4 | **Structured output** | Declared JSON Schema enforced; conforming pass, non-conforming **surfaced** (never silently repaired-and-delivered), malformed handled | `03` NFR-SO, T-013 |
| CV-5 | **Usage accounting** | Token/usage figures mapped to neutral metering fields; missing/estimated usage flagged as such (never fabricated) | `05` C5, `08`, `14 §10` |
| CV-6 | **Error normalization** | Provider errors → neutral taxonomy (`12 §12`); no provider-native error leaks; retryable vs terminal classified correctly | `12 §12/§16.10` |
| CV-7 | **Retry behavior** | Retryable classification drives retry engine; budgets/backoff honored; non-idempotent never auto-retried | `12 §14`, T-017 |
| CV-8 | **Provider-neutral guarantees** | **Zero provider-native field leaks** across the neutral surface; identical neutral semantics across providers (ties to T-015 matrix) | `12 §16.10`, BR-006 |

**§C.1.3 — Artifacts & versioning**
- **Fixture set** — per provider, per provider-API-version, version-controlled under `testing/conformance/<provider>/<provider-api-version>/`; each fixture records: request, raw provider response/stream, expected neutral output (**golden response**), capture timestamp, provider-version identifier, and capture-tool version.
- **Provider-version tracking** — a `provider-versions.lock` manifest records the provider API version / model-endpoint version each fixture was captured against; the live suite reads the *actual* provider-reported version and **fails on unrecorded version change** (drift signal).
- **Golden responses** — the expected neutral output for each fixture; treated as code (reviewed, owned, diff-gated).

**§C.1.4 — Fixture refresh & freshness policy**
- **Refresh triggers:** (a) scheduled **weekly** live-full run detects drift; (b) provider announces/changes an API version; (c) adapter change; (d) **freshness window** exceeded.
- **Freshness window:** a provider's fixtures must have a **passing live-validation within 14 days** to be release-eligible; beyond that the provider is marked **stale** and blocks its own release track (not others).
- **No silent refresh:** fixtures are never auto-overwritten in place on a gating branch; regeneration is an explicit, reviewed change (§C.1.6).

**§C.1.5 — Drift detection**
- The **live suite** compares live provider behavior against recorded golden responses across CV-1…CV-8 using **tolerant assertions** (shape/schema/semantics/enum-membership, not byte-equality).
- A mismatch opens an automatic **drift ticket** tagged with provider, version, and failing CV-dimension, and **fails the scheduled conformance gate** (not unrelated PR builds).
- **Version drift** (provider-reported version ∉ `provider-versions.lock`) is itself a drift signal even if behavior appears unchanged.

**§C.1.6 — Golden-response regeneration & approval workflow**
1. Drift ticket (or intentional provider-version adoption) triggers regeneration.
2. Capture new fixtures/golden responses against the live provider in the isolated test tenant.
3. Open a PR containing the fixture diff + `provider-versions.lock` update; **CI re-runs Track 1 against the new goldens** and Track 2 (live) confirms.
4. **Approval:** provider-adapter CODEOWNER **and** a correctness/QA reviewer must approve (dual review; ties to `10 §16`); the diff must show **no neutral-contract change** unless a corresponding `12`-governed contract change (major) is referenced.
5. Merge updates the baseline; the drift ticket is closed with the version delta recorded.

**§C.1.7 — Failure handling**
- **Track 1 failure (PR/merge queue):** our regression → **blocks merge**; owner fixes mapping or, if the golden itself is wrong, follows §C.1.6.
- **Track 2 failure (scheduled):** provider drift → drift ticket + scheduled-gate red; **release track for that provider blocks** until reconciled; **other providers/PRs unaffected**.
- **Freshness/stale failure:** provider marked stale → its release track blocks until a passing live run; unrelated work proceeds.
- **Credential/cost/outage in live suite:** classified as **infrastructure-not-drift** (distinct signal), retried within budget; a sustained provider outage degrades to "last-known-good fixtures + explicit staleness flag," never a false "conformant."

### §C.2 — Golden Dataset Lifecycle & Approval *(resolves TM-4; expands T-016/T-061 — additive)*
- **Creation:** synthetic only (generator or hand-authored), **PII/PHI-free** (scanner-gated, T-057), tenant-neutral, with a declared purpose (correctness / conformance / streaming / regression).
- **Ownership:** each dataset has a CODEOWNER; datasets live under `testing/golden/<domain>/` and are versioned.
- **Change workflow:** any change is a reviewed PR showing the input/expected diff + rationale; **dual approval** (domain owner + correctness/QA) for correctness/conformance datasets; intentional drift must state *why the expected output changed* and reference the governing behavior/contract change.
- **Versioning & pinning:** datasets are semver-tagged; suites pin a dataset version so a dataset bump is a deliberate, reviewable event (no silent baseline shift).
- **Deprecation:** retired datasets are archived, not deleted, for audit reproducibility.
- **Enforcement / Build-Fail:** PII in a dataset (scanner); golden regression without an approved dataset-change PR; a suite referencing an unpinned/floating dataset.

---

## D. Reliability & Resilience

### T-017 — Retry & idempotency testing (`07 §13`, `12 §14`, `11` R-046/R-047)
- **Problem:** unsafe retries duplicate side effects. **Rule:** tests prove **idempotent consumers** (duplicate delivery → single effect), **idempotency-key** replay returns the same result without repeating side effects, **retry budget ≤ 10%** enforced (no storms), and **non-idempotent ops are never retried** without an idempotency guarantee. **Why:** effectively-once + no duplicate consequential actions (`NFR-RTY-001`). **Bad:** a test that only checks the happy path of a consumer. **Enforcement:** duplicate-delivery + replay tests. **Build-Fail:** duplicate side effect or retry-storm detected in test.

### T-018 — Failure injection (`03 §54`, `07`)
- **Rule:** systematic fault injection of **malformed/invalid structured output, mid-stream truncation/corruption, corrupted/partial tool calls, provider errors/timeouts/slowness, DB/cache/Kafka failures**; asserts **detection ≥ target, integrity, and explicit surfacing** (`03` NFR-SO/STRM/REL-003, BRULE-1). Runs in **CI (per-PR for correctness)**. **Why:** the product's core value must be proven under fault. **Enforcement:** fault-injection suite (mandatory). **Build-Fail:** any silent-incorrect-delivery or escape above budget under injection.
- **Scope of validation (test-vs-production limitation — TM-3):** these tests validate the **gateway's detection and handling** of malformed/incorrect model output — i.e., that the gateway *never silently delivers* incorrect output and always surfaces it. They do **not** and cannot validate the **models' own correctness or true production escape rate**, which is model-dependent and explicitly out of scope (`OOS-6`). The gateway guarantees *detection and surfacing*, not *model correctness*. Production escape behavior is observed operationally via telemetry (`14`), not asserted in CI; the fault-injection suite proves the *handling contract* holds for every injected fault class.

### T-019 — Chaos engineering (`03 §53`, Chaos Mesh/Litmus)
- **Rule:** **scheduled** chaos/game-days inject **node/zone/region loss, provider degradation/failure, dependency (DB/Kafka/cache) failure**; each defines steady-state hypotheses and verifies **SLOs hold or degrade gracefully** and **invariants never break** (`04 §56`, AD-017). Provider-failure chaos verifies **residency-safe failover** (`08 §16`). **Why:** untested resilience fails in real incidents. **Enforcement:** scheduled chaos pipeline; findings tracked. **Build-Fail (nightly gate):** SLO breach within the rated envelope or any invariant violation during chaos.

### T-020 — Resilience testing (graceful degradation)
- **Rule:** tests prove **fail-safe/graceful degradation** — control-plane outage → data plane on last-known-good (AD-022); cache/in-memory loss → fail-safe (rate limits conservative, cache miss → origin); backbone stall → WAL absorbs, fail-safe on saturation (`07` EV-D3); overload → shed low-priority, no corruption (`03` NFR-ST). **Why:** degradation must be bounded, safe, and legible (EP-10). **Enforcement:** resilience suite + chaos. **Build-Fail:** fail-open / corruption under degradation.

---

## E. Performance & Concurrency

### T-021 — Performance testing (`03 §51`, vs baselines)
- **Rule:** perf tests run against **`benchmarks/` baselines** and the **added-latency budget** (`06 §11`: P50 ≤ 5ms/P95 ≤ 20ms/P99 ≤ 50ms added; streaming first-token per NFR-PERF-002); **regression beyond threshold fails** (nightly gate). **Why:** low overhead is a product promise (`02` SLA-2). **Enforcement:** perf pipeline + baseline diff. **Build-Fail (nightly):** P99 added-overhead regression > threshold vs baseline.
### T-022 — Load testing (`03 §51`, Gatling/k6): sustain **rated** throughput 24h soak within SLOs. **Build-Fail:** SLO breach at rated load.
### T-023 — Stress testing (`03 §52`): 1.5–2× rated → graceful shedding, correctness preserved, clean recovery, **no data corruption**. **Build-Fail:** corruption/cascading failure under stress.
### T-024 — Spike testing: sudden 2×/5× surges within the elasticity envelope hold SLOs (`03` NFR-ELAS). **Build-Fail:** SLO breach on rated spike.
### T-025 — Soak testing (72h streaming): **no resource leak / memory growth / degradation** over sustained operation (`03` NFR-CONC-001, PRB-020). **Build-Fail (scheduled):** leak/growth beyond threshold.
### T-026 — Benchmark testing (JMH): microbenchmarks for hot-path components (correctness/streaming/crypto); baselines versioned in `benchmarks/`. **Build-Fail:** benchmark regression on a gated component.
### T-027 — Concurrency testing (mandatory for hot-path/shared) (`11` R-085)
- **Rule:** hot-path/shared components have **concurrency tests** (parallel load) asserting **no cross-request/tenant leakage** (`NFR-REL-002`, invariant) and **no data races**. **Why:** correctness under concurrency (TP-2, CP-3). **Enforcement:** concurrency suite + isolation detectors. **Build-Fail:** leakage/race detected.
### T-028 — Virtual Thread testing (AD-023)
- **Rule:** Loom-based code tested for **carrier pinning** (no blocking in `synchronized`, `11` R-049), correctness under high concurrency (many virtual threads), cancellation/backpressure, and no `ThreadLocal`-at-scale leaks. **Why:** the concurrency model is Virtual Threads. **Enforcement:** pinning detection (JFR) in perf tests + concurrency suite. **Build-Fail:** carrier pinning above threshold; VT concurrency correctness failure.

---

## F. Security Testing (`13 §27`)

### T-029 — Security testing (baseline)
- **Rule:** every PR runs **SAST** (SpotBugs/FindSecBugs + SAST), **secret scan**, **dependency/CVE**, **license**; staging runs **DAST** (OWASP ZAP). **Why:** shift-left security (`13 §23`). **Enforcement:** CI gates. **Build-Fail:** any high/critical security finding on changed code; secret detected; critical CVE; disallowed license.
### T-030 — Authentication testing: JWT validation (alg-confusion/`alg:none` rejection, iss/aud/exp/tenant-claim), mTLS, API-key hashing, token lifetime/rotation/revocation (`13 §6`). **Build-Fail:** an auth bypass or accepted `alg:none` in test.
### T-031 — Authorization testing (negative-first, BOLA): deny-by-default, object-level authz, **cross-tenant access impossible** (403/404 matrix `12 §19`), scope enforcement, field-level authz (`13 §7`). **Build-Fail:** a protected op without an authz negative test; any cross-tenant access succeeding.
### T-032 — Penetration testing (`13 §27`): ≥ annually + on major change by qualified parties; findings remediated per SLA (`13 §24`). **Build-Fail:** n/a (scheduled/process); overdue critical pentest finding gates release.
### T-033 — Fuzz testing: API/input fuzzing (`12`), event/schema fuzzing, streaming/correctness fuzzing (malformed provider output). **Why:** find edge-case crashes/leaks. **Enforcement:** fuzz pipeline (scheduled + PR smoke). **Build-Fail:** a crash/leak/unhandled path from fuzzing.
### T-034 — Prompt-injection / content-trust testing (`13 §11`, `14 §11`): a **maintained adversarial suite** (direct/indirect injection, jailbreak, tool-abuse, exfiltration) tracks a **reduction metric** (non-guaranteed, OOS-6); regressions flagged. **Build-Fail:** efficacy regression below the tracked baseline (adversarial gate).

---

## G. Advanced Techniques

### T-035 — Property-based testing (jqwik)
- **Rule:** parsers, validators, stream/tool-call reconstruction, idempotency keys, and value-object invariants are tested with **property-based** tests (generated inputs + invariants), not just examples. **Why:** finds edge cases examples miss (correctness core). **Good:** "for any fragmented JSON stream, reconstruction is valid-or-surfaced." **Enforcement:** jqwik suites on critical parsers/validators. **Build-Fail:** a property counterexample.
### T-036 — Mutation testing (PITest)
- **Problem:** high coverage with weak assertions proves little. **Rule:** **mutation testing** runs on critical modules; scope, cadence, and thresholds are governed by **§G.1**. **Why:** proves tests actually *detect* faults, defeating coverage-gaming (TP-3). **Enforcement:** PITest per §G.1. **Build-Fail:** mutation score below the module-criticality threshold (§G.1) on gated modules.

### §G.1 — Mutation Testing Policy *(resolves TH-3; expands T-036 — additive, keeps CI practical)*

**§G.1.1 — Critical modules (mutation-required).** These load-bearing modules MUST meet the mutation threshold; they map to the correctness/reliability/governance/security core (`05`, `11 §K`):

| Critical module | Maps to | Why mutation-critical |
|---|---|---|
| **SchemaLock** (structured-output enforcement) | `03` NFR-SO, T-013 | Silent-incorrect-delivery is the flagship risk (BRULE-1) |
| **StreamGuard** (streaming/tool-call integrity) | `12 §16`, T-012 | Truncation/partial-JSON must be detected, not delivered |
| **Provider Router** (routing/failover/neutralization) | AD-007, `05` C1, T-014/T-015 | Mis-routing / leak / bad failover breaks provider-neutral guarantees |
| **Retry Engine** (retry/idempotency/budgets) | `12 §14`, T-017 | Duplicate side effects / retry storms are consequential |
| **Security** (authn/authz/tenant-scope enforcement) | `13`, AD-021, T-030/T-031 | Bypass or cross-tenant leak is Sev1 |

**§G.1.2 — CI strategy (four tiers, so mutation never makes CI impractical).**

| Tier | When | Scope | Gate |
|---|---|---|---|
| **Incremental mutation** | Every PR touching a critical module | **Only mutated lines / changed classes** in that module (PITest incremental analysis + changed-classes scope) | **Blocks merge** if below threshold |
| **Changed-modules mutation** | Merge queue | Full mutation of any **critical module with changes** in the merge set | **Blocks merge** |
| **Nightly full mutation** | Nightly | **All five critical modules**, full | Fails nightly gate → drift ticket; blocks next RC |
| **Release full mutation** | Release candidate | All critical modules, full, on the RC commit | **Release-blocking** |
| *(Non-critical modules)* | Nightly (advisory) | Sampled/optional | Advisory only — never blocks a PR |

- **Practicality controls:** PR tier uses **incremental + changed-classes + history file** (skip already-killed mutants), **module-scoped** (never whole-repo on a PR), and a **per-PR time budget**; whole-codebase mutation is confined to nightly/RC. This keeps PR mutation cost proportional to the diff, not the codebase.

**§G.1.3 — Thresholds (by module criticality — see also §J.3 / T-054).**
- **Critical modules (the five above):** **mutation score ≥ 85%** on changed critical code (PR) and ≥ 85% full (nightly/RC).
- **Security module specifically:** **≥ 90%** (highest, ties to `13`).
- **Other modules:** advisory target ≥ 60%, non-blocking.
- All numbers are **initial calibration targets** to be tuned per §J.3 (TM-2) once real code exists; tightening is allowed, loosening a critical threshold requires a recorded exception (`13 §29`) and is never applied to the Security module.
- **Build-Fail:** critical-module changed code below its threshold (PR/merge queue); any critical module below threshold at nightly/RC.
### T-037 — Static analysis (`11 §I`): Error Prone/NullAway (compile), SpotBugs/FindSecBugs, PMD/Checkstyle, **ArchUnit (AU-01…12)**, Sonar quality gate — all build-failing on new code. **Build-Fail:** any of the above on changed code (`11 §I/§J`).
### T-038 — Dynamic analysis: DAST (§T-029), runtime profiling (pinning, leaks), race detection under load. **Build-Fail:** DAST high/critical; detected race/leak.
### T-039 — Regression testing: every fixed bug gets a **regression test**; regression suites gate releases; no re-introduction of a fixed defect. **Enforcement:** required regression test on bug-fix PRs. **Build-Fail:** bug-fix PR without a failing-then-passing regression test.

---

## H. E2E & Clients

### T-040 — End-to-end testing (thin, stable)
- **Rule:** e2e (`testing/e2e`) exercise **critical user journeys** against a real (staging/ephemeral) environment — auth → request → provider → correctness → response/stream → audit; kept **few, stable, deterministic** (no reliance on flaky external live providers on the gate — use recorded fixtures on the gate, live nightly). **Why:** proves the whole path without a brittle top-heavy pyramid. **Enforcement:** e2e pipeline. **Build-Fail:** e2e critical-journey failure.
### T-041 — UI testing (admin portal, `06` C13): component + e2e (Playwright-class) for the admin portal; **accessibility (WCAG 2.1 AA, `03` NFR-A11Y)** tested; tenant-safe views verified. **Build-Fail:** accessibility violation on new UI; a customer-facing panel leaking cross-tenant/provider data (`14 §14`).
### T-042 — SDK compatibility testing (`12 §26`): each SDK (Java/Python/TS/Go) tested against the contract + a **compatibility matrix** (language/runtime versions, `03` NFR-SDK); backward-compat verified. **Build-Fail:** SDK contract mismatch or compat-matrix regression.
### T-043 — Plugin compatibility testing (AD-004): plugins tested in the **sandbox** for **additive-only** behavior, **no bypass** of auth/governance/correctness/isolation, resource limits, and against the stable `gateway-plugin-api` version. **Build-Fail:** a plugin that bypasses a guarantee or crosses tenant boundary (sandbox test).

---

## I. Platform, Data & Ops

### T-044 — Multi-tenant isolation testing (invariant, AD-021)
- **Problem:** isolation is the highest-consequence invariant. **Rule:** dedicated isolation suites assert **zero cross-tenant leakage** of data/quota/cache/telemetry under **concurrency and load** (T-027), across DB (RLS + per-tenant keys + scope, `13 §15`), cache (`13 §16`), events, and telemetry (`14 §3`). Any leakage = test failure = would-be Sev1. **Why:** absolute isolation (CP-3). **Enforcement:** isolation suite + always-on detectors. **Build-Fail:** any cross-tenant leakage in test.
### T-045 — Residency testing (AD-014): data/telemetry/backup/failover **stay in permitted regions**; **failover never violates residency** (`08 §16`, `14 §18.1`). **Build-Fail:** a residency-constrained datum/telemetry crossing a non-permitted region in test.
### T-046 — Audit integrity testing (`08 §10`, `13 §19`): **completeness = 100%** (loss detector = 0, `RequestFinalized` manifest, `06 §23.11`), **tamper-evidence** (per-record signature + Merkle checkpoint verification), **immutability** (WORM), **crypto-shred erasure** verified (subject unrecoverable). **Build-Fail:** audit gap, tamper-evidence mismatch, or recoverable-after-erasure in test.
### T-047 — Backup & restore testing (`08 §16`, NFR-BAK-001): **quarterly test-restores** with integrity verification; encrypted/immutable backups; **key-version retention** verified (restore with old key; shredded stays shredded). **Build-Fail (scheduled):** a failed/integrity-invalid test-restore.
### T-048 — Disaster recovery testing (`08 §16`, NFR-DR-001): **quarterly DR game-days** meet tiered **RTO/RPO** (audit RPO=0); **coordinated multi-service restore** to a consistent recovery point (`08 §16 H4`); residency-safe. **Build-Fail (scheduled):** RTO/RPO target missed in a DR drill.
### T-049 — Upgrade & rollback testing (AD-015, `03` NFR-UPG): **zero-downtime rolling upgrade** + **automated rollback ≤ 5 min** on regression; backward compatibility preserved; data-plane runs on last-known-good during control-plane upgrade. **Build-Fail:** request-path downtime during upgrade (Enterprise/Regulated), or failed rollback in test.
### T-050 — Database migration testing (`08 §17`): **expand-then-contract**, backward-compatible, online, reversible; migrations applied in integration CI; **no breaking in-place change**. **Build-Fail:** out-of-order/edited applied migration; failing/irreversible migration; N+1 regression (`11` R-039).
### T-051 — Kubernetes deployment testing (`09`/`13 §21`): Helm charts **lint + install-test** on kind/ephemeral; **admission policies** (signed images, no-privileged, PSS-restricted, network-policy) verified; deployment models per tier. **Build-Fail:** chart install failure; admission-policy violation.
### T-052 — Telemetry & observability testing (`14`): **100% instrumentation** of request-path stages (spans/metrics), **correlation propagation**, **telemetry privacy** (no secrets/PII/content — allow-list + scanner, `14 §7.1`), **SLI↔metric** coverage, **exemplar chain**, and **plane separation** (no analytical label in Prometheus). **Build-Fail:** a request path without instrumentation; PII/secret/content in telemetry; SLO without a metric (`14 §19`).

### §I.1 — Multi-Region Testing Strategy *(resolves TM-5; expands T-045/T-048 — additive, states the CI-vs-environment boundary)*

Residency and multi-region behavior (AD-014, `08 §16`, `14 §18.1`) span three test tiers because **not every guarantee is CI-reproducible** — the boundary is stated explicitly so nothing is assumed-covered:

| Tier | Environment | What is validated | Cadence | Gate |
|---|---|---|---|---|
| **Tier 1 — Logical (CI, deterministic)** | Testcontainers / simulated regions (region-tagged stores/topics, config-driven region identity) | **Residency routing/confinement logic**: a residency-tagged datum/telemetry is *rejected/routed* correctly; **failover selects only permitted regions** (`08 §16`); cross-region correlation is **id-only** (`14 §18.1`); no residency-constrained payload crosses a boundary in the model | **Every PR** (affected) | **Blocking** |
| **Tier 2 — Multi-region staging** | Real multi-region ephemeral/staging topology (≥2 regions) | **Actual** cross-region behavior: replication scope, backup region confinement, coordinated multi-service restore point (`08 §16 H4`), telemetry residency in a real network | **Weekly + pre-release** | Release-blocking (RC) |
| **Tier 3 — DR / residency game-day** | Production-like multi-region | **RTO/RPO** under real region loss (audit RPO=0), **residency-safe failover** end-to-end, operator runbook | **Quarterly** (T-048) | Scheduled; overdue/failed drill blocks release |

- **Stated limitation:** true multi-region network behavior, real replication latency, and region-loss dynamics are **only verifiable in Tier 2/3 environments**, not in per-PR CI. Tier 1 proves the **logic** is correct and blocks merges; Tiers 2–3 prove the **deployed reality** and gate releases. A residency guarantee is considered fully verified only when its Tier-1 logic test **and** a within-window Tier-2/3 run are green.
- **Enforcement / Build-Fail:** Tier-1 residency-logic failure blocks the PR; a missing/failed Tier-2 residency run blocks the RC; an overdue Tier-3 DR/residency drill blocks release. **No residency invariant is ever waivable by exception (T-064).**

---

## J. Quality Gates & Process

### T-053 — Release acceptance criteria
- **Rule:** a release ships only when **all gates are green**: unit/integration/component + ArchUnit + contract/event/CDC + fault-injection (correctness) + security (SAST/secret/CVE/DAST) + coverage + mutation (critical) + **nightly perf/chaos/soak green** + isolation/residency/audit suites + migration/upgrade/rollback + K8s install + telemetry. Regulated releases add compliance sign-off (`13 §26`). **Why:** reliability-first release discipline. **Enforcement:** release pipeline (`10 §15`) + Release-Manager approval (`10 §16.10`). **Build-Fail:** any required gate red blocks release.

### T-054 — Coverage policy (floor, not goal) (`11` R-080)
- **Rule:** coverage floors and mutation scores are set **by module criticality** per the **§J.3 calibration table** (Critical/Security-critical ≥ 90%/85% line/branch; Standard ≥ 85%/80%; Support ≥ 80%/75%). Coverage is a **floor, not a target** — no test-for-coverage; **mutation testing (§G.1) guards against weak assertions**. **Why:** coverage without assertion quality is theater (TP-3). **Enforcement:** JaCoCo + Sonar + PITest. **Build-Fail:** new-code coverage below the module's §J.3 floor; mutation score below its §G.1 threshold.

### T-055 — Build-failing quality gates (summary)
| Gate | Fails when… |
|---|---|
| Unit/integration/component | any test red |
| ArchUnit (AU-01…12) | any boundary/layer/port/provider-SDK/cross-service-DB/controller-repo violation (`11 §J`) |
| Contract/event/CDC | spec/schema mismatch or incompatible change without major (`12 §27`, `07 §9`) |
| Fault-injection (correctness) | silent-incorrect-delivery / escape above budget (per-PR) |
| Coverage / Mutation | below floor / below mutation threshold on critical code |
| Security (SAST/secret/CVE/DAST/license) | any high/critical / secret / critical CVE / disallowed license (`13 §28`) |
| Isolation | any cross-tenant leakage |
| Telemetry privacy/instrumentation | PII/secret/content in telemetry; uninstrumented path (`14 §19`) |
| Flaky | a quarantined-flaky test on a required gate (T-062) |
| Perf/Chaos/Soak (nightly/scheduled) | regression/SLO breach/leak beyond baseline |

### T-056 — CI/CD testing workflow (`10 §15`)
- **Rule:** the pipeline order is fixed (`10 §15`): lint/format/ArchUnit → compile → unit+slice → static/security → **affected-module** integration (Testcontainers) → package/sign/SBOM/scan → deploy ephemeral/staging → e2e → **nightly** perf/soak, **scheduled** chaos/DR/pentest. **Affected-build** (`mvn -pl -am`) for speed; full on `main`/nightly. Merge queue serializes `main`. **Enforcement:** required checks (`10 §16`). **Build-Fail:** any PR-gate red. *(Full stage/gate matrix: §J.1.)*

### §J.1 — Shift-Left Test Staging & Promotion Gates *(resolves TH-2; expands T-056 — additive, no pipeline redesign)*

Six stages, each a **promotion gate**: code cannot advance until the prior stage is green. The rule that closes TH-2: **architecture, contracts, security, provider conformance, and critical performance regressions CANNOT reach `main` unnoticed** — each has a fast merge-time safeguard even when its heavy form is scheduled.

| Stage | Trigger | Tests that MUST run (blocking) | Promotes to |
|---|---|---|---|
| **1. Pre-commit** (local hook) | `git commit` | Format/lint, Checkstyle, **fast unit tests for changed modules**, secret scan (staged files) | Push allowed |
| **2. Pull request** | PR opened/updated | **ArchUnit (AU-01…12)**, full unit, **affected-module integration (Testcontainers)**, **contract/event/CDC diff**, **fault-injection (correctness)**, **incremental mutation on changed critical modules (§G.1)**, SAST/secret/CVE/license, coverage floor, **provider fixture conformance (Track 1, §C.1)**, **perf-smoke micro-gate** (hot-path JMH/short k6 vs baseline — see §J.2) | Merge queue |
| **3. Merge queue** | Enqueue to `main` | Re-run PR gates on the merged result + **changed-modules full mutation** (§G.1) + full affected integration; serialized | `main` |
| **4. Nightly** | Scheduled (daily) | **Full mutation** (5 critical modules), **full perf/load vs baseline**, **soak (short)**, **live provider smoke (Track 2, §C.1)**, DAST (staging), full e2e | RC eligibility |
| **5. Weekly** | Scheduled (weekly) | **Chaos/game-day (T-019)**, **endurance/72h soak (T-025)**, **large-scale load/stress/spike (T-022–024)**, **full live provider conformance matrix (Track 2)**, DR drill cadence | Trend/health |
| **6. Release candidate** | RC cut | **All gates green** + **release full mutation** + **provider freshness check (§C.1.4)** + isolation/residency/audit suites + migration/upgrade/rollback + K8s install + compliance sign-off (regulated) | Release (T-053) |

**§J.2 — Merge-time lightweight safeguards (why heavy-but-scheduled ≠ unguarded).**
Chaos, endurance, and large-scale load legitimately remain **scheduled** (stages 4–5) — they are too slow/expensive per-PR. But each leaves a **fast proxy at the PR gate** so a regression cannot merge silently:
- **Performance →** a **perf-smoke micro-gate** at PR: JMH microbenchmarks on hot-path components (SchemaLock/StreamGuard/Router) + a short k6 burst against an ephemeral instance, compared to `benchmarks/` baseline; **a >X% P99 hot-path regression blocks the PR** (X calibrated per §J.3). Full load/soak stays nightly/weekly.
- **Resilience →** **resilience unit/component tests** (T-020) run at PR (fail-safe degradation on injected dependency loss is deterministic and fast); full chaos stays weekly.
- **Architecture / contracts / security / provider conformance →** already **fully at PR** (ArchUnit, contract diff, SAST/secret/CVE, Track-1 conformance) — no scheduled-only gap exists for these four; the matrix above makes that explicit.

**§J.3 — Threshold calibration governance *(resolves TM-2 — additive).***
All numeric thresholds in this document — coverage floors (T-054), mutation scores (§G.1), perf-regression bounds (§J.2/T-021), flaky-rate SLO (T-062) — are **initial calibration targets**, set by module criticality and refined against real code:

| Criticality class | Modules | Line/Branch coverage | Mutation | Perf-regression tolerance |
|---|---|---|---|---|
| **Critical** | SchemaLock, StreamGuard, Provider Router, Retry Engine | ≥ 90% / ≥ 85% | ≥ 85% | tightest (hot-path gated) |
| **Security-critical** | Security (authn/authz/tenant scope), crypto | ≥ 90% / ≥ 85% | **≥ 90%** | n/a |
| **Standard** | domain/application services | ≥ 85% / ≥ 80% | ≥ 60% (advisory) | standard |
| **Support/adapters** | infra adapters, non-hot-path | ≥ 80% / ≥ 75% | advisory | relaxed |

- **Calibration process:** thresholds are reviewed at the first stable build and quarterly thereafter; **tightening** needs only owner review; **loosening** a Critical/Security-critical threshold requires a recorded, time-boxed exception (`13 §29`) and is **never** permitted for the Security module or any invariant/security gate (T-064).
- **Anti-thrash:** during the initial calibration window a threshold may run in **report-only (non-blocking)** mode for ≤ one milestone, explicitly labeled, before becoming blocking — this is a recorded governance decision, not a silent waiver.

### T-057 — Test data management
- **Problem:** prod data in tests = breach. **Rule:** tests use **synthetic, PII/PHI-free, tenant-scoped** data; **never production data/secrets** (`13 §20`); regulated-data tests use synthetic classified fixtures; data generated/seeded deterministically. **Why:** privacy + determinism (CP-3). **Enforcement:** secret/PII scanner on fixtures. **Build-Fail:** PII/secret/prod-data pattern in test fixtures.

### T-058 — Test environment strategy
- **Rule:** **ephemeral, reproducible** environments (Testcontainers per test; kind/ephemeral K8s for component/e2e); staging mirrors prod topology (`04`); environments are immutable and torn down. **No shared mutable test env** that causes cross-test interference. **Why:** parity + determinism. **Enforcement:** IaC/ephemeral provisioning. **Build-Fail:** test depending on a shared mutable external env.

### T-059 — Test naming conventions
- **Rule:** descriptive, behavior-oriented names: `should_<expected>_when_<condition>` (or `@DisplayName`); test classes `<Type>Test`/`<Type>IT` (unit vs integration, Surefire/Failsafe). No cryptic names. **Why:** tests are documentation. **Enforcement:** Checkstyle/review. **Build-Fail:** naming-convention violation (lint) for IT/UT suffix (drives Surefire/Failsafe split).

### T-060 — Mocking policy
- **Problem:** over-mocking tests the mocks, not the system. **Rule:** **mock ports/collaborators, use real backends via Testcontainers** for integration; **no mocking of value objects or the class under test**; no `PowerMock`-style deep mocking; provider externals stubbed via **recorded fixtures/WireMock** (deterministic), live only in scheduled suites. **Why:** test behavior, not implementation. **Bad:** mocking `PostgreSQL` behavior instead of using Testcontainers. **Enforcement:** review + ArchUnit. **Build-Fail:** banned deep-mock library present.

### T-061 — Golden dataset strategy (cross-ref T-016)
- **Rule:** golden datasets for correctness/conformance/streaming are **versioned, reviewed, synthetic, PII-free**; drift is deliberate and reviewed; each dataset has an owner. **Enforcement:** dataset changes CODEOWNER-gated + PII scan. **Build-Fail:** golden regression; PII in a dataset.

### T-062 — Flaky test policy (zero-tolerance on gates)
- **Problem:** flaky tests destroy trust in the whole suite. **Rule:** a test that fails non-deterministically is **quarantined within 24h** (off the blocking gate, tracked) and **fixed or deleted within one sprint** — **never** left flaky-and-blocking or silently `@Disabled` forever. A quarantined test on a required gate **fails the build** until resolved; **flaky rate is an SLO**. **Why:** a flaky gate is worse than no gate (TP-3). **Bad:** `@Disabled("flaky")` with no issue. **Enforcement:** flake-detector + quarantine registry + `@Disabled` requires an issue (`11` R-091 TODO policy). **Build-Fail:** `@Disabled`/`@Ignore` without a tracked issue; a stale quarantined test past its deadline.

**Flaky-detection workflow *(resolves TM-1 — additive):***
1. **Detect** — CI records per-test pass/fail history keyed by test id + commit; a test that yields **different results on the same commit** (retry-on-CI or the merge-queue re-run) or exceeds a **rolling flip-rate threshold** across recent runs is auto-flagged flaky. Detection is a CI capability (test-history/retry analysis in GitHub Actions + a results store), not a manual judgment.
2. **Quarantine (≤24h)** — the flagged test is tagged (`@Tag("quarantine")`), removed from the **blocking** gate, and auto-registered in the **quarantine registry** (`testing/quarantine.md` or tracker) with owner, first-seen, and a **hard deadline (one sprint)**. It still runs in a **non-blocking** lane so its flip-rate keeps being measured.
3. **Triage & fix** — owner root-causes (nondeterministic time/random → `11` R-063; ordering; shared state → T-058; real product race → T-027) and either fixes or deletes; a fixed test must demonstrate stability (N consecutive green in the non-blocking lane) before re-promotion to blocking.
4. **Escalate** — a quarantined test **past its deadline fails the build** (registry-driven gate), forcing fix-or-delete; the quarantine registry itself is CODEOWNER-reviewed so it cannot silently grow.
5. **Measure** — **flaky rate is a tracked SLO** (quarantined-count and mean-time-to-resolve reported in test analytics, TL-3); a rising trend triggers review. **No test may be silently `@Disabled` to dodge this workflow (T-063).**

### T-063 — No skipped/ignored tests without governance
- **Rule:** `@Disabled`/skip requires a tracked issue + expiry (like exceptions); no silently disabled tests. **Enforcement:** lint. **Build-Fail:** disabled test without issue reference.

### T-064 — Governance & exception process
- **Rule:** testing standards are owned by **Platform/SRE + context teams**; changes to gates/thresholds are reviewed; **exceptions** (e.g., temporarily lowering a threshold) require a **recorded, time-boxed, approved exception** (`10 §16`/`13 §29`) — never silent; invariant/security tests **cannot** be exception-waived. **Why:** gates erode silently otherwise. **Enforcement:** exception registry + review. **Build-Fail:** a gate disabled without a recorded exception.

---

## K. Reviews

### Traceability

| Testing area | BR | NFR | ADR | Coding (`11`) | API (`12`) | Sec (`13`) | Obs (`14`) |
|---|---|---|---|---|---|---|---|
| Unit/Integration/Component (T-005–007) | — | NFR-TEST-001 | AD-016 | R-080/081/082 | §30 | §27 | — |
| API/Contract/CDC (T-008–010) | BR-028 | NFR-IF/SDK/VER | AD-015 | R-084 | §27/§30 | — | — |
| Event contract (T-011) | BR-011 | NFR-AUD/Q | AD-005/009 | R-042 | — | §14 | §9 |
| Streaming (T-012) | BR-002 | NFR-STRM-001 | AD-016/018 | R-051 | §16 | §16 | §11 |
| Provider compat/conformance (T-014–015) | BR-006 | NFR-IF-001 | AD-007 | AU-06 | §16 | §12 | §10 |
| Retry/idempotency (T-017) | BR-004 | NFR-RTY-001 | AD-016 | R-046/047 | §14 | — | — |
| Fault injection (T-018) | BR-001 | NFR-SO/STRM/REL-003 | AD-018 | R-086 | §16 | — | — |
| Chaos/Resilience (T-019–020) | BR-003 | NFR-CHAOS/AV/DR | AD-016/017 | — | — | §25 | §17 |
| Performance suite (T-021–026) | BR-005 | NFR-PERF/LT/ST/CAP | AD-020 | R-054 | §29 | — | §15 |
| Concurrency/VT (T-027–028) | BR-005 | NFR-CONC/REL-002 | AD-023 | R-048/049/085 | — | — | — |
| Security testing (T-029–034) | BR-013/018/019/021 | NFR-SECT/SEC/AUTH/AUTHZ | AD-012/018/021 | R-067/071 | §23 | §27 | — |
| Property/Mutation (T-035–036) | BR-001 | NFR-SO | AD-016 | §I | — | — | — |
| E2E/UI/SDK/Plugin (T-040–043) | BR-028/029 | NFR-A11Y/SDK | AD-004/015 | — | §26 | §11 | §14 |
| Isolation/Residency (T-044–045) | BR-021/023 | NFR-REL-002/MR | AD-021/014 | R-040 | §19 | §15/§18 | §18.1 |
| Audit integrity (T-046) | BR-011/024 | NFR-AUD-001 | AD-009 | — | — | §19 | §8 |
| Backup/DR/Upgrade/Migration/K8s (T-047–051) | BR-003/031 | NFR-BAK/DR/UPG/DS | AD-014/015 | R-041 | — | §21 | — |
| Telemetry testing (T-052) | BR-010 | NFR-OBS/MET/TRC | AD-011 | R-055–059 | §24 | §20 | §7.1/§19 |
| Coverage/Gates/Flaky (T-053–064) | — | NFR-TEST-001 | AD-016 | §I/R-091 | §33 | §28 | §19 |
| Provider Conformance Framework (§C.1) | BR-006 | NFR-IF-001 | AD-007 | AU-06 | §12/§16/§16.10 | §12 | §10 |
| Golden Dataset Lifecycle (§C.2) | BR-006 | NFR-SO | AD-016 | — | — | §20 | — |
| Shift-Left Staging & Gates (§J.1–J.3) | — | NFR-TEST-001 | AD-016 | §I/§J | §27/§30 | §23/§28 | §19 |
| Mutation Testing Policy (§G.1) | BR-001 | NFR-SO | AD-016/007 | §I | — | §27 | — |
| Multi-Region Testing (§I.1) | BR-021/023 | NFR-MR/DR | AD-014 | — | §19 | §18 | §18.1 |

### Internal Consistency Review

| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| `11 §K` (R-080…086) | pyramid, JUnit5/Mockito/Testcontainers, contract/concurrency/fault-injection | A/B/E (T-003–007, 017–028) | ✅ |
| `11 §J` (AU-01…12) | ArchUnit boundaries are tests | T-037/§J | ✅ |
| `03 §50–55` | NFR-TEST/LT/ST/CHAOS/FI/SECT | D/E/F | ✅ |
| `03 §61–63` | SLOs/error budgets tested | T-002/§J | ✅ |
| `12 §27/§30` | contract/CDC/backward-compat/OpenAPI | T-008–010 | ✅ |
| `12 §16` | streaming contract + neutralization | T-012/T-014 | ✅ |
| `07 §9/§13/§14` | event compat, idempotency, DLQ | T-011/T-017 | ✅ |
| `08 §16/§17` | backup/DR/RPO-RTO, migrations | T-047–050 | ✅ |
| `08 §10/§14/§15` | audit integrity, crypto-shred, per-tenant keys | T-046 | ✅ |
| `09` | Testcontainers/JUnit5/Mockito/ArchUnit + tooling | throughout | ✅ |
| `13 §27/§28` | pentest/fuzz/security testing + build-fail | F/§J | ✅ |
| `14 §7.1/§19` | telemetry privacy + instrumentation tests | T-052 | ✅ |
| AD-021 | isolation tested to zero leakage | T-044 | ✅ |
| AD-014 | residency tested | T-045 | ✅ |
| AD-023 | Virtual Thread testing | T-028 | ✅ |
| AD-007/`12 §16.10` | provider neutralization tested | T-014/T-015/§C.1 | ✅ |
| AD-016 | reliability-first: prove guarantees | TP-1/T-002 | ✅ |

**No contradictions found.** The additive corrective pass (§C.1, §C.2, §G.1, §I.1, §J.1–J.3, and the TM-3 scope note) introduced **no new architecture, no ownership change, no consistency-model change, no security-model change, and weakened no invariant** — every addition is a testing procedure, cadence, or threshold that *verifies* an already-frozen guarantee. Verified against **00–14** and **AD-001…AD-023**: consistent.

### Architecture Validation
- **Modular monolith (`06 §10`, AD-020):** module boundaries are **tested** (ArchUnit AU-01…12, T-037) and each `dp-*` module is unit/component-testable in isolation — the tests are what keep the monolith modular. The §G.1 critical modules (SchemaLock/StreamGuard/Provider Router/Retry Engine/Security) name *logical* correctness/security concerns, not new services — no boundary or ownership change. ✅
- **Store-per-service (`08`, AU-07):** cross-service DB access is test-forbidden; component tests use each service's own store. ✅
- **Event-driven (`07`, AD-005):** event contracts + idempotency + DLQ + replay are contract/fault tested. ✅
- **Provider independence (AD-007):** the §C.1 two-track framework proves the neutral contract (CV-1…CV-8) without altering the adapter architecture. ✅
- **Residency (AD-014):** §I.1 states the CI-vs-environment boundary and keeps residency invariants non-waivable — confinement is *verified*, never redefined. ✅
- **Reliability invariants (`03` App. D):** each invariant (isolation/no-silent-delivery/audit/residency/non-exposure) has a dedicated zero-tolerance test (T-018/044/045/046/052); the TM-3 note sharpens the scope (gateway detection vs. model correctness, `OOS-6`) without weakening it. ✅
- No architectural contradiction; the test strategy remains a faithful, executable projection of the frozen architecture.

### Adversarial Review (re-run after corrective pass)

**Prior High findings — resolution**
- **TH-1 — Provider conformance strategy → RESOLVED (§C.1, §C.2).** Two tracks separated (deterministic CI fixtures vs. scheduled live), with the **C-INV** rule that *fixtures never replace live validation*; fixture versioning, `provider-versions.lock`, freshness window (14-day), drift detection, golden regeneration + dual-approval workflow, and per-track failure handling all specified; the eight validated dimensions (CV-1…CV-8: canonical request, canonical streaming, tool calls, structured output, usage accounting, error normalization, retry behavior, provider-neutral guarantees) enumerated.
- **TH-2 — Shift-left strategy → RESOLVED (§J.1–J.3).** Six mandatory stages (pre-commit → PR → merge queue → nightly → weekly → RC) with promotion gates; architecture/contracts/security/provider-conformance/critical-perf all gated **at or before merge**; chaos/endurance/large-scale load remain scheduled but each carries a **lightweight merge-time safeguard** (§J.2 perf-smoke micro-gate + resilience unit/component tests).
- **TH-3 — Mutation scalability → RESOLVED (§G.1).** Five critical modules named (SchemaLock, StreamGuard, Provider Router, Retry Engine, Security); four-tier CI strategy (incremental on PR → changed-modules on merge queue → nightly full → RC full); thresholds by criticality (≥85%, Security ≥90%); practicality controls (incremental + changed-classes + history + time budget) keep PR cost proportional to the diff.

**Prior Medium findings — resolution**
- **TM-1 → RESOLVED (T-062 flaky-detection workflow):** 5-step detect→quarantine→triage→escalate→measure, CI-history/flip-rate driven, deadline-gated registry.
- **TM-2 → RESOLVED (§J.3):** thresholds calibrated by criticality class; tighten-freely / loosen-by-exception (never for Security/invariants); report-only anti-thrash window.
- **TM-3 → RESOLVED (T-018 scope note):** gateway *detection & surfacing* is tested; model correctness / true escape rate is explicitly `OOS-6`, observed via telemetry, not asserted in CI.
- **TM-4 → RESOLVED (§C.2):** golden dataset creation/ownership/change/versioning/deprecation + dual approval.
- **TM-5 → RESOLVED (§I.1):** three-tier multi-region strategy with the CI-vs-environment boundary stated and residency invariants non-waivable.

**New findings from the re-run**

**🔴 Critical:** none.
**🟠 High:** none.
**🟡 Medium:** none blocking. (All numeric thresholds remain explicit **calibration targets** under §J.3 governance — this is a stated, governed design choice, not an open defect.)
**🟢 Low (deferred to later standards, non-blocking):**
- **TL-1 — Pact broker/versioning** operational details → tooling/CI standard (`16+`).
- **TL-2 — Concrete perf baseline numbers** → calibrated against real code in the performance/benchmark standard.
- **TL-3 — Test-suite analytics/reporting** (flaky-rate dashboards, mutation trends) → observability-of-CI tooling.
- **TL-4 — Exact SDK version matrix** → `26-Module-SDK.md`.
- **TL-5 — Concrete perf-smoke regression % (X in §J.2) and flip-rate threshold** → set during §J.3 first-build calibration.

### Readiness Score: **97 / 100**
All three High findings and all five Medium findings resolved additively, with no architecture, ownership, consistency-model, or security-model change and no invariant weakened. The provider-conformance framework, shift-left staging, mutation policy, flaky-detection workflow, golden-dataset lifecycle, and multi-region strategy are now fully specified and machine-enforceable. The residual 3 points reflect only **honest, governed calibration items** (thresholds pending real code) and deferred **Low** tooling details — none blocking, all explicitly owned by §J.3 governance or later standards.

### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`14` or AD-001…AD-023; no architectural, ownership, consistency, or security-model changes; no invariant weakened.

---

## Appendix
**A. Rule → tool quick map.** Unit: JUnit5/Mockito. Integration/component: Testcontainers. Architecture: ArchUnit. Contract/CDC: Pact + Spectral + Apicurio-compat. Provider: recorded fixtures/WireMock + live suites. Perf/load: Gatling/k6; benchmark: JMH. Concurrency: JCStress-class + injected load. Property: jqwik. Mutation: PITest. Security: SpotBugs/FindSecBugs/SAST + ZAP (DAST) + secret/CVE/license scanners. Chaos: Chaos Mesh/Litmus. Coverage: JaCoCo + Sonar. UI: Playwright-class + a11y.
**B. Relationship to other docs.** Verifies `11` (coding), `12` (API), `13` (security), `14` (observability); proves `03` NFRs/SLOs and `07`/`08` contracts; enforced via `10 §15/§16`. Does not modify frozen documents.
**C. Maintenance.** Frozen at v1.0. New rules carry the full template + a traceability row +, where possible, a build-failing gate. TP-1/TP-2/TP-3, the C-INV conformance invariant, and the invariant/security test gates are stable law; changes go through the §T-064 governance/exception process.

---

*End of document — 15-Testing-Standards.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
