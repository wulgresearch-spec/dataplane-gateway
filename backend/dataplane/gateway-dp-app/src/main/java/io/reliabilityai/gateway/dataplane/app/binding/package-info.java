/**
 * Composition-root bindings: the small, explicit adapters that connect a module's driven port to
 * something that already exists on the single VPS (an immutable snapshot supplied by the operator,
 * an in-process counter, or the durable event publisher). They contain no transport, no I/O beyond
 * the publisher, and no policy decisions — every decision stays in the module that owns it.
 *
 * <p>These are deliberately <b>not</b> infrastructure adapters: there is no Kafka, no AWS, no Redis
 * and no framework here. On migration to AWS the modules and these bindings stay put; only the
 * durable adapters behind the publisher change (AD-020).
 */
package io.reliabilityai.gateway.dataplane.app.binding;
