/**
 * Governance Engine ports and value objects (Doc 21 §7). The inbound {@link
 * io.reliabilityai.gateway.dataplane.governance.api.GovernanceEnginePort} is called by the pipeline
 * between authentication and routing; every outbound port is a read of an already-cached snapshot
 * or a write to an audit sink. No port here performs network I/O, touches credentials, or names a
 * provider.
 */
package io.reliabilityai.gateway.dataplane.governance.api;
