/**
 * Governance internals: bundle compilation, the generation store, the fold cache and the reload
 * path.
 *
 * <p>Everything here is in-memory and node-local. Nothing in this package opens a file, a socket or
 * a connection — the Governance Engine owns no persistent store (Doc 21 §34, GV-A10), and {@code
 * PolicyStore} is a reference cell, not a database. Durable policy lives in the control plane and
 * arrives through {@code PolicySourcePort}.
 */
package io.reliabilityai.gateway.dataplane.governance.internal;
