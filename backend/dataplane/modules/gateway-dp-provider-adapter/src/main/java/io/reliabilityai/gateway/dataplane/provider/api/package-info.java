/**
 * Provider Adapter API (C1, Doc 25 §7) — the outbound ports and translation SPI of the runtime
 * Anti-Corruption Layer: the pure {@code ProviderTranslator} mapping core (§23.1 DET-1), the impure
 * {@code ProviderTransportPort} shell (the only provider-coupled surface, PA-D1), the read-only
 * capability seam (§14.1), the short-lived credential seam (§33.1), content-free telemetry (§35),
 * and the neutral transport envelopes. Provider SDKs/types/names never appear here (AD-007).
 */
package io.reliabilityai.gateway.dataplane.provider.api;
