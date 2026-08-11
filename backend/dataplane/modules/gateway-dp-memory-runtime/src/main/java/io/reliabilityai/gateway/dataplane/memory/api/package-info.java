/**
 * The Memory Runtime's public contracts (C17, AD-026).
 *
 * <p>Immutable value types and outbound ports only. Nothing here executes anything, and nothing
 * here knows what a database is.
 *
 * <p><b>What this package deliberately cannot reach.</b> There is no import of a provider type, a
 * database driver, a vector library, an embedding model, an agent-runtime type, a plugin-runtime
 * type or a governance type anywhere in this module. AD-026 MEM-1 (provider neutrality), MEM-2 (no
 * storage or vector logic) and MEM-5 (boundary) are therefore properties of the dependency graph
 * rather than rules a reviewer has to enforce.
 */
package io.reliabilityai.gateway.dataplane.memory.api;
