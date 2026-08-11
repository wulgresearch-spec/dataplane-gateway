/**
 * SchemaLock API (C3, Doc 17 §6) — the inbound {@code SchemaLockPort}/{@code
 * StructuredStreamSession} contracts, the port-crossing value objects ({@code OutputSchema}, {@code
 * CompiledSchema}, {@code StructuredOutputRequest}, {@code CanonicalOutput}, {@code
 * SchemaLockPolicy}, {@code Mode}), and the outbound seams: the replaceable {@code
 * SchemaValidatorPort} (no validator library in SchemaLock, SL-A2), the neutral {@code
 * ProviderGenerationPort} (no provider SDK, SL-A1), the StreamGuard {@code StreamSourcePort}
 * (§43.1), the {@code RetryDecisionPort}, and the content-free {@code CorrectnessOutcomeSink}.
 */
package io.reliabilityai.gateway.dataplane.schemalock.api;
