# ADR-027 — Durable Memory Store: Journal Adapter

**ADR ID:** AD-027 · **Status:** Proposed · **Date:** 2026-08-05
**Part of:** ADR Register — see `000-ADR-Index.md`
**Closes:** blocker **B22** (AD-026 §14.1) — "no vendor storage adapter ships"
**Implements:** `MemoryStorePort` (AD-026 §12) · **Amends:** nothing
**Does NOT amend:** `AD-026`, the Memory Runtime (C17), the Plugin Runtime, Governance, `RequestPipeline`

---

## §1 — Decision

**One durable `MemoryStorePort` adapter ships: a segmented, append-only journal engine built on the JDK
alone, with one log per tenant, fsync-before-acknowledge durability, checksummed framing, torn-write
recovery, compaction and point-in-time snapshots.**

It lives in its own module, `gateway-dp-memory-store-journal`. The dependency runs one way — the adapter
depends on C17; C17 depends on nothing of ours — so the memory runtime cannot reference a storage engine
even by accident.

```
Memory Runtime (C17)         policy · ranking · retrieval · governance · classification
      │                      ── unchanged by this milestone ──
      ▼  MemoryStorePort
JournalMemoryStore           persistence, and nothing else
      ▼
TenantPartition (one per tenant)   in-memory index + write lock + optimistic stamps
      ▼
SegmentLog                   append-only segments, CRC framing, fsync, torn-tail recovery
      ▼
filesystem
```

## §2 — Why this engine, and not MongoDB

The MongoDB driver **is** resolvable in this environment (`mongodb-driver-sync 5.5.1` is in the local
repository). It was the obvious candidate and it was rejected for one reason:

> **No MongoDB server is reachable.** `mongod` is absent and the Docker daemon is not running. A Mongo
> adapter would compile and could never be executed.

That fails this milestone's own honesty rule — *"do not claim production readiness if any contract is
unverified"* — and its conformance requirement. It would have shipped several hundred lines of
vendor-specific code against which **not one of the seven adapter guarantees could be demonstrated**.
AD-026 §12.4 already refused to ship exactly that kind of unexercised stub, and refusing again is
consistent rather than novel.

The journal engine is chosen because every contract it claims can be executed, and because it matches
the deployment actually in front of us — a single Linux VPS, no server dependencies.

**What is honestly lost by not choosing Mongo:** horizontal scale, an existing operational story,
secondary indexes, and a capacity ceiling above heap. §9 records those as blockers rather than
pretending the journal is a general answer. §11 is the migration path.

## §3 — Design

### §3.1 — One log per tenant

The tenant is a **filesystem boundary**, not a column. Each partition owns a directory, a log and an
index. A query reaches another tenant only by being handed the wrong partition, and the partition is
chosen from the caller's already-narrowed scope — so there is no predicate anywhere that a bug could
weaken into matching two tenants at once.

The directory name is sanitised for containment and suffixed with a digest of the raw tenant identity.
Sanitising alone is not injective — `a/b` and `a-b` collapse to one name — and two tenants sharing a
partition is precisely the failure this layout exists to prevent.

### §3.2 — Records in memory, log on disk

`search` must filter on scope, type, metadata and time and then score what survives. Serving that from
disk would mean reading every record on every query. Live records are therefore held in memory and the
log is the durable record of truth — the append-only-file shape, not a B-tree.

The cost is honest and is stated in §9: **capacity is bounded by heap, not by disk.**

### §3.3 — Framing

`<crc32hex>:<payload>\n`, one entry per line, three entry kinds: `put`, `del` (a tombstone), `touch`.

Two independent corruption modes need catching separately:

| Mode | Cause | Detected by |
|---|---|---|
| Torn tail | Power lost mid-write | End of file with no newline |
| Corrupt line | A partial sector containing a newline | Checksum mismatch |

On open the log is read to the first failure and truncated to the last good boundary. **A partial entry
is discarded, never repaired** — a half-parsed record is a fabricated fact, and integrity, audit and
delete proof all assume a record is exactly what was written.

### §3.4 — No control bytes in the format

The codec encodes an absent workspace or project with a leading `-`/`+` presence prefix rather than an
in-band sentinel character. The first version used a NUL as the sentinel, which was wrong twice: a
sentinel can collide with a real value, and that particular byte put unprintable characters into a
line-oriented journal and into everything that read it. A test now asserts the journal contains no
control bytes at all.

### §3.5 — Concurrency, in two halves

**Writes** take a per-partition lock, so appending, syncing and publishing to the index are one
indivisible step. The log and the index can therefore never disagree, which is what makes recovery
sound. **Reads** take no lock, working from a concurrent map. Different tenants never contend.

**Optimistic concurrency** is offered separately, as `putIfUnchanged(record, key, stamp)`: a caller
reads a stamp, decides, and writes back naming it; if another writer intervened the call returns empty
rather than overwriting. It is an *adapter capability*, not a port method — adding it to
`MemoryStorePort` would have changed C17, which this milestone forbids. Nothing in the runtime uses it,
and that is the point.

### §3.6 — Compaction and snapshots

Compaction rewrites only live records, bounding the growth an append-only log otherwise has (a
much-read record leaves one touch entry per read). It writes to a staging file and moves it into place
atomically — an in-place rewrite, if interrupted, would leave a log that is neither the old one nor the
new one, the single state from which no recovery is possible.

Snapshots copy each partition's log while holding that partition's write lock. **Consistent per
partition, not globally**: a store-wide barrier would stall every tenant to serve one, which is the
wrong trade for a multi-tenant plane.

## §4 — What the adapter does not do

Ranking · security decisions · policy · embedding · retrieval strategy · authorization · classification
· TTL decisions. Every one belongs to C17. The adapter's only judgement is *where bytes go and how they
come back*.

Two places where this is visible and deliberate:

- `expired()` returns records **under legal hold too**. Filtering them would put a compliance rule
  inside the adapter; the sweeper checks the hold itself, unconditionally, on every path.
- Sealed bodies are **never searched**. Matching ciphertext is meaningless, and decrypting to search
  would put key handling inside a storage adapter, which MEM-8 forbids.

## §5 — Conformance report

The suite is written against the published port and runs **twice — once per implementation**. A case
passing for one and failing for the other is a hole in the port, not a bug in an adapter.

| AD-026 | Guarantee | Verified |
|---|---|---|
| **A1** | `put` durable before returning | ✅ — plus an explicit sync-count assertion (§9.1 on its limits) |
| **A2** | `put` idempotent on `(scope, writeKey)` | ✅ — including concurrently and across a restart |
| **A3** | `search` returns only the narrowed scope | ✅ — tenant, workspace and owner |
| **A4** | Unavailability throws, never empty | ✅ — `put`, `get`, `search` |
| **A5** | Bounds honoured | ✅ — `search` and `expired` |
| **A6** | Bytes returned byte-identical | ✅ — every field, including newlines, separators, escapes, unicode, all flags |
| **A7** | `delete` idempotent, reports what it did | ✅ |

**Results.** 34 conformance cases × 2 stores = 68 invocations, all green. Total adapter module: **154
tests**. Backend total **20,867 → 21,019** — a delta of exactly 152 plus the 2 format-regression tests
added afterwards, i.e. no existing test changed.

**The Memory Runtime's own 154 test methods were not modified.** The full runtime — policy,
classification, redaction, sealing, ranking, delete proof, sweeper — was additionally driven end to end
over the durable adapter in `MemoryRuntimeOverJournalTest`, with one constructor argument different and
nothing else.

## §6 — Threat model

| # | Threat | Mitigation | Verified |
|---|---|---|---|
| T1 | Cross-tenant read | Partition per tenant, chosen from the narrowed scope; visibility re-checked | ✅ |
| T2 | Cross-tenant write | Partition derived from the record's scope, never from a payload field | ✅ |
| T3 | Enumeration via `get` | Forbidden and absent are the same result | ✅ |
| T4 | Enumeration via `delete` | Same — both report `false` | ✅ |
| T5 | Enumeration via the stamp API | Stamp is scoped like everything else | ✅ |
| T6 | Enumeration via timing | Both paths do the same lookup and filter; measured ratio **1.01–1.04** | ✅ |
| T7 | Leakage through file names | No content, id or write key in any path component | ✅ |
| T8 | Leakage of the user list | Partition keyed by tenant only — never workspace, owner or session | ✅ |
| T9 | Plaintext at rest | The adapter stores what it is given; sealed bodies stay sealed | ✅ |
| T10 | Metadata cross-tenant match | Metadata filters cannot reach another partition | ✅ |
| T11 | Path traversal via tenant name | Sanitised, digest-suffixed, containment asserted | ✅ |
| T12 | Index exposure | No public method returns a map, path or channel | ✅ |
| T13 | Deleted content lingering | Tombstone hides it; **compaction removes the bytes** | ✅ — and §6.1 |
| T14 | Corruption accepted as data | CRC + torn-tail truncation; unknown entry stops replay | ✅ |

### §6.1 — Erasure needs compaction, and that is a real gap

A tombstone makes a record unreachable. It does **not** remove the bytes: they stay in the segment until
compaction rewrites it. A test asserts both halves of that, because the honest statement is *"deleted is
unreachable immediately and unrecoverable after compaction"*, not *"deleted is gone"*.

For an erasure-rights workflow this means compaction is part of the obligation, not an optimisation.
The runtime's delete proof is issued at tombstone time, so **a proof can currently precede the bytes
actually going**. That is recorded as blocker **B28**.

## §7 — Failure analysis

| Failure | Behaviour | Verified |
|---|---|---|
| Clean restart | Everything replays | ✅ |
| Unclean restart (process vanishes) | Every acknowledged write survives | ✅ |
| Torn final entry | Discarded; prefix intact; file truncated so the next append is valid | ✅ |
| Corrupt line with valid framing | Discarded at the checksum | ✅ |
| Segment with no header | Not interpreted — guessing its tenant would be worse | ✅ |
| Well-framed unknown entry kind | **Replay stops.** Skipping would apply later entries onto a state that missed one | ✅ |
| Storage unavailable | Throws; never an empty result | ✅ |
| Failure mid-write | Nothing half-stored; index unchanged | ✅ |
| Duplicate request | Idempotent, live and across restart | ✅ |
| Concurrent writers, one tenant | Serialised; all land; all recover | ✅ (8 × 250) |
| Concurrent writers, many tenants | Parallel; no leakage | ✅ (8 tenants) |
| Concurrent duplicate writes | Exactly one creates | ✅ (16-way race) |
| Concurrent deletes | Exactly one removes | ✅ (12-way race) |
| Concurrent touches | No lost counts | ✅ (400 touches) |
| Concurrent compaction and writes | No records lost | ✅ |
| Stale optimistic write | Refused, not silently applied | ✅ |
| Compaction interrupted | Staging file + atomic move; old log intact until the new one is durable | Design; not fault-injected (**B29**) |

**Network timeout** has no analogue here: the engine is local, so a storage call cannot hang on a
network. That is a genuine simplification of this engine and a genuine gap relative to a networked
store, which is what makes §11's conformance requirement matter for the next adapter.

## §8 — Benchmarks

Measured in-process on the development machine (Windows, JDK 21). **Not** invented; every figure is
printed by `JournalBenchmarkTest`.

| Operation | Result |
|---|---|
| **Write, fsync per op (durable)** | **3.5–4.1 ms/op · ~286 ops/sec** |
| Write, 8 tenants, durable | **~607 ops/sec** |
| Write, no fsync (**not durable**) | 13–34 µs/op · 76,000 ops/sec |
| Write, 8 tenants, no fsync | 198,624 ops/sec |
| Write, 1 tenant, no fsync | 24,428 ops/sec |
| Point read (10k records) | 2.7 µs · 372,023 ops/sec |
| Concurrent point read (8 threads) | 1.2 µs · **812,957 ops/sec** |
| Scoped search over 5,000 records | 2.1 ms |
| Cold restart, 20,000 records | **173 ms** (8.6 µs/record) |
| Cold restart, 50 tenants | 322 ms |
| Compaction (5,000 written, 4,000 deleted) | 29.6 ms · 1.53 MB → 289 KB |
| Snapshot (10,000 records) | 78 ms |
| Resident overhead | **636 bytes/record** |
| On-disk overhead | **308 bytes/record** |

### §8.1 — Reading the write number honestly

**286 durable writes/sec on one tenant is the headline constraint of this adapter.** It is one fsync per
write by design, because A1 says durability must be real before the call returns.

The unsynced figure (76,000/sec) shows that **~99.6% of that cost is the disk, not the engine**. The
remedy is well understood and not implemented here: **group commit** — batching concurrent writers into
one fsync — would raise concurrent durable throughput by roughly the batch factor. That is blocker
**B30**, and it is the first thing to do if this adapter is ever the bottleneck.

Cross-tenant parallelism already helps (286 → 607 across 8 tenants) because each partition syncs
independently, but it does not help a single busy tenant.

## §9 — Blockers

| # | Blocker |
|---|---|
| **B28** | Erasure completes only at compaction; a delete proof can precede the bytes going (§6.1). Compaction should be triggerable by, or coupled to, the erasure path. |
| **B29** | Compaction interruption is handled by design (staging + atomic move) but **not fault-injected**. Unverified. |
| **B30** | No group commit. Single-tenant durable write throughput is one fsync per write (§8.1). |
| **B31** | **Capacity is bounded by heap**, not disk: every live record is resident. Fine for a single-node deployment, wrong for a very large tenant. |
| **B32** | Single-node only. Two processes over one directory would each keep their own index and both could win an append. No cross-process locking. |
| **B33** | `fsync` is **counted, not proven**. Nothing inside the process can prove the OS honoured it — the page cache serves reads either way, which is why the restart tests pass even with `fsync` removed. Real proof needs power-cut testing or syscall interception. |
| **B34** | `search` is O(n) within a tenant. No secondary index. Acceptable at thousands of records per tenant; not at millions. |
| **B1/B2** | Inherited: Maven blocked, so Checkstyle, SpotBugs, ErrorProne, JaCoCo and PITest have never run; ArchUnit absent, so the module boundary is verified by import scan rather than an enforced test. |

**Pre-existing finding, outside this milestone.** `gateway-dp-app/.../SnapshotCapabilitySource.java`
line 103 contains a literal NUL byte as a composite-key separator (`routeRef + '\0' + modelId`). It
works, but it makes the file "binary" to tooling. A one-character change to `' '` would fix it with
identical behaviour. Not touched here — this milestone is B22 only.

## §10 — Recovery analysis

**Recovery is replay.** Opening the store scans every partition directory, replays every segment in
order, and rebuilds the index. There is no separate recovery mode and no repair step, so the code that
runs after a crash is the same code that runs on a clean start — the path is exercised by every single
test that reopens a store.

| Property | Value |
|---|---|
| Time | ~8.6 µs/record; 173 ms for 20,000 records |
| Cost | One sequential read per segment. No random I/O |
| Idempotent | Yes — replaying twice gives the same state (asserted for touch counts) |
| Partial data | Truncated at the last good boundary |
| Unknown entries | Replay stops rather than skipping |
| Failure mode | Throws at construction — a node with a corrupt journal fails to start rather than serving wrong data |

**Access counters are the one thing that can be lost.** They are appended but not fsynced per touch — a
touch happens on every read hit, and syncing each would make reads pay a disk round trip to update a
ranking signal. The port declares `touch` best-effort for exactly this reason. A crash loses recent
counts, which degrades ranking slightly and loses no memory.

## §11 — Migration guide

### §11.1 — Adopting this adapter

Construct `new JournalMemoryStore(path)` and pass it where `InMemoryMemoryStore` went. **Nothing else
changes** — the runtime is assembled identically. There is no schema, no migration and no data to move,
because there is no prior durable store to move from.

### §11.2 — Operating it

| Concern | Guidance |
|---|---|
| Backup | Copy the root directory, or use `snapshotTo`. Per-partition consistent (§3.6) |
| Restore | Point a new store at the copy. A snapshot opens as a store in its own right — tested |
| Compaction | Not automatic. Schedule it, and couple it to erasure (**B28**) |
| Capacity | Watch resident bytes; ~636 bytes/record plus body (**B31**) |
| Offboarding | Remove the tenant's directory. Tested that it leaves others intact |
| Deployment | **One process per directory** (**B32**) |

### §11.3 — Migrating to a different adapter later

Because a `MemoryRecord` is portable (MEM-4), migration is read-all-write-all through the port. The
three rules from AD-026 §15.4 apply unchanged: legal holds transfer first and are verified before any
source deletion; residency is re-evaluated against the target; the source is not deleted until the
target's digests verify.

### §11.4 — Admitting the next adapter

It passes `MemoryStoreConformanceTest` **unmodified**. The suite is already parameterised over two
implementations; a third is a one-line addition to its source list. That is the entire admission
criterion, and it is why this milestone put the suite in the adapter module rather than beside the
engine.

## §12 — Rejected alternatives

| Rejected | Why |
|---|---|
| MongoDB adapter | No reachable server; every contract would be unverified (§2) |
| Embedded SQL (H2/SQLite/Derby) | No driver resolvable; same problem |
| One file per record | An fsync and an inode per write; directory scans for search; far worse on every axis |
| B-tree / LSM on disk | Correct for a store that outgrows heap, and far more machinery than one node needs today (**B31** names the trigger) |
| Records read from disk on demand | `search` filters on four dimensions; every query becomes a full scan of the disk |
| Global snapshot barrier | Stalls every tenant to serve one (§3.6) |
| Optimistic concurrency on the port | Would change C17, which this milestone forbids (§3.5) |
| Tombstone-free delete (rewrite in place) | Append-only is what makes crash recovery a replay |
| Skipping unknown entries on replay | Applies later entries onto a state that missed one — silently wrong |
| Adapter-side legal-hold filtering | Puts a compliance rule in nine future adapters (§4) |

---

*End of ADR-027.*
