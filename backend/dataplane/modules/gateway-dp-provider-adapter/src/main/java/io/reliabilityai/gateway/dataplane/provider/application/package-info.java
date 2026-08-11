/**
 * Provider Adapter application (C1, Doc 25 §8) — the provider-neutral Anti-Corruption coordinator
 * ({@code ProviderAdapterService}: consume-capability → acquire-credential → translate-out →
 * transport → translate-in, fail-closed throughout) and the data-driven dispatch registry ({@code
 * AdapterRegistry}: {@code providerRouteRef → adapter}, no provider-name branching, §7.1). Executes
 * exactly one attempt per {@code invoke} (§17.1 TO-5); authors no retry/timeout/failover policy
 * (Reliability's, Doc 20).
 */
package io.reliabilityai.gateway.dataplane.provider.application;
