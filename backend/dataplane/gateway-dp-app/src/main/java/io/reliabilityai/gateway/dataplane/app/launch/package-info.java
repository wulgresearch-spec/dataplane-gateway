/**
 * The process entry point and the operator-supplied wiring a single node needs to run.
 *
 * <p>Everything in this package answers questions the runtime deliberately refuses to answer for
 * itself: which provider, which tenant, which policy, which price list, which port. The runtime
 * composes components; this package decides what they are pointed at.
 */
package io.reliabilityai.gateway.dataplane.app.launch;
