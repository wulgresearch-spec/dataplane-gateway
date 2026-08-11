# AD-030 — Production Embedding Provider

**Status:** Proposed
**Date:** 2026-08-07
**Supersedes:** nothing. **Superseded by:** nothing.
**Closes:** B25 (Real Embedding Provider).
**Depends on:** AD-026 (Memory Runtime, C17), AD-007 (multi-provider abstraction), AD-025A (Provider Abstraction V2), AD-021 (tenant isolation).
**Modules:** `backend/dataplane/modules/gateway-dp-embedding` · `backend/dataplane/providers/gateway-provider-openai`

---

## 1. Context

AD-026 §12 defined the whole of the Memory Runtime's relationship with embedding technology:

```java
float[] embed(String text);
```

No model, no dimension, no provider, no tokenizer — MEM-2 forbids an embedding model inside C17 and
MEM-1 forbids naming a provider anywhere in it. AD-026 §14.1 then recorded the consequence as **B25**:

> **B25** — No real embedding provider. Port only.

This milestone builds what sits behind that port: provider-neutral embedding **generation**, with
batching, caching, retry, budget, health, cost and audit. It builds no index, no similarity, no
retrieval and no RAG. Those are the vector-adapter half of AD-026 §15.2 Phase 5 and later, and §15
records what was deliberately not built and how that boundary is enforced rather than merely stated.

### 1.1 The state this milestone started from

A previous session had written most of the module and stopped mid-flight. What was found, and what was
done about it, is recorded here rather than quietly tidied away, because three of the five were
**defects in shipped logic** rather than unfinished work — and one of those three would have silently
authorised a thousand times the intended spend:

| Found | Action |
|---|---|
| The module was not registered in `backend/pom.xml` or in `dependencyManagement`, so none of it was built or tested by anything. | Registered. |
| One test file did not compile: Java string literals carrying an extra level of escaping. | Fixed; the escaping case it was trying to test is now asserted correctly (§12.2). |
| Model prices were **off by a factor of a thousand** — the published dollar-per-million figure written where micros-per-million was required. | Fixed, and the unit is now asserted by a test whose name states the trap (§8.1). |
| The cache's capacity eviction freed at most one entry per store and re-read a stale size, so the bound never recovered from an overshoot and every store at capacity walked the whole map. | Rewritten to evict to a low-water mark (§6.4). Found by a concurrency test, not by review. |
| The vendor adapter sat in `dataplane/modules`, which the repository-wide `ProviderNeutralityTest` forbids. | Moved into `dataplane/providers/gateway-provider-openai` (§3.4). |

---

## 2. Decision

Build a provider-neutral embedding layer as its own module, behind one port, with exactly one place
where a vendor is known.

```
   Memory Runtime (C17)
          │  float[] embed(String)          ← unchanged, AD-026 §12
          ▼
   PipelineEmbeddingPort                    ← the bridge, and the limitation (§3.2)
          ▼
   EmbeddingBatcher                         ← coalesce many callers into few calls
          ▼
   EmbeddingPipeline                        ← cache → budget → split → attempt → normalize
          ▼
   EmbeddingProviderRegistry                ← which provider serves which neutral model
          ▼
   EmbeddingProviderPort                    ← THE seam
          ▼
   OpenAiEmbeddingProvider                  ← the only file that knows a vendor
          ▼
   EmbeddingTransportPort                   ← the only thing that may touch a network
```

Everything above `EmbeddingProviderPort` is neutral, and that is enforced structurally rather than by
convention: `EmbeddingNeutralityTest` scans every source file in the module for vendor names,
endpoints, network types and vector-index vocabulary, and the repository-wide `ProviderNeutralityTest`
independently forbids a vendor name in any production source outside `dataplane/providers`.

**The Memory Runtime is not modified.** Not one line. Removing this module restores the previous
behaviour exactly.

---

## 3. The ports

### 3.1 `EmbeddingProviderPort` — batch-shaped, single-attempt

```java
ProviderId          id();
EmbeddingCapability capability();
EmbeddingResponse   embed(EmbeddingRequest request);   // exactly one attempt
EmbeddingHealth     health();
```

Three decisions are worth stating.

**Batch-shaped.** Every provider worth using charges less and answers faster for a batch than for the
same inputs one at a time. A single-text port would make batching impossible to express without a
second interface, so a single-text request is simply a batch of one.

**Implementations must not retry.** One call here is one attempt. Retry, backoff, budget and failure
policy live in `EmbeddingPipeline` so they are decided once for every provider, rather than
reimplemented — differently, and discovered to be different during an incident — inside each adapter.

**A provider-side refusal is a result, not an exception.** A rate limit, a rejection or an oversized
input comes back as a `Failed` outcome for that input. Only a failure that happened *before the
provider reached any verdict* throws, because that is the one case where nothing is known about
whether the work happened — which is exactly the case retry must always cover (§9).

### 3.2 The C17 bridge, and the limitation at the heart of this milestone

`EmbeddingPort` is `float[] embed(String)`. That is the whole of what C17 hands over, and widening it
would be a Memory Runtime change this milestone is forbidden to make. Three consequences follow, and
all three are limitations rather than choices:

- **The tenant is fixed at construction.** One `PipelineEmbeddingPort` serves one tenant. A deployment
  serving many needs one instance per tenant, which works but is not what a single `MemoryRuntime` is
  wired for today. **B55.**
- **Batching only happens across concurrent callers.** A single-threaded writer waits out the batch
  timeout and then sends a batch of one. **B56.**
- **A failure becomes an exception with no reason.** The port returns `float[]`, so there is nowhere to
  put a neutral failure kind; every refusal collapses to `MemoryStoreUnavailableException`, which C17
  already handles by refusing the write. The reason is not lost — metrics and audit recorded it first —
  but it is not visible to the caller.

`MemoryRuntimeBridgeTest` asserts, against the compiled interface, that `EmbeddingPort` still declares
exactly one method taking a `String` and returning `float[]`, and that its surface names no provider,
model, dimension or tenant. That test exists to fail if a future milestone "improves" the bridge by
leaking provider knowledge upward.

### 3.3 `EmbeddingTransportPort` — the only network seam

Deliberately narrow, and deliberately owned here rather than reused from the request pipeline's
`ProviderTransportPort`. That port carries a credential lease, an attempt budget, a pinned version and
a route target, all belonging to the inference path's routing and reliability machinery. An embedding
call has none of those concerns, and dragging them in would couple this module to the whole request
pipeline for no gain.

Narrowness is also what makes the adapter testable: a fake transport replaying recorded response shapes
exercises every line of it, which matters because no provider is reachable from this environment (§12).

### 3.4 Why the adapter lives in the provider module

`ProviderNeutralityTest` — which predates this milestone — scans every production source outside
`dataplane/providers` for eleven vendor names and fails the build on a hit. A vendor adapter under
`dataplane/modules` therefore does not survive the suite, whatever package it hides in. The rule was
found the hard way here: the adapter was written into `gateway-dp-embedding/internal` and the full
suite rejected it.

The outcome is better than the intent. `gateway-dp-embedding` now contains **no vendor name at all**,
rather than containing one in an agreed-upon corner, and `EmbeddingNeutralityTest` can assert over the
whole module with no exempt directory.

---

## 4. Models and capability

### 4.1 A model id is a deployment's label, not a vendor's product name

```java
record EmbeddingModel(String id, int dimension, int maxInputTokens, long costPerMillionInputTokensMicros)
```

`text-default-1536` is a neutral id. Mapping it onto whatever a vendor calls the thing happens in the
adapter and nowhere else, which is what lets a deployment repoint that id from one vendor to another
without a line changing above the adapter layer.

`EmbeddingCapability` declares what one provider can do — models served, maximum batch size, maximum
payload bytes, whether vectors arrive normalized, whether dimension reduction is offered. **Declared
rather than discovered**, because the alternative is finding out in production. The batcher reads these
limits and splits accordingly; nothing in the pipeline guesses at them.

### 4.2 The registry, and why selection is deterministic

Registration is the only place a vendor enters the system. Selection is by neutral model id, ordered
**healthy before degraded before unavailable, then by provider id** — and the determinism matters more
than the cleverness.

An embedding from provider A and one from provider B are vectors in different spaces. A registry that
silently failed over mid-corpus would write records that no later query can match, and would do it
without an error appearing anywhere. `CanonicalEmbedding` records which provider produced it precisely
so that mismatch is *detectable* rather than mysterious.

One deliberate exception: **an unavailable provider is still selected when it is the only one.**
Refusing to call the only provider available would convert a transient outage into a permanent one and
would remove the only route back to health, because health here is observed from real traffic and there
is no probe to recover with (§7.3). **B59.**

---

## 5. `CanonicalEmbedding` — where provider differences stop

```java
record CanonicalEmbedding(
    ProviderId providerId, String modelId, int dimension, Instant createdAt,
    EmbeddingUsage usage, boolean normalized, Map<String,String> metadata, float[] vector)
```

Every provider returns something different: a different dimension, a different envelope, normalized or
not, usage reported elsewhere or not at all. This is where all of that ends. Above the adapter layer,
this is the only embedding shape anything sees.

**Validation is in the constructor, not in the pipeline.** A vector whose length disagrees with its
declared dimension cannot be *held*, not merely cannot be produced — so no future caller can route
around the check.

**The vector is copied in and copied out.** A record that hands out its own array is a record whose
contents any caller can rewrite, and an embedding mutated after the cache stored it would poison every
later hit. The copy costs about 130 ns for 1536 floats (§11); the bug class it removes is silent.

**`toString` prints provenance and not components.** Three thousand floats in a log line are useless to
read and are a copy of derived tenant content in a place nobody audited. `EmbeddingRequest.toString`
does the same for its inputs, which are frequently the same content the PII engine (AD-029) has just
classified as sensitive.

### 5.1 Normalization has two jobs, and the second is the one that matters

**Unit length.** Some providers normalize, some do not. Cosine similarity over unnormalized vectors is
simply wrong, and the error is silent — results still come back ranked, just ranked badly. Normalizing
here lets the future vector index assume it.

**Width.** A provider returning a different dimension than the model declares is **refused**, not padded
and not truncated. Silently reshaping produces an index that looks healthy and retrieves nonsense, which
is far worse than a write that fails loudly. Truncation happens only where a provider declares it
supported, and the result is renormalized, because a truncated unit vector is no longer a unit vector.
A vector *narrower* than declared is always refused: truncation can shorten a vector, nothing can
lengthen one, and zero-padding would be inventing components.

A zero-magnitude or non-finite vector is refused rather than divided by. Without that, every component
becomes NaN and the failure surfaces much later as a query that matches nothing, in a component that
did nothing wrong.

---

## 6. The pipeline

The order is the design. Each stage exists to stop work reaching the next one.

1. **Cache**, positive and negative — the cheapest provider call is the one not made.
2. **Governance**, on the misses only, and *before* any call.
3. **Split** into provider-sized pieces.
4. **Attempt** with retry, one piece at a time.
5. **Normalize** and width-check, so nothing above ever sees a provider-shaped vector.

Outcomes come back aligned to the request whatever route each input took. A caller cannot tell a cache
hit from a fresh embedding except by the usage figures, which is the point.

**Cache before governance, deliberately.** A budget check on work that will be served from memory would
refuse spend that was never going to happen. `EmbeddingPipelineTest` asserts governance is not consulted
at all for a fully-cached request.

### 6.1 The cache

Keys are **SHA-256 over tenant, model and text, each length-prefixed**. Length-prefixing matters for the
same reason it did in AD-028: concatenating fields with a separator lets a cunningly chosen tenant name
collide with another pair, and here a collision would hand one tenant an embedding computed for another.
`EmbeddingCacheTest` asserts that `("ac","me")` and `("a","cme")` do not collide.

**The tenant is in the key even though the vector does not depend on it.** The same text under the same
model embeds identically for everyone, so a shared cache would be arithmetically correct and still
wrong: a cross-tenant hit is an oracle telling tenant B that tenant A has embedded a particular string.
Paying for the duplicate work is the cheaper mistake.

**The negative cache** remembers inputs that failed in ways retrying cannot fix — too long, rejected.
Without it, a document that will never embed is re-sent on every write attempt and the provider charges
for every one of those refusals. Only non-retryable reasons are remembered: caching a rate limit would
turn a momentary throttle into a lasting refusal, which is precisely the wrong response to
back-pressure.

### 6.2 Batching: two separate mechanisms

Conflating these is a common way to get batching wrong.

**Splitting** is a pure function: a request of any size becomes a list of requests each within the
provider's declared batch and payload limits. No threads, no timing, no state — which is why it is a
static method and is exhaustively testable. An input that alone exceeds the payload limit still gets its
own piece rather than being dropped locally; refusing it is the provider's answer to report, and
inventing the refusal here would mean two places deciding what is too large.

**Coalescing** is the part that needs a clock. Inputs are grouped by tenant, model and purpose, because
a provider call carries one model and mixing tenants in one call would make per-tenant cost attribution
guesswork. Back-pressure is made visible rather than absorbed: past `maxQueueDepth`, submission is
refused. Growing the queue without bound converts a slow provider into an out-of-memory failure, which
is a worse outage than a refused write.

Shutdown completes every waiting future with a refusal rather than abandoning it. A caller blocked on a
future nobody will complete is the shutdown bug that survives to production, because it only appears
when something else is already going wrong.

### 6.3 The batch-alignment hazard

The batcher completes futures by position within a drained batch. An off-by-one there gives every caller
a plausible vector belonging to its neighbour — the single most damaging bug this component can have,
and one that no count-based assertion can see, because the counts are all correct.

`EmbeddingConcurrencyTest` therefore asserts on **identity**: a hundred concurrent callers each submit a
distinct text and each verifies it received the vector for *its own* text. Sabotage S10 (§16) confirms
that test fails when the alignment is broken.

### 6.4 Eviction, and the defect a concurrency test found

The original eviction freed at most one entry per store and computed remaining capacity from a stale
count. Two consequences, neither visible single-threaded at low volume:

- At capacity, **every** store walked the entire map to remove one entry — O(n) work per write, for ever.
- Under concurrent writers it never caught up: each removed one and added one while the others also
  added, so the cache settled at whatever high-water mark the race produced. With a ceiling of 50 and a
  hundred concurrent writers, the observed resident size was **441**.

It now evicts down to a low-water mark (a tenth below capacity), amortising the walk and letting one
writer pull an overshoot back in a single pass.

The bound is still **soft**, and that is stated rather than hidden: there is no lock, so any number of
writers can pass the capacity check before any of them stores, and the cache can exceed `maxEntries` by
roughly the number of concurrent writers. Closing that gap needs a lock on the write path, and a
slightly larger cache is cheaper than a contended one. What is guaranteed is that the overshoot is
bounded and transient rather than permanent — the difference between a memory ceiling and a memory leak.

---

## 7. Retry and failure

### 7.1 What is retried is decided in one place

`EmbeddingFailure` carries the classification, so the rule lives on the enum rather than in a condition
each adapter writes for itself.

| Retried | Never retried |
|---|---|
| `RATE_LIMITED` (429) | `REJECTED` (400, 404) |
| `TIMEOUT` (408, 504) | `AUTH_FAILED` (401, 403) |
| `UNAVAILABLE` (5xx) | `TOO_LARGE` |
| `NETWORK` (reset, connect failure) | `DIMENSION_MISMATCH`, `BUDGET_EXCEEDED`, `INTERNAL` |

This is the mission's retry list exactly. Repeating a 400 repeats the same mistake at the same cost;
repeating a rejected credential is an authentication attack against your own provider. `EmbeddingDomainTest`
asserts the verdict for **every** enum constant, so adding a failure kind without deciding its retry
behaviour fails the build.

### 7.2 Backoff is exponential with full jitter

Equal backoff across many callers reconverges them on the same instant and turns one rate limit into a
standing wave of them; full jitter spreads retries across the whole window rather than the top half.
The ceiling is applied before the shift so a long-lived retry loop cannot overflow into a negative
delay. The jitter source is injectable solely so tests are deterministic.

### 7.3 Health is observed, never probed

A provider is degraded after three consecutive failures and unavailable after ten; one success clears
the streak completely. This is a plain failure count, **not** a circuit breaker with half-open probing.
A breaker here would need a probe request, and a synthetic probe against a paid endpoint is a bill that
arrives whether or not anyone is using the feature. Recovery is therefore immediate rather than gradual,
because there is nothing to recover *with* except real traffic.

An exhausted failure is reported per input, never thrown. The write pipeline above can then decide for
itself whether a memory without an embedding is worth storing — a policy question belonging to C17, not
one this module should pre-empt.

---

## 8. Cost and governance

Governance is asked **before** any provider is called, on the cache misses only. Asking permission once
the money is spent is not governance, it is reporting. `EmbeddingPipelineTest` asserts that a DENY
results in zero provider calls.

`EmbeddingGovernancePort` is separate from the memory governance port on purpose: that one decides
whether content may be stored, this one decides whether money may be spent, and conflating them would
let a storage policy silently authorise expenditure.

### 8.1 Micros, and the thousand-fold trap

Cost is integer **micros** throughout — a millionth of a currency unit, matching `Money` in the Cost
Engine. Accumulating cost as a double across a million records drifts, and a billing figure that drifts
is a billing figure that gets disputed.

`costPerMillionInputTokensMicros` is the price of one million input tokens *in micros*. A published
price of $0.02 per million tokens is therefore **20 000**, not 20. The values shipped in this milestone
were originally written as 20 and 130 — off by a factor of a thousand, which is not a rounding error but
a budget check that admits a thousand times the spend the operator authorised, silently, on the
cheapest-looking path in the system. Both the field's documentation and a test named
`pricesAreMicrosPerMillionTokensNotDollarsPerMillionTokens` now state the trap explicitly.

Rounding is **up**, with a floor of one micro for any chargeable call. Rounding down would let a large
number of small requests each cost nothing, which is the shape of every metering argument.

### 8.2 The estimate is not very good, and this is the honest part

Real tokenisation is model-specific, and running a provider tokeniser here would mean shipping a vendor
vocabulary into a module whose entire purpose is not knowing which vendor is behind it. So the estimate
is **four characters per token**.

That is close for English prose and materially wrong elsewhere. Code and punctuation-heavy text run
nearer two characters per token. CJK text runs at roughly one, where this **under-counts by about four
times** — a test asserts exactly that, so the weakness stays visible rather than becoming folklore.

The consequence is real: a budget check that passes when the true spend would have exceeded it. The
estimate is biased upward with a floor per input so short strings are not counted as free, but that does
not close the gap. The honest fix is a token-count port implemented per adapter. It is **B58**, and it
is not built.

---

## 9. Failures are per input, not per batch

A batch of sixty-four in which one input is too long must return sixty-three embeddings and one refusal.
Failing the whole batch would let a single oversized record block every write batched alongside it, and
callers would have no way to tell which input was at fault.

`EmbeddingResponse` therefore carries one `Outcome` per input, in request order — a sealed interface of
`Embedded` and `Failed`. A width mismatch discovered during normalization fails that input alone and is
never retried, because the provider will return the same width next time.

---

## 10. Observability

`EmbeddingMetricsPort` reports request latency, provider-call latency and batch size, cache hit and
miss, negative-cache hit, retries by reason, failures by reason, cost in micros and input tokens, and
queue depth. Every parameter is an identifier, an enum, a count or a duration; none is input text, and
none can be widened to carry it without changing the interface.

Two signals are worth more than the rest. The **cache hit ratio** decides whether the spend is what
anyone expects — the difference between "cheap because the cache is working" and "cheap because
everything is failing" is invisible in the result and entirely visible here. The **queue depth** is the
only warning that the batcher is absorbing more load than the provider is draining, and it is the number
that goes from fine to catastrophic without passing through concerning.

`EmbeddingAuditPort` records counts and costs, never content. An audit trail quoting embedded text would
be a second copy of tenant data in the store most likely to be exported for compliance review.

---

## 11. Benchmarks

Wall-clock timing on a developer laptop (Windows 11, JDK 21), not JMH: no fork isolation, no blackholes,
and the JIT warmed by a fixed iteration count rather than to a steady state. **Treat anything within a
factor of two as equal.** The provider double returns instantly, so every figure is the *gateway's own
overhead* — the only part this milestone controls.

### 11.1 End to end, single thread

| Input | Cache miss | Cache hit |
|---|---|---|
| 64 B | 24.7 µs | 2.4 µs |
| 1 KB | 19.1 µs | 2.0 µs |
| 8 KB | 20.3 µs | 10.0 µs |
| 64 KB | 89.3 µs | 75.4 µs |

A **cache hit at 1 KB is roughly ten times cheaper than a miss**, and the miss figure excludes the
provider call entirely. Against a real provider at 50–500 ms, the hit is three to four orders of
magnitude cheaper. That ratio is the whole argument for the cache.

Hit cost grows with input size while miss cost barely does, because a hit is dominated by hashing the
input for the key while a miss is dominated by fixed per-vector work. At 64 KB the two converge —
hashing 64 KB costs about as much as everything else put together.

### 11.2 Components

| Operation | Cost |
|---|---|
| `normalize`, 1536 floats | 1.90 µs |
| `conform`, 1536 floats | 1.88 µs |
| cache key, SHA-256 over 1 KB | 1.24 µs |
| cost estimate | 2 ns |
| defensive vector copy out, 1536 floats | 0.76 µs |

The defensive copy that `CanonicalEmbedding` makes on every read costs **0.76 µs** — about 4 % of a
cache miss and a third of a cache hit. That is the price of the whole class of bug in §5, and it is
worth it.

### 11.3 A performance defect found and fixed

`normalize` originally measured **9.7 µs** — five times the figure above, and the dominant term in the
entire normalization path. The cause was a per-component float **divide** in the scaling loop. Every
embedding the gateway produces passes through that loop exactly once, so it is as hot as any code in
the module.

Computing the reciprocal once and multiplying brought it to **1.90 µs, a 5.1× improvement**, and pulled
the end-to-end 1 KB miss from 24.1 µs to 19.1 µs. The result differs from a per-component divide by at
most one ulp, far inside the one-part-in-ten-thousand tolerance `isUnitLength` applies, and the full
suite is unchanged.

### 11.4 Concurrency

| Threads | Latency per op | Throughput |
|---|---|---|
| 1 | 18.7 µs | 53 k ops/s |
| 8 | 44.4 µs | 161 k ops/s |
| 16 | 85.9 µs | 165 k ops/s |

Throughput scales 3× from one thread to eight and then **flattens** — sixteen threads deliver
essentially what eight do, at double the per-operation latency. The JVM reports 16 available
processors, so the plateau arrives at half of them.

The shape is consistent with resource saturation rather than lock contention — per-op cost rises in
proportion to the thread count once throughput stops improving, which is what a full machine looks
like, whereas a contended lock usually shows throughput *falling* as threads are added. That is an
inference from the shape, not a measurement: no profiler was run, and whether the limit is physical
cores, memory bandwidth or allocation rate is **not established here**. What can be said firmly is that
the pipeline takes no locks on the hot path — `ConcurrentHashMap` for the cache, an `AtomicReference`
per provider for health — and that nothing in it creates threads of its own, which
`aConcurrentBurstHoldsNoMoreProviderCallsOpenThanThePoolAllows` asserts directly.

### 11.5 Batching, and an honest reading of a bad-looking number

| Configuration | Throughput | Provider calls for 32 000 inputs |
|---|---|---|
| 16 threads, direct | 165 k ops/s | 32 000 |
| 16 threads, via batcher (10 ms window) | 1.3 k ops/s | **2 001** (mean batch 16.0) |

Taken alone the batcher looks catastrophic: **125× less throughput**. Taken with the second column it
is doing exactly its job — the same work in **one sixteenth of the provider calls**.

The benchmark's provider returns instantly, so coalescing can only ever cost latency here. Against a
real provider a call is 50–500 ms, sixteen inputs per call is sixteen times fewer round trips and
sixteen times fewer rate-limit tokens, and the 10 ms window is noise beside it. The mean batch of
exactly 16.0 is also the clearest possible statement of **B56**: it equals the thread count, because
each caller blocks on its own submission, so the batch is precisely "however many callers happened to
be in flight" and never more.

**Do not enable the batcher in front of a fast local provider.** It is for the case it was built for.

### 11.6 Allocation

| Measurement | Cost |
|---|---|
| pipeline miss + cache store, 1 KB input | 13.0 KB retained per op |
| cache resident cost, 1536-float vector | 3.2 KB per entry |

A 1536-float vector is 6 KB on its own; a cached entry costs 3.2 KB because the benchmark's provider
returns one shared template array that the cache's entries reference after copying — so the figure is a
floor, not a typical case. Sized honestly: **a 100 000-entry cache of 1536-dimension vectors needs
roughly 600 MB–1 GB of heap.** That is the number an operator must plan for, and `maxEntries` is the
knob.

### 11.7 Method and its limits

Wall-clock on one machine, single JVM, no fork isolation, no blackholes, GC not controlled for. The
allocation figures come from `Runtime.totalMemory() - freeMemory()` around a `System.gc()`, which is
indicative and not authoritative. Every figure is from a provider double that returns instantly, so
none of them says anything about end-to-end embedding latency in production — only about what this
gateway adds.

---

## 12. The adapter, and what is not verified

### 12.1 Verification limit, stated plainly

**No OpenAI endpoint is reachable from the environment this was written in.** Every path in
`OpenAiEmbeddingProvider` is exercised against a transport double replaying recorded response *shapes*;
none has been run against the live API. The JSON handling, the header set and the error mapping are
therefore a real check of the adapter's own logic and **no check at all** of whether those shapes match
what the service sends today. **B57.**

Status classification is delegated to `HttpStatusErrorClassifier` from Provider Abstraction V2 rather
than re-derived, so embedding retries behave like every other retry in the gateway instead of like a
second, subtly different policy nobody remembers exists.

### 12.2 Hand-rolled JSON, and the case it exists for

The module has no JSON dependency and adding one for two shapes would be a poor trade, so encoding is a
purpose-built writer and decoding is a purpose-built scanner that refuses anything it does not
recognise. A general parser that accepted a surprising document would hand a malformed vector to the
normalizer.

Control characters are escaped numerically. That is the case a naive escaper gets wrong, and it arrives
the first time someone embeds a document containing a tab or a `0x01` — at which point the provider
rejects the whole body and it presents as a provider fault rather than as an encoding bug.

The credential is read from a `Supplier` at call time rather than captured at construction, so a
rotation is a non-event rather than a delayed outage. A test asserts a rotated key reaches the second
call.

### 12.3 The local reference provider is not an embedding provider

`DeterministicEmbeddingProvider` computes vectors from a hash. **These are not embeddings.** They are
deterministic and well-distributed and carry no semantic information whatsoever; two paraphrases of the
same sentence are as far apart as two unrelated ones.

It is named for what it is, for the same reason `NotRealCryptoSealer` was in AD-026: a class called
`DefaultEmbeddingProvider` gets deployed by someone in a hurry. It exists so the whole pipeline can be
exercised end to end without a network, and so a deployment has something to run against while
credentials are being arranged. **Semantic retrieval over these vectors returns arbitrary results.**

---

## 13. Security

| Requirement | How it holds |
|---|---|
| No provider secrets in Memory Runtime | The credential is a `Supplier<String>` held only by the adapter, in the provider module. C17 links against `float[] embed(String)`. |
| No vendor branching | `EmbeddingNeutralityTest` scans every module source for eleven vendor names; `ProviderNeutralityTest` scans the whole backend outside `dataplane/providers`. |
| No provider literals outside adapters | Same two checks; the module contains no vendor name at all (§3.4). |
| No network outside provider adapters | `EmbeddingNeutralityTest` forbids `java.net.`, `HttpClient`, `Socket` and `URLConnection` anywhere in the module. Even the adapter reaches the network only through `EmbeddingTransportPort`. |
| Tenant isolation | Cache keys are length-prefixed SHA-256 over tenant, model and text (§6.1). Asserted against boundary-shifting collisions and under 100-way concurrency. |
| No content in observability | Every metrics and audit parameter is an id, enum, count or duration. `CanonicalEmbedding.toString` and `EmbeddingRequest.toString` are overridden to omit vectors and inputs. |

---

## 14. Honest limitations

| # | Limitation |
|---|---|
| **B55** | `EmbeddingPort` carries no tenant, so `PipelineEmbeddingPort` fixes one at construction. A multi-tenant deployment needs one bridge instance per tenant; a single `MemoryRuntime` is not wired for that. Closing it requires a C17 signature change. |
| **B56** | Batching only coalesces *concurrent* callers. A single-threaded writer pays the batch timeout and then sends a batch of one. Inherent to a synchronous single-text port. |
| **B57** | The OpenAI adapter has never run against the live API. Wire format, headers and error mapping are unverified against the real service. |
| **B58** | Token counts are a four-characters-per-token heuristic, not tokenisation. Under-counts non-Latin script roughly fourfold, so a budget check can pass when the true spend would exceed it. Needs a per-adapter token-count port. |
| **B59** | With one provider configured, an unavailable one is still called. Deliberate (§4.2), but it means health cannot protect a single-provider deployment from anything. |
| **B60** | `invalidateTenant` clears the whole cache. Keys are hashes, so entries cannot be selected by tenant without a reverse index. Correct but over-broad; other tenants lose their entries too. |
| **B61** | Eviction is not LRU and the capacity bound is soft under concurrency (§6.4). Expect a lower hit ratio than LRU when the working set exceeds the cache, and a transient overshoot of roughly the concurrent-writer count. |
| **B62** | **Nothing constructs this pipeline at startup.** The module is built and tested but is not wired into `gateway-dp-app`, and `MemoryRuntime` is still assembled with whatever `EmbeddingPort` its own composition supplies. Wiring it is a composition-root change, and the mission scoped Memory Runtime changes out. Until B62 is closed, this milestone ships capability, not behaviour. |
| **B63** | ArchUnit is not available in this environment, so the module has no ArchUnit layering test to match its peers. Neutrality and layering are asserted by source-scanning tests instead, which catch names but not, for example, an illegal call direction that uses no forbidden name. |

### 14.1 Also true, and not a gap

Cost figures are estimates that governance acts on *before* the call; the invoice figure comes from the
provider afterwards. §8.2 quantifies the error. This is a property of pre-authorisation, not a defect,
but a deployment reconciling spend should expect the two to differ.

---

## 15. What this milestone deliberately did not build

No vector index. No approximate nearest neighbour, no HNSW, no FAISS. No similarity function, no
ranking, no retrieval, no RAG. No Memory Runtime, Agent Runtime or Plugin Runtime change.

The boundary is enforced, not merely declared: `EmbeddingNeutralityTest` fails the build if `hnsw`,
`faiss`, `annoy`, `cosinesimilarity` or `topk` appears anywhere in the module. A half-built index
landing here under cover of "just a helper" is the usual way that boundary erodes.

---

## 16. Sabotage verification

Ten mutations, each applied to the real source, rebuilt, run against the affected packages, then
restored and verified byte-identical by SHA-256. A mutation that left the suite green would be reported
as a coverage gap, not as a pass.

Baseline before mutation: **383 tests green** across `…dataplane.embedding`, `…provider.openai` and
`…dataplane.app.runtime`.

| # | Mutation | Detected by |
|---|---|---|
| **S1** | Splitting ignores the provider's batch and payload limits; every request goes as one piece. | **3** — batch-size split, payload-size split, pipeline split-on-limit |
| **S2** | Every positive cache lookup misses. | **9** — cached request costs nothing, partly-cached sends only misses, second request skips the provider, governance not consulted for a cached request, 100 concurrent same-text agree, plus four cache tests |
| **S3** | A width mismatch is padded or truncated instead of refused. | **4** — pipeline fails that input rather than corrupting it, refuses wider without declared truncation, refuses narrower, names the widths |
| **S4** | The neutral pipeline learns a vendor name and branches on it. | **2** — the module's own neutrality scan **and** the repository-wide `ProviderNeutralityTest` |
| **S5** | Permanent failures become retryable. | **13** — pipeline does-not-retry, six per-kind assertions, six parameterised verdicts |
| **S6** | The cache key drops the tenant. | **6** — cross-tenant isolation in pipeline, under concurrency, and through the C17 bridge; negative-cache isolation; boundary-shift collision; key differs per field |
| **S7** | The budget check is skipped. | **2** — governance refuses before any provider call; the bridge surfaces the refusal |
| **S8** | Capacity eviction never runs. | **4** — concurrent ceiling, batch eviction, ceiling held, ceiling recovered |
| **S9** | Model prices written as dollars where micros are required. | **1** — the adapter price-unit assertion |
| **S10** | The batcher completes each caller's future with its neighbour's outcome. | **1** — 100 concurrent callers each receive their own text's vector |

All ten detected. All ten files restored and verified **byte-identical by SHA-256**.

### 16.1 The harness reported ten false passes before it reported ten real ones

The first run said all ten mutations were detected. None of them had been: the harness invoked `bash`,
which on this machine resolves to WSL rather than Git Bash, so every build died instantly with a
non-zero exit — and a harness that treats "non-zero exit" as "mutation detected" reports a perfect
score for a suite that never ran.

It was caught because every mutation reported **zero failing test names** while claiming detection. A
sabotage that kills nothing by name has not been demonstrated to be caught by anything.

Two changes followed, and they are the reason the table above can be believed:

- **Git Bash is pinned by absolute path.**
- **The harness runs an unmutated baseline first and aborts if it is not green**, and each mutated run
  must contain `Test run finished` or it is reported as `HARNESS-ERROR` rather than as a detection.

A verification harness that cannot fail is not a verification harness. This one could, did, and now
records the named tests that die for each mutation.

### 16.2 What sabotage did not cover

The mutations are single-point textual edits to production source. They do not model a wrong
*interaction* between two correct components, and they do not touch the adapter's wire format beyond
pricing — which §12.1 already records as unverified.

---

## 17. Test report

**Full backend suite: 21 510 tests, 0 failures.** The baseline before this milestone was 21 296, so
B25 adds **214**: 184 in `gateway-dp-embedding` and 30 in `gateway-provider-openai`.

| Suite | Tests | Covers |
|---|---|---|
| `EmbeddingPipelineTest` | 22 | Stage order, cache hit/miss/partial, tenant isolation, governance admit and deny, retry on transient, no-retry on permanent, retry exhaustion, timeout, health degradation, partial batch failure, negative cache, width mismatch, unserved model, splitting, usage, determinism |
| `EmbeddingBatcherTest` | 12 | Split by count, split by bytes, order and coverage, oversized single input, concurrent coalescing, timeout flush, full-batch immediate send, queue-full back-pressure, shutdown completion, executor failure isolation, queue-depth drain |
| `EmbeddingCacheTest` | 19 | Key shape, determinism, per-field variance, boundary-shift collision resistance, empty text, hit/miss accounting, TTL expiry and eviction-on-read, copy-out, negative cache including refusal to cache transient failures, negative TTL, negative-cache tenant isolation, capacity ceiling, batch eviction, overshoot recovery, invalidation |
| `EmbeddingDomainTest` | 51 | Normalization (unit length, idempotence, no input mutation, zero-magnitude, NaN and infinity, conform exact/wider/narrower/truncating, error text); retry (four transient, six permanent, every enum constant, attempt budget, exponential backoff and ceiling, full jitter, out-of-range jitter, invalid policies); cost (chars-per-token, floor, growth, batch sum, micros rounding, non-zero floor, free models, model limit, CJK under-count); failure policy (fresh, degraded, unavailable, streak reset, last reason, last success preserved, invalid thresholds) |
| `EmbeddingConcurrencyTest` | 10 | **100 concurrent requests** for distinct texts and for one text, concurrent tenant isolation, **100 concurrent callers through the batcher each receiving their own vector**, coalescing, queue drain, no self-inflicted concurrency, cross-pipeline determinism, reference-provider determinism, concurrent cache bound |
| `EmbeddingProviderRegistryTest` | 13 | Empty registry, model-based selection, unserved model, deterministic tie-break, healthy over degraded, unavailable skipped, unavailable-but-only still selected, recovery, per-provider health, unknown provider tolerated, deregistration, idempotent re-registration, health reset on re-registration |
| `EmbeddingContractTest` | 34 | Width/dimension agreement, provenance, copy in and out, frozen metadata and texts, redacted `toString`, empty and null-bearing batches, single-text batch, every purpose, partial-response accessors, usage arithmetic, capability and model validation, retry classification for every failure kind |
| `EmbeddingAuditTest` | 8 | Success counts, fully-failed request audits zero embedded, partial split of successes and refusals, governance refusal before any call, unserved model, cached request audits nothing, partly-cached audits only misses, NOOP |
| `EmbeddingNeutralityTest` | 6 | Scan reaches the sources, no vendor name anywhere, no endpoint, no network type, no vector-index vocabulary, request never renders its text |
| `MemoryRuntimeBridgeTest` | 9 | Width and normalization through the port, determinism, copy-out, refusal mapping, shutdown refusal, wait-budget refusal, two-tenant behaviour (B55), **`EmbeddingPort` surface unchanged**, minimal integration contract |
| `OpenAiEmbeddingAdapterTest` | 30 | Response mapping, vendor model translation, credential per call and rotation, JSON escaping including control characters, ten status mappings, seven retry-rule assertions, health after non-200, truncated body, empty data, non-numeric component, vector-count mismatch, unserved model, transport failure, declared capability, price units |

### 17.1 What the tests do not cover

- **The live provider API.** §12.1, B57.
- **ArchUnit layering.** The jar is not available in this environment, so the module has no ArchUnit
  test where its peers have one; neutrality and layering are asserted by source scanning instead. B63.
- **Maven-gated static analysis.** Checkstyle, SpotBugs, ErrorProne, Spotless, forbidden-apis, JaCoCo
  and PITest could not be run: Maven cannot resolve this project's dependencies in this environment
  (TLS interception on Maven Central). Everything above was compiled and run with `javac` plus the
  JUnit platform launcher directly. **No claim is made that those gates pass.**
- **Retrieval quality.** Nothing here measures whether the embeddings are any *good*, because nothing
  here consumes them. That needs a labelled set and an index, and both belong to the vector-adapter
  half of AD-026 §15.2 Phase 5.

---

## 18. Rejected alternatives

**Widen `EmbeddingPort` to carry tenant and model.** Would have removed B55 and B56 outright. Rejected:
it is a Memory Runtime change, which the mission excludes, and it would put a model id inside C17 in
direct violation of MEM-2. The limitation is the honest cost of the constraint.

**Reuse `ProviderTransportPort` from the request pipeline.** Would have avoided a second transport
abstraction. Rejected: it carries credential leases, attempt budgets, pinned versions and route targets
that an embedding call has no use for, and adopting it would couple this module to the entire request
pipeline for no gain (§3.3).

**A circuit breaker with half-open probing.** Rejected: the probe is a paid request that arrives whether
or not the feature is in use (§7.3).

**True LRU eviction.** Rejected: needs an access-ordered structure behind a lock, and a lock on every
cache read costs more than the duplicate embeddings it saves. B61 records the consequence.

**Ship a tokeniser for cost estimation.** Rejected: a vendor vocabulary inside a module whose purpose is
not knowing the vendor. B58 records the correct fix.

**Cache shared across tenants.** Arithmetically correct — the same text embeds identically for everyone
— and rejected, because a cross-tenant hit is an oracle (§6.1).

---

*End of document — adr/ADR-030-Embedding-Provider.md*
