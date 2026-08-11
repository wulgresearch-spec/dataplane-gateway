/**
 * The Plugin Runtime's decision logic (Doc 28 §D, §H).
 *
 * <p>Everything here is pure: state machine, dependency resolution, manifest validation, permission
 * evaluation, ordering and failure classification. No I/O, no clock, no threads, no mutable state.
 *
 * <p>That purity is what makes Doc 28's determinism requirements testable. Ordering (§POC) and
 * dispatch logic (§RPC RPC-5) have to be reproducible over recorded inputs, and a decision core
 * with no ambient state has nothing to be irreproducible about.
 */
package io.reliabilityai.gateway.dataplane.plugin.domain;
