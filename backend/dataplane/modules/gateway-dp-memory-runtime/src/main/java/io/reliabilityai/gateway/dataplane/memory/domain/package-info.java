/**
 * The Memory Runtime's decision core (C17, AD-026).
 *
 * <p>Pure functions and immutable values. Nothing here reads a clock, performs I/O, or holds
 * mutable state that outlives a call — the same discipline the governance engine's domain layer
 * follows, and for the same reason: a decision core that can be tested by enumeration is one whose
 * merge algebra can actually be proven rather than sampled.
 *
 * <p>Policy merging, ranking and digests live here. Each is a total function of its inputs, so the
 * same question always gets the same answer.
 */
package io.reliabilityai.gateway.dataplane.memory.domain;
