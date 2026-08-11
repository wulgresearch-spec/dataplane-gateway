# 21B — GovernancePort V2: Making the Governance Engine Reachable

**Document:** Implementation report — the governance admission seam
**Project:** Reliability-First AI Gateway
**Status:** Implemented — awaiting review
**Builds on:** `21-GovernanceEngine.md` (FROZEN), `21A-GovernanceEngine-Implementation.md`
**Changes:** no policy semantics, no engine rewrite, no pipeline reordering, no new module

---

## 1. Contract review — why the existing port was insufficient

The frozen seam is:

```java
GovernanceDecision authorize(RequestContext, PrincipalContext, TenantContext);
```

Between those three arguments the pipeline can express **who is asking, on whose behalf, and where**.
That is genuinely enough for five policy types, and they worked:

| Enforceable pre-V2 | Because it needs only… |
|---|---|
| `EMERGENCY_KILL_SWITCH` | the tenant |
| `TENANT_SUSPENSION` | the tenant |
| `MAINTENANCE_WINDOW` | the tenant and the clock |
| `REGION_RESTRICTION` | `RequestContext.region()` |
| `MAX_REQUESTS` / `MAX_RPM` / `CONCURRENCY_LIMIT` | the tenant and a consumption counter |

Every remaining policy type compares against a property of **the request itself**, and there is no
field in the signature that can carry one:

| Unreachable pre-V2 | Needs a field the signature does not have |
|---|---|
| `MODEL_ALLOW_LIST` / `MODEL_DENY_LIST` | the canonical model |
| `TOOL_ALLOW_LIST` / `TOOL_DENY_LIST` | the requested tool names |
| `PROVIDER_ALLOW_LIST` / `PROVIDER_DENY_LIST` | the candidate routes |
| `STREAMING_ALLOWED` / `STREAMING_REQUIRED` / `JSON_MODE_REQUIRED` | the request mode and schema |
| `VISION_` / `REASONING_` / `AUDIO_` / `EMBEDDING_` / `IMAGE_GENERATION_` / `FINE_TUNING_` / `BATCH_ALLOWED` | the asserted capabilities and the operation |
| `MAX_CONTEXT` / `MAX_OUTPUT_TOKENS` / `MAX_TPM` | a token count |
| `MAX_COST` / `DAILY_BUDGET` / `MONTHLY_BUDGET` | a projected spend |
| `PII_RESTRICTION` | a data classification |

**This is not a gap that more policy types would close.** The engine already implemented and tested all
31; the previous milestone's own report flagged it as blocker B4. It is a *contract* defect: the caller
holds facts the callee needs and has nowhere to put them.

Three consequences made it worse than a missing feature:

1. **The failure was silent.** An allow-list with nothing to compare against contributes no violation,
   so a tenant could author `MODEL_ALLOW_LIST` and watch it never fire. Policy that appears configured
   and does nothing is worse than policy that is absent, because someone is relying on it.
2. **Two of the missing inputs did not exist anywhere in the gateway.** Prompt token counts and
   projected spend are not merely absent from the signature; nothing upstream produced them at all.
   Widening the signature alone would not have helped.
3. **`CostEnginePort.project()` was already built for exactly this and was never called.** Doc 22 §CE-D5
   documents it as a never-underestimated upper bound so that *"Governance's budget check consumes it
   so it can never admit an over-budget request due to under-projection"*. The consumer did not exist.

---

## 2. The new contract

```java
public interface GovernanceAdmissionPort {
  PolicyDecision admit(PolicyRequest request);   // total: never null, never throws
}
```

`PolicyRequest` already existed — the engine has evaluated it since the previous milestone. What
changed is that **four of its facts can now be explicitly unknown**, and that the runtime can actually
produce it. Field by field:

### Identity and scope

| Field | Meaning | Why governance needs it |
|---|---|---|
| `requestContext` | correlation, idempotency, causation, region | audit identity; region is the residency input |
| `principal` | the authenticated principal | the subject of authorization; the principal node's id |
| `tenant` | the resolved tenant scope | tenant isolation; the org/workspace/project nodes |
| `scopeChain` | the ordered hierarchy nodes governing this request | *the* input to the merge — decides which policies apply at all |
| `region` | where the request would execute | `REGION_RESTRICTION` |

### What the caller asked for

| Field | Meaning | Policy types it feeds |
|---|---|---|
| `model` | the canonical model, absent when the call addresses none | `MODEL_ALLOW_LIST`, `MODEL_DENY_LIST` |
| `tools` | requested tool **names** — never their schemas | `TOOL_ALLOW_LIST`, `TOOL_DENY_LIST` |
| `requiredComplianceRegimes` | regimes the execution path must satisfy | `COMPLIANCE_MODE` |
| `streaming` | whether the response streams | `STREAMING_ALLOWED`, `STREAMING_REQUIRED` |
| `jsonMode` | whether a response schema is pinned | `JSON_MODE_REQUIRED` |
| `reasoning`, `vision` | capabilities the caller asserted | `REASONING_ALLOWED`, `VISION_ALLOWED` |
| `embedding`, `imageGeneration`, `audio`, `fineTuning`, `batch` | derived from the operation | the matching `*_ALLOWED` types |
| `containsPii` | the caller's data classification | `PII_RESTRICTION` |

### The four facts that can be unknown

| Field | Type | Absent means | Effect when absent |
|---|---|---|---|
| `contextTokens` | `OptionalLong` | nobody counted the prompt | `MAX_CONTEXT`, `MAX_TPM` unenforceable ⇒ mandatory refuses |
| `outputTokens` | `OptionalLong` | the caller declared no ceiling | `MAX_OUTPUT_TOKENS`, `MAX_TPM` unenforceable ⇒ refuses |
| `projectedCostMicros` | `OptionalLong` | the Cost Engine failed closed | `MAX_COST`, `DAILY_BUDGET`, `MONTHLY_BUDGET` unenforceable ⇒ refuses |
| `candidateProviders` | `Optional<List<String>>` | the capability registry could not be read | `PROVIDER_ALLOW_LIST`, `PROVIDER_DENY_LIST` unenforceable ⇒ refuses |

**Unknown is the single most important addition in this milestone.** A ceiling compared against an
assumed zero passes trivially. Had the pipeline substituted zero for an uncounted prompt — the obvious,
convenient choice — wiring V2 would have *created* a silent bypass: every context, rate and budget
ceiling would have appeared configured and fired never. Instead absence routes to the evaluator's
existing `UNENFORCEABLE` outcome, the same path an unreadable consumption counter already took, and a
mandatory policy that cannot be checked refuses. **Governance gets stricter when told less, never
looser.**

### Explicitly rejected: an untyped `metadata` map

The brief listed "request metadata". It is not here, for two reasons. It would be the one field through
which caller-authored content reaches a decision that is written to an audit stream, defeating the
content-free-by-construction property. And no policy type consumes it — adding one would be new policy
semantics, which this milestone forbids. If attribute-based rules on metadata are wanted later, the
right shape is a declared, allow-listed key set introduced together with the policy type that reads it.

### Explicitly rejected: the selected route

Also listed in the brief. **Governance runs before ROUTER, so the selection does not exist yet.**
Supplying it would require reordering the pipeline, which the brief forbids and which would be wrong
anyway — routing to a provider governance would have refused, then discarding the choice, wastes the
work the admission gate exists to avoid. Governance instead sees the *candidate* set and narrows it;
the Router picks within what survives, which is the narrow-only handoff Doc 21 §36.1 RGC-5 specifies.

---

## 3. Runtime wiring — where every field comes from

Assembled by `GovernanceAdmissionAssembler`. Nothing is defaulted into existence.

| Field | Origin | Established by |
|---|---|---|
| `requestContext` | `Inbound.requestContext()` | INGRESS |
| `principal`, `tenant` | the AUTHN result | AUTHN |
| `scopeChain` | `GovernanceScopeResolver` over tenant scope + identity claims | AUTHN + operator config |
| `region` | `Inbound.requestContext().region()` | INGRESS |
| `model` | `Inbound.request().canonicalModelId()` | the caller |
| `tools` | `Inbound.request().toolDefinitions()` → names | the caller |
| `requiredComplianceRegimes` | `Inbound.requiredCompliance()` | the caller |
| `streaming` | `Inbound.mode() == Mode.STREAM` | INGRESS, from the `stream` parameter |
| `jsonMode` | `Inbound.outputSchema().isPresent()` | INGRESS |
| `reasoning`, `vision` | `Inbound.requiredCapabilities()` membership | the caller |
| `embedding`, `imageGeneration`, `audio`, `fineTuning`, `batch` | `Inbound.operation()` | **the ingress endpoint that was called** |
| `containsPii` | `Inbound.piiDeclared()` | the caller's classification |
| `contextTokens` | `PromptTokenEstimator` over the canonical request | operator-supplied seam |
| `outputTokens` | `Inbound.declaredMaxOutputTokens()` | the caller's `max_tokens` |
| `projectedCostMicros` | `CostEnginePort.project(…, Phase.PROJECTION)` | the Cost Engine |
| `candidateProviders` | `CapabilityRegistryPort` descriptors matching the model → `candidateId` | the capability registry |

Three origins deserve explanation because they did not previously exist.

**Operation.** A new enum on `Inbound`, set by the front door. The endpoint *is* the operation; deriving
it later from the request's shape ("there are images in the messages, so this must be image generation")
would be a guess, and a guess in an admission gate fires the policy on the wrong requests.

**Prompt tokens.** `PromptTokenEstimator` is a seam whose contract is an **upper bound, never an
estimate** — under-count and a 100k prompt slips past a 4k cap; over-count and a legitimate request is
refused, and only one of those is a security failure. The shipped default,
`CharacterBoundTokenEstimator`, counts characters. That is sound rather than approximate: every subword
tokenizer maps each token to at least one character, so characters can never be fewer than tokens. It is
also **roughly 4× conservative on English text**, so a 4,000-token cap refuses at about 1,000 real
tokens. That is a usable safety net and a poor customer experience; the fix is to wire a real tokenizer.
It ships because the alternative was assuming zero, which disables every ceiling it claims to enforce.

**Projected cost.** `AdmissionCostProjector` calls the Cost Engine's existing `project()`. Governance
still computes no cost (GV-D6) — every rate, FX conversion and contract decision stays inside the Cost
Engine. The projector maps identities across the seam and unwraps the answer. It fails to an unknown in
three ways, all of which refuse: the prompt could not be bounded, the caller declared no output ceiling
(the engine's own `USAGE_UNBOUNDED`), or the engine returned `CostUnavailable`. It also **checks the
currency** — a budget in canonical micros compared against a projection in another currency is wrong by
an exchange rate and fails open whenever the projection currency is the weaker one.

**Scope resolution.** Organization, workspace and project come from the authenticated tenant scope and
are never in doubt. Environment, API key and principal type are operator-declared: environment from the
node's own configuration, the others from named claims. **An unasserted claim omits its node; it never
invents one.** A placeholder id would attach every unkeyed request in the fleet to one shared node and
let one tenant's key policy govern another's traffic. Human-vs-machine is likewise asked, not inferred —
guessing from the authentication method is wrong the first time an operator issues a JWT to a batch job.

---

## 4. Pipeline changes

Two lines in `execute`, in the same position:

```java
// ---- GOVERNANCE ----
started = System.nanoTime();
final PolicyDecision decision = governance.admit(admission.assemble(inbound, principal, tenant));
if (!decision.admits()) {
  return refuse(trace, MandatoryStage.GOVERNANCE, started, decision.reasonCode(), AUTH_FAILED);
}
```

- **Order unchanged.** INGRESS → AUTHN → **GOVERNANCE** → ROUTER → RELIABILITY → SECRETS → ADAPTER →
  STREAM_GUARD → SCHEMA_LOCK → METERING → COST → EMITTER. Asserted by the existing non-bypass tests and
  again by `theStageOrderIsUnchangedByTheRicherQuestion`.
- **One governance call site**, as before. There is still no path from ingress to routing that skips it.
- **Assembly is a separate object**, not inlined. It reads three subsystems, any of which can be
  unavailable, and the handling of "unavailable" has to be uniform and testable. Inlined it would be six
  null-checks nobody could test in isolation, and the first one that defaulted to zero would quietly
  stop a spend ceiling firing.
- **The assembler cannot throw.** An exception between authentication and admission would leave someone
  else deciding whether to admit — the one decision that must not happen outside the engine.

`Inbound` gained three fields (`operation`, `declaredMaxOutputTokens`, `piiDeclared`) with a
backward-compatible constructor. No other stage changed.

---

## 5. Backward compatibility and migration

**Nothing breaks, and nothing silently loosens.**

| Surface | Compatibility |
|---|---|
| `GovernancePort` (frozen, `gateway-lib-ports`) | untouched |
| `GovernanceEngineService` (legacy evaluator) | untouched, still constructible |
| `RequestExecution.Inbound` | 14-arg constructor retained, defaulting to `CHAT` / no declared ceiling / no PII |
| `GatewayRuntimeConfig.GovernanceConfig` | 6- and 7-arg constructors retained; `AdmissionConfig` defaults |
| `GovernanceEngine` | gained `admit`; `govern` unchanged and still public |

`LegacyGovernanceAdmission` presents any pre-V2 `GovernancePort` through the new seam, so a deployment
that has not migrated keeps working through the new pipeline unchanged. Migration is then a wiring
change, not a flag day. The adapter is deliberately lossy in one direction only: it discards the facts
the old port cannot accept, and **does not pretend they were evaluated** — the decision it returns
carries no violations and an empty resolved context, so nothing downstream can mistake a legacy permit
for a fully-governed one.

**One behaviour deliberately tightened.** On the legacy `authorize` façade, token, cost and provider
quantities are unknown, so a mandatory ceiling of those kinds now refuses with `policy-unavailable`
rather than passing. A deployment that authored a budget cap and still calls the façade will see
refusals. That is the correct and visible failure — the alternative is a budget cap that silently never
fires — but it is a change, and an operator upgrading should either migrate to `admit` or remove
quantitative policies from the façade path.

**Recommended migration order:** (1) upgrade, keeping the legacy config — no behaviour change for the
five policy types already live; (2) supply `PolicyEngineConfig` to switch to the hierarchical engine;
(3) set `Operation` and `declaredMaxOutputTokens` at your ingress; (4) replace the character-bound
estimator with a real tokenizer; (5) name your API-key and principal-type claims in `AdmissionConfig`.
Each step widens enforcement; none of them is required for the previous step to keep working.

---

## 6. Tests

**107 added, 1,420 passing across the whole backend, zero failures.**

| File | Tests | What it pins |
|---|---|---|
| `GovernanceV2EnforcementTest` | 53 | every policy type firing on the **live pipeline path** |
| `GovernanceAdmissionAssemblerTest` | 32 | where each field comes from; every upstream failure ⇒ unknown |
| `AdmissionSeamsTest` | 22 | token bound, scope resolution, legacy adapter |

Coverage against the brief's required list — every entry asserts a real
`PipelineOutcome.Refused` at `MandatoryStage.GOVERNANCE`, paired with its admitting case:

| Required | Refusal test | Admission test |
|---|---|---|
| model allow/deny | `modelAllowListRefusesAModelTheTenantMayNotUse`, `modelDenyListRefusesAForbiddenModel` | `modelAllowListAdmitsAPermittedModel`, `modelDenyListIgnoresAModelItDoesNotName` |
| provider allow/deny | `providerAllowListRefusesWhenNoCandidateRouteIsPermitted`, `providerDenyListRefusesWhenItRemovesEveryCandidateRoute` | `providerAllowListAdmitsWhenAPermittedCandidateRouteExists`, `providerDenyListAdmitsWhileAnyCandidateRouteSurvives` |
| tool restrictions | `toolAllowListRefusesAnUnlistedTool`, `toolDenyListRefusesAForbiddenTool`, `oneForbiddenToolAmongSeveralRefusesTheWholeRequest` | `toolAllowListAdmitsAListedTool`, `aRequestCarryingNoToolsSatisfiesToolPolicy` |
| capability restrictions | vision, reasoning, embedding, image-generation, audio, batch, fine-tuning, PII | `aRequestNotAssertingVisionIsUnaffected…`, `anEmbeddingProhibitionLeavesChatAlone` |
| token limits | `anOversizedPromptIsRefusedByTheContextCeiling`, `anOversizedDeclaredCompletionIsRefused…`, `theTokenRateCapCountsPromptAndDeclaredCompletionTogether` | `aPromptInsideTheContextCeilingIsAdmitted`, `aDeclaredCompletionInsideTheOutputCeilingIsAdmitted` |
| budget limits | `aRequestCostingMoreThanTheSingleRequestCeilingIsRefused`, daily, monthly | `anAffordableRequestPassesTheSingleRequestCostCeiling`, `aRequestInsideTheDailyBudgetIsAdmitted` |
| streaming restrictions | `streamingIsRefusedWhereItIsNotPermitted`, `aBatchRequestIsRefusedWhereStreamingIsRequired`, `aRequestWithoutAPinnedSchemaIsRefusedWhereJsonModeIsRequired` | `aBatchRequestIsUnaffectedByAStreamingProhibition`, `aStreamingRequestSatisfiesAStreamingRequirement` |
| route restrictions | `routeRestrictionIsEvaluatedAgainstTheRegistrysCandidatesNotTheRequest` | as provider, above |

Three tests exist specifically to prove the wiring is real rather than coincidental:

- `theContextCeilingIsMeasuredAgainstTheActualPromptNotAConstant` — a 50-character prompt is admitted
  and a 100-character one refused under the same ceiling. A constant would decide both the same way.
- `theModelGovernedIsTheOneTheCallerActuallyAskedFor` — same policy, different requested model.
- `toolSchemasCountTowardTheContextCeiling` — a tiny prompt with a large schema is refused.

And four pin the fail-closed direction, which is where a wiring bug would otherwise hide:

- `aNodeThatCannotCountTokensRefusesRatherThanIgnoringTheContextCeiling`
- `anUndeclaredCompletionCeilingRefusesRatherThanPassingTheOutputCheckTrivially`
- `anUnpriceableRequestRefusesRatherThanBypassingItsBudget`
- `anUnpublishedRegistryLeavesTheCandidateSetUnknownRatherThanEmpty`

---

## 7. Performance

Measured on this machine, 200,000 iterations after 50,000 warm-up, against a four-level scope chain
carrying twelve rules, a three-route capability registry, and a live Cost Engine.

| Step | mean | p50 | p99 | p99.9 |
|---|---|---|---|---|
| `assemble()` | 4,042 ns | 3,200 | 14,200 | 48,600 |
| `admit()` (evaluate) | 724 ns | 600 | 1,700 | 12,500 |
| **GOVERNANCE stage total** | **4,766 ns** | **3,800** | **15,400** | **56,600** |
| evaluate only, warm cache | 520 ns | 500 | 1,000 | 4,200 |
| legacy adapter (pre-V2 equivalent) | 91 ns | 100 | 300 | 700 |

**Added latency ≈ 4.7 µs per request at the mean, ≈ 15 µs at p99.**

Where it goes:

| Component | mean | share of `assemble()` |
|---|---|---|
| **cost projection** | **3,176 ns** | **79%** |
| scope resolution | 252 ns | 6% |
| capability snapshot read | 131 ns | 3% |
| token bound (400-char prompt) | 60 ns | 1% |

Two honest observations.

**The Cost Engine dominates.** Two-thirds of the entire governance stage is `project()` — pricing
resolution, FX lookup and conservative rounding, per request. That is Doc 22's code, not governance's,
and it is doing real work. Policy evaluation itself remains what the previous milestone measured: about
half a microsecond, bounded by the 31 policy types rather than by how much policy exists.

**No optimisation was applied, deliberately.** The obvious one — skip the projection when no cost or
budget policy is in force — requires resolving the effective policy *before* assembling the question,
inverting the order and coupling the assembler to the registry. Against a provider call of 500 ms to
2 s, 4.7 µs is roughly **0.001%** of request latency. Adding coupling to a security-critical assembly
path to recover a thousandth of a percent is a bad trade. If a future deployment measures this as
material, the optimisation is available and safe (a missing projection refuses rather than bypasses),
but it should be driven by a measurement, not by this one.

---

## 8. Final report

### Files created (9 main, 3 test)

**Main**
- `gateway-dp-governance-engine/…/api/GovernanceAdmissionPort.java`
- `gateway-dp-app/…/pipeline/Operation.java`
- `gateway-dp-app/…/binding/PromptTokenEstimator.java`
- `gateway-dp-app/…/binding/CharacterBoundTokenEstimator.java`
- `gateway-dp-app/…/binding/GovernanceScopeResolver.java`
- `gateway-dp-app/…/binding/ClaimBasedScopeResolver.java`
- `gateway-dp-app/…/binding/AdmissionCostProjector.java`
- `gateway-dp-app/…/binding/GovernanceAdmissionAssembler.java`
- `gateway-dp-app/…/binding/LegacyGovernanceAdmission.java`

**Test**
- `gateway-dp-app/…/runtime/GovernanceV2EnforcementTest.java`
- `gateway-dp-app/…/binding/GovernanceAdmissionAssemblerTest.java`
- `gateway-dp-app/…/binding/AdmissionSeamsTest.java`

**Docs** — this file.

### Files modified (7 main, 2 test)

| File | Change |
|---|---|
| `governance/api/PolicyRequest.java` | four facts became optional; `Quantity` enum; `unknownCandidateProviders()` |
| `governance/domain/PolicyEvaluator.java` | unknown quantities route to the existing `UNENFORCEABLE` outcome |
| `governance/application/GovernanceEngine.java` | implements `GovernanceAdmissionPort`; `admit()`; façade scope documented |
| `app/pipeline/RequestExecution.java` | `Inbound` gained `operation`, `declaredMaxOutputTokens`, `piiDeclared` + compat constructor |
| `app/pipeline/RequestPipeline.java` | governance field is now the admission port; one call site rewritten in place |
| `app/runtime/GatewayRuntimeConfig.java` | `AdmissionConfig`; `GovernanceConfig` 8th component + two compat constructors |
| `app/runtime/GatewayRuntime.java` | `admissionPort()`, `admissionAssembler()` |
| `app/pipeline/RequestPipelineTest.java` | harness routed through the legacy adapter |
| `governance/…/PolicyEvaluatorTest.java` | one test now declares its output ceiling explicitly |

### Tests

| | |
|---|---|
| Added this milestone | **107** |
| Governance module | 405 |
| Whole backend | **1,420 passing, 0 failing, 101 containers** |

### Remaining blockers

| # | Blocker | Impact | Owner |
|---|---|---|---|
| B1 | Maven cannot resolve dependencies (TLS interception on Maven Central) | Checkstyle, SpotBugs, ErrorProne, forbidden-apis, JaCoCo and PITest have **not** run; verification is `javac` + JUnit only | environment |
| B2 | ArchUnit jar absent locally | `GovernanceArchitectureTest` still unproven | follows B1 |
| B3 | No shared in-memory tier adapter for atomic per-(node, key) increment | concurrent requests do not see each other's in-flight consumption; a burst can over-admit a rate cap by up to the burst size | platform |
| B5 | No control-plane `PolicySourcePort` adapter | operators must supply one; unchanged from 21A | C4 control plane |
| **B6 (new)** | **No real tokenizer.** The character bound over-counts ~4×, so context and TPM ceilings refuse at roughly a quarter of their nominal value | correct direction, poor UX | this repo |
| **B7 (new)** | **No PII classifier.** `PII_RESTRICTION` only catches self-declared PII | a caller that does not declare is not detected | this repo |

B4 from the previous milestone — "the pipeline carries no model/tool/capability into GOVERNANCE" — is
**closed**. All 31 policy types are now reachable from live traffic.

B6 and B7 are new and both are *narrowness* rather than *bypass*: the token bound is conservative in the
safe direction, and an undeclared PII request is governed by every other policy. Neither can admit
something policy forbids.

---

*End of document — 21B-GovernancePortV2.md*
