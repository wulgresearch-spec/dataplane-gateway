/**
 * Event envelope + Avro schemas (Doc 07 §6/§9). Content-free envelope carrying correlation +
 * causation ids for cross-service replay; ZL topics guarantee RPO=0 (Doc 07 ZL contract). Avro
 * schemas evolve backward-compatibly within a major version (Doc 07 §9 / Doc 16 D-033).
 */
package io.reliabilityai.gateway.events;
