/**
 * The routing use-case (Doc 19 §8): resolve policy, hard-filter in canonical tier order,
 * soft-score, deterministically select, and emit an immutable decision or a fail-closed
 * binding-constraint failure.
 */
package io.reliabilityai.gateway.dataplane.router.application;
