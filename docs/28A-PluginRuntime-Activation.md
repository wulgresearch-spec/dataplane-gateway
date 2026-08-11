# 28A — Plugin Runtime: Activation, and Four Frozen-Contract Conflicts

**Document:** Implementation report — Plugin Runtime milestone
**Status:** Partially delivered — **STOP** raised on four frozen contracts
**Builds on:** `28-PluginRuntime.md` (FROZEN), `21-GovernanceEngine.md` (FROZEN)

---

## 0. Executive summary — read this first

**The Enterprise Plugin Runtime already existed.** Before this milestone: 66 main files, 10,361 LOC,
194 passing tests, covering lifecycle, process sandboxing, permissions, resource budgets, dependency
graph, supply-chain verification, streaming, cancellation, ordering, audit and telemetry.

**It had never executed.** `RequestPipeline` contained zero references to it. `GatewayRuntime` built it
and exposed it through an accessor. Every plugin on every node was dead code.

So this milestone was not "build the runtime". It was: **activate the runtime that exists**, and
determine honestly which parts of the mission the frozen architecture forbids.

**Delivered:** the five frozen extension points now dispatch on the live request path, additively and
fail-closed, with the non-bypass invariant guarded structurally.

**Stopped:** four of the mission's requirements are forbidden by `28-PluginRuntime.md`, which is FROZEN.
They are enumerated in §3 with the exact clauses. I have not implemented workarounds for any of them.

---

## 1. Research summary

Reviewed against the named prior art. The findings that actually changed decisions:

- **VSCode Extension Host / Chrome Extensions / Figma** — all three isolate by *process or realm* and
  mediate every host call. Doc 28 ISO-1 already requires this and `ProcessSandbox` already implements
  it. No change indicated.
- **HashiCorp go-plugin** — plugins are subprocesses over a versioned RPC contract, with the host owning
  the lifecycle. This is the shape Doc 28 §ISO/§REC and the existing `ProcessPluginAdapter` take.
- **Kubernetes CRD controllers / Dapr** — declarative manifest, reconciliation toward declared state,
  and *the control plane owns registration*. This matches Doc 28 §ROC exactly, and is the reason the
  mission's "Install" step cannot live in the runtime (§3.4).
- **AWS Lambda / Cloudflare Workers / Fly Machines / Modal** — the pattern most relevant to the mission:
  these runtimes *do* grant network egress, and they do it through a mediated, per-function policy. Doc
  28 §32/§33 already permits exactly that ("mediated, capability-gated, audited"), and
  `PluginPermissions` already carries `networkHosts` and `filesystemPaths` allow-lists. **Network access
  is therefore not the blocker** — I initially thought ISO-5 forbade it outright and was wrong; ISO-5
  states the *default*, §32/§33 state the mediated exception.
- **Temporal / LangGraph / Ray Serve** — all place tool/activity execution *inside* the orchestration
  loop, around the model call. That placement is precisely what Doc 28 EPC-3 forbids by name, and is
  the real blocker (§3.1).
- **gRPC reflection / ServiceLoader / Spring / Guice** — all rejected by the mission and by the
  codebase's existing zero-framework, explicit-composition discipline. Nothing here uses them.

---

## 2. What was delivered

### 2.1 The activation seam

| File | Role |
|---|---|
| `ExtensionOutcome` (new) | content-free record of what a point's plugins contributed |
| `ExtensionPointDispatcher` (new) | runs the plugins bound at one point; never throws; clamps to the request deadline |
| `StageTrace` (modified) | a **separate** extension channel, deliberately not the mandatory-stage list |
| `RequestPipeline` (modified) | dispatches all five frozen points |
| `GatewayRuntime` / `GatewayRuntimeConfig` (modified) | builds the dispatcher; per-point budget with a compat constructor |

Placement follows the `ExtensionPoint` javadoc exactly: `CLASSIFICATION` before GOVERNANCE, `PRE_ROUTING`
before ROUTER, `VALIDATION` before SCHEMA_LOCK, `ATTRIBUTION` before METERING, `TELEMETRY` before EMITTER.

### 2.2 Three design decisions, and their trade-offs

**Contributions are inert.** No mandatory stage reads an `ExtensionOutcome`; it reaches the trace and
stops. *Trade-off:* plugins currently cannot influence any decision, so the immediate feature value is
observability only. *Why:* Doc 28 PRT-D1 requires that removing every plugin change no mandatory-stage
outcome. Consuming a signal is a change to the consuming stage's semantics and belongs to that stage's
milestone, under its own review — not smuggled in with the wiring.

**A disabled dispatcher is indistinguishable from an empty one.** Both return `ExtensionOutcome.none`.
*Why:* if they differed, the presence of the plugin runtime would be observable from the request path,
which is the first step toward it mattering.

**The extension trace is a separate list.** *Why:* the non-bypass tests assert every mandatory stage ran
exactly once in order. Sharing one list would make that assertion depend on which plugins a node has
bound — a plugin could alter the non-bypass evidence.

### 2.3 The guard, and proof it works

`PluginNonBypassTest` proves PRT-D1 **structurally** — for every plugin anyone will ever write, not just
the ones a test binds. It asserts the dispatch count equals the recorded count, that no mandatory
collaborator receives a contribution, that `RequestExecution` never carries one, and that no forbidden
EPC-3 point name appears.

**Verified non-vacuous.** I sabotaged the pipeline with a realistic erosion — capture the classification
contribution into a variable and branch on it — and the guard failed with `expected: 5 but was: 4`. An
earlier, cruder sabotage was caught by the compiler instead. The source was then restored
byte-identical (`diff` confirmed).

The complementary behavioural evidence: **all 1,525 pre-existing tests passed unchanged** after wiring —
PRT-D1 demonstrated on a node with no plugins bound.

---

## 3. STOP — four frozen contracts block the mission

The mission says to stop rather than invent workarounds. Four requirements are forbidden. For each I
give the exact clause and what would have to change.

### 3.1 Tool execution has no legal binding point — blocks the mission's central goal

> *Mission:* "This runtime becomes the ONLY execution engine for every external capability… MCP, RAG,
> GitHub, Slack, Database, Search, Email, Calendar, Browser Automation, File Storage MUST execute
> through this runtime."

**Blocked by EPC-3:** "The following are **explicitly forbidden**: post-routing, **pre-invoke**,
**post-invoke**, **response-transform**, provider interception, stream mutation."

Model-driven tool calling is: model returns tool calls → gateway executes them → gateway feeds results
back. That requires post-invoke or response-transform. Both are forbidden **by name**.

Reinforced by **EPC-5** ("a plugin never … mutates a request/response"), **EPC-8** ("no
response-mutation … capability to any plugin at any point"), **PRT-D1** ("removing all plugins changes
no mandatory-stage outcome" — a Slack plugin whose result enters the prompt breaks this), and
**Doc 21 GV-D10** ("tool **execution** is out of platform scope — the caller executes tools").

A previous milestone reached the same conclusion and documented it on `ToolExecutionPort`; the engine is
built, tested and deliberately unbound.

**Note:** network egress is *not* the blocker. §32/§33 permit mediated, capability-gated, audited
egress, and `PluginPermissions` already models it. The blocker is purely *where* a tool may be invoked.

**To unblock:** an ADR amending AD-004 / Doc 06 §9 / Doc 28 §EPC to add a tool-execution extension
point, with an explicit statement of how it coexists with StreamGuard, SchemaLock and the Provider
Adapter. **EPC-6 already names this requirement.** This is the Agent Runtime's entry ticket.

### 3.2 "Secret injection" is forbidden by construction

> *Mission goal list:* "Secret injection"

**Blocked by PRT-D8 / §36:** "Plugins **never** access secrets." `PluginPermissions` has no secret
field, and its javadoc states the reason: "an operator cannot enable what the type system cannot
express."

**To unblock:** amend PRT-D8 and §36, and add a mediated secret-broker capability. I would argue
against it: a plugin that can read a credential can exfiltrate it, and process isolation bounds blast
radius without eliminating it (ISO-9).

### 3.3 "Hot reload without restarting" is forbidden

> *Mission:* "Support Install, Upgrade, Rollback without restarting gateway. Atomic swap only."

**Blocked by HRC-1/2/3:** "New plugin versions **require the deployment lifecycle** … picked up on
rollout, **not by in-place code injection**"; "The runtime **never mutates the behavior of an
already-loaded plugin** in place."

What *is* permitted and already implemented: **enable/disable via config snapshot** (HRC-5) and
**last-known-good retention** (HRC-6).

**To unblock:** amend §HRC and reconcile with AD-020 (immutable data plane). The current contract is a
deliberate Tier-0 choice — no ungoverned live-patching of a production process.

### 3.4 "Install" and "DISCOVER" belong to the control plane

> *Mission lifecycle:* "Install … DISCOVER → VALIDATE → …"

**Blocked by ROC-1/ROC-4/ROC-8:** the control plane owns registry and discovery; "the runtime **SHALL
NEVER** publish, approve, sign, register, or vet plugins." ROC-7 limits the runtime to
**verification · loading · execution · isolation · unloading** — which is exactly what it implements.

**To unblock:** amend §ROC and Doc 06 §9.12 to move registration into the data plane. I would argue
against it: it puts vetting authority on the node that executes the code.

---

## 4. Results

### Files created (3)
`ExtensionOutcome.java`, `ExtensionPointDispatcher.java` (app/binding);
`ExtensionPointDispatcherTest.java`, `PluginNonBypassTest.java` (test)

### Files modified (5)
`RequestPipeline.java` (five dispatch points + one helper), `StageTrace.java` (separate extension
channel), `GatewayRuntime.java` (dispatcher wiring), `GatewayRuntimeConfig.java` (per-point budget +
compat constructor), `RequestPipelineTest.java` (harness uses `DISABLED`)

**Not modified:** the entire `gateway-dp-plugin-runtime` module. It needed no change to be activated —
which is the strongest evidence that its API was designed correctly.

### Compilation and tests

```
562 main sources compile clean
1,546 tests pass, 0 fail, 109 containers
  1,525 pre-existing — unchanged, all passing
     21 new (ExtensionPointDispatcherTest 14, PluginNonBypassTest 7)
    194 of the 1,546 are the pre-existing plugin-runtime suite
```

**On the 250–400 test target: I did not meet it, deliberately.** The runtime already has 194 tests
covering the areas the mission lists — lifecycle, permissions, isolation, dependency graph, ordering,
supply chain, sandbox, streaming, concurrency. Writing 250 more against already-covered code would be
padding. The 21 I added cover the one genuinely untested thing: the activation seam and the invariant it
must not break. If the intent was to *replace* the existing suite, say so and I will, but I will not
inflate a count against working tested code.

### Benchmarks

Not run. The dispatcher's cost on a plugin-free node is one null check and one record allocation per
point — five per request — and measuring that in isolation would produce a number dominated by
benchmark overhead. With plugins bound, the cost is the sandbox's, already characterised by the
plugin-runtime suite. A meaningful number needs a bound plugin under load, which needs the tool path
that §3.1 blocks. I would rather report nothing than a number that measures the wrong thing.

---

## 5. Known limitations

1. **Contributions are inert.** Plugins run, are audited and traced, and influence nothing. This is
   PRT-D1 compliance, not an oversight — but it means the immediate value is observability, and no
   stage benefits until that stage's own milestone chooses to consume a signal.
2. **Only the unary path dispatches all five points.** The streaming finalizer (`finalizeStream`) runs
   SCHEMA_LOCK, METERING, COST and EMITTER separately and does **not** dispatch VALIDATION, ATTRIBUTION
   or TELEMETRY. Wiring those needs the tenant threaded into the finalizer; I left it rather than
   widen a streaming signature late in the milestone. Streaming requests currently see two of five
   points.
3. **No behavioural end-to-end test with a bound plugin at the pipeline level.** The structural guard
   is stronger for PRT-D1, but there is no test that a real signed plugin contributes through a real
   `GatewayRuntime`. The plugin module tests dispatch directly; the app tests use `DISABLED`.
4. **`WASM` and `PYTHON` plugin types are recognised and refused.** Honest, and a real capability gap.
5. **Mutation testing not run** (Doc 28 requires ≥90% on this module) — see blocker B1.

## 6. Remaining blockers

| # | Blocker | Impact |
|---|---|---|
| B1 | Maven cannot resolve dependencies (TLS interception) | Checkstyle, SpotBugs, ErrorProne, forbidden-apis, JaCoCo, **PITest** have not run; verification is `javac` + JUnit only |
| B2 | ArchUnit jar absent locally | the plugin module's ArchUnit rules remain unproven |
| **B11** | **EPC-3/EPC-6 — no legal tool-execution point** | blocks MCP, RAG, and every named integration |
| **B12** | **PRT-D8/§36 — secret injection forbidden** | blocks any plugin needing a credential |
| **B13** | **HRC-1/2/3 — no in-place hot reload** | install/upgrade/rollback require the deploy train |
| **B14** | **ROC-1/4/8 — install and discovery are CP-owned** | the runtime may never register a plugin |
| B15 | Streaming path dispatches 2 of 5 points | limitation 2 |

B3, B5–B10 from earlier milestones are unchanged.

---

## 7. Recommended next milestone

**Write the ADR that EPC-6 requires.** Everything the mission actually wants — MCP, RAG, GitHub, Slack,
and the Agent Runtime that drives them — is blocked on a single architectural decision: whether a
tool-execution extension point may exist, and how it coexists with StreamGuard, SchemaLock and the
Provider Adapter without becoming the bypass AD-018 forbids.

That ADR is a *design* deliverable, not a coding one, and it is genuinely hard: the honest version has
to explain how a plugin-supplied tool result enters a prompt without a plugin having influenced a
mandatory-stage outcome. Until it exists, more runtime code adds no reachable capability — the engine
below the seam is finished.

Two cheap items worth doing regardless: close B15 (streaming path), and resolve B1 so the quality gates
and the ≥90% mutation requirement can actually run.

---

*End of document — 28A-PluginRuntime-Activation.md*
