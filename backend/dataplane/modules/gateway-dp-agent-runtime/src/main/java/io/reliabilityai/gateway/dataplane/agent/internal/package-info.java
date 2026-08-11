/**
 * Concrete adapters for the Agent Runtime's own ports (C14, AD-025).
 *
 * <p>The run store, the journal codec, the step read model, the plan registry and the metrics sink.
 * Nothing here is part of the public contract; callers depend on the interfaces in {@code
 * ...agent.api} so that a durable store can be swapped for a different one without touching the
 * runtime above it.
 *
 * <p>These adapters reach the filesystem and nothing else. There is no HTTP client, no database
 * driver and no provider SDK in this package, which is what keeps the module deployable on a single
 * host with no server dependencies.
 */
package io.reliabilityai.gateway.dataplane.agent.internal;
