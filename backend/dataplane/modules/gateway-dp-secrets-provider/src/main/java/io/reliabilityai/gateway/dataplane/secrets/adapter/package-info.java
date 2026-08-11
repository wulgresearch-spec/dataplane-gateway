/**
 * Secrets adapters (Doc 26 §7, AD-022): the last-known-good credential-snapshot cache (the DP
 * Secret-cache node, Doc 26 §12/§13), its clone-on-write/clone-on-read master material source, and
 * the {@code SystemClock} time seam. JDK-only; no framework, provider SDK, or persistence.
 */
package io.reliabilityai.gateway.dataplane.secrets.adapter;
