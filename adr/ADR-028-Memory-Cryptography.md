# AD-028 — Production Cryptography for Memory Runtime Content

**Status:** Accepted
**Date:** 2026-08-05
**Supersedes:** nothing. **Superseded by:** nothing.
**Closes:** B24 (Real Crypto).
**Depends on:** AD-026 (Memory Runtime, C17), AD-027 (journal storage adapter).
**Module:** `backend/dataplane/modules/gateway-dp-memory-crypto`

---

## 1. Context

AD-026 §12 defined `MemoryCryptoPort` and shipped `NotRealCryptoSealer` behind it — a reference
implementation whose name was chosen so that nobody could deploy it by accident. It performed a
reversible transformation and offered no confidentiality whatsoever. Every claim AD-026 made about
sealing was, until now, a claim about a placeholder.

This ADR records the implementation that replaces it, and — more importantly — records precisely what
that implementation does and does not defend against, because the gap between "content is encrypted"
and "content is safe" is where security architectures usually fail.

### 1.1 A correction to the milestone brief

The B24 brief specified this layering:

```
Memory Runtime  →  MemoryStorePort  →  Crypto Layer  →  Durable Storage
```

**That is not where the crypto port sits, and it could not be moved there under the brief's own
constraint that Memory Runtime remain unchanged.** In the implemented system:

```
MemoryWritePipeline ──seal()──►  MemoryCryptoPort           (C17 calls the cipher directly)
        │
        └──put()──►  MemoryStorePort  ──►  journal adapter  ──►  disk
```

`MemoryWritePipeline` calls `crypto.seal(...)` at line 208 and `MemoryReadPipeline` calls
`crypto.unseal(...)` at line 321, both *above* the store port, and sealing is gated on
`policy.requiresSealing(classification)`. Relocating the cipher beneath `MemoryStorePort` would mean
deleting those call sites, removing the `sealed` flag from `MemoryOutcome.Written`, and changing what
`MemoryContent.sealed(...)` means — all C17 changes, all forbidden by the same brief.

The cipher was therefore implemented where the port already is. §2.2 records the security consequence,
which is real and is not what the brief's diagram would have produced.

---

## 2. Decision

Implement `MemoryCryptoPort` as **AES-256-GCM envelope encryption**:

- a **fresh 256-bit data key per record**, drawn from `SecureRandom`;
- the **body sealed under that data key**;
- the **data key sealed under a versioned master key** held by a `MasterKeyProvider`;
- both operations authenticating the same **additional data**, which binds the format version, the
  key version, and the **scope** the content belongs to;
- **96-bit random nonces**, one per operation, with a bounded reuse canary;
- **key versions** carried in the envelope *and* returned as the port's `keyRef`, cross-checked on
  every open.

No new dependency. JDK `javax.crypto` only, which on this JVM uses AES-NI.

### 2.1 What the scope binding buys

A ciphertext lifted out of one tenant's storage and written into another's fails tag verification.
The same holds across workspace, owner and partition. This is defence in depth that matters
specifically because of what the AD-027 sabotage run demonstrated: removing the journal adapter's
`visibleTo` filter is a two-line edit that the compiler accepts and that only two tests catch. When
that filter is the only thing between tenants, tenant isolation is one careless refactor deep. With
scope-bound AAD, an attacker who defeats the filter gets ciphertext.

### 2.2 What policy-gated sealing does **not** buy — the honest position on "stolen disk"

Sealing happens only when `policy.requiresSealing(classification)` says so. Consequently, on a stolen
disk an attacker still reads, in the clear:

| Always in the clear on disk | Why |
|---|---|
| **Every record whose policy does not require sealing** | The policy gate is per-classification |
| **All metadata**: record ids, scopes, tenant and user identifiers, memory types, timestamps, TTLs, sizes, tags | Never passed through the cipher; the journal needs it to filter |
| **All embeddings** in the vector index | Computed pre-seal, stored unsealed |
| **The plaintext digest** of every record, sealed or not | `MemoryContent.digest` is stored beside the ciphertext |

That last row deserves emphasis: the digest is of the *plaintext*. For low-entropy content it is a
confirmation oracle — an attacker who guesses a body can verify the guess without any key.

The complete fix is encrypting the journal segments themselves, below the store port, which is where
the brief's diagram pointed. That is a change to AD-027's module and a separate decision. **Recorded
as B35.**

### 2.3 What the port signature makes impossible

```java
Sealed seal(MemoryScope scope, String plaintext);
```

The cipher is given a scope and a body. **It is never told which record it is sealing.** Therefore the
additional data cannot name a record, and:

- two sealed bodies **within one scope** can be exchanged and both will open cleanly;
- a ciphertext **replayed into its own scope** is indistinguishable from the original.

Neither is detectable at this layer, by construction rather than by oversight. `CryptoSecurityTest`
asserts both as *limitations* (tests T5 and T7) so that no reader can mistake the suite for covering
them; if those tests ever fail, the gap has been closed and they should be inverted.

Closing it requires a record identifier in the port signature — a C17 change, out of scope for B24.
**Recorded as B36, and it is the largest gap in this design.**

---

## 3. Cryptographic design

### 3.1 Algorithm

**AES-256-GCM**, 128-bit tags, 96-bit nonces.

Chosen over the alternatives for reasons of what can be *verified here* rather than what is
fashionable:

| Candidate | Why not |
|---|---|
| AES-GCM-SIV | Nonce-misuse resistant, which would close a real risk — but there is no JDK provider, so it means a third-party dependency in a zero-framework codebase, and Maven cannot resolve dependencies in this environment (B1). |
| ChaCha20-Poly1305 | Available in the JDK and a fine choice; AES-GCM wins only because this hardware has AES-NI and the ciphertext is the same shape. Genuinely close. |
| AES-CBC + HMAC | Encrypt-then-MAC done by hand is where implementations go wrong. GCM makes the correct construction the only construction. |
| AES-CTR, AES-ECB, XOR | Unauthenticated. A stream cipher without a tag means an attacker who cannot read the record can still flip bits in it deterministically. |

### 3.2 Envelope format

```
magic        3   'M' 'K' '1'
format       1   currently 1
versionLen   1   1..64
version      n   ASCII key version label
wrapNonce   12   GCM nonce for the data-key wrap
wrapLen      2   big-endian length of the wrapped data key (48 for a 32-byte key + tag)
wrappedDek   m   data key sealed under the master key
dataNonce   12   GCM nonce for the body
ciphertext   *   body sealed under the data key, tag included
```

Base64URL-encoded without padding. **Fixed overhead: 102 bytes** raw per record, independent of body
size.

Nothing in the header is secret. What matters is that all of it is *authenticated*: the version is
bound into the AAD of both GCM operations, so editing the version byte to point at a weaker or
attacker-known key produces a tag failure, not a downgrade.

Parsing is strict and total — every declared length is checked against what remains before it is used.
`MalformedEnvelopeException` is package-private and never escapes: `unseal` catches it and returns
empty, so a caller cannot distinguish "not an envelope" from "does not verify".

### 3.3 Additional authenticated data

```
be32(3) || 'M''K''1' || be32(len(version)) || version || be32(len(scopeKey)) || scopeKey
```

**Length-prefixed, not delimited.** A delimiter can be forged by choosing a tenant name containing it,
which would let two different scopes produce identical AAD and quietly reopen the cross-tenant hole
this exists to close. `MemoryScope.key()` is itself already length-prefixed for the same reason.

### 3.4 Why a data key per record

One extra GCM operation over 32 bytes, and in exchange the nonce-reuse risk on the body disappears
entirely — a repeated body nonce is harmless when no two bodies share a key.

The residual risk concentrates on the wrap, where one master key does cover many records. That is
bounded explicitly: NIST SP 800-38D limits random 96-bit nonces to 2³² invocations under one key, and
`AeadMemoryCrypto` **refuses to seal** past that count rather than continuing outside the standard's
safety argument.

### 3.5 Nonce generation and the canary

`SecureRandom` (platform default; non-blocking, unlike `getInstanceStrong()`, which can block on
entropy and has no place on a write path).

`NonceCanary` keeps a bounded, hashed, process-local sample of recently issued nonces in a 64Ki-slot
`AtomicLongArray`. On a hit the nonce is redrawn; three consecutive hits fail the write closed.

**This is a canary, not a guarantee.** It catches a generator that has stopped producing fresh output
— a cloned VM image, a stuck entropy pool, a test double wired in by mistake — usually on the very
next call. It cannot detect a collision between nonces issued far apart, on different processes, or
before the last restart. A clean canary is not evidence that no nonce has ever repeated.

### 3.6 Failure reporting is deliberately asymmetric

`unseal` returns `Optional.empty()` for **every** failure — malformed, wrong key, wrong tenant, forged
tag — with no message, no exception and no timing difference the test suite can detect.

The operator gets the distinction through `CryptoMetricsPort.OpenFailure`. A caller supplying
ciphertext does not, because a method that answers "wrong key" versus "bad tag" is a decryption
oracle.

---

## 4. Key lifecycle

### 4.1 Custody

`MasterKeyProvider` is a port. **There is no constructor anywhere in this module that accepts a
literal key.** Every implementation loads material from outside the program.

`FileMasterKeyProvider` is the implementation the single-VPS target uses:

```
primary = 2026-07
2026-07 = <base64 of 32 random bytes>
2026-04 = <base64 of 32 random bytes>
```

Read **once** at construction. Re-reading per operation would let anyone who can write that file
choose which key seals the next record, at a moment of their choosing rather than an operator's.

Refuses at startup: a missing file, a non-32-byte key, a non-base64 value, a malformed line, a missing
`primary`, or a `primary` naming a version with no key. Startup is the right time for a configuration
error; the first write of the day is the worst time.

**Permission checking is platform-dependent.** On POSIX, a keyring readable by group or other is
refused outright. **On Windows that check cannot run and is skipped** — the file may be world-readable
and this class will load it anyway. **Recorded as B38.**

### 4.2 Key hygiene

Keys never reach logs, audit, exceptions, metrics or telemetry:

- every `CryptoMetricsPort` parameter is a version *label*, a size or an enum — the interface cannot
  carry material without being changed;
- exception messages name the version label and the structural fault, never a value; the
  `GeneralSecurityException` cause is deliberately **dropped** when sealing fails, because provider
  messages can name algorithms and key specs and end up in logs;
- `FileMasterKeyProvider.toString()` is overridden to print only the primary label and a count;
- data-key buffers are zeroed in `finally` on both paths.

`CryptoSecurityTest` T14 asserts each of these, including collecting everything the metrics port emits
across a seal, a successful open and a failed open, and checking the whole set is
`{"2026-07", "AUTHENTICATION_FAILED"}`.

> Zeroing is best-effort. `SecretKeySpec` copies its input and exposes no destroy; the JVM may have
> copied any buffer during GC. **B41.**

---

## 5. Rotation strategy

Rotation and re-encryption are separate operations, and conflating them is how rotations become
outages.

**`rotateTo(version)`** — an atomic volatile write. New content seals under the new version; existing
records are untouched and stay readable under their own. Cost: microseconds, independent of corpus
size. Refuses a version the provider cannot load, leaving the primary unchanged, so a failed rotation
is not a write outage.

**`rewrap(scope, ciphertext, keyRef)`** — opens under the recorded version and re-seals under the
current primary. Deliberately **not on `MemoryCryptoPort`**: rotation is an operational concern of
whoever holds the keys, and putting it on the port would tell C17 that keys have versions, which
AD-026 §12 keeps out of the runtime on purpose. Returns empty for an unopenable record, so a migration
over a million records is not aborted by the one that is corrupt.

Migration order, as tested:

1. add the new key to the keyring, leaving the old one in place;
2. `rotateTo` the new version — new writes move immediately;
3. `rewrap` the corpus, resumably; **both halves stay readable throughout**, which is the property
   that makes the migration runnable at all rather than a big-bang cutover;
4. once no record references the old version, remove it from the keyring;
5. the old key's destruction is then the erasure event for anything still sealed under it.

---

## 6. Recovery strategy

| Situation | Outcome |
|---|---|
| Process restart | Full recovery. Only the keyring and the envelopes are needed; canary and wrap counters are process-local and their loss is harmless. Tested. |
| Keyring restored from an older backup | Records sealed under versions in that backup open; newer ones report `UNKNOWN_KEY_VERSION` and surface as undecryptable, not as an error. Tested. |
| Key retired while records still reference it | Those records become permanently undecryptable. The read returns them marked unreadable and the rest of the result set is unaffected — AD-026 §11. Tested end-to-end through C17. |
| **Keyring lost entirely** | **Total, unrecoverable data loss for all sealed content.** There is no escrow, no split-knowledge recovery and no second custodian. The data is not *exposed*, which is the property this layer guarantees, but it is gone. **B40.** |
| Corrupted envelope | Refused, reported, isolated to that record. |
| Torn write leaving a partial envelope | Structurally refused by the length checks; the journal's own CRC framing (AD-027) catches it first. |

Key backup is therefore the single highest-value operational control in this design, and this module
provides none of it.

---

## 7. Threat model

Each row is a test in `CryptoSecurityTest` unless stated.

| # | Threat | Status | Mechanism |
|---|---|---|---|
| T1 | Stolen disk | **Partial** | Sealed bodies yield nothing. Metadata, embeddings, digests and unsealed records do not. §2.2, B35 |
| T2 | Partial file disclosure | **Covered** | Every fragment of an envelope is structurally refused |
| T3 | Record modification | **Covered** | GCM tag |
| T4 | Record swapping across scopes | **Covered** | Scope-bound AAD |
| T5 | Record swapping **within** a scope | **NOT COVERED** | No record identity in the port. §2.3, B36 |
| T6 | Metadata tampering | **Covered** | Version authenticated in AAD *and* cross-checked against `keyRef` |
| T7 | Replay **within** a scope | **NOT COVERED** | Same cause as T5. Cross-scope replay *is* refused. B36 |
| T8 | Rollback | **Partial** | Only via key destruction, which is coarse — it invalidates every record on that version |
| T9 | Nonce reuse | **Covered for the realistic failure** | Per-record data keys; wrap budget; canary. Not a proof of global uniqueness — §3.5, B37 |
| T10 | Bit flips | **Covered** | Every bit of every byte tested; all refused |
| T11 | Invalid ciphertext | **Covered** | 500 random inputs, all refused; `unseal` never throws |
| T12 | Wrong tenant | **Covered** | Scope-bound AAD, over a 100-record corpus |
| T13 | Old key versions forced onto new writes | **Covered** | Nothing a caller passes selects a key |
| T14 | Key material in logs, metrics, exceptions, `toString` | **Covered** | §4.2 |
| T15 | Timing side channel between failure modes | **Weakly covered** | Measured ratio below 3× on an unpinned machine. Not a side-channel review. **B39** |

---

## 8. Sabotage verification

Five mechanisms disabled one at a time; the suite must notice each. All restorations verified
byte-identical with `diff`.

| # | Mechanism disabled | Edit | Tests failed | Representative failures |
|---|---|---|---|---|
| 1 | **Authentication** | `cipher.updateAAD(additionalData)` removed from `transform` | **15** | `aBodySealedForOneTenantWillNotOpenForAnother`, `everyDistinctScopeRefusesEveryOtherScopesCiphertext`, `t4RecordSwappingAcrossScopesIsRefused`, `t13CrossTenantNoCiphertextOpensUnderAnyOtherTenant`, `concurrentSealingAcrossScopesKeepsEveryScopeBindingIntact` |
| 2 | **Nonce generation** | `random.nextBytes(nonce)` removed, so every operation reuses a fixed nonce | **85** | `aThousandSealsOfOneBodyProduceAThousandDistinctCiphertexts`, `sealingTheSameBodyTwiceProducesDifferentCiphertexts`, `everySealDrawsAFreshDataKeySoNoTwoRecordsShareOne`, and the round-trip suite |
| 3 | **Key version validation** | `rotateTo`'s loadability check made unreachable | **1** | `rotatingToAVersionWithNoKeyMaterialIsRefused` |
| 4 | **Metadata verification** | envelope-version vs `keyRef` cross-check made unreachable | **1** | `anEditedKeyReferenceIsCaughtBeforeAKeyIsEverSelected` |
| 5 | **Integrity verification** | `CryptoEnvelope.read`'s bounds check made unreachable | **4** | `truncatingTheEnvelopeAnywhereRefusesTheOpenWithoutThrowing`, `flippingAnySingleBitOfTheEnvelopeRefusesTheOpen`, `t10BitFlipsEveryBytePositionIsCoveredByTheTag` |

Restoration verified byte-identical for both files with `diff -q`.

**Two of these deserve comment rather than a tick.**

*Sabotage 4 caught only one test.* That is not weak coverage — it is defence in depth showing through. The key version is authenticated in the AAD as well as cross-checked against `keyRef`, so with the cross-check disabled the GCM tag still refuses every tampered envelope. Only the one test that distinguishes *how* it was refused notices. A single failure here is the correct result; zero would have been the alarming one.

*The GCM tag itself cannot be sabotaged by a small edit.* The JDK never releases unverified plaintext from `doFinal`, so there is no two-line change that turns tag checking off — which is itself a good property of building on the platform provider rather than hand-rolling encrypt-then-MAC. Sabotage 5 therefore targets the envelope's own bounds checking, and the tag's authority is evidenced instead by `t10BitFlipsEveryBytePositionIsCoveredByTheTag`, which flips **every bit of every byte** of an envelope and requires all 992 mutations to be refused. Stated plainly so that "integrity verification: sabotaged, caught" is not read as more than it is.

---

## 9. Benchmarks

Measured by `CryptoBenchmarkTest` on a 16-core Windows 11 laptop, Java 21, AES-NI, 5,000 warm-up and
20,000 measured iterations. **Nothing here is estimated.** Run-to-run variance on this machine reaches
±35% for the large-body cases, so read the order of magnitude, not the third digit.

### 9.1 Latency and throughput

| Operation | Latency | Throughput |
|---|---:|---:|
| seal 64 B | 3.21 µs | 311,523 /s |
| seal 512 B | 3.68 µs | 272,091 /s |
| seal 1 KiB | 5.64 µs | 177,282 /s |
| seal 4 KiB | 8.83 µs | 113,252 /s |
| seal 32 KiB | 41.90 µs | 23,864 /s |
| seal 128 KiB | 162.91 µs | 6,138 /s |
| unseal 64 B | 2.84 µs | 351,570 /s |
| unseal 512 B | 2.27 µs | 439,763 /s |
| unseal 4 KiB | 8.95 µs | 111,773 /s |
| unseal 32 KiB | 42.84 µs | 23,341 /s |
| unseal 128 KiB | 148.91 µs | 6,715 /s |
| **rewrap 1 KiB** (rotation migration) | **14.94 µs** | **66,918 /s** |
| `rotateTo` itself | one volatile write; the 49 µs measured is timer overhead | — |

**Against the storage it sits on, this is free.** AD-027 measured durable writes at 3.5–4.1 ms. A 5.6 µs
seal is **0.14%** of that. Cryptography is not the bottleneck in this system and adding it changes the
write budget by nothing an operator will see.

### 9.2 Allocation and storage overhead

| | |
|---|---:|
| Allocation per seal (1 KiB body) | 10,899 bytes |
| Allocation per unseal (1 KiB body) | 9,831 bytes |
| **Envelope overhead** | **102 bytes, fixed, independent of body size** |
| Encoded overhead, 64 B body | +247% (Base64 plus a fixed header dominate tiny records) |
| Encoded overhead, 1 KiB body | +47% |
| Encoded overhead, 32 KiB body | +34% |
| Projected migration of 1,000,000 records | 14.9 s single-threaded |

The 33% floor on encoded overhead is Base64, not cryptography. Storing the envelope as bytes rather
than as a string would remove it, but `MemoryCryptoPort.Sealed` carries a `String`; changing that is a
C17 change. **B43.**

### 9.3 A performance defect found and fixed, and a wrong hypothesis before it

Concurrent sealing did not scale: eight threads reached 113,000 seals/sec against 108,000 on one
thread, with per-operation CPU inflating from 9.3 µs to 62.8 µs.

The **first hypothesis was wrong.** `SecureRandom.nextBytes` is synchronised and is called three times
per seal, so it looked like the culprit; giving each thread its own generator changed the eight-thread
figure by less than the machine's noise.

A probe isolating each shared component found the real cost:

| Component | 1 thread | 8 threads | Scaling achieved of a possible 8× |
|---|---:|---:|---:|
| `Cipher.getInstance` alone | 1.98 µs | 1.27 µs | 1.57× |
| shared `SecureRandom`, 12 B | 0.51 µs | 1.05 µs | 0.49× — *degrades* |
| thread-local `SecureRandom`, 12 B | 0.57 µs | 0.47 µs | 1.23× |
| shared `AtomicLong` increment | 0.012 µs | 0.129 µs | 0.10× — *degrades* |
| GCM 1 KiB, fresh `Cipher` | 7.97 µs | 1.36 µs | 5.85× |
| **GCM 1 KiB, `Cipher` cached per thread** | **1.66 µs** | **0.31 µs** | **5.40×** |

Constructing a `Cipher` was costing 7.97 of the 8.35 µs a seal took. Caching one **per thread** —
never shared, and `init()` fully resets it before every use — produced:

| | before | after |
|---|---:|---:|
| seal 64 B | 10.16 µs | **3.21 µs** |
| seal 1 KiB | 9.26 µs | **5.64 µs** |
| unseal 64 B | 5.21 µs | **2.84 µs** |
| allocation per seal | 16,320 B | **10,899 B** |
| allocation per unseal | 15,320 B | **9,831 B** |

AD-028 §13 originally rejected cipher caching. That rejection was about a *shared* instance, and it
still stands; a thread-local one carries none of that hazard.

### 9.4 What is still not scaling, honestly

Even after the fix, eight threads produce 84,000–137,000 seals/sec across runs against 108,000–177,000
single-threaded — on a **16-core** machine, so cores are not the constraint. The probe shows the
primitive itself scaling 5.4×, which means the ceiling is above the cipher: at ~137,000 seals/sec and
10.9 KB per seal, this allocates roughly **1.5 GB/s**, which is a plausible allocator and GC limit for
this hardware.

That diagnosis is inferred from the allocation rate, **not** confirmed with a GC or allocation
profiler, and no such profiler was run. **B44.** Reducing allocation further — reusing output buffers,
avoiding the intermediate Base64 string — is the obvious next step and was not attempted, because the
storage layer beneath this is 600× slower and would hide every microsecond of it.

---

## 10. Security review

**What is genuinely strong here.** The construction is standard and boring, which is the correct
aesthetic: AES-256-GCM from the platform provider, per-record data keys, envelope wrapping, 128-bit
tags, length-prefixed AAD, strict total parsing, uniform failure reporting, keys loaded from outside
the program with no literal anywhere in the module. The scope binding gives real defence in depth
against exactly the class of bug the previous milestone's sabotage run showed is easy to introduce.

**What a reviewer should push back on.**

1. **§2.2 is the headline.** "Memory content is encrypted at rest" is not true of this system as a
   whole. It is true of policy-selected bodies. Metadata is not encrypted, and the plaintext digest
   stored beside every ciphertext is a confirmation oracle for low-entropy content.
2. **§2.3 is the second headline.** No record binding means no swap protection and no replay
   protection inside a scope. For a memory system feeding an agent, "an attacker substituted one of
   this user's memories for another of this user's memories" is a plausible and serious attack, and
   nothing here stops it.
3. **Keyword retrieval silently returns nothing for sealed content** (§11). Discovered while writing
   the end-to-end test, not predicted.
4. **The wrap budget is process-local**, so the 2³² bound is enforced per process lifetime, not per
   key lifetime. A service restarting daily resets it daily. B37.
5. **No HSM, no KMS, no envelope root outside the host.** A host compromise yields the keyring.
6. **This code has had no external cryptographic review**, no fuzzing beyond the random-input test,
   and no formal analysis. It is a competent assembly of standard primitives, which is not the same
   thing as a reviewed cryptosystem.

**Production readiness.** Suitable for the single-VPS deployment target with the limitations above
understood and accepted. **Not** suitable where the threat model includes an attacker with write
access to storage (T5/T7), nor where "encrypted at rest" must cover metadata (T1), until B35 and B36
are closed.

---

## 11. Interaction with C17 retrieval — a finding

Sealing content makes it invisible to keyword retrieval. The store holds ciphertext; the keyword is
not there to match. `runtime.read(caller, MemoryQuery.ofKeyword(scope, type, "merger"))` returns **zero
hits** for a record whose plaintext contains "merger", while `ofScope` returns it and opens it
correctly.

Unaffected: `SCOPE`, `METADATA`, `TIME`, and `SEMANTIC` — semantic works because the embedding is
computed from plaintext before sealing.

This is not a defect introduced by B24; it is what encrypting a searchable field costs, in this system
and in every other that is not doing searchable encryption. It is recorded here, and asserted in
`MemoryRuntimeWithRealCryptoTest`, because a silent "zero results" is the kind of behaviour an operator
otherwise discovers in production. **B42.**

---

## 12. Blockers

| # | Blocker | Severity |
|---|---|---|
| **B35** | Metadata, embeddings, digests and unsealed records are plaintext on disk. Needs segment-level encryption in AD-027's module | **High** |
| **B36** | No record-identity binding: no swap or replay protection within a scope. Needs a record id in `MemoryCryptoPort` | **High** |
| **B37** | Wrap budget is process-local, so the 2³² nonce bound is per process lifetime, not per key | Medium |
| **B38** | Keyring permission check does not run on Windows | Medium |
| **B39** | Timing equivalence measured, not analysed; no constant-time review | Medium |
| **B40** | No key escrow or backup mechanism; losing the keyring is unrecoverable loss | Medium (operational) |
| **B41** | Key zeroing is best-effort; `SecretKeySpec` copies and the GC may have too | Low |
| **B42** | Keyword and hybrid retrieval silently return nothing for sealed content | Medium |
| **B43** | Base64 in `Sealed.ciphertext` costs a 33% storage floor; a byte-carrying port would remove it | Low |
| **B44** | Concurrent sealing does not scale on 16 cores; allocation rate is the inferred cause, unconfirmed by any profiler | Medium |
| B1 | Inherited: Maven blocked, so Checkstyle, SpotBugs, JaCoCo and PITest have never run | High |
| B2 | Inherited: ArchUnit jar unavailable, so the module-boundary rules are unproven by tooling | Medium |

---

## 13. Rejected alternatives

| Option | Why rejected |
|---|---|
| Move the cipher below `MemoryStorePort`, per the brief's diagram | Requires deleting C17 call sites and changing `MemoryOutcome.Written`. Forbidden by the same brief. §1.1 |
| Encrypt whole records including metadata | The journal filters on scope, type and time; encrypting them makes `search` impossible. The workable version is segment-level encryption — B35 |
| One key per tenant | Attractive for blast radius and per-tenant erasure, but multiplies key custody by the tenant count with no HSM to hold them, and makes the wrap budget per tenant. Revisit with a real KMS |
| Deterministic encryption for searchability | Turns storage into an equality oracle across tenants. Refused outright |
| Cache a **shared** `Cipher` across threads | A shared, improperly reset `Cipher` is a classic way to leak one thread's plaintext into another's response. Still rejected. A **thread-local** cipher carries none of that hazard and was adopted after measurement — §9.3 |
| Keep `NotRealCryptoSealer` as a fallback when the keyring is missing | A "fail open to plaintext" path is the entire breach. Sealing failure refuses the write instead |
