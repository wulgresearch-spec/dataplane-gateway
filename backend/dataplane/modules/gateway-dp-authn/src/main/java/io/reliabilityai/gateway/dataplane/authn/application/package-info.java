/**
 * The C6 authentication use-case (Doc 37 §6): pin verification keys → verify forwarded identity →
 * resolve tenant scope post-auth → record a content-free decision. Authentication only, never
 * authorization (Doc 37 §ANZ); no secret (IAU-A9); no online IdP (VKR-3); fail-closed on every
 * uncertainty (Doc 37 §14).
 */
package io.reliabilityai.gateway.dataplane.authn.application;
