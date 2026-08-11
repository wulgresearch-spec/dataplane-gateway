/**
 * SchemaLock application (C3, Doc 17 §7/§8) — the non-bypassable resolution engine ({@code
 * SchemaLockService}: compile/cache → strategy → generate → NR-1 repair → authoritative
 * completion-validate → resolve (guided-retry) → conformant output or surfaced non-conformance,
 * fail-closed, SL-INV), the bounded content-hash compiled-schema LRU ({@code CompiledSchemaCache},
 * §10), and the terminal-gated streaming session ({@code StructuredStreamSessionImpl}, §18/§43.1).
 * No path from generate to a returned value skips validation (SL-A3); any validator error fails
 * closed (§48).
 */
package io.reliabilityai.gateway.dataplane.schemalock.application;
