package io.reliabilityai.gateway.dataplane.ingress;

/**
 * The seam between the HTTP server and the request pipeline (Doc 30, AD-018).
 *
 * <p>The server never decides anything about a request beyond transport validity — it decodes,
 * bounds and hands off. Every admission decision belongs to the pipeline behind this interface,
 * which is what makes it structurally impossible for the ingress to bypass authentication,
 * governance or metering.
 */
@FunctionalInterface
public interface IngressHandler {

  /**
   * Runs one decoded request through the pipeline.
   *
   * @param request the decoded, bounded request
   * @return the pipeline outcome
   */
  IngressOutcome handle(IngressChatRequest request);
}
