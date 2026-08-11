/**
 * Production cryptography for Memory Runtime content (AD-028, closes B24).
 *
 * <p>One implementation of {@code MemoryCryptoPort} and the key custody it needs. AES-256-GCM,
 * envelope encryption with a fresh data key per record, versioned master keys, rotation, and
 * additional authenticated data that binds every ciphertext to the scope it was written for.
 *
 * <p><b>What this package must never grow.</b> No policy, no ranking, no retrieval, no embedding,
 * no governance, no search, and no knowledge of storage. Those belong to C17 and to the journal
 * adapter respectively. The dependency arrow runs one way — this module depends on the Memory
 * Runtime and the Memory Runtime depends on nothing here — so a cipher cannot become a policy
 * decision by accident.
 *
 * <p><b>What this package cannot do</b>, stated here because it is easy to assume otherwise: it
 * cannot bind a ciphertext to a record identity, because the port it implements is not given one.
 * Records inside a single scope are interchangeable as far as these tags are concerned. See AD-028
 * §3 and blocker B36.
 */
package io.reliabilityai.gateway.dataplane.memory.crypto;
