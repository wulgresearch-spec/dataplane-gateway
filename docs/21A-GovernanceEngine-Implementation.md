# 21A — Governance Engine: Implementation Report

**Document:** Implementation report for the Enterprise Governance Engine
**Project:** Reliability-First AI Gateway
**Status:** Implemented — awaiting review
**Module:** `backend/dataplane/modules/gateway-dp-governance-engine`
**Implements:** `21-GovernanceEngine.md` (FROZEN, v1.0)
**Introduces:** no new module, no new service, no new store, no new ADR, no ownership change

This document reports what was built, how it works, what it does not do, and what remains blocked. It is
a report *against* the frozen `21`, not a replacement for it.

---

## 1. Architecture

```
                         C4 CONTROL PLANE (authors policy — not this module)
                                        │
                                        │ versioned bundles
                                        ▼
  ┌──────────────────────────── gateway-dp-governance-engine ────────────────────────────┐
  │                                                                                       │
  │  api/                     the contract: what governance can say and what it answers   │
  │    PolicyScope  PolicyScopeRef  ScopeChain                                            │
  │    PolicyType   PolicyDomain    PolicyKind    PolicyValue    EnforcementLevel         │
  │    PolicyRule   GovernancePolicy  PolicyVersion                                       │
  │    PolicyRequest  PolicyUsage                                                         │
  │    PolicyDecision  PolicyViolation  Verdict  ResolvedPolicyContext                    │
  │    SimulationResult  SimulationImpact  PolicyCompilationException                     │
  │    ports: PolicySourcePort  PolicyUsagePort  PolicyAudit  PolicyMetrics  TickerPort   │
  │                                                                                       │
  │  domain/                  the policy model and the pure decision core                 │
  │    CompiledPolicy ──► EffectivePolicy ──► PolicyEvaluator ──► PolicyDecision          │
  │    PolicyMerge (the hierarchy algebra)     PolicySnapshot     ResolvedRule            │
  │    UsageLookup                                                                        │
  │                                                                                       │
  │  internal/                installation, storage and memoisation                       │
  │    PolicyCompiler ──► PolicySnapshot ──► PolicyStore ──► PolicyRegistry               │
  │                                             │  └─ PolicyCache (per generation)        │
  │    PolicyLoader (hot reload)                └─ bounded rollback history               │
  │    InProcessPolicyMetrics                                                             │
  │                                                                                       │
  │  application/             orchestration, audit, and the enforcement identity          │
  │    GovernanceEngine  ── implements GovernancePort (the frozen pipeline seam)          │
  │    PolicySimulationEngine ── same evaluator, zero side effects                        │
  └───────────────────────────────────────────────────────────────────────────────────────┘
                    │                                          │
                    ▼                                          ▼
             PolicyAudit → C10 Audit                  PolicyMetrics → C9 Observability
```

Layering is one-directional where it matters: `api` and `domain` never reference `internal`; nothing
outside `application` references `application`. `api` and `domain` do reference each other, by design —
the ports carry the domain's value objects — which is why the ArchUnit rules assert direction rather
than acyclicity (see `GovernanceArchitectureTest`).

---

## 2. Runtime flow

The pipeline shape is unchanged. `RequestPipeline` was not modified.

```
INGRESS → AUTHN → GOVERNANCE → ROUTER → RELIABILITY → SECRETS → PROVIDER → …
                      │
                      └─ GovernancePort.authorize(...)  ← the only call site; no bypass path exists
```

Inside `GOVERNANCE`, for one request:

```
1. read the clock                             ClockPort           (fails → Instant.EPOCH, decision continues)
2. is any generation installed?               PolicyStore         (no → DENY policy-not-found)
3. resolve the effective policy               PolicyRegistry      (volatile read + cache lookup, no lock)
     ├─ cache hit  → the memoised fold
     └─ cache miss → PolicySnapshot.effectiveFor(chain)  = fold the ≤9 documents on the chain
4. evaluate                                   PolicyEvaluator     (one pass over 31 policy types)
     └─ consumption reads, memoised per node  PolicyUsagePort     (unknown/stale → the cap refuses)
5. assemble the decision                      PolicyDecision      (verdict, violations, version, latency)
6. publish                                    PolicyMetrics, PolicyAudit  (both failure-isolated)
7. return                                     never null, never throws
```

Steps 6 and 7 cannot change each other's outcome: an audit sink that throws and a metrics backend that
throws are both caught, because an audit outage becoming an admission outage trades a record-keeping
failure for a traffic failure and gets neither right.

---

## 3. Policy hierarchy

```
  GLOBAL              PolicyScopeRef(GLOBAL, "-")            platform guards
    │
  ORGANIZATION        the outermost tenant boundary
    │
  WORKSPACE
    │
  PROJECT
    │
  ENVIRONMENT         production / staging
    │
  API_KEY             key-level least privilege
    │
  USER  /  SERVICE_ACCOUNT
    │
  REQUEST             caller self-restriction only  — declared, NOT enforced (§11.10)

  merge direction ────────────────────────────────────────────►  strictly tightening
```

A request's address in this hierarchy is a `ScopeChain`: an ordered, strictly-increasing list of the
nodes that govern it, always beginning at `GLOBAL`. Levels a request does not populate are simply absent
and contribute nothing — which is safe precisely because contributing nothing is the neutral element of
a most-restrictive-wins merge.

**`REQUEST` is declared but not enforced by the shipped runtime.** The level exists in
`PolicyScope` and `ScopeChain.Builder` can express it, but no code path in this build ever puts a
`REQUEST` node into a chain. A document authored against it compiles, installs and is stored, and
then contributes nothing, because the fold visits only the refs a chain actually contains. Such a
document is still accepted — refusing it would break a deployment that already publishes one — but
it is no longer silent: each generation put in force carrying one increments
`PolicyMetrics.unenforceableScope(REQUEST)`. Treat the level as reserved. See §11.10.

**Precedence tiers** (`PolicyDomain`, declaration order = evaluation order):

| # | Tier | Hard? | Policy types |
|---|---|---|---|
| 1 | SECURITY | yes | `EMERGENCY_KILL_SWITCH` |
| 2 | TENANT | yes | `TENANT_SUSPENSION`, `MAINTENANCE_WINDOW` |
| 3 | COMPLIANCE | yes | `COMPLIANCE_MODE`, `PII_RESTRICTION` |
| 4 | RESIDENCY | yes | `REGION_RESTRICTION` |
| 5 | AUTHORIZATION | yes | `PROVIDER_ALLOW/DENY_LIST`, `MODEL_ALLOW/DENY_LIST`, `TOOL_ALLOW/DENY_LIST` |
| 6 | CAPABILITY | no | `STREAMING_ALLOWED`, `STREAMING_REQUIRED`, `JSON_MODE_REQUIRED`, `REASONING_ALLOWED`, `VISION_ALLOWED`, `IMAGE_GENERATION_ALLOWED`, `AUDIO_ALLOWED`, `EMBEDDING_ALLOWED`, `FINE_TUNING_ALLOWED`, `BATCH_ALLOWED` |
| 7 | QUOTA | no | `MAX_CONTEXT`, `MAX_OUTPUT_TOKENS`, `MAX_REQUESTS`, `MAX_RPM`, `MAX_TPM`, `CONCURRENCY_LIMIT` |
| 8 | BUDGET | no | `MAX_COST`, `DAILY_BUDGET`, `MONTHLY_BUDGET` |

31 policy types, matching the mission list exactly. **Hard tiers are non-demotable**: `PolicyCompiler`
refuses to compile a hard-tier rule authored as advisory or shadow. That refusal is where "a feature flag
can never turn off compliance" stops being a promise in a document — the dangerous configuration is not
expressible, so no evaluation path has to defend against it.

---

## 4. Evaluation algorithm

```
evaluate(request, effectivePolicy, usageLookup, now, ticker):
    start      ← ticker.nanos()
    violations ← []
    worst      ← ALLOW

    for type in PolicyType.values():                  # fixed order, 31 iterations, no allocation
        rule ← effectivePolicy.ruleFor(type)          # array read, indexed by ordinal — O(1)
        if rule is absent: continue

        outcome ← check(type, rule, request, usageLookup, now)
        if outcome is SATISFIED: continue

        reason    ← (outcome is UNENFORCEABLE) ? POLICY_UNAVAILABLE : type.denialReason()
        violation ← (type, rule.source().scope(), rule.ruleId(), rule.enforcement(), reason)
        violations.append(violation)
        worst ← max(worst, rule.enforcement().verdict())

        if worst is DENY: break                       # deny-overrides short-circuit

    latency ← ticker.nanos() − start
    context ← (worst is DENY) ? denied() : resolve(request, effectivePolicy, now)
    return PolicyDecision(worst, reasonOf(worst, violations), violations, version, latency, context)
```

**The verdict is a maximum, and maximum is commutative.** Precedence order therefore changes only
*which* violation is reported and *how early* the loop stops — never what is decided. The short-circuit
on the first mandatory refusal is safe for exactly that reason: nothing later in the loop can be more
severe than a refusal.

**The verdict ladder** (`Verdict`, ascending severity):

| Verdict | Admits? | Produced by | Meaning |
|---|---|---|---|
| `ALLOW` | yes | no violation | nothing objected |
| `DRY_RUN` | yes | a `SHADOW` rule | would have refused; recorded so the blast radius is measurable before promotion |
| `SOFT_DENY` | yes | an `ADVISORY` rule | breached a soft cap; recorded, surfaced as an obligation, audited |
| `DENY` | **no** | a `MANDATORY` rule | refused; never reaches the Router |

Two of the four admit. That is not a hole in deny-by-default: both can only be produced by a rule the
control plane explicitly authored as non-mandatory, on a tier that permits it, and both carry their
violations into the decision, the caller's obligations and the audit trail. **Nothing is ever admitted
silently** — which is the actual content of GV-INV.

**Every uncertainty refuses.** No generation installed, an unknown consumption counter, a reading past
its freshness tolerance, a collaborator that throws: all produce a refusal. Admitting against a cap the
engine cannot see is, from outside, indistinguishable from having no cap.

---

## 5. Merge algorithm

Per `PolicyKind`, applied left-to-right down the chain:

| Kind | Combinator | Consequence |
|---|---|---|
| `ALLOW_LIST` | **intersection** | a child may only narrow what its parent permitted |
| `DENY_LIST` | **union** | a denial added anywhere sticks |
| `CAPABILITY` | **logical AND** | once off, no descendant turns it back on |
| `REQUIREMENT` | **logical OR** | once required, required below |
| `PROHIBITION` | **logical OR** | any scope may pull the cord; none may un-pull it |
| `CEILING` | **minimum** | the tightest bound anywhere wins |
| `WINDOW` | **union** | more blocked time is the restrictive direction |
| *(enforcement)* | **strictest** | a child cannot downgrade an inherited mandatory rule |

Every combinator is **idempotent, commutative, associative and monotonically non-loosening**. Together
those four properties make the merge a *meet* on a semilattice of policies, and the effective policy a
plain fold. That is what makes the hierarchy unambiguous as an algebraic fact rather than a convention:
the result cannot depend on the order the chain was walked, on which intermediate scopes happened to be
populated, or on a scope appearing more than once. `PolicyMergeTest` asserts all four properties
exhaustively over every kind.

The enforcement rule is the load-bearing one for security. Without "strictest wins", write access to any
leaf scope would be enough to neutralise every inherited control by re-declaring it in shadow.

**Attribution.** A merged value is attributed to the parent when the parent's value survived unchanged,
and to the child otherwise. That is what lets a violation say *the binding statement is `org-policy-7`
at the organization node* rather than merely *a model allow-list refused you*.

---

## 6. Thread model

| Concern | Mechanism |
|---|---|
| Reading policy | one volatile read of `AtomicReference<Generation>` → a deeply immutable object graph |
| Locks on the request path | **none** |
| Snapshot replacement | `compareAndSet` publishing `{snapshot, cache}` as one indivisible unit |
| Cache invalidation | structural — a new generation carries a new cache, so there is nothing to invalidate |
| Fold cache | `ConcurrentHashMap.get` → miss computes *outside* the map → `putIfAbsent` |
| Per-request state | a local `HashMap` memo that never escapes; nothing shared across requests |
| Rollback history | a small `synchronized` deque, touched only off the request path |
| Virtual threads | no `synchronized` on the hot path, so no pinning (Doc 11 R-049, Doc 21 §29) |

`computeIfAbsent` is deliberately **not** used for the fold cache: it holds a bin lock across the
computation, and a `synchronized` block on the request path is precisely what pins a virtual thread.
Two threads racing on the same cold chain may both compute the fold; that is harmless, because the fold
is a pure function and both compute the same value.

A snapshot swap is invisible to in-flight evaluations — they finish against the generation they started
on, which is also why the version each decision stamps is a true statement about what governed it.
`GovernanceConcurrencyTest` exercises this with 100 concurrent virtual threads, with installs and
rollbacks interleaved into the traffic.

---

## 7. Performance analysis

| Step | Cost | Allocation |
|---|---|---|
| Generation read | 1 volatile read | none |
| Fold cache hit | 1 hash lookup on a precomputed hash | none |
| Fold cache miss | ≤9 hash probes + ≤9×31 array merges | one `EffectivePolicy` (once per chain per generation) |
| Evaluation | 31 array reads + ≤31 typed comparisons | one `ArrayList(2)` |
| Consumption reads | ≤1 per distinct binding node, memoised | one small `HashMap` |
| Decision assembly | 1 record + 1 context record | 2 |

**O(1) in the size of policy.** Cost is bounded by the number of policy *types* — a fixed 31 — not by
how many documents an organization has authored. A customer with a thousand policy documents evaluates
in the same time as one with three, because the hierarchy is folded away at install time and the fold is
memoised per distinct scope chain.

**No parsing, no reflection, no scripting on the request path.** Authored policy is translated into
ordinal-indexed arrays once, at install. `PolicyValue` is a sealed hierarchy switched on by pattern
match, so the compiler proves the switches exhaustive; there is no expression tree to walk and no rule
list to scan.

`ScopeChain` precomputes its hash because it is hashed on every request and a record would recompute it
from a nine-element list each time.

**Not measured.** No JMH benchmark was run — the benchmark harness (Doc 21 GVL-2) is out of scope for
this milestone and, more practically, Maven cannot resolve dependencies in this environment (§11). The
analysis above is a structural argument about operation counts, not a latency measurement, and should
not be quoted as one.

---

## 8. Files created

**Main — 42 files, `gateway-dp-governance-engine`**

`api/` (26): `PolicyScope`, `PolicyScopeRef`, `ScopeChain`, `PolicyDomain`, `PolicyKind`, `PolicyType`,
`EnforcementLevel`, `Verdict`, `PolicyValue`, `PolicyRule`, `GovernancePolicy`, `PolicyVersion`,
`PolicyRequest`, `PolicyUsage`, `PolicyDecision`, `PolicyViolation`, `ResolvedPolicyContext`,
`PolicyAuditEvent`, `PolicyAudit`, `PolicyMetrics`, `PolicySourcePort`, `PolicyUsagePort`, `TickerPort`,
`PolicyCompilationException`, `SimulationResult`, `SimulationImpact`

`domain/` (7): `CompiledPolicy`, `EffectivePolicy`, `PolicySnapshot`, `PolicyMerge`, `PolicyEvaluator`,
`ResolvedRule`, `UsageLookup`

`internal/` (7): `PolicyCompiler`, `PolicyStore`, `PolicyCache`, `PolicyRegistry`, `PolicyLoader`,
`InProcessPolicyMetrics`, `package-info`

`application/` (2): `GovernanceEngine`, `PolicySimulationEngine`

**Tests — 11 files**

`PolicyFixture`, `PolicyMergeTest`, `PolicyEvaluatorTest`, `PolicyHierarchyTest`, `PolicyLifecycleTest`,
`GovernanceEngineTest`, `PolicySimulationEngineTest`, `GovernanceConcurrencyTest`,
`GovernanceInvariantsTest`, `GovernanceArchitectureTest`, and in `gateway-dp-app`:
`PolicyEngineCompositionTest`.

**Docs:** this file.

## 9. Files modified

| File | Change |
|---|---|
| `gateway-dp-governance-engine/pom.xml` | added `<mutation.threshold>90</mutation.threshold>` (GV-A15: Security-critical module) |
| `gateway-dp-app/…/runtime/GatewayRuntimeConfig.java` | added `PolicyEngineConfig`; added `Optional<PolicyEngineConfig>` to `GovernanceConfig` **plus a 6-arg constructor preserving the previous shape**, so no existing composition breaks |
| `gateway-dp-app/…/runtime/GatewayRuntime.java` | `governanceEngine` field widened to `GovernancePort`; construction extracted to `constructGovernance(...)`, which builds the hierarchical engine when configured and the previous evaluator otherwise; added `policyEngine()` accessor for reload/rollback/simulate |

**`RequestPipeline` was not touched.** Both implementations satisfy `GovernancePort`, so the
GOVERNANCE stage is bound either way and no configuration produces a node serving traffic with the gate
absent.

---

## 10. Test results

```
gateway-dp-governance-engine   376 new tests   (405 including the 29 pre-existing)
gateway-dp-app                  12 new composition tests
whole backend                 1313 tests, 98 containers — all passing
```

Coverage against the mission's required list:

| Required | Where |
|---|---|
| allow / deny | `PolicyEvaluatorTest` — every one of the 31 types, both directions |
| hierarchical inheritance | `PolicyHierarchyTest` — inherit, tighten, cannot widen, 4-level chains |
| conflicting policies | `PolicyHierarchyTest` — deny beats allow, compliance intersection, advisory vs mandatory |
| budget exceeded | `PolicyEvaluatorTest` — per-request, daily, monthly, exact-boundary, overflow-near-`Long.MAX_VALUE` |
| token exceeded | `PolicyEvaluatorTest` — context, output, TPM counting context+output |
| provider disabled | `PolicyEvaluatorTest` — allow-list excludes all, deny-list removes all, partial survival admits |
| model disabled | `PolicyEvaluatorTest` — allow-list, deny-list, deny beats allow |
| tool disabled | `PolicyEvaluatorTest` — allow-list, deny-list, empty ask |
| stream denied | `PolicyEvaluatorTest` — `STREAMING_ALLOWED` false, `STREAMING_REQUIRED` unmet |
| json required | `PolicyEvaluatorTest` — unmet requirement refuses; met requirement resolves into the context |
| simulation | `PolicySimulationEngineTest` — past / current / candidate, what-if, batch impact, zero side effects |
| hot reload | `PolicyLifecycleTest`, `PolicyEngineCompositionTest` — tighten a running node, next request feels it |
| rollback | `PolicyLifecycleTest`, `PolicyEngineCompositionTest` — restore, bounded history, unknown target refused |
| audit emission | `GovernanceEngineTest` — every decision incl. admissions; sink failure isolated |
| parallel evaluation | `GovernanceConcurrencyTest` |
| 100 concurrent requests | `GovernanceConcurrencyTest` — 100 virtual threads, released together |
| determinism | `PolicyEvaluatorTest` (25× identical), `GovernanceConcurrencyTest` (100 threads → 1 distinct decision) |

---

## 11. Honest limitations

1. **Maven could not run.** Maven Central is unreachable from this environment — TLS interception
   produces `PKIX path building failed`, and the local repository has never held `spring-boot-dependencies`
   or `testcontainers-bom`, so the parent POM will not even resolve. Everything above was verified by
   compiling all 533 main sources and 95 test sources with `javac` and running them on the JUnit 5
   platform directly. **What this means: Checkstyle, SpotBugs, ErrorProne, Spotless, forbidden-apis,
   JaCoCo and PITest have not run against this code.** The 90% mutation threshold is declared, not
   demonstrated.

2. **`GovernanceArchitectureTest` has never executed.** ArchUnit is not in the local repository. Every
   rule in it was verified by hand with `grep` and each rule mirrors the exact API usage of
   `CostArchitectureTest`, which does run in CI — but the file itself is unproven. Treat a failure there
   on first CI run as expected-cost, not as a surprise.

3. **The frozen `GovernancePort.authorize` façade cannot enforce model, tool or capability policy.**
   Its signature carries only request, principal and tenant context — no model, no tools, no token
   counts, no projected spend. On that path those attributes are absent and the policy types comparing
   against them contribute no violation. The façade *does* enforce kill switches, tenant suspension,
   maintenance windows, residency and every consumption-based cap. `RequestPipeline` currently calls only
   this façade, so **model/tool/provider/capability/token/cost policy is implemented and tested but not
   yet reached by live HTTP traffic.** Closing this is the next milestone (§13). The alternative —
   substituting placeholder values — would either refuse every request or admit past a real allow-list,
   so enforcing exactly what is visible and saying so is the honest option.

4. **Consumption counters are read-only here.** The engine consumes a `PolicyUsagePort`; it does not
   perform the atomic compare-and-increment of Doc 21 GC-2, because the shared in-memory tier that owns
   that operation is not part of this module. Until an adapter supplies it, concurrent requests do not
   see each other's in-flight consumption, so a burst can over-admit against a rate cap by up to the
   burst size. The direction of the error is bounded and the accounting is still C5's, but this is a
   real gap, not a rounding detail.

5. **Latency is a mean and a max, not a histogram.** `InProcessPolicyMetrics` keeps a running total and
   a maximum. That yields no p99. A real deployment should bridge `PolicyMetrics` onto the platform's
   histogram support; this implementation exists so a node without one reports something true rather
   than nothing.

6. **The fold cache is bounded and not LRU.** Past its capacity it stops admitting entries and those
   chains fold on every request. Correct, slower for the tail. An LRU would need either a lock or a
   concurrent queue on the hot path, which costs more than the misses it would save for the common case
   of a small, stable set of chains.

7. **No JMH benchmark.** §7 is a structural argument about operation counts, not a measurement.

8. **Simulation reads live consumption counters.** A simulated decision against a usage-based cap
   reflects consumption *now*, not consumption at the time the sampled request originally ran. For
   policy-shape questions this is irrelevant; for "would last Tuesday's traffic have breached this
   budget" it is misleading.

9. **`GovernanceEngineService` (the previous evaluator) is retained and still the default.** Two
   enforcement implementations now exist. That is deliberate — it kept this change non-breaking and
   let the whole existing suite stay green — but it is duplication that should not survive long.

10. **`PolicyScope.REQUEST` is declared but never constructed.** The policy model offers the level
   and the compiler accepts documents attached to it; nothing in this build ever builds the node, so
   such a document is stored and never folded into any effective policy. This is a semantics gap,
   not an enforcement bypass: a document can only ever *tighten*, so its absence loses a restriction
   the operator asked for rather than granting access. The gap is now observable —
   `PolicyMetrics.unenforceableScope(PolicyScope)` fires once per installed generation that carries
   one, and `InProcessPolicyMetrics.unenforceableScopes(scope)` reads it back — but it is not
   closed. Closing it means deciding what a caller-supplied restriction may say and how it is
   authenticated, which is a design question rather than a defect fix. `UnenforceableScopeTest`
   pins the current behaviour: accepted, stored, counted, not enforced.

---

## 12. Remaining blockers

| # | Blocker | Owner | Impact |
|---|---|---|---|
| B1 | Maven cannot resolve dependencies (TLS interception on Maven Central) | environment / infra | no static analysis, no mutation testing, no CI-equivalent verification locally |
| B2 | ArchUnit jar absent locally | follows B1 | `GovernanceArchitectureTest` unproven |
| B3 | No shared in-memory tier adapter for atomic per-(node, key) increment | platform | limitation 4 — no in-flight consumption visibility |
| B4 | `RequestPipeline` carries no model/tool/capability into GOVERNANCE | this repo, next milestone | limitation 3 — most policy types unreachable from live traffic |
| B5 | No control-plane policy source adapter exists | C4 control plane | `PolicySourcePort` has no production implementation; operators must supply one |

B1 and B2 are environmental and gate *verification*, not correctness. B3, B4 and B5 are real work.

---

## 13. Recommended next milestone

**Widen the governance seam so the pipeline can ask the full question.**

Everything else is downstream of B4. The engine can already enforce all 31 policy types across every
scope it constructs (`REQUEST` is declared but never constructed, §11.10); live traffic currently
reaches roughly a third of them because the frozen façade cannot carry
the request's shape. The work, in order:

1. Extend the data-plane governance seam so `RequestPipeline` builds a `PolicyRequest` from the inbound
   canonical request — model, tools, capability flags, token counts, projected spend from the Cost
   Engine, and the `ScopeChain` from the authenticated tenant plus the API-key identity. This is a
   contract change at a frozen seam and needs its own review against Doc 21 §36.1 before code.
2. Have the Router consume `ResolvedPolicyContext` as hard filters, honouring RGC-5 (narrow only, never
   widen) and RGC-8 (unknown field ⇒ fail closed).
3. Supply a `PolicyUsagePort` adapter over the shared in-memory tier with atomic compare-and-increment,
   closing B3.
4. Retire `GovernanceEngineService` once the full engine is the default on every deployment.

Two things worth doing sooner because they are cheap: resolve B1 so the quality gates can actually run,
and add the JMH benchmark set so §7 becomes a measurement.

---

*End of document — 21A-GovernanceEngine-Implementation.md*
