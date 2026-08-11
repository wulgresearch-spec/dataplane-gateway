package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;

/**
 * The <b>deterministic pure mapping core</b> (Doc 25 §23.1 DET-1) — the provider-specific
 * Anti-Corruption translation, one implementation per provider (AD-002/AD-007). It is a set of
 * <b>pure functions</b> of {@code (canonical input | provider-native bytes, pinned versions)} — no
 * I/O, no wall-clock, no random (Doc 11 R-063). The core <b>never</b> performs transport (that is
 * {@link ProviderTransportPort}), <b>never</b> decides retry/timeout/failover policy (that is
 * Reliability, Doc 25 §17.1), <b>never</b> validates tool calls (SchemaLock, Doc 17), and
 * <b>never</b> extracts runtime usage (StreamGuard, Doc 25 §20.1). On any untranslatable field it
 * fails closed — the coordinator surfaces a canonical error rather than a guessed object (PA-INV).
 *
 * <p>A translator that needs deterministic time in its mapping (Doc 25 §23.1 DET-1 "injected
 * clock") takes a {@link io.reliabilityai.gateway.ports.ClockPort} via its own constructor; time is
 * never a hidden wall-clock read (R-063).
 */
public interface ProviderTranslator {

  /**
   * Translate-out: canonical request → provider-native transport request (Doc 25 §8/§9). Pure. Maps
   * params/messages/tool defs per the read-only {@link CapabilityMapping}; an unmappable field must
   * fail closed (throw), surfaced by the coordinator as {@code malformed_response} (Doc 25 §26).
   *
   * @param request the canonical request
   * @param capabilities the read-only capability mapping (consumed only, Doc 25 §14.1)
   * @param pinnedVersion the pinned provider API version (Doc 25 PA-D10)
   * @return the neutral transport request envelope
   */
  TransportRequest translateOut(
      CanonicalRequest request, CapabilityMapping capabilities, PinnedVersion pinnedVersion);

  /**
   * Translate-in: provider-native transport response → canonical outcome (Doc 25 §8/§10/§11). Pure.
   * Returns a unary response, a canonical stream, or a fail-closed {@link CanonicalError} (e.g. a
   * non-success provider status). Usage is transport-format normalized only (never fabricated, Doc
   * 25 §20.1); tool-call arguments are raw and unvalidated (Doc 25 §PA-D5). An untranslatable
   * response must fail closed (throw), surfaced as {@code malformed_response} (Doc 25 §26).
   *
   * @param response the neutral transport response envelope
   * @return the canonical invocation result (unary / streaming / failed)
   */
  ProviderInvocationResult translateIn(TransportResponse response);

  /**
   * Classify a transport-layer failure into a canonical error <em>shape</em> (Doc 25 §16.1). Pure;
   * may reuse {@code TransportFailureClassifier}. The retry <em>decision</em> is Reliability's
   * (§17.1 TO-3).
   *
   * @param failure the transport failure
   * @return the canonical error (advisory hints only)
   */
  CanonicalError classifyTransportFailure(TransportException failure);
}
