/**
 * The Agent Runtime's public contracts (C14, AD-025).
 *
 * <p>Immutable value types and outbound ports only. Nothing here executes anything.
 *
 * <p><b>What this package deliberately cannot reach.</b> There is no import of a provider type, a
 * pipeline type, a plugin-runtime type or a governance type anywhere in this module. AD-025 AGT-1
 * (no direct provider call), AGT-2 (no direct pipeline invocation) and AGT-4 (no direct tool
 * execution) are therefore properties of the dependency graph rather than rules a reviewer has to
 * enforce. Every outbound edge is a port here, and every adapter lives in the composition root.
 */
package io.reliabilityai.gateway.dataplane.agent.api;
