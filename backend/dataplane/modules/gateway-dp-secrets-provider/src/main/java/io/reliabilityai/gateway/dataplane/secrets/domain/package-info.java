/**
 * Secrets domain: the single-use, zeroizable {@code SanitizableCredentialLease} (Doc 26
 * §11.1/§17.1) that holds credential material only in a mutable {@code char[]} (never a {@code
 * String}) and wipes it deterministically on release. Framework-free; depends only on canonical
 * types and ports.
 */
package io.reliabilityai.gateway.dataplane.secrets.domain;
