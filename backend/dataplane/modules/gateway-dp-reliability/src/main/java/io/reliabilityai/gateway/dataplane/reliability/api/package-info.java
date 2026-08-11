/**
 * Public surface of the Reliability Engine (Doc 20 §6/§7): the inbound {@code
 * ReliabilityEnginePort}, the invocation plan/result/history model, and the
 * policy/budget/circuit/sleeper/metrics seams. Credential-free; all provider I/O via {@code
 * ProviderAdapterPort} (Doc 20 RE-D10).
 */
package io.reliabilityai.gateway.dataplane.reliability.api;
