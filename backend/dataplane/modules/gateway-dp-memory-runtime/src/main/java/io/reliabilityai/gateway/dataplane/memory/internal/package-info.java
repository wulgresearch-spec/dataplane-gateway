/**
 * Reference adapters and in-process infrastructure (C17, AD-026).
 *
 * <p>The policy store's atomic swap, a reference record store, a reference vector index, a
 * conservative PII classifier, an in-process metrics sink — and {@code NotRealCryptoSealer}, which
 * is named for what it is so that nobody deploys it believing otherwise.
 *
 * <p>Nothing here is part of the public contract. Callers depend on the interfaces in {@code
 * ...memory.api}, so a durable adapter can replace any of these without touching the runtime. The
 * reference implementations exist to make the ports' contracts executable (AD-026 §15.3); they are
 * the conformance suite's first subject, not its only intended one.
 */
package io.reliabilityai.gateway.dataplane.memory.internal;
