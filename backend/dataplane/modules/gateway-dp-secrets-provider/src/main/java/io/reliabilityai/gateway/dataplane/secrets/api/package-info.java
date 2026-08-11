/**
 * Public surface of the data-plane Secrets Provider (C14, Doc 26 §7): the inbound {@code
 * SecretsProviderPort} realization and the outbound seams it consumes ({@code SecretSnapshotPort},
 * {@code CredentialMaterialSource}, {@code SecretsAuditPort}, {@code SecretsMetricsPort}) plus the
 * content-free {@code MaterializationRecord}. No credential value ever appears here (Doc 26
 * §20.1/RED).
 */
package io.reliabilityai.gateway.dataplane.secrets.api;
