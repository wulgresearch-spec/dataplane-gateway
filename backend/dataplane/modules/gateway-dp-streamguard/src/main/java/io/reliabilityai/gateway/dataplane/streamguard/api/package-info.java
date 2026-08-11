/**
 * StreamGuard API (C3, Doc 18 §7) — the transport-integrity contracts and ports: the inbound {@code
 * StreamGuardPort}/{@code TransportSession} pull interface, the neutral {@code FramingType}
 * taxonomy, the {@code TransportDelta}/{@code TransportVerdict} value objects, the injected {@code
 * StreamGuardPolicy}, the {@code TransportSourcePort} ingestion seam (raw provider bytes, no
 * provider SDK), and the content-free {@code TransportOutcomeSink}. Provider identity never appears
 * (AD-007); no schema/validation type (SchemaLock's, SG-A4).
 */
package io.reliabilityai.gateway.dataplane.streamguard.api;
