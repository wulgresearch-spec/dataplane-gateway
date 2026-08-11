# 39 — Embedding Runtime: Activation, Hardening, and Three Blockers

**Document:** Implementation report — Embedding Runtime Activation (AD-026 §15.2 Phase 5, partial)
**Status:** Partially delivered — **STOP** raised on one frozen contract and two missing adapters
**Builds on:** `ADR-030-Embedding-Provider.md` (B25), `ADR-026-Memory-Runtime.md` (FROZEN invariants MEM-1…MEM-28)

---

## 0. Executive summary — read this first

**The activation seam already existed, and it is correct.** `MemoryWritePipeline` has called
`embedder.embed(plaintext)` since AD-026, after the durable store, inside a `try/catch` that marks the
record `indexPending` rather than failing the write. This milestone did not need to design ordering or
failure semantics; they were already right.

**What this milestone could deliver, it delivered.** The B25 embedding stack now drives a real `MemoryRuntime`
end to end — batcher, cache, registry, retry, normalizer, provider — proven by
`MemoryRuntimeActivationTest`. Five defects that would have made activation unsafe are fixed, each with
a regression test and each sabotage-verified.

**What it could not deliver, it stopped on.** Three things block production activation. None can be
solved without either changing a Memory Runtime contract or building an adapter that does not exist,
and the mission says to stop rather than invent a workaround:

1. **`EmbeddingPort` carries no scope**, so one shared bridge collapses every tenant into one cache
   namespace. Tenant isolation of the cache **cannot be proven** for a single shared `MemoryRuntime`.
2. **No `EmbeddingTransportPort` implementation exists anywhere in the repository.** The only one is a
   test double. Embedding cannot reach a network in any deployment.
3. **Nothing reconciles `indexPending`.** A transient provider blip costs a record its semantic
   findability permanently.

**Status: PARTIAL.** Embedding is BUILT, TESTED and PROVEN-CONNECTABLE. It is not WIRED and not
LIVE, and §5 explains why that is the correct outcome rather than an unfinished one.

---

## 0.1 A correction to this document's own label

**This report was originally headed "B26 milestone". That was wrong, and the error is recorded here
rather than quietly overwritten.**

`B26` was already allocated by `ADR-026 §14.1` to something else entirely — *"a point-in-time snapshot
of a whole scope needs an adapter capability that no port yet exposes"* — and that gap is still open and
untouched. The session that wrote this document took "B26" from the milestone brief it was handed and
never checked it against the register. `ADR-030` made the same mistake in the other direction, using
"B26" for the vector-index work.

**The register is `ADR-026 §14.1` and the documents that extend it.** B-numbers denote *gaps*, not
milestones, and each document allocates the next free contiguous block: 28A took B11–B14, AD-026 took
B21–B27, AD-027 B28–B34, AD-028 B35–B44, AD-029 B45–B54, AD-030 B55–B63, and this document B64–B68.
The next free number is **B69**.

The work this document reports is **the embedding half of AD-026 §15.2 Phase 5**, plus hardening of
AD-030. It has no B-number of its own, because delivered work is not a gap. Every B64–B68 allocation
below stands unchanged.

---

## 1. Archaeology — the real call graph

Traced from source, not assumed.

```
HTTP  →  PipelineIngress  →  RequestPipeline  →  Governance  →  Router  →  Provider Adapter
                                    │
                                    └── (no memory stage; AD-026 §rejected: "RequestPipeline
                                        ordering is frozen and a conditional stage is not a stage")

MemoryRuntime.write(caller, request)                       MemoryRuntime.java:91
  └─ MemoryWritePipeline.write(...)
       ├─ scope narrowing / caller authorization
       ├─ PiiClassifierPort.classify(body)                 ← B23 / AD-029
       ├─ MemoryGovernancePort.admitWrite(...)             ← governance, BEFORE anything is stored
       ├─ policy: classification ceiling, residency, PiiAction (REFUSE | REDACT | ENCRYPT | ALLOW)
       ├─ MemoryCryptoPort.seal(scope, plaintext)          ← B24 / AD-028, when policy requires it
       ├─ MemoryStorePort.put(record, idempotencyKey)      ← DURABLE WRITE HAPPENS HERE
       └─ if (needsIndex && !deduplicated) {               MemoryWritePipeline.java:260-271
              index.index(scope, id, embedder.embed(plaintext))
            } catch (RuntimeException) { indexPending stays true }
                       │
                       └─ MemoryEmbedder.embed(String)     MemoryEmbedder.java:41
                            └─ EmbeddingPort.embed(String) ◄── THE SEAM (frozen, MEM-3)
                                 └─ PipelineEmbeddingPort
                                      └─ EmbeddingBatcher → EmbeddingPipeline
                                           └─ EmbeddingProviderPort → OpenAiEmbeddingProvider
                                                └─ EmbeddingTransportPort ◄── NO IMPLEMENTATION
```

**Where embedding is supposed to happen:** inside `MemoryWritePipeline`, **after** the durable write.
**What interface owns it:** `EmbeddingPort`, owned by C17, implemented by B25's `PipelineEmbeddingPort`.
**Where failures go:** nowhere fatal — the record survives with `indexPending = true`.

---

## 2. The activation seam — answers to §3 of the mission

### 2.1 When should embedding happen? **After the durable write, synchronously, inside `MemoryWritePipeline`.**

Not a choice this milestone made; a choice AD-026 §11.2 already made and commented in code:

> Store first, then index. A crash between the two leaves a record that is keyword-visible but not yet
> semantically findable, which is strictly better than an index entry pointing at a record that does
> not exist.

That ordering is right and is preserved untouched. Embedding before the write would mean paying a
provider for content governance might still refuse; embedding behind `MemoryStorePort` would put
vector concerns inside a storage adapter and violate MEM-2.

### 2.2 What happens if embedding fails? **The memory survives; only its searchability is outstanding.**

Verified, not assumed —
`MemoryRuntimeActivationTest.aProviderOutageDoesNotLoseTheMemoryOnlyItsSearchability` drives a provider
that fails every attempt and asserts `MemoryOutcome.Written` with `indexPending == true`.

### 2.3 What happens if the provider is unavailable? **A provider outage never becomes a write failure.**

Same test. Retry (full jitter, transient failures only) happens inside `EmbeddingPipeline`; when it is
exhausted the write still commits.

**But recovery does not exist — see §5.3.**

---

## 3. What was delivered

### 3.1 The cache key now binds every dimension that can change the answer

Before, the key was SHA-256 over `org`, `tenant`, `modelId`, `text`. Measured consequences:

| Two requests differing only in… | shared a cache entry? |
|---|---|
| workspace | **yes** |
| project | **yes** |
| provider serving the neutral model id | **yes** |
| model dimension | **yes** |

`MemoryScope`'s own javadoc names **workspace** as an isolation level. The key ignored it.

The provider case is worse than an oracle. Demonstrated before the fix: writing under provider
`alpha`, repointing the neutral model id to provider `beta`, then rewriting the same text returned
**alpha's vector** — old and new records in one corpus, in two different vector spaces, with no error
anywhere. ADR-030 §4.2 says provenance makes that "detectable rather than mysterious"; nothing reads
the field, and the cache defeated the guard.

The key now binds a version tag, `org`, `tenant`, `workspace`, `project`, provider id, model id, model
dimension and text — each length-prefixed, with optional fields carrying a presence flag so
`workspace = null` and `workspace = ""` cannot collide.

**Still not bound: the record owner.** `MemoryScope` declares a per-principal isolation level;
`EmbeddingPort` carries no scope at all, so no owner ever reaches the cache. **B64.**

### 3.2 Cost arithmetic can no longer understate

`costMicros` multiplied `tokens × pricePerMillion` in a `long` with no overflow check. Past
~4.6 × 10¹⁴ tokens the product wraps negative, and the `Math.max(1, …)` floor turns it into **one
micro**:

| tokens (at $0.02/1M) | reported before | true |
|---|---|---|
| 461,168,601,842,739 | **1 micro** | 9,223,372,036,854 micros (~$9.2 M) |
| `Long.MAX_VALUE` | **1 micro** | ~1.8 × 10¹⁷ micros |

Input length cannot reach that frontier — but this is also called with the token count the **provider
reports**, parsed from the response body. A self-hosted endpoint speaking a vendor-compatible protocol
is an ordinary deployment shape, so that number arrives over the network and is not trusted input.

Now: `Math.multiplyExact` with saturation to `Long.MAX_VALUE`, because overstating is visible and
refusable while understating is silent. Two adjacent defects in the same path also fixed —
`"prompt_tokens": -5` read as **zero** (the digit scan stopped at the sign), and a 20-digit value threw
a raw `NumberFormatException` that is not an `EmbeddingTransportException` and therefore escaped the
pipeline's retry handling entirely.

### 3.3 The adapter honours the declared result index

`parseResponse` scanned for `"embedding"` in document order and never read the `index` field. A
response returning elements out of order gave input 0 the vector labelled index 1 — count right,
dimensions right, every value finite, corpus silently wrong for ever.

Fixed, and the fix exposed a second bug while being written: matching the token `"embedding"` also
matched `"object":"embedding"`, where it appears as a *value*. The scan now requires a colon and an
array after the field name. Indexes that are not a permutation of `0..n-1` are refused rather than
guessed at; an absent index falls back to document order.

### 3.4 The batcher no longer serialises tenants behind one provider

`flushLoop` ran the provider call on the single flusher thread. Measured: with a 50 ms batch window and
tenant A's provider taking 3 s, **tenant B waited 5,887 ms** — a tenant-isolation failure on the
availability axis (AD-021).

Timeout-flushed batches now run on a bounded `ThreadPoolExecutor` (8 threads, 64-slot queue,
caller-runs on saturation). Caller-runs, not abort: a rejected batch has callers already blocked on its
futures. `close()` drains the dispatcher before failing anything still queued.

### 3.5 The negative cache no longer poisons on operator-fixable failures

`AUTH_FAILED` is not retryable — replaying a rejected credential is an attack on your own provider —
but it is also **not a property of the input**. Caching it meant an expired key made every attempted
input look permanently unembeddable, so rotating the credential did not restore service until the
negative TTL lapsed, with nothing in the logs to explain the delay. `INTERNAL` excluded on the same
grounds. `TOO_LARGE`, `REJECTED` and `DIMENSION_MISMATCH` are still remembered — those really are about
the input.

---

## 4. Preserved guarantees (mission §15 review gate)

| Does activation change… | Answer |
|---|---|
| **Governance** | No. `MemoryGovernancePort.admitWrite` still runs before anything is stored or embedded. Separately: `EmbeddingGovernancePort` still has **no production implementation** — see §6.3. |
| **PII** | No, and the ordering is favourable. Classification runs before embedding, and `PiiAction.REDACT` rewrites the body *before* `plaintext` is captured, so the redacted text is what gets embedded. `REFUSE` returns before any embedding. |
| **Crypto** | **Yes — see §5.4.** Sealing and embedding are independent, so sealed content is still embedded from plaintext. |
| **Memory durability** | No. Store-then-index preserved; embedding failure cannot fail a write. |
| **Tenant isolation** | **Yes — improved at the key (§3.1) and at the batcher (§3.4), and still blocked at the bridge (§5.1).** |
| **Audit** | No. `MemoryAuditPort` emission is unchanged and still fires after durability. |
| **Billing** | Yes, strictly safer: cost can no longer be understated (§3.2). |
| **Provider neutrality** | No. `gateway-dp-embedding` still contains zero vendor names; verified by sabotage N1. |
| **Agent Runtime / Plugin Runtime / streaming / backpressure** | No. Not touched. Backpressure semantics unchanged; the dispatcher queue is bounded and falls back to caller-runs. |

---

## 5. STOP — what blocks production activation

### 5.1 `EmbeddingPort` carries no scope, so cache isolation cannot be proven — B65

`MemoryRuntime` is built to be a multi-tenant singleton: `write()` takes the scope per call. But the
seam it calls is

```java
float[] embed(String text);          // AD-026 §12, MEM-3
```

no tenant, no scope. So `PipelineEmbeddingPort` must fix **one** tenant at construction. A single shared
runtime therefore caches every tenant's content under one tenant's key.

`MemoryRuntimeActivationTest.oneSharedBridgeCollapsesEveryTenantIntoOneCacheNamespace` pins this: two
tenants write identical text through one runtime and produce **one** cache entry. No content crosses a
boundary and every vector is correct — but the cache is a single namespace, and a hit is observable
through the ~10× latency gap ADR-030 §11.1 measured (2.0 µs vs 19.1 µs) and through a request that
costs nothing and generates no audit entry.

**Mission §5 says tenant isolation is a hard requirement and that I must fix the key rather than add a
test. I fixed everything the key can express (§3.1). This last dimension the key cannot see.**

Two ways out, both requiring a decision I am not authorised to take:

- **Widen `EmbeddingPort` to `float[] embed(MemoryScope scope, String text)`.** This does *not* violate
  MEM-1 (no provider named) or MEM-2 (no model, no vector arithmetic) — `MemoryScope` is C17's own
  type. It does change a frozen contract, and STOP condition 1 applies.
- **One `MemoryRuntime` per tenant.** No contract change, but `MemoryRuntime` is not shaped for it and
  the composition root would own tenant lifecycle.

**Not worked around.** A thread-local carrying the scope past the port would work and is exactly the
kind of hidden channel that leaks across tenants when a thread is reused.

### 5.2 No `EmbeddingTransportPort` implementation exists — B66

`grep -rn "implements EmbeddingTransportPort"` over the whole backend returns **one** hit: a test
double inside `OpenAiEmbeddingAdapterTest`. There is no HTTP adapter. `OpenAiEmbeddingProvider` is
complete and tested against recorded response shapes, and it cannot send a byte.

This is stronger than B57 ("unverified against the live API"). The accurate statement is: **B25 cannot
call any provider at all.**

Building it is tractable — JDK `HttpClient` is available and the wire format is already written — but
it is a new production adapter, and the mission scoped this milestone to connecting what exists.

### 5.3 Nothing reconciles `indexPending` — B67

`MemoryWritePipeline:266` says the record is "marked for the sweeper to reconcile". Verified:

- `MemoryRecord.indexed()` exists to clear the flag. **No caller anywhere in the repository.**
- `MemoryLifecycleSweeper` touches the index only to `remove()` during expiry and deletion.
- The flag *is* durable — `JournalCodec` persists it as `idxp`, and it surfaces on `MemoryOutcome`.

So the substrate for durable retry exists and the worker does not. A transient provider blip costs that
record its semantic findability **permanently**, with no recovery path but a manual rewrite.

Mission §12 is explicit: *"If the existing architecture has a durable mechanism, use it. If it does not,
identify this as a blocker instead of inventing an unreliable pseudo-queue."* The durable *state*
exists; the reconciler does not. Building one is a new C17 responsibility (a sweeper pass that scans
for `indexPending` and re-embeds), which is a Memory Runtime change this milestone is forbidden to make.

`MemoryRuntimeActivationTest.aRecordLeftIndexPendingIsNeverReIndexed` pins the gap so that building the
reconciler makes it fail.

### 5.4 Sealed content is embedded from plaintext, and no policy can say otherwise — B68

`requiresSealing(classification)` and `requiresEmbedding()` are independent. A `SEMANTIC` memory in a
scope with `encryptionRequired = true` is:

- stored as ciphertext, and
- **embedded from plaintext, with the vector written to the index unsealed.**

`EffectiveMemoryPolicy` has twelve fields and **none of them expresses "do not embed this"**. An
embedding is a lossy encoding of its source, and embedding-inversion is a documented attack class, so
this is a derived-plaintext channel that policy cannot currently restrict.

This is a **pre-existing property of AD-026's design**, not something this milestone introduced — but activation
is what turns it from latent into live, which is precisely what mission §4 asked me to check. It is
inert today only because nothing is wired and the sole `VectorIndexPort` implementation is in-memory.

Closing it needs a policy field (`semanticIndexingAllowed`, default-deny for sealed content), which
means changing `EffectiveMemoryPolicy`, `MemoryPolicySnapshot` and the policy compiler — C17 changes,
and a decision about default behaviour that belongs in an ADR.

### 5.5 Composition-root wiring is gated by AD-026, not forgotten

`gateway-dp-app` depends on no memory or embedding module and constructs `MemoryRuntime` nowhere. That
is AD-026 §15.2 Phase 6, and the ADR is unambiguous:

> **Phase 6 is the one that must not be brought forward.** Until phases 3–5 are real, an agent reaching
> memory would be reaching a store with no encryption, no classification and no semantics.

Phase 5 is *"Real embedding provider **+ vector adapter**"*. B25 delivered the provider; the durable
vector adapter does not exist (`VectorIndexPort` has one implementation, `InMemoryVectorIndex`). So
Phase 5 is half done and Phase 6 must not start. **Wiring the composition root now would violate the
governing ADR**, which is STOP condition 9.

---

## 6. Honest classification

### 6.1 Capability status

| Capability | Built | Tested | Wired | Live |
|---|---|---|---|---|
| Embedding provider port + canonical model | YES | YES | **NO** | **NO** |
| OpenAI adapter (wire format, mapping) | YES | YES | **NO** | **NO** |
| HTTP transport for that adapter | **NO** | — | NO | NO |
| Cache (with full-scope + provider key) | YES | YES | **NO** | **NO** |
| Batcher (with bulkhead) | YES | YES | **NO** | **NO** |
| Retry / failure policy / health | YES | YES | **NO** | **NO** |
| Cost estimation (overflow-safe) | YES | YES | **NO** | **NO** |
| Embedding governance admission | YES (port) | YES | **NO** (no adapter) | **NO** |
| `MemoryWritePipeline` → `EmbeddingPort` seam | YES | **YES (end to end)** | **NO** | **NO** |
| Vector index (durable) | **NO** | — | NO | NO |
| Index-pending reconciliation | **NO** | — | NO | NO |
| Search integration (semantic read) | YES | YES (in-memory index) | **NO** | **NO** |

"Wired" means a production composition root constructs it. Nothing does.

### 6.2 Blockers

| ID | Blocker |
|---|---|
| **B64** | Cache key cannot bind the record owner; `EmbeddingPort` carries no scope. Two users in one workspace share entries. |
| **B65** | **Frozen contract.** `EmbeddingPort` carries no scope, so one shared `MemoryRuntime` collapses all tenants into one cache namespace. Blocks activation. |
| **B66** | No `EmbeddingTransportPort` implementation exists. Embedding cannot reach a network. |
| **B67** | Nothing reconciles `indexPending`. Embedding failure is permanent loss of semantic findability. |
| **B68** | Sealed/SECRET content is embedded from plaintext and no policy field can forbid it. |
| **B58** | Token estimate undercounts non-Latin script ~4×; unsafe for admission. Carried from B25, unchanged. |
| **B57** | Adapter never run against the live API. Carried from B25. |
| **B62** | Composition root does not construct `MemoryRuntime`. **Correctly deferred** by AD-026 §15.2. |

### 6.3 Also true

`EmbeddingGovernancePort` has no production implementation — every reference is `PERMISSIVE` or a test
lambda, and nothing bridges to `gateway-dp-governance-engine`. B25's "integrate with Governance" is a
port awaiting an adapter, and should be described that way.

---

## 7. Why no ADR-031

The mission says to create one *"only if this milestone genuinely requires an architectural decision"*.
It does not — because this milestone **stopped** rather than deciding. Everything delivered is a
correction inside B25's own module; nothing new was decided.

**ADR-031 becomes necessary when the Council decides B65 and B68**, and it should decide exactly:

1. Whether `EmbeddingPort` gains a `MemoryScope`, or a `MemoryRuntime` is instantiated per tenant.
2. Whether sealed content may be semantically indexed, and what the default is.
3. Who owns index-pending reconciliation and with what delivery guarantee.

Writing those decisions down before they are taken would be documentation pretending to be
architecture.

---

*End of document — docs/39-EmbeddingRuntime-Activation.md*
