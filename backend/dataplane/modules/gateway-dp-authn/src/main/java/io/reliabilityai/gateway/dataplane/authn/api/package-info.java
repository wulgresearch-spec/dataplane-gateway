/**
 * Public surface of the C6 authentication node (Doc 37 §4/§7): the typed failure taxonomy ({@code
 * AuthenticationFailureReason}), the sealed {@code VerificationOutcome}, the content-free {@code
 * AuthenticationDecisionRecord} replay anchor, and the outbound seams ({@code
 * IdentityVerifierPort}, {@code AuthAuditPort}, {@code AuthMetricsPort}). No token/claim content or
 * secret ever appears here (Doc 37 IAU-A11/IAU-A9).
 */
package io.reliabilityai.gateway.dataplane.authn.api;
