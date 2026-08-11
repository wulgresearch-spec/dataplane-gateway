# AD-029 — Production PII Detection for Memory Runtime

**Status:** Accepted
**Date:** 2026-08-06
**Supersedes:** nothing. **Superseded by:** nothing.
**Closes:** B23 (Real PII Classifier).
**Depends on:** AD-026 (Memory Runtime, C17), AD-028 (memory cryptography).
**Module:** `backend/dataplane/modules/gateway-dp-memory-pii`

---

## 1. Context

AD-026 §5.1 defined `PiiClassifierPort` and shipped `ConservativePiiClassifier` behind it: five
regular expressions — an email pattern, a "card-like" digit run, any run of nine or more digits, and
two keyword lists. It was honest about being a placeholder. It was also the thing deciding, on every
write, whether content was `PUBLIC`, `PII`, `SENSITIVE_PII` or `SECRET`, and therefore what governance
was told and whether the content got sealed.

### 1.1 A correction to the milestone brief

The brief states:

> PII_RESTRICTION currently depends on caller-supplied metadata.

**It does not.** `MemoryWriteRequest` has no classification field, no code path reads a caller-declared
classification, and `MemoryWritePipeline:155` passes `classified.classification()` — the classifier's
own output — into `governance.admitWrite(...)`. `PiiGovernanceIntegrationTest` asserts exactly this.

The real weakness was never provenance; it was **quality**. A caller could not declare its content
harmless, but it could rely on five crude patterns failing to notice. That distinction matters,
because it changes what was at risk: not a trust boundary, but a detection rate.

The work is the same either way. The claim is not, so it is corrected here rather than repeated.

### 1.2 A second correction: the port carries no tenant

```java
Classification classify(String body);
```

The port receives a body and nothing else. **Tenant and organization rules cannot be selected through
it.** They exist, they are compiled, they layer correctly, and they are reachable through
`RuleBasedPiiClassifier.detect(TenantScope, String)` — but not through the interface C17 calls.

Fixing that means a parameter on `PiiClassifierPort`, which is a Memory Runtime change and forbidden
by the brief's own "Memory Runtime architecture MUST remain unchanged". The engine was therefore built
in full and the seam left where it is. **Recorded as B45, and it is the largest gap in this milestone.**

---

## 2. Decision

A deterministic rule engine. No model, no network, no service, no randomness.

- **32 built-in rules** covering every type the brief lists.
- **Checksum and range validators** — Luhn, IBAN mod-97, Verhoeff, IPv4 octet range, GPS range,
  date plausibility.
- **Context scoring**, so a bare date is ignored and a date labelled "DOB" is not.
- **Exemption (`ALLOW`) rules** alongside detection (`DENY`) rules.
- **Layered rule sets** — built-in, organization, tenant — compiled once and swapped atomically.
- **A step budget** bounding tenant-supplied patterns.
- **A prefilter** skipping rules that cannot possibly match.

---

## 3. Architecture

```
MemoryWritePipeline
   │  classify(body)                    ← the port; no tenant parameter (B45)
   ▼
RuleBasedPiiClassifier ──detect(tenant, body)──►  PiiRuleRegistry
   │                                                  │ engineFor(tenant)
   │  flatten to DataClassification (B46)             ▼
   │                                            PiiDetectionEngine
   ▼                                                  │
MemoryWritePipeline ──► governance.admitWrite(..., classification, ...)
                   ──► policy.requiresSealing(classification) ──► AD-028 crypto
                   ──► store
```

Detection happens **before** governance, which happens before crypto, which happens before storage —
the order the brief requires and the order C17 already implemented. Nothing in this module knows about
storage, encryption, ranking, retrieval or policy.

---

## 4. Classification model

Two independent dimensions, because they vary independently: a patient identifier and a national
identifier are both `CRITICAL`, but only one pulls in health-data obligations.

**`PiiSeverity`** — `NONE`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. A document's severity is the
**maximum** over its spans, never an average: padding sensitive content with harmless text must not
dilute it.

**`PiiCategory`** — `PERSONAL`, `FINANCIAL`, `HEALTH`, `LEGAL`, `SECRET`, `AUTHENTICATION`,
`BUSINESS_CONFIDENTIAL`, and `MULTIPLE`. `MULTIPLE` is a **summary value only**: no span carries it,
no rule may declare it, and both `PiiSpan` and `PiiRule` throw if one tries.

**`PiiSpan`** carries offsets, type, category, severity, confidence, rule id and rule version — and
**never the matched text**. A result describing where the card numbers are is something you can log
and audit; one that quotes them is a second copy of the data with fewer protections than the first.

### 4.1 Severity assignment, and the judgement in it

Identifiers a person **cannot change** rank above identifiers they can. A leaked email address is a
nuisance; a leaked national identifier is permanent.

| `CRITICAL` | national id, passport, tax id, card, bank account, medical record, patient id, credential, secret key |
| `HIGH` | date of birth, driver licence, UPI id, insurance number, legal case reference |
| `MEDIUM` | email, phone, postal address, GPS coordinate |
| `LOW` | name, IP address, MAC address, employee id, customer id |

---

## 5. Rule engine

### 5.1 Rules are pure data

`PiiRule` is a record. Nothing in it is executable except the pattern, and the pattern runs under a
step budget. A rule is therefore something a tenant can be allowed to write, an operator can review in
a diff, and a store can hold — none of which is true of a rule carrying code. Validators are an
**enum**, not a function, for the same reason: a rule holding arbitrary caller code is a remote
execution primitive.

### 5.2 Compilation

`PiiRuleCompiler` refuses, at install time: duplicate identifiers, malformed patterns, and patterns
that match the empty string (which would emit a zero-width span at every offset).

It deliberately does **not** attempt to prove a pattern safe. Deciding whether a regular expression
backtracks catastrophically is not something a syntactic check does reliably, and every published
"dangerous construct" blacklist has been bypassed. The engine bounds the work instead (§8.1).

Ordering is **total and deterministic**: effective priority descending, then rule id. Behaviour never
depends on the order rules came out of a store.

### 5.3 Layering is total, not a numbers game

Each origin has a **rank** — built-in 0, organization 1, tenant 2 — and effective priority is
`(rank << 33) + (priority - Integer.MIN_VALUE)`, computed in `long`. Origin therefore occupies the
high bits and **strictly dominates**; the declared priority orders rules within a layer, across the
whole `int` domain including negatives.

Without this, whoever picked the larger integer wins, and an organization could silently overrule a
tenant **inside that tenant's own data** by choosing `Integer.MAX_VALUE`.

The first implementation added floors of 0 / 1000 / 2000 to the declared priority in `int`
arithmetic. It was wrong twice over, and the tests did not notice; §11.1 records how the sabotage run
found it and what had to change.

### 5.4 Exemptions

`ALLOW` rules suppress any detection overlapping them, and are applied **before** priority resolution
— an exemption is a statement that a region is not sensitive at all, so a higher-priority built-in
must not then report it.

Necessary for the real world: a documentation corpus full of `4111 1111 1111 1111`, a test tenant
whose fixtures are all `@example.com`. Without exemptions, the only way to stop a false positive is to
weaken the detector for everyone.

---

## 6. Detection

Four stages: **match**, **validate**, **score**, **resolve**.

### 6.1 The prefilter

Each rule declares substrings it cannot match without (`@` for email, `patient` for patient id) or a
requirement for any digit. A rule failing that check is skipped entirely.

This is not a heuristic — the substrings are taken from the pattern's own mandatory literals. It is
also the single largest performance decision in the module: see §9.2, where it is worth **192×** on
ordinary prose.

### 6.2 Validators are what make this a detector rather than a regex list

The pattern for a payment card is thirteen to nineteen digits, which also matches order numbers,
concatenated timestamps and most log lines containing an identifier. Luhn removes **roughly nine in
ten** of those — measured over 10,000 random 16-digit runs in `PiiValidatorTest`, 800–1200 survive —
while removing no genuine cards at all, because a real card carries the check digit by construction.

Verhoeff additionally catches every adjacent transposition, the commonest transcription error.

All of these are **necessary** conditions, never sufficient ones. A number passing Luhn is a number
that *could* be a card.

### 6.3 Context scoring

A match near a declared keyword gains 0.35 confidence; matches below 0.5 are not reported.

This is what lets `builtin.dob.iso` sit at base 0.35: `deployed on 1985-03-21` is ignored, and
`dob 1985-03-21` is reported. Timestamps outnumber birth dates by orders of magnitude in any real
corpus, and a detector that flags every ISO date is one that gets switched off.

The same mechanism carries `builtin.passport` (0.4): shape alone is far too common.

---

## 7. Governance integration

Detection feeds governance unchanged. `PiiGovernanceIntegrationTest` drives the real C17 pipeline and
asserts:

- governance receives `INTERNAL`, `PII`, `SENSITIVE_PII` for clean, email-bearing and card-bearing
  bodies respectively;
- a tenant policy refusing `SENSITIVE_PII` refuses the card and admits the note;
- a policy requiring encryption seals what the detector called sensitive;
- a redacting policy stores the masked body.

### 7.1 A credential now stops a write

`DataClassification.SECRET` is not storable (`MemoryWritePipeline:143`). C17 always enforced that;
nothing reliably *produced* it before. A password or API key in a memory body now refuses the write.
This is a strengthening of existing behaviour, not a new mechanism — but it is a **behaviour change**
and deployments should expect it.

### 7.2 Nothing found maps to INTERNAL, not PUBLIC

"No personal data detected" is not "safe to disclose". A strategy document contains neither, and a
detector finding nothing is also what a *blind* detector does. `PUBLIC` is a claim this engine is not
in a position to make.

### 7.3 An incomplete scan fails closed

A truncated body or an abandoned rule yields `complete() == false`, and the classifier returns
`UNCLASSIFIED` — which C17 refuses (MEM-21). The fail-open version of this is subtle and total: a body
engineered to be long enough, or to trip a pathological rule, would return "nothing found" and sail
past governance.

### 7.4 On masking, which the brief excluded

The brief says this milestone does not redact. `PiiClassifierPort.Classification` nonetheless carries
a `redactedBody`, and `MemoryWritePipeline:197` uses it when policy says `REDACT`. Returning the body
unchanged would have **silently switched redaction off**.

Masking is therefore produced, and framed accordingly: it is a mechanical consequence of knowing the
spans, not a policy decision. `PiiAction` still decides whether the mask is ever used.

---

## 8. Security

### 8.1 Regular-expression denial of service

This engine accepts tenant-supplied patterns. `ScanBudget` wraps the body and counts every
`charAt` the matcher performs — which counts the engine's actual steps, backtracking included — and
throws once the allowance is spent. The budget is **per rule**, so a document with forty rules does not
fail the fortieth because one of the first thirty-nine misbehaved.

**Two things were measured rather than assumed.**

First, the wrapper is free: an email pattern over 1 KiB ran in 72.91 µs raw and 74.24 µs wrapped —
a ratio of **1.0×**.

Second, **the textbook `(a+)+$` does not blow up on Java 21.** It completes in about 5.6 ms; the JDK's
engine has optimisations that defeat that shape. The original test used it and consequently tested
nothing. It was replaced with `(.*a){12}$`, which a probe against the same input ran for **over three
minutes** before being killed.

### 8.2 Tenant isolation

Registry keys are length-prefixed, so `("a","bc")` and `("ab","c")` cannot collide — a collision would
apply one tenant's rules to another's data, and a tenant's rule list is itself sensitive because it
names what that tenant considers secret.

### 8.3 No content in observability

Every `PiiMetricsPort` parameter is a type, a rule id, a size or a duration. The interface cannot carry
scanned content without being changed. Compilation failures name the rule, never the data.

---

## 9. Benchmarks

Measured by `PiiBenchmarkTest` on a 16-core Windows 11 laptop, Java 21. **Nothing here is estimated.**

### 9.1 Detection latency

| Body | Clean prose | Dense PII |
|---|---:|---:|
| 128 B | **1.52 µs** (658,805/s) | 118.50 µs (8,439/s) |
| 1 KiB | **11.78 µs** (84,870/s) | 762.78 µs (1,311/s) |
| 8 KiB | **78.51 µs** (12,737/s) | 6.95 ms (144/s) |
| 64 KiB | **704.84 µs** (1,419/s) | 59.35 ms (17/s) |

| | |
|---|---:|
| Throughput, clean prose | **95.9 MB/s** |
| Allocation per 1 KiB scan, clean / dense | 1,793 B / 25,936 B |
| Compiling 32 built-in rules | 229 µs |
| Compiling 532 rules | 0.56 ms |
| Registry lookup (write path) | **40.1 ns** |
| Scan 4 KiB with 32 / 132 / 332 rules | 38.9 / 293.9 / 1116.7 µs |
| Concurrent 4 KiB dense, 8 threads | 811 µs/op, 1,232 scans/s |

**Against the write path it sits on**, an 11.78 µs scan of a 1 KiB prose body is **0.3%** of AD-027's
3.5–4.1 ms durable write. Dense content is a different story — see B50.

### 9.2 A performance defect found and fixed

The first working version scanned **0.9 MB/s**: 335 µs for 128 bytes, 2.26 ms for 1 KiB. Per-rule
profiling showed every rule costing 20–85 µs per KiB of prose while a literal-prefixed rule
(`-----BEGIN`) cost 2.2 µs. The cause is the **leading lookaround** nearly every rule uses for
boundary fencing: the engine evaluates it at every position, and there is no literal to skip ahead on.

The first hypothesis — that the `ScanBudget` wrapper defeated the regex fast paths — was **wrong**, and
the measurement in §8.1 disproved it.

The fix was the prefilter (§6.1):

| | before | after | |
|---|---:|---:|---:|
| clean 128 B | 335.73 µs | **1.52 µs** | 221× |
| clean 1 KiB | 2261.70 µs | **11.78 µs** | 192× |
| clean 8 KiB | 18,087.87 µs | **78.51 µs** | 230× |
| throughput | 0.9 MB/s | **95.9 MB/s** | 106× |
| allocation, clean 1 KiB | 8,472 B | **1,793 B** | 4.7× |

Dense content is largely unimproved, because in a body full of digits and at-signs there is nothing to
skip.

---

## 10. Honest limitations

**No claim is made of perfect detection, zero false positives, zero false negatives, or any
understanding of meaning.** This is pattern matching with checksums. It finds things shaped like
identifiers near words that suggest identifiers.

### 10.1 Unlabelled personal names are not detected

`builtin.name.titled` requires a title; `builtin.name.labelled` requires a `name:` label. **"Sarah
Chen approved the transfer" is not detected.** General personal-name recognition is not achievable
with regular expressions, and a list of common first names would be a different kind of wrong — it
would miss most of the world and flag ordinary words. This is the engine's largest recall gap. **B47.**

### 10.2 Section and version numbers still look like addresses

`12.4.5.6` is a valid dotted quad. The rule carries a lookbehind list (`section`, `version`,
`chapter`, …) because no checksum can separate them, and that list is necessarily incomplete. **B48.**

### 10.3 The engine is structure-blind

It scans text, not JSON. That is a strength — no parser to defeat with an unexpected encoding — and a
weakness: a value split across string concatenation, base64-encoded, or spelled out in words is
missed entirely. **B49.**

### 10.4 Identifiers with no checksum and no label are missed

`builtin.medical.record`, `patient.id`, `insurance`, `employee.id`, `customer.id` and
`driver.license` all require a nearby label. A bare `A9928311` in a chart export is not detected.
Recall was traded for precision deliberately; the alternative flags every alphanumeric token. **B48.**

### 10.5 Cost is linear in rule count

Every rule is a separate pass: 38.9 µs at 32 rules, 1116.7 µs at 332. A combined automaton would not
be, and was not built. **B51.**

### 10.6 Precision and recall are unmeasured

There is no labelled corpus in this repository, so the suite proves **behaviour on chosen examples**,
not a detection rate. Every claim above about "nine in ten false positives removed" is about Luhn
specifically and is measured; nothing here measures end-to-end recall against real data. **B52.**

---

## 11. Sabotage verification

Six mechanisms disabled one at a time. Benchmarks excluded from these runs, so the baseline is 158
tests rather than 165. All five source files restored and verified byte-identical with `diff`.

| # | Mechanism disabled | Edit | Failed | Representative failures |
|---|---|---|---:|---|
| 1 | **Email detection** | the at-sign the pattern pivots on replaced with `#` | **17** | `everySupportedTypeIsDetectedInALabelledExample`, `aRedactingPolicyStoresTheMaskedBodyNotTheOriginal`, `anAllowRuleExemptsOnlyTheTextItCovers`, `concurrentScansOfDifferentBodiesDoNotContaminateEachOther` |
| 2 | **Phone detection** | grouped shape stops matching a four-digit final group | **3** | `everySupportedTypeIsDetectedInALabelledExample`, `mixedContentReportsEveryEntityAndTheWorstSeverity`, `piiNestedInsideJsonIsFound` |
| 3 | **Rule ordering** | comparator no longer reverses, so lowest priority is consulted first | **3** | `aTenantRuleOutranksABuiltInOnTheSameText`, `withinOneLayerTheDeclaredPriorityDecides` |
| 4 | **Priority** | the tenant layer's rank dropped to the built-in layer's | **0 → 1** | `aTenantRuleOutranksAnOrganizationRuleWhateverPriorityEachDeclares` — see §11.1 |
| 5 | **Tenant override** | every tenant served the organization default | **17** | `aTenantRuleAppliesOnlyToThatTenant`, `aScanAlreadyRunningKeepsTheRuleSetItStartedWith`, `aCatastrophicallyBacktrackingTenantRuleIsBoundedRatherThanHanging` |
| 6 | **Classification mapping** | `CRITICAL`/`HIGH` collapsed into ordinary `PII` | **2** | `governanceIsHandedTheClassificationTheDetectorProduced`, `aTenantPolicyCanRefuseStorageOnTheStrengthOfTheClassification` |

### 11.1 The priority sabotage initially caught nothing, and finding out why exposed a real defect

On the first run, dropping the tenant layer's rank produced **zero** failures. The test that should
have caught it — `aTenantRuleOutranksAnOrganizationRuleWhateverPriorityEachDeclares` — was passing,
but for the wrong reason.

That test gives the organization rule a priority of `Integer.MAX_VALUE - 1`. Effective priority was
computed as `origin.priorityFloor() + priority` in **`int` arithmetic**, so `1000 + (MAX - 1)`
**overflowed to a large negative number**. The organization rule therefore ranked last, the tenant
rule won, and the assertion held regardless of what the floors were.

Two separate defects were hiding behind that:

1. **Integer overflow.** Any rule declaring a priority near the `int` limit silently ranks *last* —
   the exact opposite of what declaring a high priority means. Fixed by computing in `long`.
2. **The floor design itself was wrong.** With overflow fixed, floors of 0 / 1000 / 2000 turned out
   to be far too small: an organization rule at `Integer.MAX_VALUE` genuinely outranked every tenant
   rule, inverting the guarantee §5.3 exists to make. Fixed by making origin a **rank in the high
   bits** — `(rank << 33) + (priority - Integer.MIN_VALUE)` — so origin strictly dominates across the
   whole `int` domain.

After both fixes the sabotage is caught. Two regression tests now sweep the cross-product of
`{MIN_VALUE, -1, 0, 1, MAX_VALUE}` for both origins, because a test using only small priorities
passes against **both** broken implementations.

This is the most useful thing the sabotage process did in this milestone: the mechanism was wrong,
the test was green, and only deliberately breaking the code revealed that the green meant nothing.

---

## 12. Blockers

| # | Blocker | Severity |
|---|---|---|
| **B45** | `PiiClassifierPort.classify(String)` carries no tenant, so tenant rules are unreachable from C17 | **High** |
| **B46** | `DataClassification` has six values and no category dimension; severity and category are flattened at the boundary | Medium |
| **B47** | Unlabelled personal names are not detected at all | **High** |
| **B48** | Unlabelled identifiers with no checksum are missed; numbering shaped like a dotted quad still matches | Medium |
| **B49** | Structure-blind: encoded, concatenated or spelled-out values are missed | Medium |
| **B50** | Dense-PII content costs 84× prose; 64 KiB dense takes 59 ms on the write path | Medium |
| **B51** | Scan cost is linear in rule count; no combined automaton | Low |
| **B52** | Precision and recall unmeasured — no labelled corpus | **High** |
| **B53** | Prefilter substrings are hand-derived; a wrong one is a silent false negative, and nothing checks them against the patterns | Medium |
| **B54** | The step budget is verified against one pattern class; it is a bound, not a proof of termination for all patterns | Medium |
| B1 | Inherited: Maven blocked, so Checkstyle, SpotBugs, JaCoCo and PITest have never run | High |
| B2 | Inherited: ArchUnit jar unavailable, module-boundary rules unproven by tooling | Medium |

---

## 13. Rejected alternatives

| Option | Why rejected |
|---|---|
| An LLM or cloud classifier | Explicitly excluded by the brief, and rightly: it would put a network call on the write path, make classification non-deterministic, and send the very content being protected to a third party |
| A statistical NER model | Would close B47, the largest recall gap. Needs a model file, a runtime and a training provenance story, none of which exist here. The honest next step if named-entity recall matters |
| A common-first-names list for B47 | Misses most of the world and flags ordinary words. A different kind of wrong, not an improvement |
| Blacklisting "dangerous" regex constructs at compile time | Every published blacklist has been bypassed. Bounding the work is decidable; classifying the pattern is not |
| Automatically deriving the prefilter from each pattern | Would remove B53, but a wrong derivation is a silent false negative. Hand-derived and reviewable beat clever and opaque here |
| Combining all rules into one automaton | Would fix B51. Loses per-rule attribution, which the audit record depends on, and per-rule budgets |
| Storing matched text on the span | Makes debugging easy and makes every log line a copy of the data |
