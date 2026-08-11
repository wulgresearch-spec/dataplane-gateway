/**
 * Public seams of the event-fabric adapter (Doc 07): the broker/WAL/dead-letter ports, the queued
 * {@code BrokerRecord}, the typed {@code BrokerException}, and the bounded {@code RetryPolicy}. The
 * Kafka-class backbone and durable store are infrastructure behind these ports (never faked).
 */
package io.reliabilityai.gateway.dataplane.eventpublisher.api;
