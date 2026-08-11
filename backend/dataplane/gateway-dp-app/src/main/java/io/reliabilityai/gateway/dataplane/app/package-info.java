/**
 * The single data-plane deployable (AD-020, Doc 10 §5): the composition root and activation-DAG
 * startup validator (Doc 29 §DAG) that enforces AD-018 non-bypass by failing closed at startup when
 * any mandatory stage is unbound. Pure JVM; no framework, reflection, or auto-wiring.
 */
package io.reliabilityai.gateway.dataplane.app;
