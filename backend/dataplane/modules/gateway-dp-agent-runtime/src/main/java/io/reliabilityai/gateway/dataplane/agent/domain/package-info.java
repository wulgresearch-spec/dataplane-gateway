/**
 * The Agent Runtime's decision core (C14, AD-025).
 *
 * <p>Pure functions over recorded data. Nothing here reads a clock, generates an identifier,
 * performs I/O, or holds mutable state that outlives a call — the constraints Temporal and Azure
 * Durable Functions place on an orchestrator, applied here as a package boundary rather than as
 * advice.
 *
 * <p>That is what makes crash recovery affordable: because the fold is deterministic, resuming a
 * run means replaying recorded results rather than re-invoking the model that produced them, so a
 * forty-step run that dies at step thirty-eight resumes for the price of a database read.
 */
package io.reliabilityai.gateway.dataplane.agent.domain;
