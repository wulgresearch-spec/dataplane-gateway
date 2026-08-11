/**
 * StreamGuard domain (C3, Doc 18 §6) — the pure, deterministic transport-integrity primitives:
 * strict incremental UTF-8 validation ({@code Utf8Validator}, reject-not-substitute, §13), bounded
 * hardened framing decoders ({@code SseFramingDecoder}/{@code NdjsonFramingDecoder}, §15), minimum
 * structural JSON scanning ({@code JsonStructuralScanner}, §20.1), monotonic sequencing ({@code
 * Sequencer}, §16), bounded duplicate suppression ({@code DedupWindow}/{@code IntegrityCheckpoint},
 * §17), the {@code TransportFailureClass} taxonomy (§22), and the internal fail-closed signal. No
 * wall-clock/random (R-063); no schema/semantic interpretation (SG-A18).
 */
package io.reliabilityai.gateway.dataplane.streamguard.domain;
