# ADR-025 — Agent Runtime V1 Architecture

**ADR ID:** AD-025 · **Also designated:** Agent Runtime Constitution — Document 001
**Status:** Proposed · **Date:** 2026-07-31
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Decision Makers:** Architecture Council
**Builds on:** `AD-024` Enterprise Tool Execution Architecture (frozen premise, §2)
**Amends:** `06-Service-Boundaries.md` (adds C14). **Requires:** a new durable store (§21.3, §86).
**Does NOT amend:** `AD-024` §EPC-equivalents, `28-PluginRuntime.md`, `21-GovernanceEngine.md`,
`RequestPipeline` ordering.

---

## Numbering note

`AD-001` is Accepted, immutable and cross-referenced throughout the documentation. This document
therefore carries two identifiers, as `AD-024` does: **`AD-025`** for the register, and **"Agent Runtime
Constitution — Document 001"** for its role. It renumbers cleanly into a separate series if the Council
prefers one.

---

## Table of Contents

**Part I — Inheritance** (§1–4) · **Part II — System Studies** (§5–12) · **Part III — What an Agent Is**
(§13–16) · **Part IV — The Decision** (§17–22) · **Part V — Invariants** (§23) · **Part VI — The Fifty
Questions** (§24–69) · **Part VII — Diagrams** (§70–76) · **Part IX — Comparison** (§77) ·
**Part X — Rejected Alternatives** (§78–79) · **Part XI — Contracts** (§80–84) ·
**Part XII — Operational Readiness** (§85) · **Part XIII — Amendments Required** (§86) ·
**Part XIV — The Long View** (§87) · **Appendices**

---

# Part I — Inheritance

## §1 — The frozen premise

`AD-024` established, and this document treats as immovable:

> The orchestration loop MUST exist **above** `RequestPipeline`. Every model invocation MUST be a
> complete `RequestPipeline` execution. `RequestPipeline` remains deterministic and stateless.

`AD-024` also created **C13, the Tool Session Orchestrator** — the component that owns that loop — and
explicitly named the Agent Runtime as *a caller of C13, not a replacement for it and not a layer inside
it* (`AD-024` §25).

This document does not revisit that. It builds the layer above.

## §2 — What C13 already owns, and therefore what this document must not re-invent

The single most common way a document like this goes wrong is by re-implementing the layer beneath it.
The division is already settled and is restated here so that every later section can refer to it:

| Concern | Owner | Established by |
|---|---|---|
| The model-plus-tools loop within one task | **C13** | `AD-024` §4 |
| Per-turn governance; per-tool-call authorization | C4 via C13 | `AD-024` §14 |
| Tool execution, sandboxing, quotas | C12 via C13 | `AD-024` §15 |
| Taint tracking, the Rule of Two | C13 | `AD-024` §35 |
| Session bounds (turns, calls, spend, deadline) | C13 | `AD-024` §9 |
| Turn-level and tool-level audit | C13 | `AD-024` §20 |
| **Planning: deciding what task to run next** | **C14 (this document)** | — |
| **Durable run state, checkpointing, crash recovery** | **C14** | `AD-024` §51.4 deferred it here |
| **Human approval and long pauses** | **C14** | `AD-024` §25 deferred it here |
| **Nested-agent composition and supervision** | **C14** | — |

**The Agent Runtime does not have a tool loop.** It opens C13 sessions, and C13 has one. This single
sentence answers Q9 and prevents the most likely design error in the entire document.

## §3 — Three corrections to the brief

A constitutional document that copies an imprecise invariant will have that imprecision copied forever.
Three of the invariants in the brief need restating before they can be adopted. I am not declining them
— I am making them true.

### §3.1 — "Every tool execution MUST create a new RequestPipeline execution"

**As stated, this contradicts `AD-024`.** A tool execution is a Plugin Runtime invocation into a
process sandbox (`AD-024` §15). It does not pass through `RequestPipeline` and must not: the pipeline's
stages — routing, provider invocation, StreamGuard, SchemaLock — have no meaning for a sandboxed
function call, and forcing one through would make ADAPTER either a no-op or a lie.

**The true and intended invariant:**

> **AGT-INV-A.** A tool result can reach a model **only** through a new `RequestPipeline` execution.
> There is no path by which a tool's output enters a model's context without a complete, governed turn.

This preserves the spirit exactly — no ungoverned shortcut from tool output to model input — while being
implementable. It is adopted as **AGT-9** in §23.

### §3.2 — "Every retry MUST create a new RequestPipeline execution"

**As stated, this contradicts `AD-024` §22**, which defines three retry layers and rules that *turn
retry does not exist*. A provider attempt-retry happens **inside** one pipeline execution, owned by
Reliability; creating a new pipeline execution for it would double-govern, double-meter and double-audit
a single logical turn.

**The true and intended invariant, split by layer:**

> **AGT-INV-B.** A retried **agent step** is a new C13 session and therefore a new set of
> `RequestPipeline` executions. A retried **provider attempt** is not, and remains Reliability's.
> The Agent Runtime never retries a turn.

Adopted as **AGT-10**.

### §3.3 — "An agent MUST NOT own Billing"

Adopted verbatim, but with a consequence the brief does not state: if the agent does not own billing, it
also **cannot enforce a budget by itself**. It must *consume* a ceiling and *observe* spend from C5/C13.
§39 specifies that seam. Stated here because "does not own billing" is often read as "ignores cost",
which for a long-running autonomous loop would be catastrophic.

## §4 — Why the corrections matter

`AD-024` exists because a previous milestone recommended adding a pipeline extension point, and that
recommendation was wrong. The lesson recorded there — that placement errors compound — applies here. An
Agent Runtime built on "every tool execution is a pipeline execution" would either route sandboxed
function calls through ADAPTER (absurd) or quietly not implement the invariant (worse, because the
document would then be lying). Fixing the wording now costs one section; discovering it in
implementation costs a rewrite.

---

# Part II — System Studies

## §5 — Method

Each system is studied for four things: **how it represents a run**, **how it survives a crash**, **how
it composes agents**, and **what it refuses to do**. The last is the most informative — a system's
refusals tell you what its designers found unworkable.

## §6 — Durable execution engines

### §6.1 — Temporal

**Run representation.** A workflow execution is a durable event history — every scheduled activity,
every completion, every timer, every signal. The history is the run; the code is a deterministic
function that replays over it.

**Crash survival.** Replay. On resume, the workflow function re-executes from the beginning; each
command it generates is checked against the recorded history, and recorded results are returned instead
of re-invoking the activity. The workflow reaches its previous position without repeating side effects.

**Composition.** Child workflows, with parent-close policies determining what happens to children when
the parent ends.

**Refuses.** Non-determinism in workflow code — no wall clock, no randomness, no direct I/O. All of it
must go through activities, which are recorded.

**Adopted here, substantially.** The event-history model (§20), the deterministic-plan/recorded-step
split (§45), the replay-based recovery (§46), and the constraint that all non-determinism is a recorded
step (AGT-13). This is the single largest architectural debt this document owes to any system.

**Declined.** Temporal's requirement that the orchestrator be *user-written code*. Here the plan is
data, not code (§26), which makes versioning tractable (§6.3) and makes the plan auditable as a value.

### §6.2 — Azure Durable Functions

**Run representation.** Orchestration history, same shape as Temporal.

**Crash survival.** Replay from scratch, consulting history; completed tasks return recorded outputs.

**Refuses.** Explicitly: `DateTime.Now`, `Guid.NewGuid()`, direct HTTP calls inside an orchestrator.
Instead: `context.CurrentUtcDateTime`, `context.NewGuid()`, and all I/O wrapped in activities.

**Teaches something Temporal's docs understate: orchestration versioning.** Durable Functions has
first-class support for it, because the problem is unavoidable — you deploy new orchestrator code while
runs are in flight, and replaying old history against new code produces a non-determinism error.

**This is the problem most likely to be forgotten in an agent runtime**, because agent definitions
change constantly — a prompt tweak, a new tool, a reordered plan. §46.4 addresses it explicitly. I would
not have prioritised it without this study.

### §6.3 — Dapr Workflows

**Run representation.** Durable Functions semantics over any container runtime; stable since 1.13.

**Teaches.** That the durable-execution pattern is portable across substrates and is no longer exotic.
It is a reasonable thing for an enterprise gateway to depend on.

### §6.4 — AWS Step Functions

**Run representation.** A declarative state machine (Amazon States Language) — the plan **is data**, not
code.

**Crash survival.** Service-managed; the execution's state is held by the service.

**Refuses.** Arbitrary code in the state machine. Every unit of work is a task; the machine only
sequences.

**Adopted.** *Plan-as-data* (§26). This is the departure from Temporal that makes versioning tractable:
if the plan is a value, an in-flight run can pin the exact plan version it started with, and a new
deployment cannot invalidate its history (§51). It also makes the plan governable — a value can be
authorized; arbitrary code cannot.

**Declined.** ASL's expressiveness. §26 deliberately specifies a smaller plan vocabulary.

## §7 — Supervision systems

### §7.1 — Erlang/OTP

**Run representation.** Processes in a supervision tree.

**Crash survival.** Supervisors restart children per a declared **strategy** — `one_for_one` (restart
only the failed child), `one_for_all` (restart all children), `rest_for_one` (restart the failed child
and everything started after it) — bounded by a **restart intensity**: at most N restarts within period
P, after which the supervisor itself terminates.

**Philosophy.** "Let it crash" is not fatalism; it is the separation of normal operation from error
recovery. Error-prone work is pushed to leaf processes that are allowed to die, and recovery is a
supervisor's declared policy rather than defensive code scattered through the leaves.

**Adopted, and this is the second-largest debt in the document.** Nested agents are supervised children
with declared restart strategies and a restart intensity (§30, §44). **No agent framework studied has a
supervision model at all** — they have try/catch and a retry count. The difference matters enormously
for a runtime that will host multi-agent systems: without restart intensity, a crash-looping child
agent is a bill.

The intensity bound in particular is a lesson learned the hard way in the Erlang community: the
documentation itself warns that a badly-chosen intensity means a supervisor "will allow child processes
to keep restarting forever, filling your logs with crash reports until someone intervenes manually."
That is precisely the agent failure mode this document must prevent.

**Declined.** Transparent restart of stateful processes. An agent step that already called a model and
spent money cannot be "restarted" as if nothing happened; restart here means *re-plan from the last
checkpoint*, not *re-execute the step* (§44.2).

### §7.2 — Akka

**Run representation.** Actors with supervision, but supervision is configured explicitly per actor
rather than being a language-level default.

**Teaches.** That supervision retrofitted onto a general-purpose runtime requires more ceremony and is
more often omitted. The lesson for this design: **supervision must be mandatory and declared, not
opt-in**, or it will not be used. Hence AGT-16: every nested agent has a supervision policy, and the
default is the strictest one.

## §8 — Agent frameworks

### §8.1 — LangGraph

**Run representation.** An explicit graph; state snapshots checkpointed at every super-step.

**Crash survival.** Resume from checkpoint; the interrupted node re-executes from its beginning.

**Composition.** Subgraphs.

**Human approval.** `interrupt()` pauses the graph, persists state, waits indefinitely, resumes on a
command.

**Adopted.** Checkpointing at step boundaries (§34). The interrupt-as-a-state model for human approval
(§36) — approval is not a special case, it is a state the machine can sit in.

**Declined.** Node re-execution on resume, for the same reason as §7.1: an agent step with side effects
cannot be replayed by re-running it. The result must come from history (§49).

### §8.2 — CrewAI

**Run representation.** Roles, tasks, and a process (sequential or hierarchical) over a crew of agents.

**Composition.** First-class — the crew *is* the composition.

**Teaches.** That role-based decomposition is what practitioners actually reach for, and that
hierarchical delegation (a manager agent assigning to workers) is the dominant multi-agent shape.

**Adopted.** The parent/child agent relationship as the composition primitive (§30), rather than
peer-to-peer messaging.

**Declined.** Roles as a runtime concept. A "role" is a prompt and a tool set; the runtime needs neither
to know it is a role nor to reason about it. Adding role semantics to the runtime would put product
vocabulary into a safety boundary.

### §8.3 — AutoGen / AG2

**Run representation.** Conversational multi-agent collaboration; AG2's runtime is event-driven,
distributed and cross-language.

**Teaches.** That free-form agent-to-agent conversation is expressive and very hard to bound. A group
chat with no turn ceiling is an unbounded loop with extra steps.

**Declined, firmly.** Free-form peer conversation as a composition primitive. Every composition in this
design is a **bounded parent/child call** with its own budget and its own supervision policy (§32). This
is the clearest place where the document trades flexibility for determinism, and §77.3 states the cost
honestly.

### §8.4 — Semantic Kernel

**Run representation.** The Process Framework — stateful, long-running processes with human-in-the-loop.
Converging with AutoGen into a unified Microsoft agent framework.

**Teaches.** That the enterprise .NET world independently arrived at "stateful process layer above
stateless model calls". Convergence from an unrelated starting point is strong evidence the shape is
right.

### §8.5 — OpenAI Agents SDK / AgentKit

**Run representation.** Agents, handoffs, guardrails, sessions — a minimal harness over the Responses
API. The 2026 revision adds a model-native harness for file and command work with native sandbox
execution.

**Teaches.** Two things. **Handoff** as a first-class primitive — an agent transferring control to
another rather than calling it as a subroutine. And that sandboxed execution is now considered part of
the agent stack rather than an add-on.

**Adopted.** Nothing structural; the sandbox lesson was already `28` §ISO.

**Declined.** Handoff. A handoff transfers control without returning, which makes the budget and audit
model much harder: who owns the remaining budget, and where does the trace go? This design has **calls,
not handoffs** (§30.3) — a child agent returns to its parent. The expressiveness lost is real and stated
in §77.3.

Also declined: guardrails-as-library. A guardrail an author can decline to import is not a boundary
(`AD-024` §72.4).

### §8.6 — GitHub Copilot Agent, Cursor, OpenHands

**Run representation.** A task loop over a workspace, executing edits and commands, iterating until done
or interrupted.

**Crash survival.** Generally none — the session is lost.

**Teaches.** That mid-flight visibility is a product requirement, not a nicety, and that users tolerate
long runs *only* when they can see progress and interrupt.

**Adopted.** Streaming orchestration events (§38) and interruptibility as a first-class operation (§32).

## §9 — Protocols

### §9.1 — Model Context Protocol

Settled in `AD-024` §26 and §84. MCP servers are plugins; `sampling` is refused because it would let a
tool drive inference. **That refusal is what makes an Agent Runtime necessary rather than optional**: if
plugins could call models, agent loops would form inside sandboxes, outside every bound. The Agent
Runtime is where that capability is offered safely, above the governance layer instead of beneath it.

### §9.2 — Google A2A

**Run representation.** Agent Card (discovery + auth metadata), Task (a unit of work with a status
across rounds), Message (multi-part, role-attributed), Artifact (a finalised deliverable). Tasks move
pending → in-progress → completed → failed, with SSE for progress. Apache-2.0, Linux Foundation
governed.

**Teaches.** The **Task with an explicit lifecycle** is the interoperable unit — which is exactly the
step model in §19. And the Message/Artifact split, already adopted in `AD-024` §80.

**Adopted.** The task lifecycle shape.

**Declined for V1.** A2A as an inbound or outbound transport. A remote A2A agent is, from the gateway's
perspective, limb₃ of the lethal trifecta by construction (`AD-024` §74.2) — an external communication
path with an unbounded remote executor behind it. Supporting it needs its own Rule-of-Two analysis, and
§79.6 records why shipping it in V1 would be irresponsible.

### §9.3 — Kubernetes controllers

**Teaches.** Declared desired state, reconciliation toward it, and registration owned by the API server
rather than the controller. The last point — the executor never registers itself — is `28` §ROC and
carries directly to agent definitions: **the Agent Runtime executes agent definitions; it does not
author or approve them** (AGT-6).

### §9.4 — Ray

**Run representation.** A distributed task and actor graph with futures.

**Teaches.** That a task graph with explicit dependencies is a natural fit for parallel fan-out, and
that the scheduler benefits from knowing the graph shape in advance.

**Adopted.** The explicit dependency graph in the plan (§26, §31), which is what makes safe parallelism
possible (§31.2) — you can only run two steps concurrently if you know they do not depend on each other.

## §10 — Provider-native agent surfaces

Covered in `AD-024` §72. The position is unchanged and worth restating because it constrains this
document: **provider-hosted tool execution and provider-hosted agent loops are declined** (`AD-024`
§51.1). An agent run must be composed of gateway-governed turns, or the gateway is not governing the
agent.

## §11 — Recent literature

The relevant threads, and what each contributes:

- **Prompt injection / lethal trifecta.** Settled in `AD-024` §35 and inherited whole. The Agent Runtime
  makes it *harder*, because a long autonomous run accumulates limbs across many steps. §60.2 addresses
  the specific escalation.
- **Concurrency anomalies in multi-agent LLM systems.** Parallel agents sharing state produce
  write-write and read-write anomalies analogous to database races. §31.3 addresses this by forbidding
  shared mutable state between parallel steps — the only reliable answer at this maturity level.
- **Agentic RAG surveys.** Retrieval as a tool rather than a pipeline stage; consistent with treating
  Memory as a tool (§42).

## §12 — What the studies collectively establish

Six conclusions, each supported by multiple independent systems:

1. **The run is an event history, and recovery is replay.** Temporal, Durable Functions, Dapr.
2. **The orchestrator must be deterministic and I/O-free.** Temporal, Durable Functions — both enforce
   it as a hard constraint with named forbidden operations.
3. **The plan is better as data than as code.** Step Functions; and Durable Functions' versioning pain
   is the counter-example that proves it.
4. **Supervision needs declared strategies and a restart bound.** OTP; and its absence everywhere else
   is conspicuous.
5. **Composition is hierarchical, not peer-to-peer.** CrewAI's hierarchical process, Temporal's child
   workflows, LangGraph's subgraphs.
6. **Human approval is a state, not an exception.** LangGraph's interrupt, Semantic Kernel's process
   framework.

Every one is reflected in Part V.

---

# Part III — What an Agent Is

## §13 — Q1. What is an Agent?

**An agent is a durable, bounded, supervised execution of a versioned plan, which reaches models and
tools only by opening governed sessions.**

Each word is load-bearing:

| Term | Meaning | Why it is in the definition |
|---|---|---|
| **durable** | The run's history outlives the process executing it | Distinguishes an agent from a request; enables crash recovery (§35) |
| **bounded** | Steps, depth, spend, wall-clock and fan-out all have mandatory ceilings | An unbounded agent is a denial-of-wallet vector (§58) |
| **supervised** | Failures are handled by a declared policy, not ad-hoc | From OTP (§7.1); the alternative is defensive code everywhere |
| **versioned plan** | The plan is data, pinned at run start | Makes replay valid across deployments (§51) |
| **governed sessions** | The only route to a model or tool is a C13 session | The entire safety argument (AGT-1…AGT-4) |

An agent is **not** defined by autonomy, intelligence, or goal-seeking. Those are properties of the plan
and the model, not of the runtime. The runtime's definition must be operational, because the runtime has
to enforce it.

## §14 — Q2. What is NOT an Agent?

Precision here prevents the runtime accreting responsibilities that belong elsewhere.

| Not an agent | Why | Correct home |
|---|---|---|
| A single model call | No plan, no durability, no steps | `RequestPipeline` |
| A model call with tools | This is a **C13 session** — bounded, ephemeral, one task | C13 (`AD-024`) |
| A tool | A function invoked by a session | C12 Plugin Runtime |
| An MCP server | A plugin with a protocol | C12 |
| A prompt template | Data consumed by a step | Agent definition |
| A workflow of deterministic steps with no model | No inference; use a workflow engine | Out of scope |
| A chat conversation | Caller-driven turn-taking, no plan | `RequestPipeline`, repeatedly |
| Memory | A store, reached as a tool | Memory Runtime (§43) |
| A scheduled job | A trigger that may *start* an agent | Scheduler, out of scope |

**The sharpest boundary is between an agent and a C13 session**, because they look similar — both loop,
both call models, both use tools. The distinction:

> A **C13 session** executes *one task* to completion: a bounded model-plus-tools loop with no plan and
> no durability. It is ephemeral and dies with the request.
>
> An **agent run** executes *a plan of tasks*: it decides which task comes next, survives crashes,
> pauses for humans, spawns children, and can last hours.

A single-task agent is legitimate and degenerates to exactly one C13 session. That degeneration is
deliberate (§87.2) — the design's simplest case is the layer below it.

## §15 — Q3. Why Agent Runtime exists

C13 solved the *governed loop*. Five things remain unsolved, and each is a reason this component exists:

1. **Nothing decides what to do next.** C13 runs one task. Multi-step work — research, then summarise,
   then draft, then verify — has no home.
2. **Nothing survives a crash.** `AD-024` §51.4 deferred durability explicitly; a session dies with its
   node (`AD-024` F-24). A three-hour run that dies at minute 170 has burned the whole budget for
   nothing.
3. **Nothing can pause.** Human approval for an irreversible action requires waiting minutes or days.
   C13's bounds are seconds-to-minutes and its state is in memory.
4. **Nothing composes.** A research agent delegating to three sub-agents needs a parent/child model with
   budgets, supervision and aggregation.
5. **Nothing bounds a *run*.** C13 bounds a session. Ten sessions in a loop is ten in-budget sessions and
   an unbounded run.

Each is a capability gap with a customer-visible failure mode, and none can be fixed inside C13 without
making C13 stateful — which would push durable state onto the request hot path and violate `AD-006`.

## §16 — Q4. Why it must live above RequestPipeline

Three independent arguments, any one sufficient.

**Argument 1 — inheritance.** `AD-024` §5 established that the loop must live above the pipeline (P1–P4:
full governance per turn, no new extension point, session-scoped enforcement, provider neutrality). The
Agent Runtime is a loop over loops. Every argument that put C13 above the pipeline puts C14 above C13
with more force, because a run spans more turns and therefore more governance surface.

**Argument 2 — statefulness.** The Agent Runtime is durable and stateful by necessity (§15, items 2–3).
`RequestPipeline` is stateless by mandate (`AD-006`, `AD-020`). Durable state cannot live inside a
stateless component; a stateful pipeline would break horizontal scaling, restartability and the
co-location latency argument simultaneously.

**Argument 3 — the governance argument.** If the agent were inside or beside the pipeline, its steps
would either bypass governance or require the pipeline to re-enter itself. Above the pipeline, every
step is a session and every session's turns are complete pipeline executions — so a 40-step agent run
produces *at least* 40 governance evaluations rather than one.

**And the falsifier:** if the Agent Runtime lived below or inside the pipeline, `RequestPipeline` would
have to become re-entrant. A re-entrant non-bypass assembly is not a non-bypass assembly, because the
re-entry point is, by definition, a path into the middle of the chain.

---

# Part IV — The Decision

## §17 — Decision

**The Agent Runtime (C14) is a durable, plan-driven, supervised orchestrator that sits above C13. It
splits into a control-plane-owned Run Store and stateless data-plane Step Executors. It reaches models
and tools only by opening C13 sessions.**

```
   caller / scheduler / API
        │
   ┌────▼──────────────────────────────────────────────────────────────┐
   │  C14  AGENT RUNTIME                                               │
   │                                                                   │
   │   ┌─────────────────┐        ┌──────────────────────────────┐     │
   │   │  RUN STORE      │◄──────►│  STEP EXECUTOR (stateless)   │     │
   │   │  (control plane)│ history│  ─ replays history           │     │
   │   │  ─ event history│        │  ─ decides the next step     │     │
   │   │  ─ checkpoints  │        │  ─ opens ONE C13 session     │     │
   │   │  ─ durable      │        │  ─ records the outcome       │     │
   │   └─────────────────┘        └───────────┬──────────────────┘     │
   │   ┌─────────────────┐                    │                        │
   │   │  SUPERVISOR     │  restart strategy  │                        │
   │   │  (OTP-shaped)   │  + intensity bound │                        │
   │   └─────────────────┘                    │                        │
   └───────────────────────────────────────────┼────────────────────────┘
                                               │ opens a session per step
                                    ┌──────────▼─────────────────┐
                                    │  C13 TOOL SESSION ORCH.    │  (AD-024)
                                    └──────────┬─────────────────┘
                                               │ one full execution per turn
                                    ┌──────────▼─────────────────┐
                                    │  REQUEST PIPELINE          │  unchanged
                                    └──────────┬─────────────────┘
                                               ▼
                                            provider
```

## §18 — The three-part split, and why

This is the load-bearing structural decision, equivalent to `AD-024`'s "loop above the pipeline".

### §18.1 — Run Store (durable, control plane)

Holds the run's **event history**: every step scheduled, every step completed with its recorded result,
every checkpoint, every approval, every supervision event. The history *is* the run.

It lives in the control plane because it is durable state, and `AD-017`/`AD-006` place durable state
there. A data-plane node holds no run state it cannot lose.

### §18.2 — Step Executor (stateless, data plane)

Given a run id, it: loads history, replays it to reconstruct position, determines the next step, executes
that step by opening exactly one C13 session, and records the outcome back to the Run Store.

**It is stateless and interchangeable.** Any node can execute any run's next step. Kill it mid-step and
another node resumes from the last recorded event. This is what makes crash recovery work (§37) and what
keeps the data plane conformant with `AD-006`.

### §18.3 — Supervisor (policy, evaluated at failure)

When a step or a child run fails, the supervisor applies the run's declared **restart strategy** bounded
by a **restart intensity** (§38). Directly from OTP (§7.1).

### §18.4 — Why not one component

A single stateful agent service would either put durable state on the request hot path (violating
`AD-006`) or hold run state in memory (losing it on restart — the exact gap §15 item 2 identifies). The split
is what lets the runtime be simultaneously durable and stateless-where-it-must-be.

## §19 — The step is the unit of everything

As the turn is to C13, the **step** is to C14.

> A **step** is one unit of plan progress, executed as exactly one C13 session, whose outcome is
> recorded in the run history before anything else happens.

Consequences, from which most of Part VI follows:

- Checkpointing is free: the history is the checkpoint (§36).
- Crash recovery is replay to the last recorded step (§37).
- Retry is re-executing a step, which is a new session and therefore new pipeline executions (§35).
- Budget is the sum of step costs, and C13 already reports session cost (§45).
- Cancellation propagates by cancelling the in-flight session (§34).
- Determinism is "the plan replays identically given the recorded step results" (§48).

## §20 — The event history

The history is an append-only, ordered sequence. Nothing else is authoritative.

| Event | Recorded when | Carries |
|---|---|---|
| `RunStarted` | Admission | run id, plan id + version, budgets, principal, tenant, parent (if nested) |
| `StepScheduled` | Before execution | step id, step index, inputs digest, session budget allotted |
| `StepCompleted` | Session ends | step id, outcome, session id, cost, result digest |
| `StepFailed` | Session fails | step id, failure class, cost incurred |
| `SupervisionApplied` | After a failure | strategy applied, restart count, decision |
| `ApprovalRequested` | Plan requires it | approval id, what is being approved, expiry |
| `ApprovalResolved` | Human responds | decision, approver identity, timestamp |
| `ChildRunStarted` | Nested agent | child run id, budget delegated |
| `ChildRunCompleted` | Child ends | child run id, outcome, cost consumed |
| `RunCheckpointed` | Step boundary | history offset |
| `RunTerminated` | End | terminal reason, totals |

**Only the Step Executor appends, and only after the fact.** A step is recorded as scheduled *before*
execution and completed *after*; the gap is what crash recovery uses to detect an interrupted step
(§35.2).

## §21 — What C14 owns and does not own

### §21.1 — Owns

Plan interpretation · step scheduling · run state and history · checkpointing · crash recovery ·
supervision · nested-agent composition · human-approval state · run-level budget tracking (consuming
C13's numbers) · run-level audit correlation · run lifecycle.

### §21.2 — Does not own

Models (C1/pipeline) · tools (C12 via C13) · governance decisions (C4) · memory (Memory Runtime) ·
billing (C5/C8) · sandboxing (C12) · routing (C1) · retry of provider attempts (C2) · the tool loop
(C13).

### §21.3 — Requires (new)

**A durable run store.** This is the one genuinely new infrastructure dependency in this document, and
§86 records it as an amendment requiring Council approval. It is control-plane-owned, tenant-partitioned
and append-oriented. Nothing else in the design needs new infrastructure.

## §22 — Plan as data

The plan is a **versioned value**, not code (from Step Functions, §6.4). This is the second structural
decision and it earns its place three times over:

- **Versioning.** A run pins its plan version at start. Deploying a new version cannot invalidate an
  in-flight run's history (§46.4) — the problem Durable Functions had to solve with dedicated machinery.
- **Governability.** A value can be authorized before it runs. Governance can evaluate "may this
  principal run this plan, with these tools, at this depth" — impossible for arbitrary code.
- **Auditability.** The plan is recorded in the history, so "what was this agent instructed to do" is
  answerable from the audit trail rather than from a code repository at an unknown commit.

§26 specifies the vocabulary.

---

# Part V — Invariants

## §23 — The constitutional invariants (AGT-1 … AGT-30)

These bind every future Agent Runtime implementation.

### Boundary invariants

| # | Invariant |
|---|---|
| **AGT-1** | An agent **MUST NOT** call a provider directly. |
| **AGT-2** | An agent **MUST NOT** invoke `RequestPipeline` directly. It opens C13 sessions; C13 invokes the pipeline. |
| **AGT-3** | An agent **MUST NOT** bypass Governance. Every step is a session; every session's turns are governed. |
| **AGT-4** | An agent **MUST NOT** execute a tool itself. Tools are C12's, reached through C13. |
| **AGT-5** | An agent **MUST NOT** own Memory. Memory is reached as a tool (§42). |
| **AGT-6** | An agent **MUST NOT** author, approve or register its own plan or tools. |
| **AGT-7** | An agent **MUST NOT** own billing. It consumes ceilings and observes spend (§39). |
| **AGT-8** | An agent **MUST NOT** hold secrets. Inherited from `28` PRT-D8. |

### Execution invariants

| # | Invariant |
|---|---|
| **AGT-9** | A tool result reaches a model **only** through a new `RequestPipeline` execution (§3.1). |
| **AGT-10** | A retried **step** is a new C13 session and new pipeline executions. A retried **provider attempt** is not. The Agent Runtime never retries a turn (§3.2). |
| **AGT-11** | A recursive or nested execution is a new run with its own sessions and pipeline executions. There is no in-place recursion. |
| **AGT-12** | Exactly one C13 session per step. A step that needs two sessions is two steps. |
| **AGT-13** | The plan interpreter is **deterministic and I/O-free**. All non-determinism — model output, tool output, time, randomness, external input — is a recorded step result. |
| **AGT-14** | Nothing is executed before it is recorded as scheduled, and nothing proceeds before its outcome is recorded. |
| **AGT-15** | The history is append-only and is the sole authority on what happened. |

### Safety invariants

| # | Invariant |
|---|---|
| **AGT-16** | Every run has a supervision policy with a declared strategy and a finite restart intensity. There is no unsupervised run. |
| **AGT-17** | Every run is bounded in steps, depth, fan-out, wall-clock time and spend. All five are mandatory; none may be infinite. |
| **AGT-18** | Bound exhaustion terminates the run deterministically with a distinct, auditable reason. |
| **AGT-19** | A child run's budget is **deducted from** its parent's, never added to it. Delegation cannot create budget. |
| **AGT-20** | A child's failure never silently becomes a parent's success. The supervision policy decides, explicitly. |
| **AGT-21** | An irreversible action requires either an explicit policy grant or human approval. |
| **AGT-22** | Taint and the Rule of Two accumulate across the **run**, not merely the session (§60.2). |
| **AGT-23** | Parallel steps share no mutable state. Their results merge only at an explicit join. |

### Operational invariants

| # | Invariant |
|---|---|
| **AGT-24** | Every run, step, approval, supervision event and child relationship is audited with causal edges intact. |
| **AGT-25** | A run pins its plan version at start; a plan change never alters an in-flight run. |
| **AGT-26** | Run state is tenant-partitioned. No cross-tenant reachability, in store or in executor. |
| **AGT-27** | The Agent Runtime is provider-neutral; it never names or branches on a provider. |
| **AGT-28** | A cancelled run cancels its in-flight session, its children, and bills no post-cancellation work. |
| **AGT-29** | Every run terminates. Termination is guaranteed by the bounds, not by the plan's good behaviour (§66). |
| **AGT-30** | Removing the Agent Runtime changes no behaviour of C13, the pipeline, or any single-turn request. |

---

# Part VI — The Fifty Questions

Q1–Q4 are answered in Part III (§13–16). Q5 onward follow.

## §24 — Q5. Agent Session

### §24.1 — Definition and the naming hazard

"Session" is already taken. `AD-024` §9 defines a **tool session** as C13's bounded unit. Reusing the
word at this layer would be a permanent source of confusion in code, logs and audit.

**Therefore: the Agent Runtime's unit is a RUN, not a session.** The word "session" retains its `AD-024`
meaning throughout this document. Where the brief says "agent session", read "agent run".

> A **run** is one execution of one plan version, on behalf of one principal in one tenant, with its own
> history, budgets, bounds and supervision policy.

### §24.2 — What a run carries

| Field | Purpose | Immutable after start? |
|---|---|---|
| Run id | Identity, correlation root | Yes |
| Plan id + **plan version** | What is being executed | Yes (AGT-25) |
| Principal, tenant | Authorization subject | Yes |
| Parent run id, depth | Composition position | Yes |
| Bounds (5, §46) | Termination guarantee | Yes |
| Budget grant | Spend ceiling | Yes; consumption is tracked separately |
| Supervision policy | Failure handling | Yes |
| History offset | Position | No — advances |
| Status | Lifecycle state | No |

**Everything that defines what a run may do is fixed at admission.** A run cannot widen its own bounds,
budget, permissions or plan. This is the run-level analogue of `AD-024`'s session-scoped enforcement and
is what makes the termination argument (§47) sound — you cannot prove termination against a bound the
run can raise.

### §24.3 — Run vs session vs turn vs attempt

| Level | Unit | Owner | Durable? | Bounded by |
|---|---|---|---|---|
| Run | Plan execution | C14 | **Yes** | steps, depth, fan-out, wall-clock, spend |
| Session | One task | C13 | No | turns, tool calls, session spend, deadline |
| Turn | One model call + its tool calls | C13 | No | one pipeline execution |
| Attempt | One provider call | C2 | No | retry policy |

Four levels, each with its own bound. A run of 20 steps × 8 turns × 3 attempts is 480 provider calls —
which is exactly why all four bounds are mandatory and why the run-level ones cannot be inherited from
below (§15, item 5).

## §25 — Q6. Agent Execution

An **execution** is the act of advancing a run by one step. It is what a Step Executor does.

Its shape is fixed and is the most important control-flow contract in the document:

```
1. LOAD      history for run R from the Run Store
2. REPLAY    the plan interpreter over the history          [deterministic, no I/O]
3. DECIDE    the next step, or that the run is done         [deterministic]
4. ADMIT     check bounds and budget                        [may terminate the run]
5. RECORD    StepScheduled                                  [durable write]
6. EXECUTE   open exactly one C13 session; await it         [the only I/O]
7. RECORD    StepCompleted or StepFailed                    [durable write]
8. YIELD     the run is now resumable by any node
```

Steps 2–4 are pure. Step 6 is the sole non-deterministic act. Steps 5 and 7 bracket it durably. This
bracket is the whole of crash recovery (§37), determinism (§48) and replay (§49).

Note step 8: **the executor yields after every step.** It does not hold the run. A long run may be
advanced by fifty different nodes. This is what keeps the data plane stateless (`AD-006`) and is a
deliberate divergence from every agent framework studied, all of which hold the run in one process.

## §26 — Q7. Planning

### §26.1 — Plan as data (restating §22)

A plan is a versioned, immutable value. The runtime interprets it; it does not execute code.

### §26.2 — Plan vocabulary

Deliberately small. Each construct earns its place by being impossible to express with the others and by
having a clear bound.

| Construct | Meaning | Bound |
|---|---|---|
| `Step` | One task → one C13 session | Session budget, deadline |
| `Sequence` | Steps in order | Step count |
| `Parallel` | Independent steps concurrently | **Fan-out bound** (§46) |
| `Branch` | Choose by a predicate over recorded results | Deterministic predicate only |
| `Loop` | Repeat until a predicate holds | **Mandatory iteration ceiling** |
| `Delegate` | Start a child run | **Depth bound** |
| `Approve` | Pause for a human decision | Expiry |
| `Join` | Merge parallel results | — |

### §26.3 — What is deliberately absent

| Absent | Why |
|---|---|
| Arbitrary expressions | An unbounded predicate language is a program; programs are not governable as values |
| Unbounded loops | `Loop` without a ceiling breaks AGT-29 |
| Peer messaging between steps | AutoGen's lesson (§8.3); shared mutable state (§33.3) |
| Dynamic plan mutation by the agent | Would break AGT-25 and the termination proof |
| Direct provider or tool references | Breaks AGT-1/AGT-4; steps reference *capabilities*, C13 resolves them |
| Handoff (control transfer without return) | §8.5; budget and audit ownership become ambiguous |

### §26.4 — Model-generated planning

The obvious objection: real agents plan dynamically, and a fixed plan is a workflow, not an agent.

**Both are supported, and the distinction is where the dynamism lives:**

- **Static planning.** The plan enumerates the steps. Deterministic, cheap, fully analysable.
- **Dynamic planning.** A step whose *task* is "produce a plan fragment". Its output is recorded like
  any other step result, and the interpreter's `Branch`/`Loop` consume it.

Crucially, **the model's plan fragment is data flowing through a recorded step, not code the interpreter
executes.** It is bounded (the fragment must fit the vocabulary and the remaining bounds), validated
before use, and replayed from history rather than regenerated. So dynamic planning is available without
sacrificing determinism (§48) or the termination proof (§47).

This is the resolution of the flexibility/determinism trade-off that §77.3 discusses, and it is why the
"plan as data" decision does not reduce the runtime to a workflow engine.

## §27 — Q8. Reasoning loop

**The Agent Runtime does not have a reasoning loop.** A step's reasoning happens inside the C13 session
it opens; C13 owns the model-plus-tools loop (`AD-024` §4).

The Agent Runtime has a **step loop**: decide, execute, record, repeat. The distinction is exact:

| | Reasoning loop | Step loop |
|---|---|---|
| Owner | C13 | C14 |
| Iteration | A turn | A step |
| Driven by | Model output (tool calls or a final answer) | The plan, over recorded results |
| Deterministic? | No | **Yes** (AGT-13) |
| Durable? | No | Yes |

**The step loop must be deterministic** because it is the thing replayed. If it were driven directly by
model output, replay would require re-inference. Instead: the model's output is *recorded*, and the
deterministic interpreter consumes the record. This is Temporal's split (§6.1) applied to agents, and it
is the single mechanism that makes crash recovery affordable — resuming a 40-step run costs zero
provider calls.

## §28 — Q9. Tool loop

**There is no tool loop in the Agent Runtime.** (§2.)

C13 owns it. The Agent Runtime opens a session, and the session loops. Re-implementing it here would:

- duplicate the taint tracking and Rule-of-Two enforcement of `AD-024` §35, creating two enforcement
  points that will diverge;
- duplicate per-tool-call authorization (`AD-024` §14);
- require C14 to reach C12 directly, violating AGT-4;
- and produce two loops with different bounds, so an operator could never answer "how many tool calls
  can this run make?"

**A step that needs tools opens a session with those tool capabilities. That is the entire mechanism.**

## §29 — Q10. Recursive execution

Recursion means a plan whose step delegates to a run of the same (or an ancestor) plan.

**Permitted, under four constraints:**

1. **AGT-11** — a recursive execution is a new run, never in-place re-entry. There is no stack to
   overflow; there is a depth counter in the history.
2. **Depth bound** — a hard ceiling (§46). Default 3.
3. **Budget deduction** — AGT-19. The child's grant comes out of the parent's remaining budget, so
   recursion is *self-limiting in cost* even before the depth bound applies.
4. **Cycle visibility** — the ancestor chain is in the history, so a plan recursing into itself is
   detectable and auditable, not hidden.

Constraints 2 and 3 are independent limiters, deliberately. Depth alone would allow 3 levels of a
1000-step plan; budget alone would allow deep cheap recursion. Both bound, and the tighter binds.

## §30 — Q11. Nested agents

### §30.1 — Model

Nesting is **parent/child call-and-return** (from CrewAI's hierarchical process and Temporal's child
workflows, §8.2/§6.1), not peer conversation (declined, §8.3), not handoff (declined, §8.5).

A `Delegate` step starts a child run and awaits its outcome. The child is a full run: its own history,
its own bounds, its own supervision policy, its own audit subtree.

### §30.2 — The delegation contract

| Property | Rule | Invariant |
|---|---|---|
| Budget | Deducted from parent's remaining; child cannot exceed it | AGT-19 |
| Depth | Parent depth + 1; refused past the ceiling | AGT-17 |
| Permissions | **Subset** of parent's; never a superset | AGT-6 |
| Tenant | Identical. Cross-tenant delegation is refused | AGT-26 |
| Taint | Inherited into the child; child's taint propagates back on return | AGT-22 |
| Deadline | min(parent remaining, child's own) | AGT-17 |
| Failure | Supervision policy decides; never silently ignored | AGT-20 |
| Cancellation | Parent cancellation cancels children transitively | AGT-28 |

**Permissions are the one most often got wrong.** A parent must not be able to escape its own
restrictions by delegating to a child with broader grants. Monotone narrowing is the same "most
restrictive wins" rule as the governance merge algebra, applied to composition.

### §30.3 — Why not peer-to-peer

Peer messaging (AutoGen-style) needs a global turn bound, a global budget and a global deadline for a
set of agents with no hierarchy — and when one member misbehaves, no principal is accountable. With a
tree, every node has exactly one parent that owns its budget, its supervision and its audit edge. The
loss in expressiveness is real (§77.3, item 2).

## §31 — Q12. Parallel execution

### §31.1 — Model

A `Parallel` construct runs independent steps concurrently, bounded by a **fan-out ceiling** (§46). From
Ray (§9.4): the dependency graph must be explicit, because concurrency is only safe over steps known not
to depend on each other.

### §31.2 — Rules

| Rule | Reason |
|---|---|
| Fan-out ≤ ceiling; excess queues | Bounded resource use |
| Each branch's budget is carved from the parent's *before* it starts | Prevents N branches each believing they have the full remaining budget — a real over-spend bug |
| **No shared mutable state** (AGT-23) | §11; concurrency anomalies |
| Results merge only at an explicit `Join` | Deterministic merge point |
| Join order is by branch index, not completion order | **Replay determinism** — see §31.4 |
| One branch's failure applies the supervision policy; it does not silently vanish | AGT-20 |

### §31.3 — Why no shared state

Parallel agents mutating shared state reproduce database write-write and read-write anomalies without
any of a database's machinery (§11). The options were shared state with locking (complex, deadlock-prone,
and the lock holder is a model, which may never release), transactional state (very heavy), or no shared
state (branches are pure functions of their inputs; merge at join).

**No shared state** is chosen. Branches that genuinely need to coordinate should be sequential steps —
which is honest, because they were never independent.

### §31.4 — Parallelism and determinism

Completion order is non-deterministic. If the plan observed it, replay would diverge.

**Therefore the interpreter never observes completion order.** A `Join` waits for all branches and
merges by branch index. The history records completions in whatever order they arrived, but the
*interpretation* is order-independent. This is the same discipline Temporal applies to concurrent
activity completion, and it is why AGT-13 says "deterministic" without exempting parallelism.

## §32 — Q13. Cancellation

### §32.1 — Sources

| Source | Trigger |
|---|---|
| Caller | Explicit cancel on the run |
| Parent | Parent run cancelled or terminated (AGT-28) |
| Bound | Any of the five bounds exhausted (§46) |
| Supervision | Restart intensity exceeded (§38) |
| Governance | A policy change revokes the run's authority mid-flight (§40.3) |
| Tenant | Tenant suspension or quota exhaustion |

### §32.2 — Semantics

**Cancellation is cooperative at the step boundary and forceful within a step.**

- Between steps: the executor observes cancellation before scheduling and terminates cleanly.
- Within a step: the in-flight C13 session is cancelled (`AD-024` §31 already defines session
  cancellation and its tool-call semantics). The Agent Runtime does not reach past C13 into a tool.
- Children: cancelled transitively, depth-first, before the parent is marked terminated.

### §32.3 — Billing

Work already performed is billed; work after the cancellation point is not (AGT-28). A tool that already
executed is already billed by C12 — cancellation does not refund it, and pretending otherwise would
misreport cost.

### §32.4 — Cancellation cannot be refused

A run cannot decline cancellation, and there is no cleanup step that runs after it. Cleanup steps would
be an unbounded tail on a cancelled run — a run being cancelled for budget exhaustion could then spend
more. Anything that must happen on cancellation is C13's or C12's compensation, which `AD-024` already
scopes.

## §33 — Q14. Retry

### §33.1 — The four layers

| Layer | Retries | Owner | New pipeline execution? |
|---|---|---|---|
| Attempt | Provider call | C2 Reliability | **No** — inside one execution |
| Turn | — | **Nobody** | `AD-024` §22: turn retry does not exist |
| Step | One C13 session | **C14** | **Yes** — new session, new executions |
| Run | Whole run | Caller / scheduler | Yes — a new run entirely |

AGT-10 is precisely this table.

### §33.2 — Step retry rules

| Rule | Reason |
|---|---|
| Only for retryable failure classes (§67.1) | Retrying a governance denial is pointless and burns budget |
| Retry count is bounded per step and counts toward restart intensity | OTP (§7.1) |
| Each retry is a **new C13 session** | AGT-12; a session is not resumable |
| Each retry consumes budget; the failed attempt's spend is not refunded | Honest accounting |
| Retries are recorded individually | Auditability; a 3-retry step shows 4 entries |
| A step with irreversible effects is **not** retried unless declared idempotent | §33.3 |

### §33.3 — Retry and irreversibility

If a step sent an email and then failed, retrying sends a second email.

**Rule:** a step is retryable only if every capability it was granted is declared idempotent or
read-only. A step granted an irreversible capability fails to the supervision policy rather than
retrying. This is more conservative than any framework studied — most retry blindly — and it is
deliberate: a duplicated irreversible action is a customer incident, while a non-retried transient
failure is an inconvenience.

## §34 — Q15. Checkpointing

### §34.1 — The history is the checkpoint

There is no separate checkpointing mechanism (from Temporal/Durable Functions, §6). Recording
`StepCompleted` *is* the checkpoint.

This is a genuinely simplifying result: no checkpoint scheduling policy, no "how often do we
checkpoint?", no divergence between checkpoint and log. LangGraph's per-super-step checkpoint (§8.1)
converges on the same granularity from the other direction.

### §34.2 — Granularity

**Step boundaries only.** Not within a step — a partially-executed C13 session is not a resumable state
(`AD-024` F-24 makes sessions node-bound), so a mid-step checkpoint would record a position that cannot
be resumed. Recording something unresumable would be worse than not recording it, because recovery would
trust it.

### §34.3 — What is and is not recorded

Recorded: step identity, outcome class, cost, session id, **digest** of the result.
Not recorded verbatim: full model outputs and tool payloads. They are referenced by digest and stored
under the existing retention and redaction rules (`AD-024` §20). The history is a control-plane
structure and must not become a shadow copy of every payload the run touched — that would be both a
retention hazard and a cross-tenant data concentration risk (AGT-26).

## §35 — Q16. Crash recovery

### §35.1 — Mechanism

A Step Executor dies. Any other node picks the run up:

```
1. Load history                    → last durable position
2. Replay interpreter over history → same position, zero I/O, zero provider calls
3. Inspect the tail:
     ends with StepCompleted/StepFailed → schedule the next step
     ends with StepScheduled            → an interrupted step; see §35.2
4. Continue
```

**Recovery costs no inference.** This is the central practical payoff of the whole design: a 3-hour,
40-step run that dies at step 38 resumes at step 38 for the price of a database read. Compare LangGraph,
which re-executes the interrupted node (§8.1), and the CLI agents, which lose the run entirely (§8.6).

### §35.2 — The interrupted-step problem

A history ending in `StepScheduled` with no completion means the executor died mid-step. The step's C13
session may have completed, partly completed, or never started. **The history cannot tell us which** —
this is the fundamental limit of the two-phase record, and no amount of design removes it.

Resolution, in order:

1. **Query C13 by session id.** `StepScheduled` records the session id, so if C13's own audit shows a
   terminal outcome, adopt it. This resolves the common case.
2. **If C13 has no record**, the session never started or died with its node. Treat as failed and apply
   the supervision policy.
3. **If C13 shows in-flight**, wait for the session deadline (which is bounded), then treat as failed.

### §35.3 — Exactly-once is not achievable; at-most-once for irreversible actions is

Honesty required. If a step executed a tool with an irreversible effect and the executor died before
recording the completion, we cannot know whether the effect happened.

The design's answer is the same as §33.3: **a step granted an irreversible capability is never
automatically retried.** So the guarantee is:

- Read-only and idempotent steps: **at-least-once** (safe to re-execute).
- Irreversible steps: **at-most-once** (never automatically re-executed; the run fails to supervision,
  and a human or a compensation plan decides).

This is weaker than "exactly once", which is not achievable across a non-transactional boundary. Any
document claiming exactly-once here would be wrong.

## §36 — Q17. Human approval

### §36.1 — Approval is a state, not an exception

From LangGraph's `interrupt` (§8.1) and Semantic Kernel's process framework (§8.4). An `Approve` step
records `ApprovalRequested` and the run enters `AWAITING_APPROVAL`.

**Crucially, the executor yields.** No thread, no process, no memory is held. The run is a row in the
Run Store. A run can wait for days at zero cost — which is exactly what C13 cannot do (`AD-024` §51.4
deferred it here for this reason).

### §36.2 — Properties

| Property | Rule |
|---|---|
| Approver identity | Authenticated; recorded in `ApprovalResolved`; must satisfy the plan's approver policy |
| Approver ≠ requester | Enforced where the policy demands separation of duties |
| Expiry | Mandatory. On expiry the run terminates with `APPROVAL_EXPIRED` — it does not silently continue |
| Default | **Deny.** An unanswered approval never becomes an approval |
| Scope | Approves *this step, this run, this time*. Never a standing grant |
| Wall-clock | Approval wait does **not** consume the run's wall-clock bound; the expiry bounds it instead |
| Audit | Request and resolution both recorded with causal edges |

The wall-clock exemption is a deliberate carve-out: without it, every approval-bearing plan would need a
wall-clock bound longer than the slowest human, which would defeat the bound's purpose for the rest of
the run.

### §36.3 — What requires approval

Determined by policy, not by the plan alone (a plan that could waive its own approvals would be
worthless — AGT-6). Governance decides; the plan may only request *additional* approvals. Irreversible
capabilities require either an explicit standing policy grant or per-execution approval (AGT-21).

## §37 — Q18. Long-running execution

Enabled by three properties already established: the executor yields between steps (§25, step 8), the history
is durable (§20), and approval waits are free (§36.1).

| Constraint | Value |
|---|---|
| Maximum run duration | Bounded by the wall-clock bound (§46); no unbounded runs |
| Resource held while idle | **None** — no thread, no connection, no memory |
| Resumption | Any executor node |
| Long tool executions | C12's timeouts apply; the Agent Runtime does not extend them |
| Progress visibility | Streamed events (§42) |
| Cost | Accrues only during steps |

**What this does not enable:** a step that itself runs for hours. Step duration is bounded by the C13
session deadline. Long *runs* are supported; long *steps* are not. Work that genuinely takes hours must
be an external job that the agent polls — as separate steps, each cheap, with waits between them.

## §38 — Q19. Streaming orchestration

### §38.1 — Two distinct streams, deliberately not merged

| Stream | Content | Owner |
|---|---|---|
| **Token stream** | Model output tokens within a turn | Pipeline → C13 |
| **Orchestration stream** | Run events: step started/completed, approval needed, child started, budget consumed | C14 |

They are not merged into one channel, because they have different lifetimes (a token stream lives for
one turn; the orchestration stream lives for the run), different consumers (a UI renders tokens; an
operator console renders progress), and different sensitivity (tokens may carry tenant data; run events
carry structural metadata). Merging them would force the stricter handling on both.

### §38.2 — Properties

| Property | Rule |
|---|---|
| Delivery | Best-effort. **The history is authoritative** (AGT-15); the stream is a view of it |
| Loss | A dropped stream never affects run correctness — a consumer reconnects and reads from an offset |
| Ordering | Per-run ordered, by history offset |
| Backpressure | A slow consumer never slows the run (§45.3) — the stream is dropped, not buffered unboundedly |
| Content | Structural events. Model output is only included where the caller is authorized for it |
| Nested runs | A child's events surface under the parent's stream with the child's run id |

The "best-effort, history is authoritative" rule is what prevents the observability channel from
becoming a correctness dependency — a mistake that turns a UI outage into a run outage.

## §39 — Q20. Budget tracking

### §39.1 — The runtime does not price anything

AGT-7. Pricing is C5's, metering is C8's, and per-session cost already comes back from C13 (`AD-024`
§21). The Agent Runtime is a **consumer of a ceiling and an accumulator of reported spend**.

This constraint is why §3.3 flagged the invariant: "does not own billing" must not be read as "ignores
cost", because an autonomous multi-step loop is the highest-risk cost surface the gateway has.

### §39.2 — The budget cascade

```
tenant quota           (C4 governance, existing)
  └─ run grant         (fixed at admission; AGT-19)
       ├─ step allotment      (per step, from the remaining grant)
       │    └─ session budget (handed to C13; AD-024 §9 enforces it)
       └─ child run grant     (deducted from the remaining grant)
```

Each level is **carved from** the level above, never added to. A child cannot receive more than its
parent has left; a step cannot be allotted more than the run has left.

### §39.3 — Rules

| Rule | Reason |
|---|---|
| The grant is fixed at admission and cannot be raised mid-run | Otherwise the termination argument (§66) fails |
| A step is admitted only if the remaining grant covers its allotment | Pre-admission check; refuse before spending |
| Actual spend comes from C13's reported session cost | Single source of truth; no independent estimation |
| Overspend within a session is C13's to prevent; C14 reconciles after | Two enforcement points, both bounded |
| Parallel branches carve their allotments **before** starting | §31.2 — prevents N branches each seeing the full remainder |
| Exhaustion terminates the run with `BUDGET_EXHAUSTED` | AGT-18; distinct, auditable |
| Unusable spend (a failed step) is still spend | Honest accounting; failures cost money |

### §39.4 — The reserve

A run reserves a small fraction of its grant, unspendable by steps, so that termination bookkeeping —
final audit, summarisation of the outcome, notification — can complete after the main budget is
exhausted. Without a reserve, a budget-exhausted run cannot afford to report that it was exhausted.

## §40 — Q21. Governance interaction

### §40.1 — Two enforcement points, and why both

| Point | What is checked | When |
|---|---|---|
| **Run admission** | May this principal run this plan version, in this tenant, at this depth, with these capabilities and this budget? | Once, at start |
| **Per turn** | Everything `21-GovernanceEngine.md` already checks | Every turn of every session, unchanged |

Run admission is new; per-turn governance is untouched. Both are required, and neither substitutes for
the other:

- Per-turn alone cannot express "this principal may not run autonomous agents", "not past depth 2", "not
  with a budget over X", or "not with the browse capability" — these are properties of the *run*, not of
  any turn.
- Run admission alone cannot see what the run actually does, because the run's content is generated as
  it goes.

### §40.2 — What run admission evaluates

Plan identity and version · declared capability set · depth and parent chain · requested budget ·
requested bounds · irreversible capabilities requested · principal, tenant, and the existing RBAC/ABAC
truth table (`21` §14.1).

The **plan is a value** (§22), which is what makes this possible. A code-based plan could not be
authorized in advance — this is the governance payoff of the plan-as-data decision.

### §40.3 — Mid-run policy change

A policy change during a long run is unavoidable — runs last hours; policies change.

**Rule: the next step's admission uses the current policy snapshot; completed steps are never
retroactively invalidated.** If the new policy denies the run's continuation, it terminates with
`POLICY_REVOKED` (§32.1). Retroactive invalidation is rejected because it would mean a completed,
audited, billed step could become "not to have happened", which no audit model can represent honestly.

`21`'s immutable-snapshot semantics apply per step, exactly as they apply per request.

### §40.4 — What the Agent Runtime does not do

It does not evaluate policy, cache decisions across steps, or interpret verdicts beyond ALLOW/DENY
routing. `SOFT_DENY` and `DRY_RUN` behave exactly as `21` defines them; C14 adds no semantics. Adding
agent-specific policy interpretation would create a second governance implementation, which is the
failure mode `21` GV-INV exists to prevent.

## §41 — Q22. Plugin interaction

**The Agent Runtime has no plugin interaction.** AGT-4.

| Path | Status |
|---|---|
| C14 → C12 directly | **Forbidden** |
| C14 → C13 → C12 | The only path |
| Agent-specific extension points in `28` §EPC | **None added.** `AD-024` §3.4's finding stands |
| Plugins observing agent runs | Via the existing TELEMETRY point at each turn |

`28` PRT-D1 — "removing all plugins changes no mandatory-stage outcome" — is preserved without
modification, because nothing in this document introduces a plugin call.

The one thing worth stating: a plan's step declares *capabilities*, not plugin identities. Capability →
plugin resolution is C13's, using the existing capability negotiation. So a plan is portable across
plugin deployments, and an agent cannot pin itself to a specific plugin build.

## §42 — Q23. Memory interaction

The brief asks for interfaces only, not implementation. Held to strictly.

### §42.1 — Memory is a tool, not a runtime dependency

AGT-5. The Agent Runtime does not read, write, own or embed memory. A step that needs memory is granted
a memory capability and reaches it through C13 like any other tool.

**Why this is right and not merely convenient:** if the Agent Runtime read memory directly, memory
content would enter a model's context without a governed turn — violating AGT-9 and creating exactly the
ungoverned path the whole architecture exists to prevent. Memory content is untrusted input (it may
contain material written by a previous prompt injection), so it must be subject to taint tracking, which
lives in C13 (`AD-024` §35). Routing memory through a tool call gets taint tracking for free; routing it
around C13 would require re-implementing taint at C14.

### §42.2 — The interface seam (conceptual, no implementation)

| Seam | Direction | Semantics |
|---|---|---|
| Memory-read capability | Step → C13 → C12 → Memory | A tool call. Result is tainted as external input |
| Memory-write capability | Same | A tool call. Subject to the same authorization and the irreversibility rules of §33.3 |
| Run history ≠ memory | — | The history is control-plane execution metadata, not a knowledge store. It is never a memory backend |
| Memory scoping | — | Memory's own concern. C14 passes principal/tenant context and nothing more |

### §42.3 — The one thing C14 must not do

It must not "helpfully" inject prior run results into a later step's context outside a governed turn. If
step 7 needs step 3's output, the plan passes it as a step input — which flows into the session, through
the pipeline, under governance, with taint intact. There is no side channel.

## §43 — Q24. Provider neutrality

AGT-27. The Agent Runtime is the *most* provider-neutral component in the system, because it is the
furthest from a provider — three layers up.

| Concern | Where it lives |
|---|---|
| Provider selection | C1 Router, per turn, unchanged |
| Provider capabilities | `ProviderCapability` / `CapabilitySet` (`25A`) |
| Provider-native agent loops | Declined (`AD-024` §51.1, §10 here) |
| Model choice per step | A step declares *requirements*; routing resolves them |

**A step never names a model or a provider.** It declares what it needs — reasoning depth, tool support,
context size, modality — and the existing capability negotiation resolves it. This is the direct
inheritance of `25A`'s rule: ask "can this provider perform this capability?", never "is this provider
X?".

**Enforceable check:** the Agent Runtime's source contains no provider name. This is the same scan
already applied to `GatewayRuntime` in Milestone 3, and it should be applied here as a test when the
component is built.

## §44 — Q25. Failure handling and supervision

The largest single import in the document (§7.1).

### §44.1 — Every run has a supervision policy

AGT-16. There is no unsupervised run and no opt-out. Akka's lesson (§7.2) is that optional supervision
is omitted supervision.

### §44.2 — Strategies

Adapted from OTP, with names kept recognisable:

| Strategy | On a child/step failure | Use |
|---|---|---|
| `FAIL_RUN` | The run terminates immediately | **Default.** Strictest; correct when any failure invalidates the result |
| `RETRY_STEP` | Re-execute the failed step, bounded by intensity | Transient failures on idempotent steps |
| `SKIP_STEP` | Record the failure, continue the plan | Optional enrichment steps |
| `RESTART_FROM` | Return to a declared earlier step and re-run forward | The plan's later state depends on the failed step |
| `ESCALATE` | Fail this run; the parent's supervisor decides | Nested runs |
| `COMPENSATE` | Run a declared compensation step, then fail | Steps with reversible external effects |

`FAIL_RUN` ≈ OTP's terminate; `RETRY_STEP` ≈ `one_for_one`; `RESTART_FROM` ≈ `rest_for_one` (the failed
unit and everything after it); `ESCALATE` is OTP's supervisor-failure propagation. There is no
`one_for_all` analogue, because completed steps have already spent money and had effects — restarting
them is not free the way restarting an Erlang process is. **That difference is the key adaptation:** OTP
restarts are cheap and side-effect-free; agent step restarts are neither.

### §44.3 — Restart intensity — the bound that matters most

> At most **N** restarts within period **P**. On exceeding it, the supervisor stops: the run terminates
> with `RESTART_INTENSITY_EXCEEDED`.

Directly from OTP, and the reason it is here rather than a simple retry counter: a per-step retry count
does not bound a plan that *loops* over a flaky step. Step 5 fails, retries 3 times, succeeds; the loop
comes back; step 5 fails again. A per-step counter resets each iteration and the run restarts forever.
An intensity window over the whole run does not reset, and terminates the run.

Without this, a crash-looping agent is an unbounded bill. **No agent framework studied has this bound.**

### §44.4 — Failure of the supervisor's own machinery

If the Run Store is unreachable, the executor cannot record, and by AGT-14 must not proceed. It fails the
step attempt and yields; the run is picked up later when the store recovers. **Fail-closed**, matching
`21` GV-INV. An executor that proceeded with unrecorded steps would be executing a run whose history is a
lie — worse than a stalled run.

## §45 — Q26. Determinism

### §45.1 — What is and is not deterministic

| Component | Deterministic? |
|---|---|
| Plan interpreter | **Yes** — mandatory (AGT-13) |
| Step scheduling decisions | **Yes** — a pure function of the history |
| Model output | No — recorded |
| Tool output | No — recorded |
| Time, randomness, external input | No — **recorded as step results** |
| Parallel completion order | No — **never observed** (§31.4) |

### §45.2 — The forbidden operations

Straight from Durable Functions (§6.2), and worth naming explicitly because unnamed constraints are not
enforced:

> The plan interpreter MUST NOT read a wall clock, generate randomness or identifiers, perform I/O, read
> mutable configuration, depend on hostname or node identity, iterate an unordered collection, or observe
> completion order.

Where these are genuinely needed:

| Need | Mechanism |
|---|---|
| Current time | A recorded value: `RunStarted` carries the start time; a `Now` step records a timestamp |
| Identifiers | Derived deterministically from run id + history offset |
| Timers / delays | A recorded scheduled-wake event, not a sleep |
| Configuration | Pinned in `RunStarted` alongside the plan version |

### §45.3 — Why this is worth the constraint

Determinism buys three things that are otherwise unavailable: **replay without re-inference** (§46),
which makes crash recovery affordable; **auditability**, because the history fully explains the run; and
**testability**, because a recorded history is a regression test that runs with no provider access.

The cost is that plan authors cannot write arbitrary logic. §26.4 shows why that is tolerable: the
dynamism lives in step results, which are recorded, rather than in the interpreter.

## §46 — Q27. Replay

### §46.1 — What replay is

Re-running the plan interpreter over a recorded history to reconstruct the run's position, **returning
recorded results instead of executing steps**.

It is used in three places: **recovery** (§35), **inspection** (understanding a past run without
re-running it), and **testing** (a recorded history as a fixture).

### §46.2 — What replay is not

It is **not** re-execution. No model is called, no tool runs, no money is spent, no side effect recurs.
The distinction is the whole value: LangGraph re-executes the interrupted node on resume (§8.1); this
design does not.

### §46.3 — Replay divergence

If the interpreter, replaying, produces a different next step than the history records, something is
wrong — non-determinism leaked in, or the plan changed under an in-flight run.

**Rule: divergence is a hard failure.** The run terminates with `REPLAY_DIVERGENCE` and is flagged for
investigation. It is never "fixed" by preferring one side, because a run whose history no longer
explains its behaviour has lost its audit integrity — and the audit trail is the product.

### §46.4 — Plan versioning — the problem Durable Functions taught

The scenario: a run starts on plan v3, executes 20 steps, and v4 is deployed. On resume, replaying v4's
interpreter over a v3 history diverges. Durable Functions needed dedicated machinery for this; it is the
most under-appreciated problem in durable orchestration.

**This design's answer is structural rather than mechanical, and it is the payoff of plan-as-data
(§22):**

| Rule | Effect |
|---|---|
| A run pins its plan **version** at `RunStarted` (AGT-25) | Replay always uses the version the history was created with |
| Plan versions are **immutable values**, retained while any run references them | v3 cannot change under a running v3 run |
| A new deployment creates v4; it does not mutate v3 | No in-flight run is affected |
| Version retention is bounded by the maximum run duration | v3 can be garbage-collected once no run can reference it |
| **No mid-run version migration in V1** | See below |

**Mid-run migration is explicitly declined for V1.** Migrating a run from v3 to v4 requires a
history-compatibility judgement that only the plan author can make, and getting it wrong silently
corrupts a run. Long-lived runs continue on their pinned version; the wall-clock bound guarantees they
eventually end. This costs the ability to hot-patch a broken plan for in-flight runs — an operator must
instead cancel and restart them, which is visible and auditable. That is the right trade for V1, and
§79.7 records it as a deliberate rejection rather than an oversight.

## §47 — Q28. Audit

### §47.1 — The history is the audit trail

Not a parallel structure — the same one (§20). A separate audit log would be a second source of truth
that can drift from the first.

What the history additionally emits to the existing audit sink (`21` PolicyAudit, `AD-024` §20): run
admission decisions, terminal reasons, approval requests and resolutions, supervision events, and
budget events.

### §47.2 — Causal completeness

Every entry carries its causal parent, so the full chain is reconstructible:

```
run  →  step  →  session  →  turn  →  pipeline execution  →  provider attempt
 │                    │
 └── child run        └── tool call
```

**The question "why did this provider call happen?" must be answerable by walking upward to the run and
its plan.** This is the audit property that matters for an autonomous system: without it, an operator
sees a provider call with no explanation, and "an agent did it" is not an answer.

### §47.3 — Properties

Append-only · tenant-partitioned (AGT-26) · digests not payloads (§34.3) · approver identity recorded ·
retention per the existing policy · redaction per the existing rules. The Agent Runtime introduces **no
new audit sink and no new retention class** — it emits into what exists.

## §48 — Q29. Observability

Three layers, addressing distinct questions:

| Layer | Question | Mechanism |
|---|---|---|
| Live | What is this run doing right now? | Orchestration stream (§38) |
| Historical | What did this run do? | History replay (§46.1) |
| Aggregate | How are runs behaving overall? | Metrics (§49) |

**All three are views of the history**, never independent recordings — which is what guarantees they
agree. A monitoring system that disagrees with the audit trail is worse than no monitoring, because it
is trusted.

Operator-facing surfaces required: run list by status/tenant/principal, run detail with the step
timeline, the pending-approval queue, budget consumption per run and per tenant, supervision-event rates,
and the stuck-run view (runs with no progress within a threshold).

## §49 — Q30. Metrics

Emitted through the existing metrics port; naming follows OTel GenAI conventions where they apply
(`gen_ai.operation.name` = `invoke_agent` for runs, per §9's research).

| Metric | Type | Dimensions |
|---|---|---|
| Runs started / completed | Counter | tenant, plan, plan version, terminal reason |
| Run duration | Histogram | tenant, plan |
| Steps per run | Histogram | plan |
| Step duration | Histogram | plan, step |
| Step failures | Counter | plan, step, failure class |
| Supervision actions | Counter | strategy, outcome |
| **Restart intensity exceeded** | Counter | plan, tenant |
| Depth distribution | Histogram | plan |
| Fan-out distribution | Histogram | plan |
| Approval wait time | Histogram | tenant |
| Approvals expired | Counter | tenant, plan |
| Budget consumed / grant | Histogram | tenant, plan |
| Runs terminated by bound | Counter | **which bound** |
| Replay divergence | Counter | plan, plan version |
| Interrupted steps recovered | Counter | resolution path (§35.2) |
| Store unavailability | Counter | — |

**The four that matter most operationally**, because each is silent until it is expensive: *runs
terminated by bound* (tells you which ceiling is mis-set), *restart intensity exceeded* (crash loops),
*replay divergence* (non-determinism leaking in — should be zero, and a non-zero value is a defect not a
warning), and *approvals expired* (a broken human workflow, visible as abandoned runs).

## §50 — Q31. Tracing

| Span | Parent | Notes |
|---|---|---|
| `agent.run` | Caller's span, or none for scheduled runs | Root of the run's trace |
| `agent.step` | `agent.run` | One per step, including retries |
| `agent.child_run` | The delegating `agent.step` | Links to the child's root span |
| `agent.approval` | `agent.run` | Long-lived; spans the wait |
| C13 session span | `agent.step` | Existing (`AD-024` §21) |
| Pipeline execution span | Session span | Existing |

**Two trace-specific problems this layer creates:**

1. **Very long traces.** A multi-hour run produces a span tree that most backends will not hold open.
   Resolution: the run's trace is *linked*, not nested — each step is a trace rooted at the run id via a
   span link, so no span stays open for hours. The run id is the join key.
2. **Resumed runs cross processes.** Trace context must come from the history, not from the executor's
   memory. `RunStarted` records the root context; each resuming node continues from it.

## §51 — Q32. Correlation IDs

| Id | Scope | Source |
|---|---|---|
| `run_id` | The run | Generated at admission |
| `root_run_id` | The whole tree | The topmost ancestor's run id |
| `step_id` | One step, including its retries | Deterministic: run id + history offset (§45.2) |
| `attempt_id` | One execution of a step | step id + retry index |
| `session_id` | The C13 session | C13's, recorded in the history |
| `correlation_id` | Caller-supplied | Passed through unchanged |
| `tenant_id`, `principal_id` | Authorization | Fixed at admission |

**`root_run_id` is the important one.** It is what makes a tree of nested runs queryable as one unit —
total cost, total duration, whether any branch failed. Without it, a 12-run tree is 12 unrelated records
and no one can answer "what did that agent cost?".

`step_id` is deterministic by construction, which is required by §45.2 — a random id would make replay
produce different identifiers each time.

## §52 — Q33. Execution graph

### §52.1 — Two graphs, and they are not the same

| Graph | Nodes | Known when |
|---|---|---|
| **Plan graph** | The plan's constructs | Before the run — it is the plan (§22) |
| **Execution graph** | Actual steps taken, with results | Reconstructed from history |

The plan graph is the *possible*; the execution graph is the *actual*. A `Branch` means the execution
graph is a path through the plan graph; a `Loop` means the execution graph unrolls it.

### §52.2 — Properties

The execution graph is a **DAG** — enforced by construction:

- Steps have exactly one predecessor (`Sequence`) or a join predecessor (`Parallel`).
- Loops **unroll** into distinct step instances rather than creating back-edges — iteration 3 of step 5
  is a different node from iteration 2.
- Recursion creates child run subgraphs, not cycles in the parent (AGT-11).

**No cycles is not a stylistic preference — it is what makes the termination argument (§66) a finite
argument.** A DAG with bounded node count terminates by construction.

### §52.3 — Uses

Visualisation · cost attribution per branch · critical-path analysis · replay verification (the
reconstructed graph must match the recorded history — a mismatch is §46.3 divergence).

## §53 — Q34. State machine

### §53.1 — Run states

| State | Meaning | Terminal |
|---|---|---|
| `ADMITTING` | Governance evaluating run admission | No |
| `RUNNABLE` | Ready; awaiting an executor | No |
| `EXECUTING` | A step is in flight | No |
| `AWAITING_APPROVAL` | Paused for a human (§36) | No |
| `AWAITING_CHILDREN` | Blocked on child runs | No |
| `SUSPENDED` | Paused by an operator | No |
| `COMPLETED` | Plan finished | **Yes** |
| `FAILED` | Terminated by failure or supervision | **Yes** |
| `CANCELLED` | Terminated by cancellation | **Yes** |
| `TERMINATED_BOUND` | A bound was exhausted | **Yes** |

### §53.2 — Transitions

```
                          ┌──────────────┐
              ┌──────────►│  ADMITTING   │
              │           └──────┬───────┘
              │            deny  │  allow
              │       ┌──────────┴───────────┐
              │       ▼                      ▼
              │   [FAILED]            ┌─────────────┐
              │                       │  RUNNABLE   │◄──────────────┐
              │                       └──────┬──────┘               │
              │              executor claims │                      │
              │                              ▼                      │
              │                       ┌─────────────┐   step done   │
              │                       │  EXECUTING  │───────────────┤
              │                       └──┬───┬───┬──┘               │
              │        Approve step      │   │   │  Delegate step   │
              │       ┌──────────────────┘   │   └───────────┐      │
              │       ▼                      │               ▼      │
              │ ┌──────────────────┐         │      ┌────────────────────┐
              │ │AWAITING_APPROVAL │         │      │ AWAITING_CHILDREN  │
              │ └────┬────────┬────┘         │      └─────────┬──────────┘
              │      │approve │expire/deny   │   all children │
              │      └────────┼──────────────┼────────────────┘
              │               │              │  (approved / children done → RUNNABLE)
              │               ▼              ▼
              │          [FAILED]      supervision / bound / cancel
              │                              │
              │        ┌─────────────────────┼──────────────────────┐
              │        ▼                     ▼                      ▼
              │   [FAILED]           [TERMINATED_BOUND]        [CANCELLED]
              │
              └── operator resume ── [SUSPENDED] ◄── operator suspend (from RUNNABLE/EXECUTING)

     plan exhausted, from EXECUTING ────────────────────────► [COMPLETED]
```

### §53.3 — Invariants on the machine

| # | Invariant |
|---|---|
| SM-1 | Terminal states are absorbing. No transition leaves `COMPLETED`, `FAILED`, `CANCELLED`, `TERMINATED_BOUND` |
| SM-2 | Every non-terminal state has at least one path to a terminal state (§66) |
| SM-3 | `EXECUTING` is the only state in which a C13 session is open |
| SM-4 | Transitions are recorded before they take effect (AGT-14) |
| SM-5 | Only `RUNNABLE` may be claimed by an executor — this is what prevents two nodes running one step |
| SM-6 | `AWAITING_APPROVAL` and `AWAITING_CHILDREN` hold no execution resources |
| SM-7 | Cancellation is accepted from **every** non-terminal state |
| SM-8 | A bound may terminate the run from any non-terminal state, including the waiting ones |

SM-8 matters: without it, a run parked in `AWAITING_APPROVAL` could sit past its wall-clock bound
indefinitely, which is why approval has its own expiry (§36.2).

## §54 — Q35. Lifecycle

| Phase | Actions | Failure |
|---|---|---|
| **Submit** | Validate the plan reference; resolve plan version; establish identity | Reject before any record |
| **Admit** | Governance run admission (§40.1); budget reservation; bound assignment | `FAILED`, recorded |
| **Start** | Record `RunStarted` with the pinned plan version, bounds, budget, trace root | Store failure → no run exists |
| **Execute** | The step loop (§25), repeatedly, across arbitrarily many nodes | Per-step supervision (§44) |
| **Pause** | `AWAITING_APPROVAL` / `AWAITING_CHILDREN` / `SUSPENDED` — no resources held | Expiry → terminate |
| **Terminate** | Record the terminal reason; cancel children; final metering; emit audit | Reserve (§39.4) funds this |
| **Retain** | History retained per policy; plan version retained while referenced (§46.4) | — |
| **Purge** | History purged per retention; tenant-scoped | — |

**Startup and shutdown of the runtime itself** (as opposed to a run): because executors are stateless, a
node shutting down abandons its in-flight step, which becomes an interrupted step (§35.2) recovered by
another node. Graceful shutdown may wait for the current step to record its completion, bounded by the
step deadline. **No run state is lost on node loss** — the property that distinguishes this from every
in-process agent framework studied.

## §55 — Q36. Scheduling

### §55.1 — Pull, not push

Executors **claim** runs in `RUNNABLE`. No dispatcher assigns work to nodes.

Pull is chosen because it needs no membership view (a push dispatcher must know which nodes are alive —
a distributed-systems problem this document should not acquire), it load-balances naturally (a busy node
simply claims less), and node loss is a non-event (an unclaimed run is claimed by someone else).

### §55.2 — Claim semantics

| Property | Rule |
|---|---|
| Exclusivity | A claim is exclusive with a lease; only one executor advances a run at a time (SM-5) |
| Lease expiry | Bounded by the step deadline plus a margin; an expired lease returns the run to `RUNNABLE` |
| Double-claim | Prevented by the store's conditional write on the history offset — the second writer loses |
| Fairness | Round-robin across tenants, so one tenant's fleet of runs cannot starve another's |
| Priority | Interactive runs before background runs; **starvation-free** — a background run's wait is bounded |

The conditional write on history offset is the correctness mechanism: even if two executors both believe
they hold the claim (clock skew, network partition), only one can append at offset N. The other's write
fails and it abandons the step. **Safety does not depend on the lease being correct** — the lease is an
optimisation to avoid wasted work, not the mutual-exclusion mechanism. This distinction is what makes
the design safe under partition.

### §55.3 — Scheduled and triggered runs

A run may be started by an API call, a schedule, or an event. **All three are external triggers**; the
Agent Runtime does not contain a scheduler. Adding one would put a durable timer service inside a
component that already needs a durable store, and cron is a solved problem elsewhere.

The one timer the runtime does need — approval expiry and wall-clock bound enforcement — is a periodic
sweep over the store, not a per-run timer. A sweep is stateless and idempotent; per-run timers are
neither.

## §56 — Q37. Concurrency

Four distinct concurrency questions, often conflated:

| Level | Rule |
|---|---|
| Runs across the fleet | Unbounded by design; bounded in practice by tenant quota |
| Steps within one run | **One at a time**, unless inside a `Parallel` (§31) |
| Branches within a `Parallel` | Bounded by the fan-out ceiling |
| Child runs | Each is an independent run; the parent's `AWAITING_CHILDREN` state joins them |

**Two executors never advance the same run concurrently** (SM-5, §55.2). This single rule removes an
entire class of races: no locking within a run, no concurrent history writers, no lost-update problem on
run state.

The concurrency the design *does* have to handle is between a run and the outside world — a cancellation
arriving while a step is in flight, or a policy change between steps. Both are handled at step
boundaries (§32.2, §40.3), which is why the step boundary is the design's synchronisation point.

## §57 — Q38. Backpressure

| Pressure | Response |
|---|---|
| Too many `RUNNABLE` runs | Runs queue in the store. **Queueing is free** — a queued run holds nothing |
| Too many runs admitted | Admission refuses at the tenant concurrency limit, before `RunStarted` |
| Provider saturation | C2 Reliability's existing behaviour; surfaces to the step as a failure |
| Tool saturation | C12's existing quotas; surfaces through C13 |
| Store saturation | Executors back off; runs stall rather than proceeding unrecorded (§44.4) |
| Slow stream consumer | The stream is dropped, never buffered unboundedly (§38.2) |

**The key property: backpressure manifests as delay, not as failure or unbounded memory.** A run waiting
in `RUNNABLE` costs a row. This is a direct consequence of the stateless-executor design — an in-process
agent framework under load holds N live runs in N threads with N contexts in memory, and its backpressure
response is to fall over.

Admission is the only place the system says no, and it says no *before* creating a run, which is the
cheapest possible refusal.

## §58 — Q39. Bounded execution

### §58.1 — The five mandatory bounds

Every run has all five. None may be infinite (AGT-17).

| Bound | Limits | Default | Terminal reason |
|---|---|---|---|
| **Step count** | Total steps across the run | 50 | `MAX_STEPS_EXCEEDED` |
| **Depth** | Nested-run levels | 3 | `MAX_DEPTH_EXCEEDED` |
| **Fan-out** | Concurrent branches per `Parallel` | 5 | `MAX_FANOUT_EXCEEDED` |
| **Wall-clock** | Elapsed time, excluding approval waits (§36.2) | 1 hour | `DEADLINE_EXCEEDED` |
| **Spend** | Total cost across the run tree | Tenant-derived | `BUDGET_EXHAUSTED` |

Defaults are conservative on purpose. An operator raising them is making a visible, auditable,
policy-governed choice; an operator who never thinks about them gets a run that cannot hurt them.

### §58.2 — Why five and not one

Each bounds a different resource and each has a failure mode the others do not catch:

- Step count alone: 50 cheap steps and 50 expensive ones are the same number, wildly different bills.
- Spend alone: a cheap infinite loop runs for days without exhausting the budget.
- Wall-clock alone: a parallel fan-out spends a fortune in ninety seconds.
- Depth alone: no bound on breadth.
- Fan-out alone: no bound on anything sequential.

Any subset leaves a hole. **The tightest bound binds**, and which one that is varies by workload — which
is itself the argument for having all five.

### §58.3 — Bounds on a run tree

Depth, spend and wall-clock are **tree-wide**: a child inherits the remaining budget and the remaining
deadline, and its depth is the parent's plus one. Step count and fan-out are **per-run**: a child gets
its own step allowance.

Step count is deliberately per-run rather than tree-wide, because a tree-wide step budget makes a parent's
allowance depend on how many children it spawns — so adding a child silently starves the parent. Depth and
spend already bound the tree's total work; making step count tree-wide as well would over-constrain
without adding safety.

### §58.4 — Bounds cannot be raised mid-run

AGT-17 and §24.2. A run that could raise its own bounds has no bounds. Bound *lowering* by an operator is
permitted — it can only bring termination closer.

## §59 — Q40. Maximum recursion

| Aspect | Rule |
|---|---|
| Ceiling | Depth 3 by default; policy-configurable per tenant and plan |
| Counting | The root is depth 0; each `Delegate` adds 1 |
| Enforcement | At the child's **admission**, before any work is done |
| Exceeded | The delegating step fails with `MAX_DEPTH_EXCEEDED`; the parent's supervision policy decides |
| Cycles | A plan delegating to itself is permitted but bounded by depth *and* budget (§29) |
| Absolute cap | A hard maximum exists above any configured value, so misconfiguration cannot produce unbounded depth |

**The absolute cap matters.** A policy-configurable bound with no ceiling is a bound only until someone
sets it to a million. The hard cap is a property of the runtime, not of policy.

Note that depth interacts with budget multiplicatively in the worst case: depth 3 with fan-out 5 at each
level is 125 leaf runs. This is why fan-out and depth are separately bounded and why budget is deducted
rather than granted afresh (AGT-19) — the budget cascade is what makes the multiplicative case
affordable rather than merely bounded.

## §60 — Q41. Security

### §60.1 — Threat model

| Threat | Mechanism |
|---|---|
| Prompt injection steering an agent | Inherited from `AD-024` §35; escalated by §60.2 |
| Data exfiltration over many steps | Run-level taint accumulation (§60.2) |
| Privilege escalation via delegation | Monotone permission narrowing (§30.2) |
| Budget exhaustion / denial-of-wallet | Five bounds (§58) + restart intensity (§44.3) |
| Cross-tenant leakage | Tenant partitioning (§63) |
| Plan tampering | Plans are versioned immutable values; AGT-6 |
| Approval bypass | Deny-by-default, expiry, authenticated approver (§36.2) |
| Crash-loop amplification | Restart intensity (§44.3) |
| Malicious plan author | Plans are authored and approved outside the runtime (AGT-6); admission governs execution |

### §60.2 — The run-level trifecta — the escalation this layer creates

`AD-024` §35 tracks the lethal trifecta (private data · untrusted content · external communication)
within a session. **A run spans many sessions, and the limbs can arrive in different ones.**

Step 1 reads private data. Step 4 processes untrusted web content. Step 9 sends an email. Each session
individually satisfies the Rule of Two. The *run* does not.

**AGT-22: taint accumulates across the run, not merely the session.** Concretely:

- A step's granted capabilities are evaluated against the **run's accumulated taint**, not just its own.
- Taint recorded in `StepCompleted` carries forward through the history and into child runs (§30.2).
- A step requesting the third limb is refused; the run terminates with `TRIFECTA_VIOLATION` or, where
  policy permits, escalates to human approval (§36).
- Taint never decays. There is no "it was several steps ago" exemption, because a model's context and a
  run's memory both persist.

**This is the single most important security property in the document**, because it is the one that does
not exist in the layer below and cannot be added there — C13 by construction cannot see across sessions.
An Agent Runtime without it would *weaken* the gateway's security posture relative to using C13 directly,
which would make shipping it a net negative.

### §60.3 — What the Agent Runtime does not do

It does not authenticate (C3), authorize policy (C4), sandbox (C12), or hold secrets (AGT-8). It is a
consumer of security decisions, not a maker of them — with the single exception of run-level taint
accumulation, which no other component is positioned to do. That exception is deliberate and narrow, and
it is stated as an exception rather than hidden.

## §61 — Q42. Permission

### §61.1 — Model

A run carries a **capability set**, fixed at admission, resolved from the plan's declared requirements
intersected with the principal's grants.

| Rule | Statement |
|---|---|
| P-1 | A step's capabilities are a **subset** of the run's |
| P-2 | A child run's capabilities are a **subset** of its parent's (§30.2) |
| P-3 | Capabilities are never widened by anything, including approval — approval authorizes a *use*, not a grant |
| P-4 | Irreversible capabilities require an explicit grant plus §36.3's approval rule (AGT-21) |
| P-5 | Capabilities are declared, not discovered; a plan cannot enumerate what it might be allowed |
| P-6 | The intersection is computed at admission and recorded in `RunStarted`, so it is auditable and replay-stable |

**P-3 is the one that prevents the most dangerous mistake.** If approval could widen a capability set, a
single approval early in a long run would leave the run permanently more powerful — and the approver
almost certainly did not intend to authorize the remaining 40 steps.

### §61.2 — Least privilege over time

A run should not hold, at step 40, a capability it needed only at step 2.

**V1 position:** capabilities are fixed for the run but *granted per step* — a step receives only the
capabilities its plan node declares, and C13's session is opened with only those. So the run's set is a
ceiling, and each session's set is much smaller. Dynamic revocation of the run-level ceiling is not in
V1; per-step narrowing achieves most of the benefit with none of the complexity, because the session is
where a capability is actually usable.

## §62 — Q43. Isolation

| Dimension | Mechanism |
|---|---|
| Between runs | No shared state. Runs communicate only via parent/child (§30) |
| Between parallel branches | AGT-23 — no shared mutable state |
| Between tenants | §63 |
| From tools | C12's sandbox, unchanged; C14 never touches a tool |
| From providers | Three layers of separation |
| Executor from run | The executor holds no run state between steps; a compromised step cannot corrupt the run's history beyond its own append |
| History from payloads | Digests, not payloads (§34.3) |

**The executor/run isolation is a real security property, not just a design tidiness.** Because the
executor is stateless and reloads history each step, there is no in-memory run state for a subsequent
step to corrupt, and no cross-run contamination through a long-lived process. The statelessness chosen
for scalability turns out to buy isolation as well.

## §63 — Q44. Multi-tenancy

| Property | Rule |
|---|---|
| Partitioning | Run state is tenant-partitioned in the store (AGT-26) |
| Reachability | A run may never reference a run in another tenant, as parent, child or otherwise |
| Delegation | Cross-tenant delegation is refused at admission |
| Quotas | Per-tenant concurrent-run limits; a tenant's runs cannot exhaust the fleet |
| Fairness | Round-robin claiming across tenants (§55.2) |
| Noisy neighbour | A tenant's crash-looping run is bounded by its own restart intensity and budget |
| Audit | Tenant-scoped; no cross-tenant visibility |
| Plans | Tenant-scoped or explicitly shared; a shared plan runs under the *caller's* tenant and permissions |

The last row is subtle and worth stating: a shared plan definition does not carry its author's
privileges. It executes with the invoking principal's capability set, which is why P-6 computes the
intersection at admission rather than trusting the plan's declaration.

## §64 — Q45. Resource accounting

Distinct from cost accounting (§65): resources are what the run *consumes*, cost is what it is *charged*.

| Resource | Accounted | Bounded by |
|---|---|---|
| Steps | Count per run and per tree | Step bound |
| Sessions | One per step | Implied |
| Turns | Aggregated from C13 | Session bounds |
| Tool calls | Aggregated from C13/C12 | Session bounds |
| Tokens | Aggregated from metering | Budget |
| Wall-clock | Elapsed, minus approval waits | Deadline |
| Executor time | Sum of step durations | — |
| Store operations | Reads and appends per run | — |
| Concurrent branches | Peak fan-out | Fan-out bound |

The last two are the ones that would otherwise be invisible: a run that reads the store 400 times to
execute 40 steps is a design problem that no cost metric shows, because store operations are not billed
to the tenant. Accounting for them is how the runtime's own efficiency stays observable.

**Everything is attributed to the `root_run_id`** (§51), so a tree's total consumption is a single query.

## §65 — Q46. Cost accounting

### §65.1 — Sources, not calculations

AGT-7. C13 reports session cost (`AD-024` §21); C5 prices; C8 meters. The Agent Runtime **sums what it
is told** and attributes it. It performs no pricing arithmetic of its own — the moment it did, there
would be two cost figures for the same run and a reconciliation problem.

### §65.2 — Attribution

| Level | Attributed to |
|---|---|
| Turn | The step's session |
| Step | The run |
| Child run | Both the child (its own total) and the parent (as delegated spend) |
| Run tree | `root_run_id` |

Double-counting is avoided by distinguishing *own spend* from *tree spend*: a parent's own spend excludes
its children's; its tree spend includes them. Both are recorded, so a bill can be assembled either way
without ambiguity.

### §65.3 — Cost of failure

Failed steps, expired approvals, cancelled runs and abandoned interrupted steps all cost money and are
all recorded as spend. There is no "we do not charge for failures" rule, because the provider charged us.
Surfacing this honestly is what lets an operator see that a flaky plan is expensive.

### §65.4 — Pre-execution projection

Before a step, its allotment is checked against the remaining grant (§39.3) using the existing Cost
Engine projection (wired in Milestone 2). If projection is unavailable, the check is **unenforceable**
and the run fails closed — the same `UNENFORCEABLE` semantics already built into the governance
evaluator. Substituting zero would create exactly the silent bypass that decision was made to prevent.

## §66 — Q47. Termination guarantees

### §66.1 — The guarantee

> **Every run terminates.** Not "should", not "usually" — the bounds guarantee it independently of the
> plan's behaviour, the model's behaviour, or any tool's behaviour.

### §66.2 — The argument

Let a run have step bound S, depth bound D, fan-out bound F, deadline T and budget B, all finite
(AGT-17), fixed at admission and non-raisable (§58.4).

1. **The execution graph is a finite DAG.** Steps have at most S nodes per run; loops unroll into
   distinct nodes (§52.2) and consume step count; there are no back-edges.
2. **The run tree is finite.** Depth ≤ D and fan-out ≤ F, so nodes ≤ (F^(D+1) − 1)/(F − 1), a finite
   number. Recursion creates children, not cycles (AGT-11).
3. **Each step terminates.** A step is one C13 session, and `AD-024` §9 bounds every session in turns,
   tool calls, spend and deadline. A session cannot run forever.
4. **Every step consumes at least one unit of step count**, which is monotonically non-decreasing and
   bounded by S. So the step loop cannot iterate more than S times.
5. **Waiting states are bounded.** `AWAITING_APPROVAL` has a mandatory expiry (§36.2). `AWAITING_CHILDREN`
   is bounded by the children's own termination, which is this argument applied inductively at depth+1 —
   and the base case is depth D, which cannot delegate.
6. **Independently, T bounds elapsed time and B bounds spend**, and SM-8 permits termination from any
   non-terminal state when a bound is exhausted, including the waiting ones.
7. **Failure paths terminate.** Supervision either terminates the run or retries within a finite restart
   intensity, after which it terminates (§44.3).
8. **Infrastructure failure does not create a non-terminating run.** An unreachable store stalls a run in
   `RUNNABLE`, but the wall-clock sweep (§55.3) still terminates it at T.

**Therefore the run terminates**, by (4) if nothing else fires first, and earlier by any of (5)–(8).

### §66.3 — What could break the guarantee

Stated so that any future change touching them is recognised as constitutional:

| Would break it | Prevented by |
|---|---|
| An infinite bound | AGT-17 |
| A run raising its own bounds | §58.4 |
| A cycle in the execution graph | §52.2 |
| Unbounded approval wait | §36.2 mandatory expiry |
| Unbounded restart | §44.3 restart intensity |
| A non-terminating step | `AD-024` §9 session bounds |
| Unbounded delegation | §59 depth cap + AGT-19 budget deduction |
| A waiting state exempt from bounds | SM-8 |

Each row is a single mechanism. If any one is removed, the guarantee is gone — which is why they are
invariants rather than defaults.

## §67 — Q48. Failure taxonomy

Every failure has exactly one class. The class determines retryability and the supervision response.

### §67.1 — Classes

| Class | Example | Retryable | Default supervision |
|---|---|---|---|
| `ADMISSION_DENIED` | Governance refused the run | No | Terminate |
| `PLAN_INVALID` | Plan version missing, or fails validation | No | Terminate |
| `BOUND_EXCEEDED` | Any of the five (§58) | No | Terminate |
| `BUDGET_EXHAUSTED` | Grant consumed | No | Terminate |
| `STEP_TRANSIENT` | Provider unavailable after C2's retries | **Yes** | `RETRY_STEP` |
| `STEP_PERMANENT` | Malformed step output; schema violation | No | Policy |
| `STEP_DENIED` | Governance denied a turn within the step | No | Terminate |
| `STEP_TIMEOUT` | Session deadline | Yes, if idempotent (§33.3) | Policy |
| `TOOL_FAILURE` | Tool errored; surfaced through C13 | Depends on tool declaration | Policy |
| `CHILD_FAILED` | A child run failed | Via `ESCALATE` | Policy |
| `APPROVAL_EXPIRED` | No response in time | No | Terminate |
| `APPROVAL_DENIED` | Human refused | No | Terminate |
| `TRIFECTA_VIOLATION` | Run-level Rule of Two (§60.2) | **No** | Terminate |
| `POLICY_REVOKED` | Policy changed mid-run (§40.3) | No | Terminate |
| `REPLAY_DIVERGENCE` | History and interpreter disagree (§46.3) | No | Terminate + alert |
| `RESTART_INTENSITY_EXCEEDED` | Crash loop (§44.3) | No | Terminate |
| `STORE_UNAVAILABLE` | Run Store unreachable | Yes — the *run*, not the step | Stall, then bound |
| `INTERRUPTED_UNRESOLVED` | §35.2 could not determine the outcome | No | Terminate |
| `CANCELLED` | External cancellation | No | Terminate |
| `TENANT_SUSPENDED` | Tenant-level action | No | Terminate |

### §67.2 — Rules

- **Closed set.** New classes require an amendment to this document. An open taxonomy becomes a
  free-text field, and free-text failure reasons cannot be alerted on.
- **Exactly one class per failure.** No compound classifications.
- **Retryability is a property of the class**, not a per-site judgement — otherwise the same failure is
  retried in one place and not another.
- **`TRIFECTA_VIOLATION` and `REPLAY_DIVERGENCE` are never retryable.** Both indicate that the run's
  integrity assumptions are broken; retrying would be retrying into a known-bad state.
- **Every class maps to exactly one terminal reason** (§67.3), so a run's terminal reason is always
  explicable.

### §67.3 — Terminal reasons (closed set)

`COMPLETED_SUCCESS` · `COMPLETED_PARTIAL` (some `SKIP_STEP` failures) · `FAILED_ADMISSION` ·
`FAILED_PLAN` · `FAILED_STEP` · `FAILED_CHILD` · `FAILED_INTEGRITY` (trifecta, divergence) ·
`TERMINATED_BOUND` · `TERMINATED_BUDGET` · `TERMINATED_POLICY` · `TERMINATED_APPROVAL` ·
`CANCELLED_CALLER` · `CANCELLED_PARENT` · `CANCELLED_OPERATOR` · `CANCELLED_TENANT`.

Fifteen reasons, closed, each mapping from at least one failure class. `AD-024` §46.1 established the
closed-terminal-reason discipline; this is its analogue at the run level.

## §68 — Q49. Extension points

### §68.1 — The default answer is none

`AD-024` §3.4 found that the correct answer to "where does the new extension point go?" was "nowhere —
the layer goes above". The same discipline applies here, and the burden of proof is on any proposed
point.

**No new points are added to `28` §EPC.** PRT-D1 is preserved.

### §68.2 — The seams that do exist

These are *interfaces the runtime consumes*, not extension points plugins hook into:

| Seam | Consumer | Constraint |
|---|---|---|
| Plan source | Where plan versions come from | Read-only; control-plane owned; AGT-6 |
| Run store | History persistence | The one new dependency (§21.3) |
| Approval channel | How humans are asked and answer | Must authenticate the approver |
| Supervision policy source | Where policies come from | Declared, not computed by the agent |
| Orchestration stream sink | Where run events go | Best-effort; never a correctness dependency |
| Clock / sweep | Expiry and deadline enforcement | Must be a recorded step result, never read by the interpreter (§45.2) |

Each is a port with a single obvious implementation in V1. They are seams for testability and for the
DP/CP split, not for third-party extension.

### §68.3 — Extension points explicitly refused

| Refused | Why |
|---|---|
| A plugin hook between steps | It would run outside a governed turn and outside taint tracking |
| A plugin that mutates the plan | Breaks AGT-25 and the termination proof |
| A plugin that observes run state | Cross-tenant data concentration; use the audit stream |
| Custom supervision strategies as code | Strategies must be declarable values so they can be governed |
| Custom bound types | The five are exhaustive for resources the gateway can measure |
| A pre-step or post-step interceptor | Precisely `28` EPC-3's forbidden shape, one layer up |

## §69 — Q50. Migration strategy

### §69.1 — The migration is additive

Nothing existing changes. AGT-30: removing the Agent Runtime restores today's behaviour exactly. No
single-turn request, no C13 session, no pipeline execution behaves differently because C14 exists.

### §69.2 — Phases

| Phase | Content | Gate |
|---|---|---|
| **0** | Council approval of this ADR; the Run Store dependency accepted (§86) | Approval |
| **1** | Run Store + history + replay, no execution. Prove replay determinism against synthetic histories | Replay is bit-stable |
| **2** | Step Executor with `Sequence` only. Single-step runs — which degenerate to exactly one C13 session | A one-step run is indistinguishable from a direct session |
| **3** | Crash recovery, including §35.2's interrupted-step resolution | Kill-mid-step tests recover with zero extra provider calls |
| **4** | Bounds, budget cascade, termination reasons | Every bound demonstrably terminates a run |
| **5** | Supervision: strategies + restart intensity | Crash-loop test terminates within intensity |
| **6** | `Branch` and `Loop` | Termination proof holds under adversarial plans |
| **7** | `Approve` — human-in-the-loop | Expiry defaults to deny; approver authenticated |
| **8** | `Delegate` — nested runs, depth, budget deduction, permission narrowing | A child cannot exceed its parent in any dimension |
| **9** | `Parallel` + `Join` | Replay determinism holds under concurrent completion |
| **10** | Run-level taint accumulation (§60.2) | The cross-session trifecta test is refused |
| **11** | Streaming, metrics, tracing, operator surfaces | Observability agrees with history |

**Phase 10 is late in the list but is a shipping gate, not an enhancement.** The runtime must not be
generally available before run-level taint accumulation works, because without it the Agent Runtime is
security-negative relative to using C13 directly (§60.2). Phases 1–9 may be exercised internally; phase
10 gates external availability.

### §69.3 — Compatibility

| Concern | Position |
|---|---|
| Existing API | Unchanged. Agent runs are a new surface |
| Existing sessions | Unchanged. C13 does not know it has a caller |
| Existing plugins | Unchanged. No new extension points |
| Existing governance | Unchanged semantics; one new admission decision point |
| Rollback | Disable run admission. In-flight runs terminate at their bounds; no data model changes elsewhere |

### §69.4 — What is deliberately not in V1

`Delegate` across tenants (never) · A2A inbound/outbound (§79.6) · mid-run plan version migration
(§46.4) · dynamic capability revocation (§61.2) · a built-in scheduler (§55.3) · shared mutable state
between branches (never) · `one_for_all` supervision (§44.2) · handoff (§79.4) · exactly-once
irreversible steps (not achievable, §35.3).

# Part VII — Diagrams

## §70 — Component diagram

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                              CONTROL PLANE                                    │
│                                                                               │
│   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   │
│   │ Plan Registry│   │  RUN STORE   │   │   Policy     │   │  Approval    │   │
│   │ (versioned,  │   │  ── history  │   │  Source      │   │  Channel     │   │
│   │  immutable)  │   │  ── run rows │   │  (existing)  │   │              │   │
│   └──────┬───────┘   └──────┬───────┘   └──────┬───────┘   └──────┬───────┘   │
└──────────┼──────────────────┼──────────────────┼──────────────────┼───────────┘
           │ read plan v      │ read/append      │ snapshot         │ ask/answer
           │                  │                  │                  │
┌──────────┼──────────────────┼──────────────────┼──────────────────┼───────────┐
│          ▼                  ▼                  ▼                  ▼           │
│   ┌─────────────────────────────────────────────────────────────────────┐     │
│   │              C14  AGENT RUNTIME  (data plane, stateless)            │     │
│   │                                                                     │     │
│   │   ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐    │     │
│   │   │ Run Admission│   │ Plan         │   │  Supervisor          │    │     │
│   │   │ (calls C4)   │   │ Interpreter  │   │  strategy + intensity│    │     │
│   │   └──────────────┘   │ DETERMINISTIC│   └──────────────────────┘    │     │
│   │                      │  NO I/O      │                               │     │
│   │   ┌──────────────┐   └──────────────┘   ┌──────────────────────┐    │     │
│   │   │ Step Executor│                      │  Bound / Budget      │    │     │
│   │   │ claim→exec→  │                      │  Enforcer            │    │     │
│   │   │ record→yield │                      └──────────────────────┘    │     │
│   │   └──────┬───────┘                                                  │     │
│   └──────────┼──────────────────────────────────────────────────────────┘     │
│              │ opens exactly ONE session per step (AGT-12)                    │
│              ▼                                                                │
│   ┌─────────────────────────────────────────────────────────────────────┐     │
│   │        C13  TOOL SESSION ORCHESTRATOR        (AD-024, unchanged)    │     │
│   │        turns · tool calls · taint · session bounds · session audit  │     │
│   └──────┬──────────────────────────────────────────────┬───────────────┘     │
│          │ one complete execution per turn              │ tool invocation     │
│          ▼                                              ▼                     │
│   ┌────────────────────────────────────┐      ┌──────────────────────────┐    │
│   │  REQUEST PIPELINE   (unchanged)    │      │  C12 PLUGIN RUNTIME      │    │
│   │  INGRESS→AUTHN→GOVERNANCE→ROUTER→  │      │  sandbox · quotas ·      │    │
│   │  RELIABILITY→SECRETS→ADAPTER→      │      │  permissions             │    │
│   │  STREAM_GUARD→SCHEMA_LOCK→         │      └──────────────────────────┘    │
│   │  METERING→COST→EMITTER             │                                      │
│   └──────────────┬─────────────────────┘                                      │
└──────────────────┼────────────────────────────────────────────────────────────┘
                   ▼
              provider (via C1 routing — never named by C14)
```

**Read the diagram for what is absent:** there is no arrow from C14 to C12, none from C14 to a provider,
and none from C14 into the middle of the pipeline. Those absences are AGT-1, AGT-2 and AGT-4.

## §71 — Sequence: a simple three-step run

```
Caller   C14-Admit   Store   C14-Exec   C13    Pipeline   Provider
  │          │         │        │        │        │          │
  ├─ start ─►│         │        │        │        │          │
  │          ├─ admit (C4: plan v, caps, depth, budget) ──────┤
  │          ├─ append RunStarted ─►│    │        │          │
  │◄─ run_id─┤         │        │        │        │          │
  │          │         │        │        │        │          │
  │          │         │◄─claim─┤  [node A]       │          │
  │          │         ├─history►│       │        │          │
  │          │         │        │ replay (no I/O) │          │
  │          │         │◄─ append StepScheduled(1)│          │
  │          │         │        ├─open session─►│ │          │
  │          │         │        │        ├─ turn ►│          │
  │          │         │        │        │        ├─ call ──►│
  │          │         │        │        │        │◄─ resp ──┤
  │          │         │        │        │◄───────┤          │
  │          │         │        │        ├─ turn (tool result) ─► ... │
  │          │         │        │◄─done──┤        │          │
  │          │         │◄─ append StepCompleted(1)│          │
  │          │         │   ✱ YIELD — any node may take step 2 │
  │          │         │        │        │        │          │
  │          │         │◄─claim─┤  [node B — different node] │
  │          │         ├─history►│  replay → position = step 2│
  │          │         │◄─ StepScheduled(2) … StepCompleted(2)│
  │          │         │        │        │        │          │
  │          │         │◄─claim─┤  [node A again]  step 3     │
  │          │         │◄─ StepScheduled(3) … StepCompleted(3)│
  │          │         │◄─ append RunTerminated(COMPLETED_SUCCESS)
  │◄─ event: completed ─┤       │        │        │          │
```

Three things to notice: the run moves between nodes freely (✱); every step is bracketed by two durable
writes; and every provider call is reached only through C13 → Pipeline.

## §72 — Sequence: delegation and human approval

```
Parent-Exec   Store    C13    Child-Exec   Approver
     │          │       │         │           │
     │  step 4 = Delegate                     │
     ├─ check depth (2<3) & carve budget ─────┤
     ├─ append ChildRunStarted ─►│            │
     ├─ parent → AWAITING_CHILDREN            │
     │  ✱ parent holds NO resources           │
     │          │       │         │           │
     │          │◄─claim─────────┤ [child runs independently]
     │          │       │◄─ steps ┤           │
     │          │◄─ ChildRun RunTerminated(COMPLETED_SUCCESS)
     │          │       │         │           │
     │◄── parent reclaimed → RUNNABLE ────────┤
     ├─ append ChildRunCompleted(cost) ──►│   │
     │          │       │         │           │
     │  step 5 = Approve (irreversible capability)
     ├─ append ApprovalRequested(expiry) ─►│  │
     ├─ run → AWAITING_APPROVAL              │
     │  ✱ zero resources held; may wait days │
     │          │       │         │           │
     │          │       │         │  ◄── notified ──┤
     │          │       │         │           │
     │          │◄──── ApprovalResolved(APPROVED, approver id) ──┤
     │◄── reclaimed → RUNNABLE ──────────────┤
     ├─ step 5 executes with the approved capability
     │          │       │         │           │
     │  (had it expired: RunTerminated(TERMINATED_APPROVAL) — deny by default)
```

## §73 — Execution graph: plan vs actual

```
PLAN GRAPH (v7, immutable)              EXECUTION GRAPH (run r-88, from history)

      ┌────────┐                              ┌────────┐
      │ step A │                              │  A     │  ok    $0.04
      └───┬────┘                              └───┬────┘
          │                                       │
      ┌───▼────┐  Branch                      ┌───▼────┐
      │  B?    │────────┐                     │  B?    │ → took "yes"
      └───┬────┘        │                     └───┬────┘
     yes  │        no   │                         │
      ┌───▼────┐   ┌────▼───┐                 ┌───▼────┐
      │ Loop C │   │ step D │                 │  C#1   │  ok    $0.11
      │ max 3  │   └────────┘                 └───┬────┘
      └───┬────┘                              ┌───▼────┐
          │                                   │  C#2   │  fail → RETRY_STEP
      ┌───▼─────────┐  Parallel               ├────────┤
      │  E1 E2 E3   │  fan-out 3              │  C#2'  │  ok    $0.13
      └───┬─────────┘                         └───┬────┘
          │ Join                              ┌───▼────┐
      ┌───▼────┐                              │  C#3   │  ok    $0.09
      │ step F │                              └───┬────┘
      └────────┘                       ┌──────────┼──────────┐
                                    ┌──▼──┐    ┌──▼──┐    ┌──▼──┐
   the POSSIBLE                     │ E1  │    │ E2  │    │ E3  │  concurrent
                                    └──┬──┘    └──┬──┘    └──┬──┘
                                       └──────────┼──────────┘
                                              ┌───▼────┐  Join — by branch index,
                                              │   F    │  NOT completion order
                                              └────────┘
                                          the ACTUAL — a finite DAG
```

The loop **unrolls** (C#1, C#2, C#2′, C#3) rather than creating a back-edge (§52.2). The retry appears
as its own node (C#2′), so the history shows four executions of one plan node — which is why step count,
not plan size, is the bound that matters.

## §74 — Failure and supervision flow

```
                        step fails
                            │
                            ▼
                  ┌───────────────────┐
                  │ classify (§67.1)  │
                  └─────────┬─────────┘
                            │
           ┌────────────────┼─────────────────┐
           ▼                ▼                 ▼
    non-retryable      retryable        integrity class
           │                │        (TRIFECTA / DIVERGENCE)
           │                ▼                 │
           │      ┌───────────────────┐       │
           │      │ irreversible caps?│       │
           │      └────┬─────────┬────┘       │
           │       yes │      no │            │
           │           ▼         ▼            │
           │      (no retry) ┌────────────────────────┐
           │           │     │ restart intensity:     │
           │           │     │ restarts in period P   │
           │           │     │      < N ?             │
           │           │     └────┬──────────────┬────┘
           │           │      yes │           no │
           │           │          ▼              ▼
           │           │   ┌─────────────┐  ┌──────────────────────────┐
           │           │   │ RETRY_STEP  │  │ RESTART_INTENSITY_       │
           │           │   │ new session │  │ EXCEEDED → terminate     │
           │           │   └─────────────┘  └──────────────────────────┘
           ▼           ▼
   ┌───────────────────────────────────────┐
   │      apply supervision strategy        │
   ├───────────────────────────────────────┤
   │ FAIL_RUN      → terminate (default)    │
   │ SKIP_STEP     → record, continue       │
   │ RESTART_FROM  → rewind to declared step│
   │ COMPENSATE    → run compensation, fail │
   │ ESCALATE      → fail; parent decides   │──► parent's supervisor
   └───────────────────┬───────────────────┘
                       ▼
              record SupervisionApplied
              (always — AGT-20: never silent)
```

## §75 — Timeline: a long-running run

```
t=0        run admitted, RunStarted, budget $5.00, deadline 1h, steps 50
│
├─ 0:00:02  ▓▓ step 1  research      node A   $0.08   ── 1 session, 3 turns
├─ 0:00:31  ▓▓▓ step 2 fetch+summarise node A  $0.22  ── 1 session, 5 turns
│
│  0:01:04  ✖ node A dies mid-step 3
│           history tail = StepScheduled(3)     ← interrupted step (§35.2)
│
├─ 0:01:19  node B claims (lease expired). Queries C13 by session id →
│           session completed. Adopts the outcome. ZERO provider calls spent.
├─ 0:01:20  ▓▓ step 3 recorded complete        $0.14
│
├─ 0:01:44  ▓▓▓▓ step 4 Delegate → child run c-2 (depth 1, budget $1.20 carved)
│    │       └─ 0:01:46 ▓▓ c-2 step 1   node C   $0.19
│    │       └─ 0:02:10 ▓▓ c-2 step 2   node A   $0.24
│    └─ 0:02:38 child completed, $0.43 consumed, returned to parent
│
├─ 0:02:39  step 5 = Approve (send external email — irreversible)
│           ApprovalRequested, expiry 24h
│
│  ░░░░░░░░░░░░░░░░░░  6h 12m waiting  ░░░░░░░░░░░░░░░░░░
│           ZERO resources held · ZERO cost accrued
│           wall-clock bound NOT consumed (§36.2)
│
├─ 6:15:03  ApprovalResolved(APPROVED by u-4471)
├─ 6:15:04  ▓▓ step 5 executes                  $0.06
│
├─ 6:15:22  ▓ step 6 verify                     $0.05
└─ 6:15:40  RunTerminated(COMPLETED_SUCCESS)
            elapsed wall-clock counted: 0:03:28 of 1:00:00
            total tree spend: $1.41 of $5.00 · steps 6+2 of 50 · depth 1 of 3
```

The 6-hour gap is the point. Nothing is held, nothing is billed, no node is pinned, and the wall-clock
bound is untouched — the approval expiry bounds that interval instead.

## §76 — Sequence: crash recovery, the interrupted-step case

```
node A            Store               C13                node B
  │                 │                  │                    │
  ├─ claim r-88 ───►│ (lease 90s)      │                    │
  ├─ append StepScheduled(7, session s-19) ──►│             │
  ├─ open session s-19 ──────────────►│                     │
  │                 │                  ├─ turn 1 …          │
  ✖ node A dies     │                  ├─ turn 2 …          │
                    │                  ├─ session s-19 completes, audited
                    │                  │                    │
                    │  lease expires   │                    │
                    │◄── claim r-88 ───────────────────────┤
                    ├── history ──────────────────────────►│
                    │        tail = StepScheduled(7): AMBIGUOUS
                    │                  │                    │
                    │                  │◄─ query s-19 ──────┤   §35.2 step 1
                    │                  ├─ COMPLETED, cost ─►│
                    │◄── append StepCompleted(7) ──────────┤
                    │        ✓ recovered, ZERO extra provider calls
                    │                  │                    │
                    │  ── alternative: C13 has no record of s-19 ──
                    │        → treat as failed → supervision (§35.2 step 2)
                    │  ── alternative: s-19 still in flight ──
                    │        → wait to the session deadline, then fail (step 3)
```

# Part IX — Comparison

## §77 — Against the named systems

### §77.1 — Matrix

| Property | **This design** | LangGraph | Temporal | CrewAI | AutoGen | Semantic Kernel | OpenAI Agents SDK | Copilot Agent |
|---|---|---|---|---|---|---|---|---|
| Durable run state | Yes | Yes (checkpointer) | Yes | No | No | Yes (process) | Session store | No |
| Recovery without re-inference | **Yes** | No (node re-runs) | Yes | — | — | Partial | — | — |
| Deterministic orchestrator | **Enforced** | No | **Enforced** | No | No | No | No | No |
| Plan as data | **Yes** | Code graph | Code | Config | Code | Code | Code | Implicit |
| Plan versioning pinned per run | **Yes** | No | Manual | No | No | No | No | No |
| Supervision strategies | **Yes (6)** | No | Retry policy | No | No | No | No | No |
| **Restart intensity bound** | **Yes** | No | No | No | No | No | No | No |
| Mandatory bounds | **5, all required** | Recursion limit | Timeouts | Max iter | Max turns | Some | Max turns | Implicit |
| Termination proof | **Yes (§66)** | No | No | No | No | No | No | No |
| Governance per model call | **Every turn** | No | N/A | No | No | Filters | Guardrails (opt-in) | Provider-side |
| Cross-session taint tracking | **Yes (§60.2)** | No | N/A | No | No | No | No | No |
| Human-in-the-loop | Yes, state + expiry | Yes (`interrupt`) | Signals | No | Yes | Yes | No | Yes (UI) |
| Approval expiry defaults to deny | **Yes** | No | No | — | No | No | — | No |
| Nested composition | Call/return, budget-deducted | Subgraphs | Child workflows | Hierarchical crew | Group chat | Sub-processes | Handoffs | No |
| Budget cascade with deduction | **Yes** | No | No | No | No | No | No | No |
| Multi-tenant partitioning | **Yes** | No | Namespaces | No | No | No | No | N/A |
| Provider-neutral | **Enforced** | Yes | N/A | Yes | Yes | Yes | **No** (OpenAI) | No |
| Stateless executor | **Yes** | No | Worker-held | No | No | No | No | No |
| Closed failure taxonomy | **Yes (20)** | No | No | No | No | No | No | No |
| Free-form agent conversation | **No** | Partial | N/A | Partial | **Yes** | Partial | Handoffs | N/A |
| Arbitrary code in orchestration | **No** | **Yes** | **Yes** | Yes | Yes | Yes | Yes | Yes |
| Ecosystem / community | **None** | Large | Large | Large | Large | Large | Large | N/A |

### §77.2 — Where this design is intentionally stronger

**1. Recovery costs no inference.** LangGraph re-executes the interrupted node; the CLI agents lose the
run entirely. Here, a 40-step run that dies at step 38 resumes for the price of a database read. On a
long expensive run this is the difference between a retry costing cents and costing the whole budget
again. This is Temporal's property, and this design is the only *agent* system in the table that has it.

**2. Restart intensity.** No system in the table bounds restarts over a window. Every one of them can be
made to crash-loop by a plan that repeatedly hits a flaky step inside a loop, and every one of them will
bill for it. This is a one-line concept borrowed from OTP that closes a real denial-of-wallet hole.

**3. Five mandatory bounds and an actual termination proof.** Others have *a* limit — recursion depth, or
max turns. None has all five, none makes them mandatory, and none argues that termination follows. §66
is a proof, not a claim. For an enterprise gateway, "this run will end" must be a guarantee, not an
expectation.

**4. Governance on every model call.** Inherited from `AD-024`, and unmatched. LangGraph and CrewAI have
no governance layer at all; the OpenAI SDK's guardrails are a library an author can decline to import.
Here, every turn of every step is a complete `RequestPipeline` execution, which means policy, quota,
metering, cost and audit apply — and an agent author cannot switch them off.

**5. Cross-session taint accumulation.** §60.2. No framework tracks the lethal trifecta across a
multi-session run, because none has the concept of a run spanning governed sessions. This is the property
that makes a long autonomous run safe rather than merely bounded, and it is the one this document would
be irresponsible to ship without.

**6. Budget cascade with deduction.** AGT-19. Others let a sub-agent be created with a fresh budget. Here
a child's grant comes out of its parent's remainder, so a tree's total cost is bounded by the root's
grant regardless of shape.

**7. Plan versioning pinned per run.** The problem Durable Functions needed dedicated machinery for.
Because a plan is an immutable value, a deployment cannot invalidate an in-flight run — a class of bug
that in code-based orchestrators shows up as a mysterious replay failure days after a release.

**8. A stateless executor.** Every framework in the table holds a run in a process. This one holds a run
in a store and lets any node advance it — which is what makes crash recovery, horizontal scale and
`AD-006` conformance the same mechanism rather than three.

### §77.3 — Where this design trades flexibility for determinism

Stated plainly, because a comparison that only lists advantages is marketing.

**1. No arbitrary code in the orchestrator.** Temporal, LangGraph, AutoGen and Semantic Kernel let you
write ordinary code between model calls. Here the plan vocabulary is eight constructs (§26.2). Anything
outside them must be a step — which means a session, which means cost and latency for work that could
have been three lines of Python. **This is the largest usability cost in the design.** §26.4 mitigates it
(dynamism lives in recorded step results), but does not remove it.

**2. No free-form agent conversation.** AutoGen's group chat is genuinely more expressive for
exploratory, emergent multi-agent problems. Bounded call/return cannot express "five agents discuss until
they converge". The judgement: emergent convergence is not something an enterprise gateway can bound,
budget or audit, and unbounded is unacceptable here even when it is more capable.

**3. No handoff.** The OpenAI SDK's handoff — transferring control without returning — is a clean fit for
triage-and-route patterns. Declined because budget and audit ownership become ambiguous the moment
control transfers without a return path (§8.5). A triage pattern here is a `Branch` plus a `Delegate`,
which is more verbose and less elegant.

**4. No mid-run plan migration.** A broken plan cannot be hot-patched for in-flight runs (§46.4). An
operator must cancel and restart. Durable Functions supports migration; this design refuses it in V1
because getting it wrong corrupts a run silently.

**5. Every step is a full governed session.** More latency and more overhead per step than any in-process
framework. A step that a framework would do in 200 ms of local logic here costs a session with
governance, routing, metering and audit. That is the price of the property in §77.2(4), and it is paid on
every step.

**6. No ecosystem.** LangGraph, CrewAI and the OpenAI SDK have integrations, examples, and people who
already know them. This is bespoke. Every capability must be built, and every plan author must learn a
vocabulary that exists nowhere else.

**7. Conservative defaults will annoy people.** 50 steps, depth 3, fan-out 5, one hour. Real agentic work
will hit these. Raising them is a governed action, which is the point, but it is friction that a library
does not have.

### §77.4 — The honest summary

**This design is worse than LangGraph or the OpenAI SDK for building an agent quickly, and worse than
AutoGen for open-ended multi-agent exploration.** It is better than all of them at answering the
questions an enterprise operator actually has to answer: *Will this terminate? What will it cost at
worst? Who authorized it? What did it do? Can it exfiltrate? What happens when the node dies? Can one
tenant hurt another?*

The trade is deliberate and matches the gateway's name. A reliability-first system should be harder to
use and impossible to misuse in the ways that matter. If the project's goal were developer velocity, the
right answer would be to adopt LangGraph and wrap it — and §79.1 explains why that was considered and
rejected.

---

# Part X — Rejected Alternatives

## §78 — Structural alternatives

### §78.1 — Extend C13 with durability, planning and approval

**Rejected.** It would make C13 stateful, which puts durable state on the request hot path and breaks
`AD-006`. It would also conflate two bound models — a session's and a run's — leaving no answer to "how
many tool calls may this run make?". `AD-024` §51.4 already deferred these capabilities out of C13 for
exactly this reason.

### §78.2 — A pipeline stage for agent orchestration

**Rejected, and this is `AD-024` §3.4's finding restated.** The pipeline is a linear non-bypass assembly.
An orchestration stage would have to re-enter it, and a re-entrant non-bypass chain is not a non-bypass
chain. It also violates `AD-024`'s frozen premise, which this document treats as immovable (§1).

### §78.3 — A stateful agent service holding runs in memory

**Rejected.** The simplest thing to build and the one every framework does. It loses runs on restart —
which is §15 item 2's gap, unfixed — cannot support hours-long approval waits without pinning resources, and
makes horizontal scale a sticky-session problem. The stateless-executor split costs one durable store and
buys crash recovery, scale, and executor/run isolation (§62) simultaneously.

### §78.4 — Plan as code

**Rejected.** More expressive, and Temporal's choice. But: it cannot be authorized as a value before it
runs (§40.2), it makes versioning a hard problem (§46.4), and it means the answer to "what was this agent
instructed to do?" lives in a repository at an unknown commit rather than in the audit trail. §77.3(1)
records the expressiveness cost honestly.

### §78.5 — Adopt an existing engine (Temporal, LangGraph, Step Functions)

**Considered seriously; rejected for V1.** Temporal is the closest fit and this design owes it a great
deal. But adopting it would mean: a substantial new operational dependency (cluster, workers, versioning
discipline); plans as Java code, which forfeits §78.4's governance property; a second identity, tenancy
and audit model to reconcile with the gateway's; and no path to run-level taint accumulation (§60.2)
without building it anyway.

**The honest counter-argument:** Temporal is battle-tested and this is not, and "we built our own durable
execution engine" is a sentence that has ended badly for many teams. The mitigation is scope — this
design implements a *narrow* subset (append-only history, replay, claim-with-conditional-write) with no
general workflow ambitions, and §86 records the dependency as requiring explicit Council acceptance
rather than slipping it in.

If the Council prefers, **the Run Store and replay layer could be implemented on Temporal** without
changing anything else in this document. The architecture is written against the *properties* of durable
execution, not against a specific engine.

### §78.6 — No agent runtime; let callers orchestrate

**Rejected.** This is today's state. It means the caller holds durable state, the caller bounds the loop,
the caller tracks the budget across sessions, and — critically — **nobody tracks taint across sessions**.
A caller-orchestrated multi-session agent has the trifecta hole of §60.2 with no component positioned to
close it. It also means every customer reimplements bounds and recovery, badly.

## §79 — Specific rejections

### §79.1 — Wrapping LangGraph

**Rejected.** Its recovery model re-executes nodes (§8.1), which is the opposite of the property this
design most needs. Its orchestrator is arbitrary Python, so determinism could not be enforced. Wrapping
it would mean owning its non-determinism without owning its code.

### §79.2 — Peer-to-peer agent messaging

**Rejected.** §30.3. No accountable principal per message, no natural budget owner, and no bound on a
conversation. AutoGen's expressiveness is real (§77.3(2)) and the trade is stated.

### §79.3 — Shared mutable state between parallel branches

**Rejected.** §31.3. Reproduces database concurrency anomalies without database machinery, and the
lock-holder would be a model, which may never release.

### §79.4 — Handoff

**Rejected.** §8.5, §77.3(3).

### §79.5 — Agent-controlled bounds

**Rejected.** A run that can raise its own limits has none, and §66's proof fails at step 1. Even
"request an increase, subject to approval" is refused for V1: it turns every bound into a negotiation
and gives an injected prompt a lever to pull.

### §79.6 — A2A as a transport in V1

**Rejected for V1, not permanently.** A remote A2A agent is an external communication path with an
unbounded remote executor behind it — limb₃ of the trifecta by construction, and the remote side's taint
state is unknowable. Supporting it needs its own Rule-of-Two analysis and probably its own ADR. The
protocol's *task lifecycle* shape is adopted (§9.2); the transport is not.

### §79.7 — Mid-run plan version migration

**Rejected for V1.** §46.4. History-compatibility judgements can only be made by the plan author, and a
wrong judgement corrupts a run silently. The cost — cancel and restart instead of hot-patch — is visible
and auditable, which is the right failure mode.

### §79.8 — Exactly-once step execution

**Rejected as unachievable, not as undesirable.** §35.3. Across a non-transactional boundary, a crash
between "the tool ran" and "we recorded that it ran" is indistinguishable from "the tool never ran". The
design offers at-least-once for idempotent steps and at-most-once for irreversible ones, and says so
rather than claiming a guarantee it cannot keep.

### §79.9 — An in-runtime scheduler

**Rejected.** §55.3. Cron is solved elsewhere; adding a durable timer service to a component that already
needs a durable store doubles the infrastructure risk for no architectural gain.

### §79.10 — Memory as a first-class runtime concern

**Rejected.** §42.1. Reading memory outside a governed turn would bypass taint tracking and violate
AGT-9. Memory as a tool gets both for free.

# Part XI — Contracts

Described, not specified in code. No Java, no interfaces, no signatures — per the brief. Each contract
states what the party must guarantee and what it may assume.

## §80 — ARC — Agent Run Contract

*Between the caller and the Agent Runtime.*

| # | Guarantee |
|---|---|
| ARC-1 | A run is identified by a `run_id` unique within the tenant, returned before any step executes |
| ARC-2 | A run's plan version, bounds, budget, capability set and supervision policy are fixed at admission and reported back to the caller |
| ARC-3 | The run terminates with exactly one terminal reason from the closed set (§67.3) |
| ARC-4 | Total tree spend never exceeds the granted budget, except by the amount of one in-flight step's allotment plus the reserve |
| ARC-5 | The caller may cancel from any non-terminal state, and cancellation is accepted, not negotiated |
| ARC-6 | The caller may read run status and history at any time, subject to authorization |
| ARC-7 | No provider or plugin is invoked outside a governed turn |
| ARC-8 | Run outcomes are reproducible from history without re-invoking any model |

**The caller may assume** the run terminates (§66), costs at most its grant, and is fully audited.
**The caller may not assume** exactly-once irreversible effects (§35.3), a specific provider or model, a
specific execution order beyond the plan's, or that a step it observed as scheduled actually ran.

## §81 — PLC — Plan Contract

*Between the plan author (and its approver) and the runtime.*

| # | Requirement |
|---|---|
| PLC-1 | A plan is an immutable, versioned value. Publishing a change creates a new version |
| PLC-2 | A plan uses only the vocabulary of §26.2. Anything else is rejected at validation |
| PLC-3 | Every `Loop` declares a finite iteration ceiling |
| PLC-4 | Every step declares its required capabilities explicitly; discovery is not available (P-5) |
| PLC-5 | Every step declares whether it is idempotent. Undeclared means **not** idempotent |
| PLC-6 | Every `Parallel` declares a fan-out no greater than the ceiling; branches declare no shared state |
| PLC-7 | Every `Approve` declares an expiry and an approver policy |
| PLC-8 | A plan declares a supervision policy; if absent, `FAIL_RUN` with the strictest intensity applies |
| PLC-9 | A plan names no provider, model, plugin or tool implementation — capabilities only |
| PLC-10 | Branch predicates are total, side-effect-free functions of recorded results |
| PLC-11 | A plan cannot modify itself, its bounds, its budget or its capability set |
| PLC-12 | A plan version is retained while any run references it (§46.4) |

**Validation is at publication, not at run time.** An invalid plan can never start a run, so a run never
fails for a reason the author could have been told about days earlier.

## §82 — SPC — Step Contract

*Between the Step Executor and C13.*

| # | Guarantee |
|---|---|
| SPC-1 | Exactly one C13 session per step attempt (AGT-12) |
| SPC-2 | The session receives only the step's declared capabilities — a subset of the run's (P-1) |
| SPC-3 | The session receives a budget allotment carved from the run's remainder, and a deadline no later than the run's |
| SPC-4 | The session receives the run's accumulated taint state (§60.2) |
| SPC-5 | The session's correlation context carries `run_id`, `root_run_id`, `step_id`, `attempt_id` |
| SPC-6 | The executor does not interpret the session's internal turns; it consumes the session's outcome |
| SPC-7 | The executor records `StepScheduled` before opening the session and a terminal step event after it |
| SPC-8 | On cancellation, the executor cancels the session and does not open another |

**C13 guarantees in return** (from `AD-024`): the session is bounded in turns, tool calls, spend and
time; every turn is a complete `RequestPipeline` execution; taint is tracked within the session and
returned; session cost is reported; the session terminates.

## §83 — HSC — History and Store Contract

*Between the runtime and the Run Store. The correctness-critical contract.*

| # | Requirement |
|---|---|
| HSC-1 | Append-only. No event is ever modified or deleted except by whole-run retention purge |
| HSC-2 | Total order per run, by history offset |
| HSC-3 | **Conditional append**: an append at offset N succeeds only if the current length is N. This is the mutual-exclusion primitive (§55.2) |
| HSC-4 | An append is durable before it is acknowledged. An unacknowledged append must be treated as not having happened |
| HSC-5 | Read-your-writes within a run |
| HSC-6 | Tenant-partitioned; no cross-tenant read path (AGT-26) |
| HSC-7 | Events carry digests, not payloads (§34.3) |
| HSC-8 | Unavailability is reported as unavailability, never as an empty history |

**HSC-3 and HSC-8 are the two that must not be compromised.** HSC-3 is what makes double-execution
impossible without distributed locking. HSC-8 matters because an empty history is indistinguishable from
a new run, so a store that returns empty on failure would cause a running run to be restarted from
scratch — the worst possible failure mode, and one that a naïve cache in front of the store would
introduce.

## §84 — Supervision, Approval and Observability contracts

### §84.1 — SVC — Supervision Contract

| # | Requirement |
|---|---|
| SVC-1 | Every run has a policy: a strategy, a restart intensity N, and a period P (AGT-16) |
| SVC-2 | N and P are finite and fixed at admission |
| SVC-3 | The policy is declared data, never code (§68.3) |
| SVC-4 | Every supervision decision records `SupervisionApplied` — never silent (AGT-20) |
| SVC-5 | `ESCALATE` propagates to the parent's supervisor; at the root it terminates the run |
| SVC-6 | Restart counting is over the run, not per step (§44.3) |
| SVC-7 | A step with a non-idempotent declared capability is never auto-retried (§33.3) |

### §84.2 — APC — Approval Contract

| # | Requirement |
|---|---|
| APC-1 | Deny by default. Silence is never consent |
| APC-2 | Mandatory expiry; expiry terminates the run |
| APC-3 | The approver is authenticated and recorded |
| APC-4 | Approval authorizes one use of one capability in one step of one run. Never a standing grant (P-3) |
| APC-5 | Separation of duties enforced where policy requires it |
| APC-6 | An approval request holds no execution resources |
| APC-7 | An approval cannot widen the run's capability set |
| APC-8 | Both request and resolution are audited with causal edges |

### §84.3 — OBC — Observability Contract

| # | Requirement |
|---|---|
| OBC-1 | The history is authoritative; every other surface is a view (AGT-15) |
| OBC-2 | The orchestration stream is best-effort; loss never affects correctness |
| OBC-3 | Metrics are emitted through the existing port; no new sink |
| OBC-4 | Traces link rather than nest across steps, so no span stays open for hours (§50) |
| OBC-5 | `root_run_id` is present on every emission from every level |
| OBC-6 | Replay divergence is an alert-level signal; the expected value is zero |
| OBC-7 | No observability surface exposes another tenant's data |

---

# Part XII — Operational Readiness

## §85 — Rollout risk and what to watch

### §85.1 — The three risks that matter

**R1 — The Run Store becomes a bottleneck.** Every step is at least one read and two appends. A fleet
running thousands of concurrent runs makes the store the hottest component in the control plane. *Watch:*
store operations per step (§64) — if it exceeds ~4, something is re-reading. *Mitigation:* the history is
append-only and per-run partitioned, which shards naturally by `run_id`.

**R2 — Replay divergence in production.** Non-determinism leaking into the interpreter is the failure
mode that invalidates recovery, and it can lie dormant. *Watch:* the divergence counter, whose expected
value is zero. *Mitigation:* phase 1 of the rollout (§69.2) proves bit-stable replay against synthetic
histories before any execution is built — deliberately ordered so this is proven before it can matter.

**R3 — Bounds set too low, then raised reflexively.** The predictable social failure: operators hit the
50-step ceiling, and the fix becomes "raise it to 5000" rather than "fix the plan". *Watch:* the
terminated-by-bound counter per plan, and the distribution of configured bounds over time. *Mitigation:*
bound changes are governed and audited, so the raising is visible.

### §85.2 — What "done" looks like for V1

Every one of the following must hold before general availability:

1. A killed node loses no run and costs no extra provider call (§35).
2. Every bound demonstrably terminates a run, with the correct distinct terminal reason (§58).
3. A crash-looping plan terminates within its restart intensity (§44.3).
4. A child cannot exceed its parent in budget, depth, capability or deadline (§30.2).
5. An unanswered approval terminates the run (§36.2).
6. Replay of a recorded history produces an identical step sequence with zero provider calls (§46).
7. Cross-session trifecta is refused (§60.2) — **the gate for external availability**.
8. No cross-tenant reachability in store, executor, stream or trace (§63).
9. The Agent Runtime's source names no provider (§43).
10. With the Agent Runtime disabled, the 1,546-test suite passes unchanged (AGT-30).

Item 10 is the structural guard: it makes AGT-30 a test rather than an aspiration.

---

# Part XIII — Amendments Required

## §86 — What must change, and what must not

### §86.1 — Amendments required

| Document | Change | Why |
|---|---|---|
| `06-Service-Boundaries.md` | Add **C14 Agent Runtime** with the ownership split of §21 | New component |
| `000-ADR-Index.md` | Add the AD-025 row; extend the convention line to `AD-025` | Register hygiene |
| Control-plane data model | Add the **Run Store**: runs, history, plan versions, approvals | §21.3 — the one new dependency |
| `21-GovernanceEngine.md` | Add **run admission** as a decision point. No change to policy semantics, merge algebra, snapshots or the truth table | §40.1 |
| Failure/terminal-reason register | Add the classes of §67.1 and the reasons of §67.3 | Closed sets must be registered |
| Metrics register | Add §49's metrics | Observability |

The governance amendment is deliberately minimal: **a new decision point, not new semantics.** Run
admission evaluates existing policy types over a new subject. Nothing in `21`'s algebra changes.

### §86.2 — Explicitly NOT amended

| Document | Confirmation |
|---|---|
| `AD-024` | Unchanged. This document builds above C13 and adds nothing to it |
| `28-PluginRuntime.md` §EPC | **No new extension points.** PRT-D1 preserved (§41) |
| `RequestPipeline` ordering | Untouched. Twelve stages, same order |
| `21` merge algebra, snapshots, GV-INV | Untouched |
| `25A` provider abstraction | Untouched; consumed, not modified |
| C13's session model, bounds, taint, audit | Untouched |

### §86.3 — Council decisions required

1. **Accept the Run Store dependency** (§21.3), or direct that it be implemented on Temporal (§78.5).
2. **Accept or revise the default bounds** (§58.1) — 50 / 3 / 5 / 1h.
3. **Accept the three invariant corrections** (§3), which restate the brief's wording to make it
   implementable.
4. **Confirm phase 10 as the external-availability gate** (§69.2).
5. **Confirm the numbering** — `AD-025` plus the "Agent Runtime Constitution — Document 001" designation.

---

# Part XIV — The Long View

## §87 — Ten years out

### §87.1 — What will still be true

The layering. Models will change, providers will consolidate and split, protocols will come and go. But
*a deterministic orchestrator over recorded non-deterministic steps* is a shape that predates LLMs by
decades — it is how job control, workflow engines and durable execution have always worked — and nothing
about better models makes it wrong. If anything, more capable models make the bounds *more* necessary,
because a more capable agent can do more damage per step.

The invariants will also still be true. "An agent must not bypass governance" does not become obsolete
when the model improves.

### §87.2 — What will look quaint

The bounds. Fifty steps and depth three will read like a 640K memory limit. That is fine — they are
policy values, not architecture, and the architecture is what makes raising them safe. The *mechanism*
survives; the *numbers* will not.

The plan vocabulary will likely grow. Eight constructs is a starting point chosen for provability, not
for permanence; §68.3 refuses extension *points*, not future vocabulary, and adding a construct is an
amendment to this document rather than a hole in it.

The prohibition on peer-to-peer agent conversation may not survive. If the industry develops a credible
way to bound an emergent multi-agent conversation — a real budget model, a real termination argument —
that refusal (§79.2) should be revisited. It is a judgement about the current state of the art, not a
principle.

### §87.3 — What would make this document wrong

Stated so that a future reader can recognise the conditions rather than having to infer them:

- **If provider-hosted agent loops become governable** — if a provider offers a hosted loop with
  per-step policy hooks, per-call audit and enforceable bounds — then `AD-024` §51.1's rejection and this
  document's §10 should be revisited. That is a real possibility.
- **If exactly-once tool execution becomes achievable** through a transactional protocol between the
  gateway and tool providers, §35.3's honest limitation becomes an unnecessary one.
- **If determinism stops paying for itself** — if inference becomes cheap enough that re-executing a
  40-step run costs less than maintaining a replay-safe interpreter — then §45's constraint is
  over-engineering. This is the most plausible way the document ages badly, and it depends on economics
  rather than on architecture.

### §87.4 — The one thing to preserve

If everything else in this document is eventually rewritten, preserve this:

> **The agent reaches the model only through a governed turn, and the run is bounded whether or not the
> agent cooperates.**

Every invariant in §23 is a way of saying that. An agent runtime that gives up either half is not a
weaker version of this design — it is a different thing, and the gateway would no longer be
reliability-first.

---

# Appendices

## Appendix A — Invariant index

| # | Short form | Section |
|---|---|---|
| AGT-1 | No direct provider call | §23 |
| AGT-2 | No direct pipeline invocation | §23 |
| AGT-3 | No governance bypass | §23 |
| AGT-4 | No direct tool execution | §23, §41 |
| AGT-5 | Does not own memory | §23, §42 |
| AGT-6 | Does not author its own plan or tools | §23 |
| AGT-7 | Does not own billing | §3.3, §39 |
| AGT-8 | Holds no secrets | §23 |
| AGT-9 | Tool result reaches a model only via a new pipeline execution | §3.1 |
| AGT-10 | Step retry is a new session; attempt retry is not | §3.2, §33 |
| AGT-11 | Recursion is a new run, never in-place | §29 |
| AGT-12 | Exactly one session per step | §19, §82 |
| AGT-13 | The interpreter is deterministic and I/O-free | §45 |
| AGT-14 | Record before execute; record before proceed | §20, §25 |
| AGT-15 | History is append-only and authoritative | §20, §83 |
| AGT-16 | Every run is supervised | §44 |
| AGT-17 | Five mandatory bounds | §58 |
| AGT-18 | Bound exhaustion terminates deterministically | §58, §67 |
| AGT-19 | Child budget is deducted from the parent | §30, §39 |
| AGT-20 | No silent child failure | §30, §44 |
| AGT-21 | Irreversible actions need a grant or approval | §36, §61 |
| AGT-22 | Taint accumulates across the run | §60.2 |
| AGT-23 | Parallel branches share no mutable state | §31 |
| AGT-24 | Full causal audit | §47 |
| AGT-25 | Plan version pinned at start | §46.4 |
| AGT-26 | Tenant partitioning | §63 |
| AGT-27 | Provider neutrality | §43 |
| AGT-28 | Cancellation is transitive and stops billing | §32 |
| AGT-29 | Every run terminates | §66 |
| AGT-30 | Removing the runtime changes nothing else | §69, §85.2 |

## Appendix B — Glossary

| Term | Meaning |
|---|---|
| **Run** | One execution of one plan version. C14's unit. Durable |
| **Session** | C13's unit — one task, bounded, ephemeral (`AD-024` §9) |
| **Turn** | One model call plus its tool calls. One pipeline execution |
| **Attempt** | One provider call. C2's unit |
| **Step** | One unit of plan progress = one session (§19) |
| **Plan** | An immutable versioned value describing the steps (§22) |
| **Plan graph** | What the plan permits |
| **Execution graph** | What a run actually did (§52) |
| **History** | The run's append-only event log. Authoritative (§20) |
| **Replay** | Re-running the interpreter over history with recorded results (§46) |
| **Checkpoint** | A recorded step boundary. Not a separate mechanism (§34) |
| **Supervision policy** | Declared strategy + restart intensity (§44) |
| **Restart intensity** | At most N restarts in period P (§44.3) |
| **Interrupted step** | A history ending in `StepScheduled` (§35.2) |
| **Run-level taint** | Trifecta limbs accumulated across sessions (§60.2) |
| **Reserve** | Unspendable budget fraction for termination bookkeeping (§39.4) |
| **Claim** | An executor's exclusive lease on advancing a run (§55.2) |

## Appendix C — Question-to-section map

| Q | Topic | § | Q | Topic | § |
|---|---|---|---|---|---|
| 1 | What is an Agent | §13 | 26 | Determinism | §45 |
| 2 | What is NOT an Agent | §14 | 27 | Replay | §46 |
| 3 | Why it exists | §15 | 28 | Audit | §47 |
| 4 | Why above RequestPipeline | §16 | 29 | Observability | §48 |
| 5 | Agent session (run) | §24 | 30 | Metrics | §49 |
| 6 | Agent execution | §25 | 31 | Tracing | §50 |
| 7 | Planning | §26 | 32 | Correlation IDs | §51 |
| 8 | Reasoning loop | §27 | 33 | Execution graph | §52 |
| 9 | Tool loop | §28 | 34 | State machine | §53 |
| 10 | Recursive execution | §29 | 35 | Lifecycle | §54 |
| 11 | Nested agents | §30 | 36 | Scheduling | §55 |
| 12 | Parallel execution | §31 | 37 | Concurrency | §56 |
| 13 | Cancellation | §32 | 38 | Backpressure | §57 |
| 14 | Retry | §33 | 39 | Bounded execution | §58 |
| 15 | Checkpointing | §34 | 40 | Maximum recursion | §59 |
| 16 | Crash recovery | §35 | 41 | Security | §60 |
| 17 | Human approval | §36 | 42 | Permission | §61 |
| 18 | Long-running | §37 | 43 | Isolation | §62 |
| 19 | Streaming orchestration | §38 | 44 | Multi-tenancy | §63 |
| 20 | Budget tracking | §39 | 45 | Resource accounting | §64 |
| 21 | Governance interaction | §40 | 46 | Cost accounting | §65 |
| 22 | Plugin interaction | §41 | 47 | Termination guarantees | §66 |
| 23 | Memory interaction | §42 | 48 | Failure taxonomy | §67 |
| 24 | Provider neutrality | §43 | 49 | Extension points | §68 |
| 25 | Failure handling | §44 | 50 | Migration strategy | §69 |

## Appendix D — Reversals from previous documents

The brief requires that any reversal be stated explicitly.

| # | Previous position | Now | Why |
|---|---|---|---|
| R1 | *(`AD-024` §3.4)* An agent-orchestration extension point in `28` §EPC | Confirmed — still none, one layer up | The same finding holds recursively: the layer goes above, not inside (§68.1) |
| R2 | `AD-024` §51.4 deferred durability, HITL and resumability without saying where | Placed here, in C14, with the CP/DP split of §18 | The deferral named no owner; this document supplies one |
| R3 | The brief's "every tool execution is a new pipeline execution" | Restated as AGT-9 | As written it contradicts `AD-024` §15 (§3.1) |
| R4 | The brief's "every retry is a new pipeline execution" | Restated as AGT-10 | As written it contradicts `AD-024` §22 (§3.2) |
| R5 | Earlier milestones treated cost projection as advisory | Load-bearing for step admission; unavailability fails closed | §65.4 |

**No reversal of `AD-024` is made.** Every position in that document is preserved. This one adds a layer
above it and corrects two sentences of the brief that would have contradicted it.

---

*End of ADR-025 — Agent Runtime Constitution — Document 001.*
