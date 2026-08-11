/**
 * The credential materialization use-case (Doc 26 §3/§7/§8): resolve the cached C14 snapshot,
 * validate the lease contract (Doc 26 §N CLC), materialize a single-use zeroizable lease, and emit
 * a content-free audit record. Fail-closed on every failure or uncertainty (Doc 26 SP-INV/§21).
 */
package io.reliabilityai.gateway.dataplane.secrets.application;
