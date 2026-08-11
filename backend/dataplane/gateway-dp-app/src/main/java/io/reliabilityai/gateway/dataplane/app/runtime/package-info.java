/**
 * Single-VPS composition root and runtime lifecycle (AD-020, Doc 10 §5, Doc 38 §5). This is the
 * outermost ring: the only place permitted to assemble application services with concrete local
 * adapters and own their start/stop lifecycle, using <b>explicit constructor wiring</b> — no
 * framework, reflection, {@code ServiceLoader}, or DI container.
 *
 * <p>It brings up the infrastructure spine that has real local adapters today — configuration
 * (last-known-good cache), secrets (local-master-key envelope unwrap), the event-publisher runtime
 * (in-process broker + local WAL + replay), and observability (local no-op sinks) — and gates full
 * pipeline activation through the frozen {@code GatewayDataPlaneApplication} validator, which fails
 * closed until every mandatory stage is bound (AD-018 non-bypass). Stages whose production adapters
 * are still external — provider transport, schema validation, identity verification — remain
 * honestly unbound rather than faked. Migrating to AWS swaps adapters at this ring only.
 */
package io.reliabilityai.gateway.dataplane.app.runtime;
