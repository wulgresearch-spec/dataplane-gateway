/**
 * The Memory Runtime's pipelines (C17, AD-026 §5, §6).
 *
 * <ul>
 *   <li>{@link io.reliabilityai.gateway.dataplane.memory.application.MemoryWritePipeline} — admit,
 *       classify, govern, apply policy, seal, store, index, audit. In that order, every time.
 *   <li>{@link io.reliabilityai.gateway.dataplane.memory.application.MemoryReadPipeline} — admit,
 *       narrow, govern, retrieve, re-verify, rank, audit. Narrowing happens before any adapter is
 *       touched, which is the whole of the isolation guarantee.
 *   <li>{@link io.reliabilityai.gateway.dataplane.memory.application.MemoryRuntime} — the front
 *       door: write, read, delete with proof, install policy.
 *   <li>{@link io.reliabilityai.gateway.dataplane.memory.application.MemoryLifecycleSweeper} — TTL,
 *       retention, archival and index reconciliation, out of band and idempotent.
 * </ul>
 *
 * <p>None of these orchestrates anything. Each answers one operation and returns.
 */
package io.reliabilityai.gateway.dataplane.memory.application;
