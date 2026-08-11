# 18 — StreamGuard (Streaming Transport-Integrity Engine · Correctness Pipeline)

**Document:** Component Implementation Architecture — StreamGuard
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Version:** 1.0 (frozen 2026-07-21)
**Role in domain:** the **transport-integrity** module of the streaming correctness pipeline (peer to SchemaLock `17`; both are `15 §G.1` critical modules). It realizes the streaming-transport half of `12 §16` and the **no-silent-delivery** invariant (`03` App. D).
**Deployment unit:** a **module inside the Data Plane** deployable (`dp-correctness-streamguard`), co-located per **AD-020/AD-006** — **not** a separate service, **owns no store**.
**Audience:** Correctness/data-plane engineers, SRE, security, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`17` and ADRs **AD-001…AD-023**. Everything here is **additive**. Introduces **no new architecture decision, no new service, no new store, no ownership/event/data-ownership change, no consistency/security/deployment-model change**. Every SG-Dx is a *component-internal implementation decision* inside the already-frozen streaming pipeline, expressed through hexagonal ports (AD-002).

> **What this is.** The complete implementation architecture of **StreamGuard** — the engine that guarantees **transport correctness** of provider streams so that SchemaLock (`17`) can reason about a clean, in-arrival-order, effectively-once, well-decoded byte/delta stream (guarantee & limits: §16.1/§18). Written at the scale assumption of **trillions of streamed requests** across OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI, OpenRouter, LiteLLM, and future providers — **all through one provider-neutral transport pipeline**. Every major design decision is presented adversarially and carries **Problem · Decision · Alternatives · Why selected · Trade-offs · Failure modes · Security impact · Performance impact · Enforcement**.
>
> **StreamGuard owns ONLY transport.** Never business correctness. Never schema correctness. Never prompt correctness. Never provider routing. Never policy. Never repair. (Division of labour: §5, §43 RB contract.)
>
> **THE STREAMGUARD INVARIANT (SG-INV):** *StreamGuard never emits a completed stream unless transport integrity has been proven. If integrity cannot be proven, it fails closed and surfaces — it never silently truncates, silently reorders, silently duplicates, silently recovers, guesses missing bytes, or fabricates content.* This is the transport-layer realization of **AD-016 (reliability first)** and **AD-018 (non-bypassable correctness)**.

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Interfaces** (§6–7) · **C. Pipeline & Lifecycle** (§8–9) · **D. Ingestion & Decoding** (§10–15) · **E. Sequencing & Integrity** (§16–22) · **F. Liveness & Control** (§23–27) · **G. Reconstruction & Delivery** (§28–33) · **H. Correctness Properties** (§34–35) · **I. Performance** (§36–39) · **J. Security** (§40) · **K. Observability** (§41–45) · **L. Data & Integration** (§46–52) · **M. Failure & Recovery** (§53–54) · **N. Testing & Enforcement** (§55–56) · **O. Operations** (§57–59) · **P. Boundary Contract** (§43 restated normatively) · **Q. Reviews**

---

## A. Charter

### §1 — Purpose
StreamGuard **guarantees transport correctness** of every provider stream: bytes are ingested, decoded, arrival-ordered, duplicate-suppressed, completeness-checked, and delivered **effectively-once, in arrival order, fully, or not marked complete at all** (guarantee & honest limits: §16.1/§18). It gives SchemaLock (`17`) a provably-clean delta stream to validate — and gives the platform its **no-silent-truncation / no-silent-reorder** guarantee (`12 §16`, `03` NFR-STRM-001).

### §2 — Scope
- **In scope (transport only):** provider stream ingestion; byte ordering; UTF-8 validation; incremental decoding; SSE / event-stream / JSON-array framing parse; chunk sequencing; duplicate detection; missing-chunk detection; out-of-order detection; heartbeat handling; timeout detection; provider disconnect; cancellation; transport reconstruction; partial-JSON **buffering** (not parsing for schema); exactly-once delivery; completion detection; transport retry policy; backpressure; bounded buffers; OOM protection; replay protection; stream-integrity metrics; transport observability.
- **Applies to:** every streaming request across all providers (AD-007) and all deployment targets (`16`).

### §3 — Responsibilities
Exactly the transport list in §2 — **nothing else**. StreamGuard turns a raw, possibly-messy provider byte stream into a **canonical, arrival-ordered, effectively-once, UTF-8-clean sequence of transport deltas** (guarantee & limits: §16.1) plus a **transport verdict** (integrity proven / failed), and hands both to the pipeline (SchemaLock consumes them, §43).

### §4 — Explicitly forbidden
StreamGuard must **NEVER**: validate schemas · repair JSON · infer values · modify model output · default missing fields · correct hallucinations · enforce policy · perform business validation · perform authorization · route providers · emit provider-native events. Each prohibition is a build-failing rule (§56). If StreamGuard finds itself needing to *understand* the content, it is doing SchemaLock's job — forbidden.

### §5 — What StreamGuard is NOT (peer boundary summary)
| StreamGuard (transport) | SchemaLock (semantics, `17`) |
|---|---|
| ✓ bytes, chunks, framing | ✓ logical object |
| ✓ UTF-8 decode integrity | ✓ JSON syntactic validity of the assembled object |
| ✓ SSE / event-stream / JSON-array parse | ✓ schema validation |
| ✓ chunk ordering / dedup / gap detection | ✓ repair allow-list (NR-1…NR-5) |
| ✓ partial-JSON **buffering** (opaque bytes) | ✓ tool-argument schema validation |
| ✓ completion **detection** (transport ended) | ✓ conformity **verdict** (authoritative) |
| ✓ effectively-once delivery of deltas (§16.1) | ✓ correctness verdict gating terminal success |
The seam is normative and non-overlapping (§43, reusing `17 §43.1` RB-1…RB-13).

---

## B. Domain & Interfaces

### §6 — Domain model (transport subdomain)
Pure domain types (no infrastructure, `11` hexagonal); immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `RawFrame` | VO | An ingested, framing-decoded unit (one SSE event / one event-stream message / one JSON-array element) with its raw opaque payload bytes |
| `TransportDelta` | VO | Canonical, ordered, UTF-8-clean delta emitted to the pipeline; carries a monotonic `seq`, opaque payload, and kind (`data`/`heartbeat`/`terminal`) |
| `FramingType` | enum | Closed transport taxonomy: `SSE`, `AWS_EVENT_STREAM`, `JSON_ARRAY_CHUNKED`, `NDJSON` (transport formats, **not** provider identities) |
| `StreamSession` | Aggregate (transient) | Per-request transport state: sequence cursor, reorder buffer, dedup window, byte budget, timers; **no shared mutable state across requests** (AD-021) |
| `TransportVerdict` | VO | `{integrity: PROVEN\|FAILED, class: TransportFailureClass?, deltasEmitted, completed: bool}` |
| `TransportFailureClass` | enum | §22 taxonomy (truncation, gap, decode, duplicate, timeout, disconnect, overflow, replay, corruption) |
| `StreamGuardPolicy` | VO | Injected budgets/limits (buffer caps, timeouts, retry caps, window sizes) from operational baseline (`16 §I.1`) |
| `IntegrityCheckpoint` | VO | Monotonic sequence + rolling hash state used for duplicate/replay/gap detection |

### §7 — Public interfaces (ports — AD-002 hexagonal)
**Inbound (called by the data-plane streaming pipeline):**
```
StreamGuardPort:
  openTransport(TransportSource, StreamGuardPolicy) -> TransportSession
TransportSession:
  next() -> TransportDelta | TerminalVerdict     // pull; effectively-once, arrival-ordered (§16.1)
  cancel(reason)                                  // client/pipeline cancellation
```
**Outbound (implemented by adapters elsewhere):**
```
TransportSourcePort   // <- Provider Router (C1): raw provider byte/chunk stream + neutral FramingType (§10)
ClockPort / TimerPort // deterministic time / timeouts (§34)
IdPort                // deterministic ids
MeteringSinkPort      // -> Metering (C5): transport counters (events, content-free)
AuditSinkPort         // -> Audit (C10): transport-integrity outcome events (WORM, content-free)
TelemetryPort         // -> Observability (C9): integrity metrics/traces (content-free)
```
- **Enforcement:** ArchUnit — StreamGuard imports **no** provider SDK (AU-06), no persistence driver (AU-07), no web framework, and **no JSON-schema/validation library** (that is SchemaLock's). **Build-Fail:** any such import; any schema/validation type referenced in StreamGuard.

---

## C. Pipeline & Lifecycle

### §8 — Internal pipeline (transport stages)
```
[1 Ingest raw stream (C1 connection)] -> [2 Framing decode (SSE/eventstream/json-array)]
  -> [3 UTF-8 incremental decode] -> [4 Sequence + dedup + order] -> [5 Gap/completeness check]
  -> [6 Liveness (heartbeat/timeout/disconnect/cancel)] -> [7 Bounded buffer + backpressure]
  -> [8 Emit arrival-ordered effectively-once TransportDelta] -> [9 Completion detection -> TransportVerdict]
  -> [10 Transport telemetry + events (async, content-free)]
```
Every stage is **non-bypassable** (AD-018): there is no path from ingestion (1) to a *completed* verdict (9) that skips ordering (4), completeness (5), or liveness (6). ArchUnit + tests enforce this (§56).

### §9 — Request lifecycle
- **Open:** pipeline calls `openTransport` with the C1 raw stream + neutral `FramingType` + policy.
- **Stream:** StreamGuard pulls/receives provider chunks, decodes framing + UTF-8 incrementally, sequences/suppresses-duplicates/orders, checks liveness, buffers within bounds, and **emits arrival-ordered effectively-once `TransportDelta`s** (§16.1) to the pipeline (which forwards to SchemaLock for logical assembly, §43).
- **Complete:** on an **explicit** provider terminal (transport end-of-stream), StreamGuard runs completeness checks and issues `TransportVerdict{integrity: PROVEN, completed: true}` — **only then** may the pipeline emit terminal success (gated jointly with SchemaLock, §43/§30). On any transport failure ⇒ `TransportVerdict{integrity: FAILED, class}` ⇒ **surface**, never a silent completion.

---

## D. Ingestion & Decoding

### §10 — Provider stream ingestion

**SG-D1 — StreamGuard is the sole ingestion + transport-parse point (single owner of transport)**
- **Problem:** at trillions of streams across 7+ providers, if any module can parse provider streaming, transport bugs multiply and the no-silent-truncation guarantee cannot be centrally enforced.
- **Decision:** **exactly one** module parses provider streaming transport — StreamGuard. It consumes a **raw byte/chunk stream** from the C1 provider adapter (which owns connection, auth, TLS, provider wire protocol handshake) via `TransportSourcePort`, tagged with a **neutral `FramingType`**. Provider *connection* is C1's; transport *integrity/parse* is StreamGuard's (ingestion seam, §43 RB-T2).
- **Alternatives:** (a) each provider adapter parses its own stream — rejected (transport correctness fragmented across N adapters, no central guarantee, N× the fuzzing surface); (b) StreamGuard opens provider connections itself — rejected (would pull provider SDK/auth into StreamGuard, violating AU-06/AD-007 and coupling transport to provider auth).
- **Why selected:** one auditable choke-point for the no-silent-* guarantees; C1 keeps provider-connection concerns; StreamGuard stays provider-SDK-free.
- **Trade-offs:** a crisp C1↔StreamGuard ingestion seam must be defined (raw bytes + neutral framing tag); mis-drawn, it leaks provider knowledge into StreamGuard.
- **Failure modes:** malformed raw stream ⇒ decode failure ⇒ surface; C1 connection drop ⇒ disconnect handling (§25).
- **Security impact:** single ingestion point = single place to enforce buffer/OOM/replay defenses (§40).
- **Performance impact:** one pass, streaming, no re-buffering across modules.
- **Enforcement:** ArchUnit — no other data-plane module references streaming/SSE/event-stream parsing (SG-A1); provider SDK forbidden in StreamGuard (AU-06). **Build-Fail:** any module except StreamGuard parsing provider streaming; StreamGuard importing a provider SDK.

**§10.1 — Non-bypass: ALL streaming traffic transits StreamGuard *(resolves M7 — additive; strengthens AD-018)*.** The non-bypass invariant is strengthened from "only StreamGuard *parses* streaming" to the stronger **"all provider streaming traffic MUST transit StreamGuard."** There is **no parser elsewhere, no pass-through path, and no alternate transport route** by which a streaming response reaches the client without StreamGuard's transport-integrity verdict. A module that merely *forwards* a provider stream without routing it through StreamGuard is as forbidden as one that parses it. This closes the gap where a "pass-through" consumer could bypass integrity. **Enforcement (SG-A16, extends SG-A1/AD-018):** ArchUnit — the only path from a provider stream (`TransportSourcePort`) to a client streaming response is *through* StreamGuard; contract/fault tests assert no streaming response is emitted without a StreamGuard `TransportVerdict`. **Build-Fail:** any streaming egress path lacking a StreamGuard verdict; any provider-stream forwarding that bypasses StreamGuard.

### §11 — Framing decode (provider-neutral transport taxonomy)

**SG-D2 — Framing is a closed transport taxonomy, not provider identity**
- **Problem:** OpenAI/Azure/OpenRouter/LiteLLM use SSE; Anthropic uses typed SSE; Gemini uses SSE/JSON-array; Bedrock uses AWS binary event-stream (with CRCs). Decoding framing looks like "provider awareness" — a neutrality risk (AD-007).
- **Decision:** StreamGuard decodes a **closed set of transport framings** (`FramingType`: `SSE`, `AWS_EVENT_STREAM`, `JSON_ARRAY_CHUNKED`, `NDJSON`) selected by a **neutral transport-descriptor** (supplied with the C1 stream, like SchemaLock's capability descriptor `17 §13.1`). StreamGuard decodes **"SSE," never "OpenAI."** The framing decoder extracts opaque payload bytes and never interprets their semantics (`12 §16.10` neutralization of *fields* remains C1/SchemaLock's; StreamGuard neutralizes only *framing*).
- **Alternatives:** (a) provider-keyed decoders — rejected (provider coupling, AD-007 violation); (b) assume one framing (SSE) — rejected (Bedrock event-stream, Gemini JSON-array would break).
- **Why selected:** neutral, extensible (a new provider = an existing or one new framing type, not a StreamGuard rewrite), central and fuzzable.
- **Trade-offs:** framing knowledge (SSE/event-stream grammar, AWS CRC framing) lives in StreamGuard — acceptable because it is *transport format*, not *provider business logic*; adversarially flagged (SGH-1).
- **Failure modes:** unknown framing ⇒ fail closed (surface `CORRUPTION`/`UNSUPPORTED_FRAMING`); malformed frame ⇒ surface.
- **Security impact:** each framing decoder is a hardened parser (bounded, no eval), fuzzed (§55); AWS event-stream CRC **verified** (corruption detection, §22).
- **Performance impact:** line/frame scanning is O(bytes), zero-copy where possible (§37).
- **Enforcement:** framing decoders keyed on `FramingType`, never provider identity; conformance matrix across providers (`15` T-015). **Build-Fail:** a framing decoder branch keyed on provider identity.

### §12 — Byte ordering
- Bytes/chunks are consumed in arrival order from the transport; StreamGuard assigns a **monotonic sequence** (§16) and preserves order end-to-end. Where a transport delivers frames that can arrive out of order (rare; some multiplexed transports), StreamGuard reorders within a **bounded reorder window** (§18) or fails closed on an unresolvable gap. It **never** re-orders content semantically — ordering is purely by transport sequence.

### §13 — UTF-8 validation

**SG-D5 — Strict incremental UTF-8 decoding; reject malformed, never substitute**
- **Problem:** provider streams split multi-byte UTF-8 across chunk boundaries; naive decoding yields mojibake or replacement chars — silently corrupting content (violates SG-INV).
- **Decision:** StreamGuard decodes UTF-8 **incrementally and strictly**, holding an **incomplete trailing multi-byte sequence** across chunk boundaries until completed. It **rejects** overlong encodings, illegal surrogates, and invalid sequences as a transport failure (`DECODE` ⇒ surface) — it **never** substitutes U+FFFD or drops bytes (substitution = silent corruption).
- **Alternatives:** (a) lenient decode with replacement char — rejected (silently alters content, the cardinal sin); (b) decode only at completion — rejected (defeats streaming, unbounded buffering).
- **Why selected:** strict, incremental, boundary-safe, fail-closed.
- **Trade-offs:** a truly malformed provider byte fails the whole stream — correct (better a surfaced error than corrupt text).
- **Failure modes:** split sequence at true end-of-stream (incomplete) ⇒ `TRUNCATED`; invalid sequence ⇒ `DECODE`.
- **Security impact:** rejects UTF-8 attacks (overlong/surrogate smuggling, §40).
- **Performance impact:** O(bytes), minimal state (≤3 held bytes).
- **Enforcement:** strict-decoder property/fuzz tests (malformed UTF-8, split boundaries, §55). **Build-Fail:** a lenient/replacement UTF-8 decoder, or byte-dropping on decode error.

### §14 — Incremental decoding
- Decoding (framing + UTF-8) is **incremental and streaming**: StreamGuard emits `TransportDelta`s as soon as a complete, ordered, decoded unit is available — it does not wait for end-of-stream (that would defeat streaming latency, `03` NFR-STRM). Incomplete units (split frame, split codepoint, split partial JSON) are **buffered opaquely** within bounds (§20/§30) until complete or failed.

### §15 — SSE / event-stream parsing
- **SSE:** line-based (`field: value`), event boundary on blank line, `data:` accumulation, comment/`:` heartbeats, `[DONE]`-style terminals surfaced as **transport terminals** (not interpreted semantically). Robust to CRLF/LF, chunk-split lines, and interleaved comments.
- **AWS event-stream:** binary prelude + headers + payload + **CRC validation** (prelude CRC + message CRC); a CRC mismatch ⇒ `CORRUPTION` ⇒ surface.
- **JSON-array / NDJSON:** element/line boundary detection over a streamed array or newline-delimited stream, without parsing element *contents* (opaque payload to SchemaLock).
- All parsers are **hardened, bounded, and fuzzed** (§40/§55); none interpret payload semantics.

---

## E. Sequencing & Integrity

### §16 — Chunk sequencing

**SG-D3 — Synthesize a monotonic transport sequence; delivery is *effectively-once* over StreamGuard's *emitted* deltas**
- **Problem:** at trillions of streams the delivery guarantee must be **precise and honest**. Providers generally do **not** supply reliable sequence/idempotency identifiers; the provider→StreamGuard link is **at-least-once / best-effort**. An unqualified "exactly-once" claim would be false.
- **Decision:** StreamGuard **synthesizes a monotonic per-session `seq`** as it accepts each valid, decoded unit **in transport-arrival order**, and provides **effectively-once, in-arrival-order delivery of its *emitted* `TransportDelta`s** to the pipeline/SchemaLock/client — the **same at-least-once-input + idempotent-suppression model as `07`** (AD-005). StreamGuard does **not** claim the provider emits exactly once, nor that it can reconstruct a provider's *intended* order beyond what the provider's transport actually delivers. See **§16.1** for the precise guarantee and its limits.
- **Alternatives:** (a) claim end-to-end exactly-once including provider — rejected (unachievable, dishonest); (b) trust absent provider sequence numbers — rejected (unreliable).
- **Why selected:** honest, precise, achievable; matches the frozen effectively-once posture (`07`, AD-005).
- **Trade-offs:** requires StreamGuard-side suppression + bounded window; duplicate-suppression has inherent limits without provider identifiers (§16.1).
- **Failure modes:** per-session 64-bit `seq` (overflow is a non-threat within a single stream; a guard fails closed if ever approached); window overflow ⇒ fail closed.
- **Security impact:** suppression + `IntegrityCheckpoint` mitigate provider replay/duplication within the window (§40).
- **Performance impact:** sequencing/window lookup is **O(1) per delta**; **duplicate-suppression computes a payload hash, which is O(payload bytes)** — an honest, bounded per-delta cost (§38/M-1), not O(1).
- **Enforcement:** effectively-once property + replay tests (`15` T-035/T-018, §55). **Build-Fail:** an unbounded sequence/window structure; an emission path without duplicate-suppression.

### §16.1 — The effectively-once guarantee and its precise limits *(resolves High H1 — additive; no overclaim)*
- **Provider input is at-least-once / best-effort:** the provider may (rarely) resend a frame; StreamGuard assumes nothing stronger.
- **Emitted deltas are effectively-once:** StreamGuard's *output* to SchemaLock/client is delivered **at most once per accepted unit, in arrival order** — duplicates it can detect are suppressed; nothing it emits is re-emitted.
- **Duplicate-suppression boundary:** a duplicate is suppressed **only if it falls within the bounded suppression window and its `IntegrityCheckpoint` matches** (synthesized `seq` position + payload hash). Beyond the window, an apparent duplicate that cannot be safely resolved **fails closed** (`DUPLICATE`/`REPLAY`) — never silently dropped, never silently delivered twice.
- **Explicit limitation (no invented guarantee):** because providers expose **no reliable sequence/idempotency identifier**, StreamGuard **cannot distinguish a transport-level duplicate from legitimately-repeated payload content by hash alone with certainty**. It therefore biases to **safety, not silent action**: within-window exact `IntegrityCheckpoint` matches are treated as duplicates and suppressed; ambiguous cases **fail closed and surface** rather than guess. StreamGuard **never** silently drops content it is unsure about and **never** silently delivers a duplicate. This is the honest ceiling of what transport-layer suppression can guarantee without provider metadata.

### §17 — Duplicate detection
- StreamGuard suppresses duplicate transport units via a **bounded suppression window** keyed by an `IntegrityCheckpoint` (synthesized `seq` position + a **rolling hash of the payload bytes** — **hash only, never stored/exported content**, §40). A confirmed within-window duplicate is **suppressed once** (idempotent); an ambiguous or beyond-window case ⇒ **fail closed** (`DUPLICATE`/`REPLAY`). Window size is an operational-baseline entry (`16 §I.1`). Suppression operates within the honest limits stated in §16.1.

### §18 — Ordering (arrival order, provider order, emitted order — differentiated)

**Resolves High H2 — additive; removes any claim of provider-side gap detection without provider metadata.**
- **Transport arrival order:** the order in which decoded units arrive over the connection. This is what StreamGuard observes.
- **Emitted order:** StreamGuard assigns the synthesized monotonic `seq` **in transport-arrival order** and **emits strictly in that order** — it never reorders emitted deltas.
- **Provider (intended) order:** the order the provider *intended*. StreamGuard **cannot know this independently** of what the transport delivers, because providers expose **no ordering identifier**. Therefore StreamGuard **does not and cannot detect provider-side reordering or provider-side gaps from metadata it does not have.**
- **What StreamGuard actually guarantees:** (a) emitted order == arrival order (no StreamGuard-introduced reordering); (b) **completeness is proven only by the explicit transport terminal** (§21) plus byte-level continuity — **not** by inferring "missing" sequence numbers it synthesized itself. A rare genuinely-multiplexed transport that can deliver out of arrival order is handled by a **small bounded reorder buffer**; if it cannot be resolved, StreamGuard fails closed (`GAP`) rather than emit out of order.
- **Explicit limit (no overclaim):** "missing-chunk / gap detection" means **detecting a stream that ended without an explicit terminal, or a reorder-buffer that cannot be resolved** — it does **not** mean detecting a provider dropping content mid-stream with no terminal and no metadata (undetectable at the transport layer without provider identifiers; such a case is caught only if it manifests as a missing terminal → `TRUNCATED`, or downstream by SchemaLock's completion validation). StreamGuard states this limit honestly rather than claim an impossible capability.

### §19 — Missing-chunk / incompleteness detection (within the §18 limits)
- StreamGuard detects **incompleteness** it can actually observe: (a) an unresolved **reorder-buffer gap** (§18), and (b) a stream that **ends without an explicit transport terminal** (§21) — both ⇒ `GAP`/`TRUNCATED` ⇒ **fail closed ⇒ surface**. StreamGuard **never** infers completion from silence (§21). Per §18, it makes **no claim** to detect provider-side content drops carrying no terminal and no metadata — those surface only as a missing terminal (`TRUNCATED`) or are caught downstream by SchemaLock's completion validation. The honesty of this limit is deliberate (no invented capability).

### §20 — Partial-JSON buffering & minimal structural scanning
- StreamGuard buffers **incomplete payload bytes** (e.g. a JSON fragment split across frames) so it can emit only complete transport units and hand SchemaLock **structurally well-formed** deltas (frozen `17 §43.1` RB-4). Buffering is **bounded** (transport-level size/nesting/field-count caps) and fails closed on overflow (`OVERFLOW`).

**§20.1 — Minimum structural scanning only (normative, resolves Critical C2).** To delimit transport framing and satisfy RB-4 (incremental JSON structural integrity), StreamGuard performs **only the minimum structural scanning required** — for `JSON_ARRAY_CHUNKED`/`NDJSON` this means tracking **brace/bracket depth and string/escape state** to find element boundaries and confirm each emitted delta is a **syntactically well-formed incremental JSON fragment**. This is a **bounded, transport-level structural scan**, nothing more. StreamGuard **MUST NOT** perform:
  - ❌ semantic interpretation of any value,
  - ❌ schema validation,
  - ❌ business validation,
  - ❌ logical-object validation/assembly.
  All four are exclusively SchemaLock's (§43.1-SG). "Structural" answers *"is the byte stream syntactically progressing as well-formed JSON?"*; it never answers *"does the value mean/conform to anything?"* — that is the SchemaLock boundary, and crossing it is a Build-Fail (SG-A4/SG-A2). This reconciles the earlier "opaque" wording: framing decode is *structurally aware* (it must be, to delimit) but *semantically blind*.

### §21 — Completion detection

**SG-D7 — Completion only on an explicit transport terminal; never inferred**
- **Problem:** silently treating "stream stopped arriving" as "complete" is exactly the silent-truncation failure (`12 §16`, BRULE-1-adjacent).
- **Decision:** StreamGuard marks a stream **complete only** on an **explicit provider transport terminal** (SSE `[DONE]`-class terminal, event-stream end message, closed JSON array, NDJSON EOF-with-terminal) **after** all sequence/gap/decode checks pass. A connection that closes **without** an explicit terminal is `TRUNCATED` ⇒ surface. The completion verdict is a **transport verdict**; terminal *success* to the client is gated **jointly** with SchemaLock's completion verdict (§43 RB-8, `17 §8`).
- **Alternatives:** (a) infer completion on idle/close — rejected (silent truncation); (b) timeout-as-completion — rejected (same).
- **Why selected:** the single most important transport guarantee (no silent truncation).
- **Trade-offs:** streams that legitimately lack a terminal (buggy provider) surface as errors — correct and observable (drives provider drift tickets, `15 §C.1`).
- **Failure modes:** premature close ⇒ `TRUNCATED`; missing terminal ⇒ `TRUNCATED`.
- **Security impact:** prevents truncation-based downstream confusion.
- **Performance impact:** negligible.
- **Enforcement:** fault-injection: connection-close-without-terminal ⇒ surfaced (`15` T-012/T-018). **Build-Fail:** any completion marked without an explicit transport terminal + passed integrity checks.

### §22 — Transport failure classification
| Class | Meaning | Recoverable? |
|---|---|---|
| `TRUNCATED` | closed/ended without explicit terminal | maybe (transport retry §26, pre-emission only) |
| `GAP` | missing sequence / unresolved reorder | maybe |
| `DECODE` | invalid UTF-8 / framing | no (fail closed) |
| `CORRUPTION` | CRC/framing integrity failure (e.g. event-stream CRC) | maybe (retry) |
| `DUPLICATE` / `REPLAY` | duplicate beyond safe window | no (fail closed) |
| `TIMEOUT` | inactivity/total-duration budget exceeded | maybe (retry) |
| `DISCONNECT` | provider/connection dropped | maybe |
| `OVERFLOW` | bounded buffer/window exceeded | no (fail closed) |
| `CANCELLED` | client/pipeline cancellation | terminal |
| `UNSUPPORTED_FRAMING` | unknown transport framing | no |
- Neutral classes map to the `12 §12` error taxonomy; default on any unknown = **fail closed** (surface). Never a provider-native code (`12 §16.10`).

---

## F. Liveness & Control

### §23 — Heartbeat handling
- StreamGuard recognizes transport **heartbeats/keepalives** (SSE comments, event-stream keepalive frames) as **liveness signals**, not content — they reset the inactivity timer (§24) and are **never** emitted as `data` deltas. Absence of expected heartbeats within budget contributes to timeout detection.

### §24 — Timeout detection

**SG-D9 — Dual timeout budget (inactivity + total duration); slow-loris defense**
- **Problem:** at scale, slow or stalled streams exhaust connections/memory (slow-loris, dribble attacks) and hurt tail latency.
- **Decision:** each session enforces **two budgets**: an **inactivity timeout** (max gap between bytes/heartbeats) and a **total-duration timeout** (max wall-clock for the whole stream) — both via `TimerPort`/`ClockPort` (deterministic, §34), values from the operational baseline (`16 §I.1`). Breach ⇒ `TIMEOUT` ⇒ cancel provider (§26) + fail closed.
- **Alternatives:** (a) single total timeout only — rejected (a stream dribbling one byte/sec under total budget still slow-loris's); (b) no timeout — rejected (resource exhaustion).
- **Why selected:** defends both stall and dribble; bounded resource holding.
- **Trade-offs:** legitimately slow providers may trip inactivity budget — tuned per baseline; surfaced, not silent.
- **Failure modes:** clock source issues ⇒ injected clock, tested.
- **Security impact:** primary slow-loris / connection-exhaustion defense (§40).
- **Performance impact:** protects tail latency + connection pool.
- **Enforcement:** timeout fault tests (stall/dribble). **Build-Fail:** a streaming path without both inactivity and total-duration budgets.

### §25 — Provider disconnect
- A dropped connection is `DISCONNECT`. If **no** `TransportDelta` has yet been emitted downstream (§28 point-of-no-return), a **bounded transport retry** may restart the stream (§26); if deltas were already emitted, StreamGuard **cannot** un-send — it fails closed (`TRUNCATED`) and surfaces (no silent recovery). Never fabricates the remainder.

### §26 — Transport retry policy

**SG-D8 — Transport retry is restart-before-first-emission only; never mid-stream fabrication**
- **Problem:** most providers cannot **resume** a stream mid-way; retry naïvely re-streams from the start, but by then StreamGuard may have already delivered bytes downstream — you cannot un-send.
- **Decision:** StreamGuard defines a **point of no return = the first `TransportDelta` emitted downstream**. **Before** it, a recoverable transport failure (`TRUNCATED`/`TIMEOUT`/`DISCONNECT`/`CORRUPTION`) may trigger a **bounded transport retry** (restart the provider stream via C1, discarding any buffered partial). **After** it, retry is **forbidden** — StreamGuard fails closed and surfaces, because re-streaming would duplicate/reorder client-visible output (violating SG-INV). Transport retry is **distinct** from SchemaLock's guided retry (`17 §24`) and the Retry Engine's request retry — it is *connection-level*, bounded, and never fabricates missing content.
- **Alternatives:** (a) resume from offset — rejected (unsupported by most providers; provider-specific); (b) merge partial + retried streams — rejected (duplication/ordering hazard); (c) retry anytime — rejected (double-delivery).
- **Why selected:** the only transport retry that cannot violate exactly-once/ordering.
- **Trade-offs:** post-first-byte failures are non-retryable at transport level (may retry at the request level, owned by C2, §47.1/§49.1) — correct trade for integrity.
- **Explicit limitation (resolves M2):** transport retry is **only applicable before the first downstream emission.** Because streaming emits the first delta within milliseconds (low first-token latency is streaming's purpose), transport retry is in practice available only for **early failures** (e.g. connect/first-frame failures before any delta reaches the consumer). This is stated honestly: transport retry is a **narrow, early-window** mechanism, not a general mid-stream recovery — mid-stream recovery is impossible without violating effectively-once/ordering.
- **Failure modes:** retry budget exhausted ⇒ surface.
- **Security impact:** bounded retries prevent amplification.
- **Performance impact:** pre-emission retry hidden from client; bounded.
- **Enforcement:** disconnect/truncation fault tests assert no post-emission retry, no duplicate delivery. **Build-Fail:** a transport retry after first downstream emission; a retry that merges partial streams.

### §27 — Cancellation

**SG-D12 — Cooperative cancellation propagates to the provider (stop token spend)**
- **Problem:** a client cancel or pipeline abort must stop provider generation, or tokens/cost accrue and connections leak.
- **Decision:** `cancel()` **cooperatively cancels** the in-flight provider stream (via `TransportSourcePort`, C1 closes the provider connection), releases buffers, emits `CANCELLED`, and stops emitting deltas — deterministically and promptly. Cancellation is **idempotent** and **race-safe** with completion (a cancel after terminal is a no-op returning the completed verdict).
- **Alternatives:** let the provider stream finish — rejected (wasted cost/connections at scale); hard-kill without provider notify — rejected (leaks provider resources).
- **Why selected:** bounds cost + resources; clean lifecycle.
- **Trade-offs:** requires C1 cooperative cancel support (contracted at the ingestion seam).
- **Failure modes:** provider ignores cancel ⇒ StreamGuard still stops downstream + enforces timeout (§24).
- **Security impact:** prevents cancel-ignoring cost amplification.
- **Performance impact:** frees resources fast.
- **Enforcement:** cancellation tests (mid-stream cancel ⇒ provider closed, buffers freed). **Build-Fail:** a cancel path that does not propagate to the provider or does not free buffers.

---

## G. Reconstruction & Delivery

### §28 — Stream reconstruction (transport assembly, structurally-aware but semantically-blind)
- StreamGuard reconstructs the **ordered, decoded, duplicate-suppressed transport stream** — the sequence of structurally-well-formed `TransportDelta`s — from possibly-messy provider input. This is **byte/frame assembly + minimal structural scanning** (§20.1, RB-4), **not** logical-object assembly or schema validation (§43.1-SG, RB-5/RB-6). SchemaLock builds the logical object from it.

### §29 — Effectively-once delivery
- Emitted `TransportDelta`s are delivered **effectively-once and in arrival order** to the downstream consumer (SchemaLock/client), per the guarantee and limits in §16.1 (synthesized `seq` + bounded duplicate-suppression). The consumer may rely on this without re-suppressing transport duplicates (`17 §43.1` RB-12). A delta is emitted **at most once**; a would-be duplicate is suppressed within the window or **fails closed** (never silently delivered twice, never silently dropped when ambiguous).

### §30 — Completion detection & joint gating
- On explicit terminal + passed integrity (§21), StreamGuard issues `TransportVerdict{PROVEN, completed}`. **Terminal success to the client requires BOTH** StreamGuard's transport-PROVEN verdict **and** SchemaLock's completion verdict (`17 §8/§43.1` RB-8). Either FAILED ⇒ surface. StreamGuard never emits a completed stream on transport grounds alone if downstream semantics gate it — and never marks complete on transport failure.

### §31 — Backpressure

**SG-D6 — Bounded buffers + backpressure; fail closed on overflow, never OOM**
- **Problem:** at trillions of streams, a fast provider + slow consumer (or an attacker) explodes memory.
- **Decision:** every buffer (framing, UTF-8 carry, reorder, dedup window, partial-transport) is **hard-bounded** (operational baseline, `16 §I.1`). StreamGuard applies **backpressure**: pull-based consumption slows provider intake where the transport allows (e.g. TCP flow control / not reading); where a provider cannot be slowed, buffering is bounded and **overflow fails closed** (`OVERFLOW` ⇒ surface) — **never** unbounded growth, never OOM.
- **Scope of backpressure (resolves M6, explicit):** **backpressure protects memory, not cost.** Applying TCP-level backpressure (or not reading) bounds StreamGuard's *memory*; it does **not** stop the provider generating (and billing for) tokens. **Only cancellation (§27) stops provider cost.** These are distinct controls with distinct purposes — backpressure = memory safety, cancellation = cost/resource termination — and the document does not conflate them.
- **Alternatives:** unbounded buffering — rejected (OOM/DoS); dropping bytes to keep up — rejected (silent corruption).
- **Why selected:** bounded-memory guarantee under adversarial load; fail-closed over corrupt.
- **Trade-offs:** a pathological fast-provider/slow-consumer stream surfaces an error instead of buffering forever — correct.
- **Failure modes:** overflow ⇒ surface + cancel provider (§27).
- **Security impact:** core buffer-explosion / memory-exhaustion defense (§40).
- **Performance impact:** predictable memory; protects the whole node (multi-tenant fairness, AD-021).
- **Enforcement:** all buffers bounded (ArchUnit/config); overflow fault tests. **Build-Fail:** an unbounded buffer in StreamGuard.

### §32 — Bounded buffers & OOM protection
- Consolidated limits (all baseline-configurable): max in-flight buffered bytes per session; max reorder-window size; max dedup-window size; max UTF-8 carry (≤3 bytes); max framing-nesting/line-length; max total session bytes; max concurrent sessions per instance (fair-share). Any breach ⇒ fail closed. Sized within container limits (`16` D-044, JVM heap ≤ limit, ZGC AD-023).

### §33 — Replay protection
- Provider (or attacker) **replay** — re-sending a prior stream/segment — is detected by the dedup window + `IntegrityCheckpoint` (sequence + rolling hash). Cross-session replay is impossible (per-session state, no shared mutable state, AD-021). A replay that cannot be safely resolved ⇒ fail closed (`REPLAY`).

---

## H. Correctness Properties

### §34 — Deterministic behavior
- The transport-integrity core is a **pure, deterministic function** of (input byte sequence, policy, injected clock): given the same bytes and timing, the same `TransportDelta` sequence and verdict result every time. **No wall-clock/random** in the core (`11` R-063); time via `ClockPort`/`TimerPort`. Enables property/mutation/replay testing (§55). **Build-Fail:** wall-clock/random in the transport core.

### §35 — Idempotency & replay semantics
- Transport processing is idempotent over its input (§34). Within a session, dedup makes duplicate provider chunks a no-op (§17). Across a re-issued **request**, the request idempotency key governs (owned by the pipeline / `17 §29.1`) — StreamGuard opens a fresh transport session per request and never merges sessions. A duplicate delta is dropped; a duplicate stream is handled independently or by the request key — never merged into a "recovered" result.

---

## I. Performance

### §36 — Buffer strategy
- **Pull-based, streaming, single-pass.** Payload bytes are held in bounded ring/segment buffers; framing/decoding operate over views into these buffers. No full-stream materialization. Buffers are drawn from a pool and recycled per session to minimize GC pressure under ZGC (AD-023) — **under the strict pooled-object lifecycle of §36.1** (no cross-tenant residue).

### §36.1 — Pooled-object lifecycle (mandatory) *(resolves High H5 — additive; reinforces AD-021, no architecture change)*
Every pooled object (session, decoder, buffer) follows this **mandatory lifecycle**; the reset step is not optional:
```
Acquire → Initialize → Zero/Reset → Validate-Clean-State → Use → Clear → Return-to-Pool
```
- **Zero/Reset** clears all bytes and resets all decoder/sequence/window state to a known-empty baseline **before** the object is used by a new session.
- **Validate-Clean-State** asserts (in debug/test builds and via a cheap invariant in production) that the object carries **no residual data** before use.
- **Clear** zeroes buffers **on return** so nothing lingers in the pool.
- **Normative rule (AD-021):** **no tenant data may survive pool return.** **Cross-tenant reuse of a pooled object without a completed Zero/Reset + Validate-Clean-State is forbidden.** A pooled buffer is per-session state *for the duration of a session only*; between sessions it holds nothing. This closes any cross-tenant-residue path and **reinforces the tenant-isolation invariant (AD-021)** — pooling is a performance technique that **must not** become an isolation hole.
- **Reconciliation with §39 "no shared mutable state":** a pooled object is **never shared concurrently** across sessions; it is **exclusively owned by one session at a time** and reset between owners. "No shared mutable state" means no *concurrent* sharing — serial reuse-after-reset is safe and does not violate it.
- **Enforcement (SG-A15):** ArchUnit/lifecycle contract test — a pool checkout path without Zero/Reset + Validate-Clean-State fails the build; isolation tests (`15` T-044) assert zero cross-session/tenant residue under pooled reuse. **Build-Fail:** pooled reuse without reset; any residual-data path across sessions.

### §37 — Zero-copy & allocation minimization (with strict lifetime rules)
- Framing/UTF-8/sequencing operate on **byte views/slices** without copying payloads where possible. **Lifetime rule (resolves M3, normative):** a `TransportDelta`'s slice **must never outlive the ownership of its backing buffer.** A buffer is **not recycled/returned to the pool until every delta referencing it has been fully consumed downstream** (reference-counted or copied-on-emit for async hand-off). An **asynchronous consumer must never be able to observe recycled memory** — if a delta may outlive the buffer's session window (e.g. reorder, async SchemaLock hand-off), StreamGuard **copies** it before the buffer is eligible for return. Object allocation on the hot path is minimized (pooled sessions/decoders per §36.1) for low tail latency + GC pauses (ZGC sub-ms, AD-023). **Build-Fail (extends SG-A7):** a buffer returned to the pool while a live delta still references it; a slice handed to an async consumer without copy-or-refcount protection.

### §38 — Latency & tail latency
- StreamGuard adds **near-zero per-delta latency** (framing scan + UTF-8 + O(1) sequence/dedup), well within the added-latency budget (`06 §11`) and streaming first-token target (`03` NFR-PERF-002). Tail latency is protected by bounded buffers (§31), dual timeouts (§24), and pooled allocation (§37). JMH microbenchmarks baseline the decode/sequence hot path (`15` T-026, `16 §J.2`).

### §39 — Throughput & concurrency
- Runs on **Virtual Threads** (AD-023): one lightweight carrier-friendly session per stream, scaling to massive concurrency. Per-session state is isolated (no shared mutable state, AD-021); shared structures (session pool) are lock-light and **non-pinning** (`11` R-049 — no blocking in `synchronized` on the hot path). Backpressure (§31) keeps per-node memory bounded regardless of concurrency. **Build-Fail:** shared mutable session state; blocking-in-`synchronized` on the transport hot path.

---

## J. Security

### §40 — Security considerations

**SG-D10 — Fail-closed, bounded, hardened transport parsing (defense-in-depth)**
- **Problem:** the provider stream is **untrusted input** at trillion-scale; it is a rich attack surface.
- **Decision:** every input vector is bounded and fail-closed:

| Threat | Defense |
|---|---|
| **Stream bombing / chunk flooding** | bounded total-bytes + inactivity/total timeouts (§24) + backpressure (§31) ⇒ `OVERFLOW`/`TIMEOUT` |
| **UTF-8 attacks** (overlong, surrogate smuggling) | strict decoder, reject-not-substitute (§13) |
| **Gigantic JSON / deep nesting** | transport-level size/nesting/line-length caps (§32); *schema* depth is SchemaLock's (`17 §33`) — StreamGuard caps *transport* framing |
| **Partial-JSON attacks** (never-completing fragment) | bounded partial buffer + timeout ⇒ `OVERFLOW`/`TIMEOUT` (§20/§24) |
| **Provider replay / duplication** | dedup window + `IntegrityCheckpoint` (§17/§33) |
| **Provider corruption** | UTF-8 strictness + event-stream CRC verification (§15) ⇒ `CORRUPTION` |
| **Slow streams / slow-loris** | dual timeout budget (§24) |
| **Memory exhaustion / buffer explosion** | hard-bounded buffers, fail-closed overflow (§31/§32) |
| **Integer overflow** | 64-bit monotonic counters with overflow guards; all sizes checked before allocation (no unchecked arithmetic on lengths) |
| **Cross-tenant leakage** | per-session isolation, no shared mutable state (AD-021) |
- **No content in telemetry/logs/events** (`13 §20`, `14 §7.1`) — StreamGuard handles opaque bytes and **must not** log or export payloads, prompts, or completions (§41/§45).
- **Alternatives:** trust provider streams — rejected (untrusted input); soft limits — rejected (DoS).
- **Why selected:** deterministic, bounded, fail-closed defense at the single ingestion choke-point (§10).
- **Trade-offs:** strictness surfaces more provider-fault errors — correct + observable.
- **Security/Performance impact:** primary DoS/corruption/leakage defense; caps also bound latency.
- **Enforcement:** fuzz + fault suites (§55); content-leak scanner on telemetry (`14 §7.1`, `15` T-052). **Build-Fail:** any unbounded input path; any content in telemetry/logs/events; unchecked length arithmetic.

---

## K. Observability

### §41 — Observability posture
- Full instrumentation per `14`, **content-free**: spans per transport stage (§8), metrics (§42), structured logs (§44), events (§45). Two-tier (`14 §5.1`): operational counters → Plane A; high-cardinality transport diagnostics → Plane B. Telemetry privacy (`14 §7.1`): **never** bytes/payload/prompt/completion.

### §42 — Metrics (SLI-bearing)
- `streamguard_integrity_failure_ratio{class}` — transport-failure ratio by class — a **correctness/transport canary signal** (`16 §E.2`).
- `streamguard_truncation_total`, `streamguard_gap_total`, `streamguard_duplicate_suppressed_total`, `streamguard_reorder_total`, `streamguard_decode_error_total`, `streamguard_corruption_total`, `streamguard_timeout_total{inactivity|total}`, `streamguard_disconnect_total`, `streamguard_overflow_total`, `streamguard_replay_blocked_total`, `streamguard_cancel_total`, `streamguard_transport_retry_total`.
- `streamguard_buffer_bytes` (gauge, saturation), `streamguard_active_sessions`, `streamguard_delta_latency`, `streamguard_stream_duration`.
- **Silent-truncation indicator:** any completion emitted without a passed transport verdict is impossible by construction; a nonzero *escape-equivalent* counter would be a Sev1 (§57). All labels low-cardinality, **no tenant content**.
- **Provider attribution (resolves M4):** a `provider` (and `framing`) dimension **MAY** appear on these metrics **as internal operational telemetry only** (needed for the §57 per-provider drift runbook), governed by `14` privacy rules. Provider identity is **not** tenant content, but it is **never exposed on any external/caller-facing surface** (API responses, error bodies, caller-visible events, `12 §16.10`) — this preserves **AD-007** provider neutrality. External neutrality and internal operability are thus both satisfied.

### §43 — Tracing
- One span per stage with neutral attributes (framing type, delta count, integrity class, completed) — **never** bytes/payload. Correlation/causation ids propagated (`07 §6`, `14`); exemplars link latency to traces.

### §44 — Logging
- Structured, content-free: ids, framing type, integrity class, counts, buffer/timeout state — **never** payload/prompt/completion (`13 §20`, `14 §7.1`).

### §45 — Events emitted
- Off the hot path (async, AD-005), **Avro** via Apicurio (`07 §9`), standard envelope (`07 §6`), **content-free**, within the **already-owned correctness-domain event space** (no new topic owner; consistent with `06 §23`, same pattern as `17 §38`). Transport-integrity outcomes (proven / truncated / gap / corruption / timeout / cancelled) consumed by Metering (C5), Audit (C10), Observability (C9). **No provider-native events** ever (§4). Event evolution follows `16` D-033. **Build-Fail:** a provider-native event exposed; content in an event.

## §43 — Boundary Contract (normative) — reuses & strengthens `17 §43.1` RB-1…RB-13

> This is the same seam frozen in `17 §43.1`, restated from StreamGuard's side and **strengthened, never overlapped**. Ownership below is identical to RB-1…RB-13; StreamGuard adds transport-only responsibilities **RB-T1…RB-T7** that live entirely on StreamGuard's side (no SchemaLock responsibility is touched, added, or moved).

| RB (from `17 §43.1`) | Owner | StreamGuard restatement |
|---|---|---|
| RB-2 byte reconstruction | **StreamGuard** | §12/§28 |
| RB-3 UTF-8 validation | **StreamGuard** | §13 |
| RB-4 **incremental JSON structural integrity** (frozen `17`) | **StreamGuard** | §15/§20 — reaffirmed exactly as frozen: StreamGuard ensures each emitted delta is a **syntactically well-formed incremental JSON fragment** and never emits partial JSON as complete, via **minimum structural scanning only** (§20.1). It performs **no** semantic/schema/business/logical validation. |
| RB-5 logical object assembly | **SchemaLock** | StreamGuard does **not** assemble the logical object |
| RB-6 schema validation | **SchemaLock** | StreamGuard performs **none** |
| RB-7 **tool-call argument reconstruction** (frozen `17`) | **StreamGuard reconstructs to JSON well-formedness; SchemaLock validates schema** | StreamGuard reconstructs the tool-call argument **JSON stream to structural well-formedness** (frozen `17` RB-7); SchemaLock validates the arguments against the tool's **parameter schema** (`17 §17`). |
| RB-8 completion | **split, joint-gated** | §21/§30 (both verdicts required) |
| RB-9 failure ownership | by class | StreamGuard owns transport classes (§22); SchemaLock owns schema classes |
| RB-10 retry | SchemaLock decides / Retry Engine executes | StreamGuard owns only **transport** retry (§26), distinct and pre-emission-only |
| RB-11 events | by domain | StreamGuard emits transport events (§45); no overlap |
| RB-12 exactly-once | **StreamGuard** | §16/§29 (scoped to emitted deltas) |
| RB-13 ordering | **StreamGuard** | §12/§18 |

**Strengthening additions (transport-only, StreamGuard side, additive):**
| # | Responsibility | Owner |
|---|---|---|
| RB-T1 | Provider stream **ingestion parse** (raw bytes → frames) | StreamGuard (§10); provider **connection/auth** stays C1 |
| RB-T2 | **Framing decode** (SSE / event-stream / JSON-array / NDJSON) | StreamGuard (§11) — neutral framing taxonomy |
| RB-T3 | **Heartbeat / liveness** recognition | StreamGuard (§23) |
| RB-T4 | **Timeout** (inactivity + total) | StreamGuard (§24) |
| RB-T5 | **Disconnect / cancellation** propagation to provider | StreamGuard (§25/§27) |
| RB-T6 | **Backpressure / bounded buffers / OOM protection** | StreamGuard (§31/§32) |
| RB-T7 | **Replay protection / dedup window** | StreamGuard (§17/§33) |
- **Non-duplication rule (normative, extends `17 §43.1`):** *StreamGuard MUST NOT validate schemas, assemble logical objects, validate tool-argument schemas, repair/infer/default/correct content, enforce policy, authorize, route providers, or emit provider-native events. SchemaLock MUST NOT parse provider framing, decode UTF-8, sequence/dedup/reorder transport, or manage transport buffers/timeouts/cancellation.* Each capability has exactly one owner.
- **Enforcement (SG-A2, extends `17` SL-A13):** ArchUnit + contract tests fail the build on any boundary violation in either direction; terminal success requires **both** verdicts (§30).

### §43.1-SG — Reaffirmation of the frozen `17 §43.1` boundary — single owner, no drift *(resolves Critical C1 — additive; the frozen boundary is authoritative, restated not redefined)*

The frozen `17 §43.1` boundary is **authoritative and unchanged**. An earlier draft of `18` narrowed RB-4/RB-7 to "framing only, no JSON"; that drift is **retracted**. `18` **reaffirms** the frozen split verbatim. There is **exactly one owner** for each of the five contested responsibilities — no ambiguity, no overlap, no ownership drift, no new architecture:

| Responsibility | **Single owner (frozen `17 §43.1`)** | What that owner does — and does not do |
|---|---|---|
| **Byte reconstruction** | **StreamGuard** (RB-2) | Reassembles ordered byte/char deltas from provider chunks; no interpretation of meaning. |
| **Incremental JSON structural integrity** | **StreamGuard** (RB-4) | Ensures each emitted delta is a **syntactically well-formed incremental JSON fragment** (structural progression only); never emits partial JSON as complete. **Not** schema validation, **not** logical-object validation, **not** semantics. |
| **Tool-call argument reconstruction** | **StreamGuard** (RB-7, transport half) | Reconstructs the argument **JSON stream to structural well-formedness**. Does **not** validate the arguments against any schema. |
| **Logical object assembly** | **SchemaLock** (RB-5) | Builds the typed logical object from StreamGuard's well-formed deltas. StreamGuard does **none** of this. |
| **Schema validation** | **SchemaLock** (RB-6) | Validates the assembled object / tool arguments against the compiled schema. StreamGuard does **none** of this. |

- **The distinction (normative):** StreamGuard owns **structural** JSON integrity (is the byte stream *syntactically* progressing as well-formed JSON?); SchemaLock owns **semantic/schema** validity (does the assembled object *mean* what the schema requires?). These are disjoint; together they leave **no gap** — every layer of JSON correctness has exactly one owner, exactly as frozen in `17`.
- **Enforcement:** SG-A2 (both directions) + contract tests (`15` T-008/T-012) fail the build on any drift from this table.

---

## L. Data & Integration

### §46 — Data ownership
- StreamGuard is **stateless** (AD-020) and owns **no persistent store** (no ownership/data-model change). It holds only **ephemeral in-memory** per-session buffers (bounded, §32). Durable transport-integrity records are owned by Audit (C10) / Metering (C5) via events (§45), consistent with store-per-service (`08`, `06 §24`). **Build-Fail:** StreamGuard acquiring a private persistent store.
- **Residency confinement (resolves M5, explicit):** the payload bytes StreamGuard buffers **are model content and may be regulated/PHI** (`08` classification). They **always remain residency-confined**: buffered **only in the in-memory of the in-region data-plane instance** serving the tenant (AD-014, `08` DA-D2, `16` D-051), **never spilled to disk, never logged, never exported** (§40/§44), and **released promptly** (per-session lifecycle §36.1). StreamGuard ships buffered content **nowhere** — it emits only content-free telemetry/events (§45). A tenant's streamed content is processed and discarded entirely within its permitted region. **Build-Fail:** buffered content written to disk, log, telemetry, or any cross-region path.

### §47 — Integration with Provider Router (C1)
- Via `TransportSourcePort`: C1 owns **provider selection, connection, auth, TLS, cancellation cooperation** and yields a **raw byte/chunk stream + neutral `FramingType`/transport-descriptor**; StreamGuard owns **transport integrity** over it (ingestion seam, §10/RB-T1). No provider SDK in StreamGuard (AU-06). StreamGuard never routes or fails-over (that is C1/AD-007).

### §47.1 — Integration with Reliability Engine (C2) — the mandatory intermediate seam *(resolves High H3 — additive; specifies responsibilities only, no architecture change)*
The frozen hot-path flow is **C1 (Router) → C2 (Reliability: retry/failover/rate-limit/circuit-break) → C3 (Correctness: StreamGuard, SchemaLock)** (`06`). C2 sits between the provider connection and StreamGuard; this contract specifies who owns what — StreamGuard **specifies responsibilities, it does not take any from C2**:

| Concern | **Owner** | Contract |
|---|---|---|
| **Provider failover** (switch provider/attempt) | **C2 Reliability** (`06`) | C2 decides and performs failover; StreamGuard never fails over (§4/§47). A failover that occurs **before StreamGuard's first downstream emission** yields a **fresh transport session** (the prior partial is discarded, §26). A failover requested **after** first emission is **impossible without violating effectively-once/ordering** — StreamGuard has already delivered bytes — so post-emission the stream is **terminal**: StreamGuard surfaces `TRUNCATED`/`DISCONNECT`, and any further attempt is a **new request** (governed by C2 at the request boundary), never a mid-stream splice. |
| **Cancellation** | **C2 initiates policy; StreamGuard executes transport cancel** | C2 (or the client/pipeline) may signal cancellation; StreamGuard performs the cooperative transport cancel + provider close + buffer release (§27). One executor (StreamGuard) for the transport action. |
| **Point of no return** | **StreamGuard-defined, C2-respected** | = first `TransportDelta` emitted downstream (§26). C2's failover/retry authority over a *stream* ends at this point; beyond it, only request-level (new-request) retry applies. |
| **Stream termination** | **StreamGuard** | StreamGuard owns the transport terminal verdict (§21/§30); C2 does not mark a stream complete. |
| **Transport lifetime** | **C1 connection · C2 reliability wrapper · StreamGuard integrity** | C1 owns the socket/auth; C2 owns the reliability decision to (re)invoke; StreamGuard owns integrity of the bytes that flow. No overlap. |
| **Retry handoff** | **§49.1 precedence** | See the single-authority retry rule below. |

### §48 — Integration with SchemaLock (C3)
- StreamGuard emits **effectively-once, arrival-ordered** `TransportDelta`s + a `TransportVerdict` via the pipeline; SchemaLock consumes them for logical assembly + schema validation (`17 §43`). **Terminal success is joint-gated** (§30). Neither duplicates the other (§43/§43.1-SG).

### §49 — Integration with Retry (transport vs reliability vs guided)
- StreamGuard's **transport** retry (§26) is connection-level and **pre-emission-only**. It is one of three retry concepts; their precedence is fixed in **§49.1** to prevent storms.

### §49.1 — Retry precedence: exactly one authority at a time *(resolves High H4 — additive; consistent with `06 D-5`, `15` T-017)*
`06 D-5` warns that uncoordinated retries cause storms. StreamGuard therefore defines a **strict, mutually-exclusive precedence** so **only one retry authority is ever active for a given attempt**:

1. **Transport Retry (StreamGuard, §26)** — scope: a single provider connection, **before first downstream emission only**. If it succeeds, no other retry engages. If it exhausts its bounded budget, StreamGuard surfaces a transport failure and **hands off** — it does **not** loop.
2. **Reliability Retry / Failover (C2, `06`)** — scope: re-invoke the provider (same or failover), engaged **when StreamGuard surfaces a recoverable transport failure and no delta has been emitted**. C2 owns the request-level retry/failover budget. Once C2 hands a (re)invocation to StreamGuard, C2's retry is quiescent for that attempt.
3. **SchemaLock Guided Retry (C3, `17 §24`)** — scope: a **new generation attempt** because the *content* was non-conformant, engaged **only after a complete, transport-valid stream** was delivered and SchemaLock's schema validation failed. It never runs concurrently with (1) or (2) for the same attempt.

- **Single-authority rule (normative):** at any instant, for a given attempt, **exactly one** of {Transport, Reliability, Guided} is the active retry authority; the others are quiescent. Control passes **strictly forward** (transport → reliability → [new attempt] → guided), never in a loop back.
- **Budget consistency:** all three share the frozen **≤10% retry budget** (`15` T-017); StreamGuard's transport retries **count against** the same request's budget (no separate hidden budget). Exhaustion at any level ⇒ **fail closed + surface**, never escalate into another loop.
- **Storm prevention:** because authority is exclusive and forward-only and the budget is shared, no combination can produce a retry storm. **Build-Fail:** a StreamGuard transport-retry loop that runs concurrently with, or re-enters, C2/SchemaLock retry; a transport retry not counted against the shared budget.

### §50 — Integration with Metering (C5)
- Via `MeteringSinkPort` (events): transport counters (deltas, bytes-class buckets, retries, cancellations) — **content-free** — as **inputs** to C5's usage/cost accounting; **C5 owns metering and cost attribution** (`05`/`06`), StreamGuard merely reports transport facts (no ownership change, no double-counting: StreamGuard does not compute cost).

### §51 — Integration with Audit (C10)
- Via `AuditSinkPort` (events): every transport-integrity outcome (proven / truncated / gap / corruption / timeout / cancelled) is a **content-free, tamper-evident** audit event (WORM + Merkle, `08 §10`, `13 §19`); audit completeness invariant (RPO=0) preserved.

### §52 — Integration with Policy / Governance
- **None (by design).** StreamGuard enforces **no** policy/authorization (§4). Governance/authorization happen upstream in the non-bypass pipeline (AD-018/AD-019); StreamGuard is a transport stage that runs **within** an already-authorized, already-governed request.

### §52.1 — Tool-call sequencing ownership *(resolves M8 — additive; references existing ownership, redefines none)*
- `06` lists **tool-call integrity** as a **C3 Correctness** capability. This document scopes StreamGuard to the **transport half** only: reconstructing tool-call argument **bytes/JSON structure** to well-formedness (RB-7, §17-referenced). **Tool-call argument schema validation** is owned by **SchemaLock** (`17 §17`). **Multi-tool sequencing / tool-call orchestration correctness** (ordering across multiple tool calls, agentic tool loops) is **neither StreamGuard's nor newly claimed here** — it remains a **C3 Correctness "tool-call integrity"** concern (`06`), owned by the Correctness context and realized by the tool-call correctness engine of C3 (a peer to SchemaLock/StreamGuard within the same context, `06:138` "Correctness Engines" plural). StreamGuard **references** this ownership and **does not redefine, claim, or move it** — no ownership change. StreamGuard's sole tool-call responsibility is transport-level argument-stream well-formedness.

---

## M. Failure & Recovery

### §53 — Failure modes (summary)

**SG-D11 — Fail-closed on all transport uncertainty (SG-INV realized)**
- **Problem:** every ambiguous transport state must resolve safely; "emit anyway / recover silently" is the cardinal sin.
- **Decision:** **every** transport failure/uncertainty — truncation, gap, decode error, corruption, duplicate/replay, timeout, disconnect, overflow — resolves to **fail closed + surface**, never to a silent completion, silent reorder, silent dedup-that-loses-data, guessed bytes, or fabricated remainder. There is no path from "uncertain" to "marked complete."

| Failure | Detection | Recovery |
|---|---|---|
| Truncation | close without terminal (§21) | pre-emission transport retry (§26) → else surface `TRUNCATED` |
| Gap / out-of-order | sequence check (§18/§19) | bounded reorder → else surface `GAP` |
| UTF-8 / framing decode | strict decoder (§13/§15) | surface `DECODE` (no substitution) |
| Corruption (CRC) | event-stream CRC (§15) | retry (pre-emission) → surface `CORRUPTION` |
| Duplicate / replay | dedup window (§17/§33) | drop-once → else surface `REPLAY` |
| Timeout | dual budget (§24) | cancel provider → surface `TIMEOUT` |
| Disconnect | connection drop (§25) | pre-emission retry → else surface `DISCONNECT` |
| Buffer overflow | bounds (§31/§32) | cancel provider → surface `OVERFLOW` |
| Cancellation | client/pipeline (§27) | propagate to provider → `CANCELLED` |
- **Enforcement:** fault-injection asserts **zero silent completion / zero silent reorder / zero silent dedup-loss** under every injected failure (`15` T-018). **Build-Fail:** any failure branch that marks complete, reorders, drops data, or fabricates.

### §54 — Recovery behavior
- StreamGuard holds **no durable state**, so recovery is **stateless**: a restarted instance serves new streams immediately (data plane fast-start, `16` D-041). An **in-flight** stream during a deploy/restart is **drained** where possible (`16` D-031) or **surfaced as `TRUNCATED`/`DISCONNECT`** to the caller — **never** a silent partial completion. Provider/control-plane issues degrade to fail-closed surfacing, never bypass (AD-018).

---

## N. Testing & Enforcement

### §55 — Testing strategy (StreamGuard is a `15 §G.1` critical module)
- **Deterministic core tests:** given a fixed byte sequence + injected clock ⇒ fixed delta sequence + verdict (§34).
- **Property-based (jqwik, `15` T-035):** *for any chunk boundary split of a valid stream, the emitted delta sequence is identical* (chunk-boundary invariance); *for any duplicate/reordered input within window, output is effectively-once + in-arrival-order*; *no input yields a silent completion*; *pooled reuse leaves zero residue* (§36.1).
- **Fuzzing (`15` T-033):** malformed UTF-8, malformed SSE/event-stream/JSON-array framing, random byte injection, CRC corruption, adversarial partial JSON, integer-boundary sizes.
- **Mutation (`15 §G.1`):** **≥ 85%** on StreamGuard (named critical module); a mutant that makes a decode/order/dedup check permissive must be killed.
- **Replay testing:** duplicate chunks, replayed segments, replayed streams ⇒ deduped/blocked, never double-delivered.
- **Fault injection (`15` T-012/T-018, per-PR):** dropped chunks, out-of-order, provider disconnect, connection-close-without-terminal, stall/dribble (slow-loris), oversized/flood ⇒ **fail closed, zero silent truncation/reorder/dup**.
- **Random chunk-boundary + large-stream + soak (`15` T-025):** boundary invariance at scale; no leak/growth over sustained streaming.
- **Cancellation tests:** mid-stream cancel ⇒ provider closed, buffers freed, `CANCELLED`.
- **Effectively-once tests:** duplicate within window ⇒ single emission; ambiguous/beyond-window ⇒ fail closed (§16.1).
- **Multi-provider transport conformance (`15` T-014/T-015):** identical neutral transport behavior across SSE/event-stream/JSON-array framings; no provider leak.
- **Concurrency/isolation (`15` T-027/T-044):** massive concurrent streams, no cross-session/tenant leakage; VT no-pinning (`15` T-028).
- **Performance (`15` T-021/T-026, `16 §J.2`):** decode/sequence hot path within budget; perf-smoke gate.

### §56 — Build-failing architectural rules
| # | Rule | Gate |
|---|---|---|
| SG-A1 | Only StreamGuard parses provider streaming (no other module) | ArchUnit |
| SG-A2 | No StreamGuard↔SchemaLock boundary violation (either direction); terminal needs both verdicts | ArchUnit + `15` T-008/T-012 (§43) |
| SG-A3 | No provider SDK / provider-specific framing branch in StreamGuard | ArchUnit AU-06 |
| SG-A4 | No schema/JSON-validation library or type referenced in StreamGuard | ArchUnit |
| SG-A5 | No path from ingest→completion that skips ordering/completeness/liveness | ArchUnit + `15` T-018 |
| SG-A6 | No emitting completion/terminal before StreamGuard **and** SchemaLock verdicts | `15` T-012 |
| SG-A7 | No unbounded buffer / window / counter; all sizes checked before allocation | config + fault test |
| SG-A8 | No provider-native event exposed; no content in telemetry/logs/events | `14 §7.1` scanner, `15` T-052 |
| SG-A9 | No duplicate completion; no partial completion; no out-of-order emission | `15` T-012/T-018 |
| SG-A10 | No lenient/replacement UTF-8; no byte-drop on error | fuzz test (§13) |
| SG-A11 | No transport retry after first downstream emission; no partial-stream merge | fault test (§26) |
| SG-A12 | No wall-clock/random in transport core; no shared mutable session state; no blocking-in-`synchronized` hot path | ArchUnit (`11` R-063/R-049) |
| SG-A13 | No private persistent store (stateless, `06 §24`) | ArchUnit AU-07 |
| SG-A14 | Mutation score ≥ 85% (critical module) | PITest (`15 §G.1`) |
| SG-A15 | No pooled-object reuse without Zero/Reset + Validate-Clean-State; no cross-session/tenant residue | lifecycle contract test + `15` T-044 (§36.1) |
| SG-A16 | ALL streaming egress transits StreamGuard; no parser/pass-through/alternate transport bypass | ArchUnit + `15` T-018 (§10.1) |
| SG-A17 | No buffer returned to pool while a live delta references it; no async slice without copy/refcount | fault test (§37) |
| SG-A18 | Structural scanning only for framing; no semantic/schema/business/logical validation in StreamGuard (reaffirms `17 §43.1`) | ArchUnit + contract test (§20.1/§43.1-SG) |

---

## O. Operations

### §57 — Operational runbook
- **Silent-completion equivalent (should be impossible; any indicator > 0):** treat as **Sev1 transport-integrity incident** — auto-halt canary (`16 §E.2`), roll back (`16` D-030), root-cause (ordering/dedup/completion logic).
- **Integrity-failure-ratio spike (`streamguard_integrity_failure_ratio`, per-`provider`/`framing` internal dimension §42):** investigate provider drift (`15 §C.1`), framing change, or network; canary auto-aborts on threshold.
- **Truncation/disconnect spike:** provider/network health; check pre-emission retry budget (§26).
- **Overflow/timeout spike:** inspect for stream-bomb/slow-loris (security §40); tune buffer/timeout baselines (`16 §I.1`).
- **Corruption (CRC) spike:** provider or network corruption; escalate to provider drift.
- **Buffer/session saturation:** scale (data plane HPA, `16` D-045) + verify backpressure; never relax bounds silently.

### §58 — Upgrade strategy
- Ships within the **data-plane release train** (`16` D-014/D-055): rolling/canary, zero-downtime (`16` D-031), transport-integrity-signal-gated (`16 §E.2`). In-flight streams drained (`16` D-031) or surfaced (§54) — never silently truncated by a deploy. Framing-decoder upgrades are conformance-tested (`15` T-014/T-015) and canary-gated.

### §59 — Backward compatibility
- The **transport contract** (canonical `TransportDelta` shape, `TransportVerdict`, `FramingType` taxonomy) is a stable internal API (`12 §27`): changes are additive/backward-compatible within a major. A **new provider framing** is added as a new `FramingType` (additive), never by breaking an existing one. Emitted events evolve under Avro compat (`07 §9`, `16` D-033). Deterministic core (§34) guarantees identical transport behavior for identical input across versions.

---

## Q. Reviews

### 1. Traceability
| StreamGuard concern | BR | NFR | ADR | Domain/Svc | API/Event | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|---|
| Transport integrity / no silent truncation (SG-INV, §1/§21/§53) | BR-002 | NFR-STRM-001 | AD-016/018 | correctness pipeline | `12 §16` | — | `14 §11` | T-012/T-018 | `16 §E.2` |
| Sole ingestion + transport parse (§10) | BR-002 | NFR-STRM | AD-018 | `06 §10` | `12 §16` | §13.1 | — | T-051 | D-014 |
| Provider neutrality / framing taxonomy (§11) | BR-006 | NFR-IF-001 | AD-007 | C1↔StreamGuard | `12 §16.10` | — | — | T-014/T-015 | — |
| Module in data plane, stateless (§46) | BR-005 | NFR-PERF | AD-020/006 | `06 §10/§24` | — | — | — | T-051 | D-014 |
| Hexagonal ports (§7) | — | — | AD-002 | `05`/`11` | — | — | — | T-008 | — |
| Effectively-once / ordering / suppression (§16/§16.1/§18/§19/§29/§33) | BR-002/004 | NFR-STRM/RTY | AD-005/016 | — | `07` (effectively-once) | §13.1 | — | T-035/T-018 | — |
| Retry precedence (transport/reliability/guided) (§49.1) | BR-003/004 | NFR-RTY-001 | AD-016 | C2↔C3 (`06 D-5`) | — | — | — | T-017 | — |
| C2 Reliability ↔ StreamGuard seam (§47.1) | BR-003 | NFR-STRM/AV | AD-017 | C1→C2→C3 (`06`) | `12 §16` | — | — | T-018 | — |
| Pooled-object lifecycle / isolation (§36.1) | BR-021 | NFR-REL-002 | AD-021 | — | — | §15 | — | T-044 | — |
| Frozen `17` boundary reaffirmation (§43.1-SG/§20.1) | BR-001/002 | NFR-SO/STRM | AD-018 | C3 | `12 §16` | — | — | T-008/T-012 | — |
| Residency of buffered content (§46) | BR-023 | NFR-MR | AD-014 | — | — | §18 | §7.1 | T-045 | D-051 |
| UTF-8 / framing / SSE / event-stream (§13/§15) | BR-002 | NFR-STRM | AD-007 | — | `12 §16` | §11 | — | T-033 | — |
| Liveness: heartbeat/timeout/disconnect/cancel (§23–27) | BR-002 | NFR-STRM/PERF | AD-016 | Retry/C1 | `12 §16` | §13.1 | §17 | T-018 | — |
| Backpressure / bounded buffers / OOM (§31/§32) | BR-005 | NFR-PERF/CONC | AD-020/023 | `06 §11` | — | §13.1 | §15 | T-025 | D-044 |
| Deterministic / idempotent (§34/§35) | BR-004 | NFR-RTY-001 | AD-016 | `11` R-063 | `12 §14` | — | — | T-035 | — |
| Security: bombs/UTF-8/replay/slow-loris (§40) | BR-018/019/021 | NFR-SEC/STRM | AD-018/021 | — | `12 §16.10` | §11/§13.1/§20 | §7.1 | T-018/T-033/T-052 | §D.1 |
| Concurrency/isolation (§39) | BR-021 | NFR-REL-002/CONC | AD-021/023 | — | — | §15 | — | T-027/T-028/T-044 | — |
| Boundary contract (§43, RB + RB-T) | BR-002 | NFR-STRM-001 | AD-018 | C3↔StreamGuard | `12 §16` | — | — | T-012/T-008 | — |
| Metering/Audit events (§45/§50/§51) | BR-011/024 | NFR-AUD-001 | AD-005/009 | C5/C10 | `07 §6` | §19 | §8 | T-046 | — |
| Testing (critical module) (§55/§56) | BR-002 | NFR-TEST-001 | AD-016 | — | — | §27 | §19 | §G.1/T-018/T-033 | §E.2 |
| Upgrade/back-compat (§58/§59) | BR-028 | NFR-UPG/VER | AD-015 | — | `12 §27` | — | — | T-049 | D-014/D-055 |

### 2. Internal Consistency Review
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-018 | non-bypassable correctness | §8/§9/§30/§53 (no ingest→complete without checks) | ✅ |
| AD-016 | reliability first | SG-INV, SG-D11 fail-closed (§53) | ✅ |
| AD-007 / `12 §16.10` | provider abstraction, no leaks | §11 (neutral framing), §45 (no provider events) | ✅ |
| AD-020/AD-006 | data plane co-located stateless module | §46 (no store), §54 (stateless) | ✅ |
| AD-002 | hexagonal replaceable adapters | §7 ports, §11 framing adapters | ✅ |
| AD-021 | tenant isolation | §39 (no shared session state), §36.1 (pool reset, no residue), §33/§40 | ✅ |
| AD-022 | last-known-good | §54 (degrade fail-closed, no bypass) | ✅ |
| AD-023 | Java 21 + Virtual Threads + ZGC | §39 (VT, no pinning), §36/§37 (pooled+lifecycle, heap≤limit) | ✅ |
| AD-005 / `07` | effectively-once, at-least-once + idempotent | §16/§16.1 (effectively-once over emitted deltas, honest limits) | ✅ |
| AD-014 / `08` DA-D2 | residency confinement | §46 (buffered content in-region, never spilled/exported) | ✅ |
| AD-017 | C1→C2→C3 hot-path flow | §47.1 (C2 Reliability seam specified) | ✅ |
| `03` NFR-STRM-001 | streaming integrity / no silent truncation | §21/§53 | ✅ |
| `06 §11` | added-latency budget | §38 (honest per-delta cost incl. hash) | ✅ |
| `06 D-5` | coordinated retry (no storms) | §49.1 (single retry authority, shared budget) | ✅ |
| `06 §23/§24` | event/data ownership, store-per-service | §45 (owned space, no new owner), §46 (no store) | ✅ |
| `12 §16` / `12 §16.10` | streaming contract; neutralization | §21/§30 (joint gating); §42 (provider label internal-only) | ✅ |
| `13 §20/§7.1` | no content telemetry; DoS defense | §40/§44, SG-D10 | ✅ |
| `14 §5.1/§7.1/§11` | two-tier, privacy, signals | §41–45 | ✅ |
| `15 §G.1` | StreamGuard critical module, mutation ≥85% | §55/§56 SG-A14 | ✅ |
| `16 §E.2` | transport/correctness canary gating | §42/§57/SG-A6 | ✅ |
| **`17 §43.1` RB-1…RB-13 (FROZEN)** | boundary authoritative | §43.1-SG **reaffirms verbatim** (RB-4/RB-7 owner = StreamGuard structural, SchemaLock semantic); §20.1 | ✅ |

**No contradictions found** against `00`–`17` and AD-001…AD-023. The corrective pass is **additive only**: no new ADR, no new service, **no new module** (the pooled-lifecycle/retry-precedence/C2-seam sections specify responsibilities of the *existing* module), no ownership/event/data-ownership change, no consistency/security/deployment-model change, and **no invariant weakened** (AD-021 and AD-018 are *reinforced*). The prior draft's drift from frozen `17 §43.1` RB-4/RB-7 is **retracted and the frozen boundary reaffirmed** (§43.1-SG).

### 3. Architecture Validation (re-run after corrective pass)
- **Frozen boundary honored (C1 resolved):** `17 §43.1` RB-4/RB-7 reaffirmed verbatim; exactly one owner for byte reconstruction (StreamGuard), incremental JSON structural integrity (StreamGuard), tool-call argument reconstruction (StreamGuard structural / SchemaLock schema), logical assembly (SchemaLock), schema validation (SchemaLock) — no ambiguity, no drift (§43.1-SG, SG-A18). ✅
- **Structural-not-semantic scanning (C2 resolved):** StreamGuard performs minimal structural scanning to delimit framing; no semantic/schema/business/logical validation (§20.1). ✅
- **Non-bypass strengthened (M7):** all streaming egress transits StreamGuard; no parser/pass-through/alternate path (§10.1, SG-A16). ✅
- **Isolation reinforced (H5):** pooled-object lifecycle mandates Zero/Reset + Validate-Clean-State; no cross-tenant residue (§36.1, SG-A15) — AD-021 strengthened, not weakened. ✅
- **Retry coordinated (H4):** single retry authority, forward-only, shared ≤10% budget — `06 D-5` storm risk closed (§49.1). ✅
- **C2 seam specified (H3):** failover/cancellation/point-of-no-return/termination/lifetime/handoff ownership all pinned without moving any responsibility (§47.1). ✅
- **Honest guarantees (H1/H2):** effectively-once with explicit limits; ordering differentiated (arrival/provider/emitted); no impossible provider-side gap-detection claim (§16.1/§18). ✅
- **Provider neutrality preserved (M4):** provider identity internal-operational-telemetry only, never externally exposed (§42) — AD-007 intact. ✅
- Introduces **no new architecture decision**; every addition specifies the existing module more precisely.

### 4. Adversarial Review (re-run after corrective pass)

**Accepted board findings — resolution**
- **Critical C-1 (RB-4/RB-7 contradiction with frozen `17`) → RESOLVED.** §43.1-SG retracts the drift and reaffirms the frozen boundary verbatim with a single-owner table; SG-A18 build-enforces it.
- **Critical C-2 (JSON framing vs opaque) → RESOLVED.** §20.1 permits **minimum structural scanning only** to delimit framing (required for JSON-array/NDJSON), explicitly forbidding semantic/schema/business/logical validation.
- **H1 effectively-once → RESOLVED (§16/§16.1):** at-least-once input, effectively-once emitted deltas, suppression boundary + honest limits stated; no overclaim.
- **H2 ordering → RESOLVED (§18):** arrival/provider/emitted order differentiated; provider-side gap-detection-without-metadata claim removed.
- **H3 C2 seam → RESOLVED (§47.1):** failover/cancellation/point-of-no-return/termination/lifetime/handoff specified.
- **H4 retry precedence → RESOLVED (§49.1):** single active authority, forward-only, shared budget, storm-proof.
- **H5 pooled-object lifecycle → RESOLVED (§36.1):** Acquire→Initialize→Zero/Reset→Validate→Use→Clear→Return; no tenant data survives return (AD-021).
- **Mediums M1–M8 → RESOLVED:** honest complexity (§16/§38, M1); transport-retry early-window-only (§26, M2); zero-copy lifetime rules (§37, M3); provider attribution internal-only (§42, M4); residency of buffered content (§46, M5); backpressure-protects-memory-not-cost (§31, M6); non-bypass strengthened (§10.1, M7); tool-call sequencing ownership referenced not redefined (§52.1, M8).
- **L-1 typo → fixed** (`streamguard_integrity_failure_ratio`, `duplicate_suppressed`).

**New findings from the re-run**
- **🔴 Critical:** none.
- **🟠 High:** none.
- **🟡 Medium:** none blocking. (Numeric budgets remain governed operational-baseline entries per `16 §I.1`; the AWS binary-framing decoder and heartbeat-classification remain the highest-attention *implementation* areas, mandated for the fuzz/fault suites §55 — noted as Low deferrals, not open design defects.)
- **🟢 Low (deferred, non-blocking):**
  - **SGL-1** — concrete SSE/event-stream parser library vs hand-rolled → tooling standard.
  - **SGL-2** — exact emitted-event names/topics → reconcile with `06 §23` registry.
  - **SGL-3** — JMH benchmark set for the decode/sequence hot path → `benchmarks/`.
  - **SGL-4** — buffer-pool / window / timeout numeric defaults → first-build calibration (`16 §I.1`).
  - **SGL-5** — AWS event-stream binary-framing decoder: mandated fuzz-corpus depth + heartbeat-classification test vectors → detailed design/tooling (implementation hardening, §55).

### Architecture Readiness Score: **97 / 100**
All accepted Critical, High, and Medium findings resolved additively, with **no new architecture, no new module, no ownership/event/data/consistency/security/deployment-model change, and no invariant weakened** (AD-021 and AD-018 reinforced; the frozen `17 §43.1` boundary reaffirmed). The engine now cleanly and honestly bounds its transport guarantees, coordinates retry with C2, isolates pooled state, and confines residency — all machine-enforceable (SG-A1…A18). The residual 3 points are honest calibration/implementation-hardening items (Low, deferred).

### Documentation Health: **96 / 100** · Implementation Readiness: **94 / 100**
Contradictions removed; guarantees stated at their honest ceiling; every load-bearing mechanism (boundary, effectively-once, ordering, retry precedence, pool lifecycle, non-bypass) now specified precisely enough to implement.

### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`17` or AD-001…AD-023; no new architecture; no ownership/event/data-ownership/consistency/security/deployment-model change; no provider coupling; no invariant weakened.

---

*End of document — 18-StreamGuard.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
