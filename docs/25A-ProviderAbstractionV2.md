# 25A — Provider Abstraction V2: The Provider Platform

**Document:** Implementation report — the provider platform
**Project:** Reliability-First AI Gateway
**Status:** Implemented — awaiting review
**Builds on:** `25-ProviderAdapter.md` (FROZEN), `19-ProviderRouter.md` (FROZEN)
**Changes:** no routing semantics, no governance semantics, no reliability changes, no pipeline reordering

---

## 0. The defect this milestone fixes

Before this change, `GatewayRuntime.constructProviderChain()` read:

```java
final OpenAiConfiguration openAi = new OpenAiConfiguration(...);
httpClient = HttpClient.newBuilder()...build();
providerTransport = new OpenAiProviderTransport(httpClient, openAi, new OpenAiAuthentication());
providerHealthCheck = new OpenAiHealthCheck(providerTransport, openAi);
providerAdapter = new ProviderAdapterService(new OpenAiTranslator(...), ...);
```

The dispatch layer was already neutral — `AdapterRegistry` looked up an opaque route reference and the
Router had never seen a provider name. **The composition root was not.** Adding a second provider meant
editing the class that wires the entire gateway, and `GatewayRuntime.providerHealth()` returned a
vendor-specific type in its public API.

The second defect was subtler: capabilities were `Set<String>` in three unrelated places — the router's
filter, the adapter's mapping, and the snapshot feeding both — with the six known tokens spelled as
string literals. A typo in any one disabled a capability with no diagnostic, and `ProviderCapabilityEntry`
was a record of six fixed booleans, so a snapshot needing `audio_input` or `batch` could not be written
at all.

---

## 1. Provider Capability Model

`ProviderCapability` (canonical lib) — a closed enum of 22 capabilities, each with a frozen wire token:

| Group | Capabilities |
|---|---|
| Generation shapes | `TEXT_GENERATION`, `CHAT_COMPLETION`, `TEXT_COMPLETION`, `RESPONSES`, `STREAMING` |
| Tools and structure | `FUNCTION_CALLING`, `PARALLEL_TOOL_CALLS`, `JSON_MODE`, `JSON_SCHEMA` |
| Modalities | `EMBEDDINGS`, `IMAGE_GENERATION`, `VISION`, `AUDIO_INPUT`, `AUDIO_OUTPUT`, `MULTIMODAL` |
| Execution | `REASONING`, `LONG_CONTEXT`, `PROMPT_CACHE`, `BATCH`, `FILES`, `ASSISTANTS`, `TOKEN_COUNTING` |

`CapabilitySet` — immutable, `EnumSet`-backed. `supportsAll` is a machine-word mask test; `missingFrom`
returns the shortfall so a routing refusal can say *"no candidate offered `json_schema`"* rather than
*"no candidate matched"*.

**Two decisions worth defending:**

- **The first six tokens are unchanged** (`tools`, `streaming`, `json_mode`, `reasoning`, `vision`,
  `embeddings`). Introducing the typed model therefore changed no routing decision — the vocabulary
  became typed without becoming different. A test pins these; renaming one would silently disable the
  capability rather than fail, because the router would simply stop matching it.
- **An unknown token is dropped, not kept.** A snapshot from a newer control plane may name capabilities
  this build has never heard of. Keeping one would make a route appear to support something no code here
  can deliver.

`PROMPT_CACHE` is declarable but **unconsumed** — this gateway implements no caching, and building one is
a separate milestone deliberately out of scope. It exists so a provider can describe itself completely
today, and is recorded here as an unconsumed declaration rather than quietly omitted.

## 2. Provider Registry

`ProviderRegistry` holds registrations and *is* a `ProviderAdapterPort`, so Reliability holds one
interface and never learns that more than one provider exists.

**Thread model:** one `AtomicReference` to an immutable view holding registrations *and* the derived
dispatch table together. A request does a single volatile read. Because the two are swapped as a unit, no
request can observe a dispatch table that disagrees with the states it came from — a provider is either
dispatchable with its adapter present, or absent from the table entirely.

`capabilitiesOf(RouteTarget)` answers the question the platform exists for, without knowing or caring
which provider serves the route.

## 3. Provider Discovery

`ProviderDiscovery` enumerates the modules the node was composed with and validates each declaration
against the operator's published snapshot.

**Discovery means enumerating declarations, not interrogating APIs.** Nothing calls a provider.
Capabilities are authored by the control plane and consumed read-only (Doc 25 CAP-1…CAP-6); a runtime
probe would move authorship out of the registry and make a route's capabilities depend on when you
asked.

The check that earns its place is **over-claim**:

| Fault | Meaning | Fatal? |
|---|---|---|
| `CAPABILITY_OVERCLAIM` | snapshot grants what the module cannot do | **yes** |
| `CAPABILITY_UNDERCLAIM` | module can do more than the snapshot publishes | no — unused, not wrong |
| `ROUTE_NOT_PUBLISHED` | module declares a route the snapshot omits | no — unreachable |
| `DUPLICATE_PROVIDER` / `DUPLICATE_ROUTE` | two modules claim the same id or route | **yes** |
| `START_FAILED` | module threw while starting | **yes** |

An over-claim means routing will select a route for work its adapter cannot perform — a failure that
recurs on every matching request, in production, with the caller charged. Startup validation turns that
into a line in a report. The comparison is asymmetric on purpose, because the snapshot is the authority.

## 4. Provider Lifecycle

`DISCOVERED → VALIDATED → READY → DEGRADED`, with `FAILED` and `DISABLED` as terminal-ish states. Only
`READY` and `DEGRADED` dispatch.

- **Degraded still routes.** Health is a background observation; refusing traffic on a stale side-channel
  verdict would take the routing decision away from Reliability's live circuit breaker.
- **One provider failing to start does not stop the others.** A node with three providers and one bad
  endpoint serves the other two. The alternative turns one misconfiguration into a total outage.
- **`enable` only works on a provider that actually started.** One rejected at validation stays failed:
  its declaration disagreed with the snapshot, and re-enabling would route traffic to an adapter that
  cannot serve it.
- **Nothing throws to the caller.** Start, probe and shutdown record outcomes, because a provider
  misbehaving during lifecycle must not become an exception the composition root has to decide about.

## 5. Capability Negotiation

`CapabilityNegotiation.negotiate(required, offered)` → `{satisfied, required, offered, missing}`, with
`negotiated()` returning the intersection.

`negotiated()` is deliberately not the provider's whole declaration: a route supporting vision, batch and
reasoning serving a plain chat request has negotiated only chat, and recording the full set would make
the audit trail claim capabilities the request never touched. The constructor rejects a negotiation
claiming success while naming a shortfall.

## 6. Runtime wiring

```java
discoveryReport   = new ProviderDiscovery().discover(provider.modules(), capabilitySource);
providerLifecycle = ProviderLifecycle.from(discoveryReport, provider.modules());
providerStartFaults = providerLifecycle.startAll(new ProviderRuntimeContext(
        credentialPort, capabilitySource, AdapterTelemetryPort.NO_OP, config.clock()));
providerRegistry  = providerLifecycle.registry();
providerAdapter   = providerRegistry.hasDispatchableProvider() ? providerRegistry : null;
```

**`GatewayRuntime` now contains zero occurrences of any vendor name.** `providerHealth()` returns the
neutral `ProviderHealth`; shutdown closes every provider's own transport.

`ProviderRuntimeContext` carries credentials, the capability snapshot, telemetry and the clock — and
deliberately **no HTTP client**. The moment the gateway hands one over it owns an opinion about protocol
version and pooling that the first non-HTTP provider must work around. A provider builds and closes its
own transport through `ProviderInstance`.

New operator surface: `providerRegistry()`, `providerLifecycle()`, `providerFaults()`.

## 7. OpenAI migration

`OpenAiProviderModule` is the reference implementation and the whole of what a provider costs: declare
routes, build a transport, hand back an instance. It touches no gateway type beyond the SPI.

Capabilities are declared **per model on route**, not per vendor. The embeddings route and the chat route
share nothing behaviourally; a vendor-level union is exactly the list that routes a vision request to an
embeddings endpoint.

**OpenAI is not broken:** every pre-existing OpenAI test passes unchanged, including
`OpenAiRuntimeWiringTest`, which drives a real `GatewayRuntime` against a fake server through the real
module.

## 8. Tests

**105 added; 1,525 passing across the backend, zero failures.**

| File | Tests | Covers |
|---|---|---|
| `CapabilityModelTest` | 20 | vocabulary, frozen tokens, set algebra, unknown-token handling |
| `ProviderPlatformTest` | 37 | negotiation, descriptors, discovery faults, lifecycle, registry, 100-thread concurrency |
| `ProviderNeutralityTest` | 4 | no vendor name in production code outside a provider module |

`ProviderNeutralityTest` is the guard on the milestone's central claim, and **I verified it is not
vacuous**: injecting `private static final String SABOTAGE = "openai";` into `ProviderRouterService`
made two of its four tests fail with the exact file named; the router source was then restored
byte-identical (`diff` confirmed).

It scans **code with comments stripped**, matching whole words. That correction came from the test's own
first run, which flagged `PolicyUsage` for "replicate" (inside *replicated*), `PolicyCompiler` for
"cohere" (inside *coherent*), and StreamGuard's `FramingType` for four vendors — the last appearing only
in a javadoc that states the neutrality rule: *"StreamGuard decodes 'SSE,' never 'OpenAI'"*. Flagging
prose that documents the principle would pressure people into writing worse documentation. **No actual
provider-name branching existed anywhere**, which is worth recording as a finding in its own right.

## 9. Performance

Measured on this machine: 12 providers × 8 routes = 96 routes, 500k iterations after 200k warm-up.

| Operation | mean | p50 | p99 |
|---|---|---|---|
| Registry dispatch | 292 ns | 200 | 600 |
| Capability lookup | 126 ns | 100 | 300 |
| Full negotiation | 132 ns | 100 | 700 |
| `supportsAll` mask test | 67 ns | 100 | 300 |

**Against the pre-V2 path, interleaved in one JVM** (separate runs are not comparable):

| | mean | p50 | p99 |
|---|---|---|---|
| pre-V2 `AdapterRegistry` | 256 ns | 200 | 500 |
| V2 `ProviderRegistry` | 292 ns | 200 | 600 |

**Added cost: ~36 ns per dispatch** — the volatile read of the immutable view. Identical p50. Against a
provider call of hundreds of milliseconds this is roughly one ten-thousandth of one percent.

Dispatch is O(1) in the number of providers: one volatile read and one hash lookup, regardless of whether
the node runs one provider or fifty.

**Startup:** discovery and validation of 96 routes took 92.9 ms. That figure includes cold class-loading
and first-call JIT, so it overstates the steady-state algorithmic cost; it is one-time, off the request
path, and buys the over-claim check.

---

## 10. Final report

### Files created (13 main, 3 test)

**Canonical lib** — `capability/ProviderCapability.java`, `capability/CapabilitySet.java`

**Provider-adapter module** — `api/ProviderId`, `api/ProviderRoute`, `api/ProviderDescriptor`,
`api/ProviderModule`, `api/ProviderInstance`, `api/ProviderRuntimeContext`, `api/ProviderProbe`,
`api/ProviderHealth`, `api/ProviderState`, `api/ProviderFault`, `api/ProviderRegistration`,
`domain/CapabilityNegotiation`, `application/ProviderDiscovery`, `application/ProviderRegistry`,
`application/ProviderLifecycle`

**Provider module** — `OpenAiProviderModule.java`

**Tests** — `CapabilityModelTest`, `ProviderPlatformTest`, `ProviderNeutralityTest`

**Docs** — this file.

### Files modified (4 main, 2 test)

| File | Change |
|---|---|
| `GatewayRuntime.java` | provider chain rebuilt on discovery + lifecycle; **all vendor names removed**; neutral health type; provider-platform accessors |
| `GatewayRuntimeConfig.java` | `ProviderConfig` gained `modules` with a compat constructor; `complete()` now requires a module |
| `ProviderCapabilityEntry.java` | carries a typed `CapabilitySet`; `of(...)` factory publishes from the typed model; boolean form retained and delegating |
| `RuntimeFixture.java` (test) | supplies `OpenAiProviderModule` — where an operator's wiring now lives |
| `OpenAiRuntimeWiringTest.java` (test) | one helper carries `modules` through when rebuilding the config |

`AdapterRegistry` was left untouched and still passes its own tests. `RequestPipeline`,
`ProviderRouterService`, the reliability engine and the governance engine were **not modified**.

### Tests

| | |
|---|---|
| Added this milestone | **105** |
| Whole backend | **1,525 passing, 0 failing, 106 containers** |

### Remaining blockers

| # | Blocker | Impact | Owner |
|---|---|---|---|
| B1 | Maven cannot resolve dependencies (TLS interception on Maven Central) | Checkstyle, SpotBugs, ErrorProne, forbidden-apis, JaCoCo, PITest have **not** run; verification is `javac` + JUnit only | environment |
| B2 | ArchUnit jar absent locally | `ProviderArchitectureTest` and the module arch tests remain unproven | follows B1 |
| B3 | Shared in-memory tier adapter still missing | unchanged from 21A | platform |
| B5 | No control-plane policy source adapter | unchanged from 21A | C4 control plane |
| B6 | No real tokenizer | unchanged from 21B | this repo |
| B7 | No PII classifier | unchanged from 21B | this repo |
| **B8 (new)** | **Only the first registered provider is probed at startup.** The health-probe config names one credential route and there is no per-provider probe binding | operator report is incomplete on a multi-provider node; **no routing effect** — nothing on the request path consults health | this repo |
| **B9 (new)** | **`ProviderTransportConfig` is now legacy.** The runtime no longer reads it for adapter construction; it survives because test fixtures translate it into `OpenAiConfiguration` | dead-ish config an operator could set expecting effect | this repo |
| **B10 (new)** | **Capabilities are declared in two places** — the provider module and the operator's snapshot — and must agree | discovery catches disagreement at startup, but a single source would be better; unifying it is a control-plane change | C4 control plane |

B8 and B9 are consequences of this migration and are narrow: neither can route a request anywhere policy
or capability declarations forbid. B10 is inherent to CAP-1 (the control plane owns capability truth) and
is mitigated rather than solved by the over-claim check.

### What "add a provider" now costs

Implement `ProviderModule` (declare routes, build a transport) and `ProviderTranslator`. Register the
module in composition. **Zero changes** to the router, the governance engine, the pipeline, the
reliability engine or `GatewayRuntime` — enforced by `ProviderNeutralityTest`.

---

*End of document — 25A-ProviderAbstractionV2.md*
