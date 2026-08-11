/**
 * Composition (outermost) wiring for a single-VPS event-publisher runtime (Doc 07, AD-020). This
 * ring is the only place permitted to assemble the application service ({@code
 * ResilientEventPublisher}) with concrete adapters ({@code InMemoryBroker}, {@code LocalWal},
 * {@code LocalFileDeadLetter}) and manage their lifecycle; the direction stays inward (composition
 * → application/adapter → ports/domain). A later AWS deployment swaps the adapters here and nowhere
 * else.
 */
package io.reliabilityai.gateway.dataplane.eventpublisher.composition;
