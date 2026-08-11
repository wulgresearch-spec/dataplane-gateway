/**
 * The resilient event publisher use-case (Doc 07): idempotent id assignment, WAL-before-send for
 * zero-loss, bounded retry with fault classification, and DLQ routing — the production realization
 * of the frozen EventPublisherPort. Side-effect-free toward runtime decisions; content-free.
 */
package io.reliabilityai.gateway.dataplane.eventpublisher.application;
