/**
 * StreamGuard application (C3, Doc 18 §8) — the fail-closed session orchestrator ({@code
 * StreamSession}: ingest → frame → UTF-8 → sequence → dedup → completeness → liveness → bounded
 * buffer → emit → verdict, non-bypassable, §8/§53), the engine entry point ({@code
 * StreamGuardService}: sole ingestion point, fresh isolated session per request, §7/§35), and the
 * neutral framing-decoder factory ({@code FramingDecoderFactory}: keyed on {@code FramingType},
 * never provider identity, §11). Never validates schemas, assembles logical objects, or infers
 * content (SchemaLock's, §4/§43.1-SG).
 */
package io.reliabilityai.gateway.dataplane.streamguard.application;
