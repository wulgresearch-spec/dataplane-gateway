/**
 * Single-VPS infrastructure adapters for the event-publisher ports (Doc 07). Each is a JDK-only, no
 * external-dependency realization of a frozen port — {@code InMemoryBroker} for {@code BrokerPort},
 * {@code LocalWal} for {@code WalPort}, {@code LocalFileDeadLetter} for {@code DeadLetterPort} —
 * kept behind the existing interfaces so a later swap to a Kafka-class backbone + durable WAL
 * changes only these adapters and the composition wiring, never the publisher or any caller (AD-002
 * / AD-009).
 */
package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;
