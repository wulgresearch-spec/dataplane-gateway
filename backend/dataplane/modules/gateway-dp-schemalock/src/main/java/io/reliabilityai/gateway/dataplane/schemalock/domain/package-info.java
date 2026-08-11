/**
 * SchemaLock domain (C3, Doc 17 §5) — the pure, deterministic structured-output primitives: the
 * content-hash {@code SchemaId} (§10), the {@code Strategy}/{@code FailureClass} taxonomies
 * (§14/§25), the content-free {@code SchemaViolation}/{@code ValidationVerdict} (§22), the
 * fail-safe {@code CapabilityDescriptor} (§13.1), deterministic {@code StrategySelector} (§14), and
 * the closed value-preserving {@code ConservativeRepair} NR-1 allow-list (§23.1). No
 * wall-clock/random (R-063); no JSON-schema/validator library (SchemaValidatorPort's, SL-A2); no
 * provider identity (AD-007).
 */
package io.reliabilityai.gateway.dataplane.schemalock.domain;
