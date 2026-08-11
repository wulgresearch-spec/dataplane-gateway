/**
 * Provider Adapter domain (C1, Doc 25 §16.1) — pure, provider-neutral classification helpers
 * reusable by provider translators: {@code HttpStatusErrorClassifier} (HTTP status → canonical
 * error category, the frozen §16.1 table) and {@code TransportFailureClassifier} ({@code
 * TransportFailureKind} → canonical error shape). No I/O, no wall-clock, no random (Doc 11 R-063);
 * advisory hints only — the retry decision is Reliability's (Doc 20, §17.1 TO-3).
 */
package io.reliabilityai.gateway.dataplane.provider.domain;
