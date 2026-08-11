package io.reliabilityai.gateway.dataplane.app.ingress;

import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.app.pipeline.CanonicalStream;
import io.reliabilityai.gateway.dataplane.app.pipeline.PipelineOutcome;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestPipeline;
import io.reliabilityai.gateway.dataplane.app.runtime.IngressLifecycle;
import io.reliabilityai.gateway.dataplane.ingress.HttpIngressServer;
import io.reliabilityai.gateway.dataplane.ingress.IngressChatRequest;
import io.reliabilityai.gateway.dataplane.ingress.IngressConfig;
import io.reliabilityai.gateway.dataplane.ingress.IngressHandler;
import io.reliabilityai.gateway.dataplane.ingress.IngressMetrics;
import io.reliabilityai.gateway.dataplane.ingress.IngressOutcome;
import io.reliabilityai.gateway.dataplane.ingress.IngressStream;
import io.reliabilityai.gateway.dataplane.ingress.RuntimeStateProbe;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import io.reliabilityai.gateway.ports.ClockPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Binds the HTTP server to the request pipeline (Doc 30, AD-020).
 *
 * <p>The only translation layer between an HTTP request and the frozen pipeline: it converts a
 * decoded {@link IngressChatRequest} into a {@link RequestExecution.Inbound} and hands the result
 * back as an {@link IngressOutcome}. It makes no admission decision of its own — authentication,
 * governance, routing, credentials, metering and cost all happen inside the pipeline it calls, so
 * there is no edge path that reaches a provider without them (AD-018).
 *
 * <p>Implements {@link IngressLifecycle}, so the runtime opens it last during startup and closes it
 * first during shutdown.
 */
public final class PipelineIngress implements IngressLifecycle, IngressHandler {

  private final HttpIngressServer server;
  private final RequestPipeline pipeline;
  private final ClockPort clock;
  private final Region region;
  private final java.time.Duration requestDeadline;
  private final long costCeilingMicros;

  /**
   * Creates the ingress.
   *
   * @param config the ingress wiring
   * @param pipeline the request pipeline every request must traverse
   * @param probe the runtime lifecycle probe backing {@code /health} and {@code /ready}
   * @param clock the injected clock, used to derive each request's deadline
   * @param region the node's region, stamped onto every request context
   * @param costCeilingMicros the per-request cost ceiling handed to the router
   */
  public PipelineIngress(
      final IngressConfig config,
      final RequestPipeline pipeline,
      final RuntimeStateProbe probe,
      final ClockPort clock,
      final Region region,
      final long costCeilingMicros) {
    Preconditions.requireNonNull(config, "config");
    this.pipeline = Preconditions.requireNonNull(pipeline, "pipeline");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.region = Preconditions.requireNonNull(region, "region");
    this.requestDeadline = config.requestTimeout();
    this.costCeilingMicros = costCeilingMicros;
    this.server = new HttpIngressServer(config, this, Preconditions.requireNonNull(probe, "probe"));
  }

  @Override
  public void startAccepting() {
    server.start();
  }

  @Override
  public void stopAccepting() {
    server.stop();
  }

  /**
   * The bound port, resolved after the server has started.
   *
   * @return the bound port
   */
  public int boundPort() {
    return server.boundPort();
  }

  /**
   * The ingress request counters.
   *
   * @return the metrics
   */
  public IngressMetrics metrics() {
    return server.metrics();
  }

  @Override
  public IngressOutcome handle(final IngressChatRequest request) {
    Preconditions.requireNonNull(request, "request");
    final PipelineOutcome outcome = pipeline.execute(toInbound(request));
    if (outcome instanceof PipelineOutcome.Completed completed) {
      return new IngressOutcome.Completed(completed.response());
    }
    if (outcome instanceof PipelineOutcome.Streamed streamed) {
      // A thin adapter, not a copy: the server pulls straight from the guarded pipeline stream, so
      // backpressure and cancellation both reach the provider unchanged.
      final CanonicalStream canonical = streamed.stream();
      return new IngressOutcome.Streamed(
          new IngressStream() {
            @Override
            public io.reliabilityai.gateway.canonical.stream.StreamChunk next() {
              return canonical.next();
            }

            @Override
            public boolean finished() {
              return canonical.finished();
            }

            @Override
            public void cancel(final String reason) {
              canonical.cancel(reason);
            }
          });
    }
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    return new IngressOutcome.Refused(refused.refusal().stage().name(), refused.refusal().error());
  }

  private RequestExecution.Inbound toInbound(final IngressChatRequest request) {
    final boolean streaming = "true".equals(request.params().get("stream"));
    final List<Message> messages = new ArrayList<>(request.messages().size());
    for (final IngressChatRequest.IngressMessage message : request.messages()) {
      messages.add(new Message(message.role(), message.content()));
    }

    final CorrelationId correlationId = new CorrelationId(request.correlationId());
    return new RequestExecution.Inbound(
        new RequestId(request.correlationId()),
        new RequestContext(
            correlationId,
            new IdempotencyKey(request.idempotencyKey()),
            new CausationId(request.correlationId()),
            "",
            region),
        // The raw credential is forwarded for AUTHN to verify; the ingress never inspects it.
        new ForwardedTransportIdentity(
            "Bearer", request.authorization() == null ? "" : request.authorization(), Map.of()),
        new CanonicalRequest(
            new CanonicalModelId(request.model()),
            List.copyOf(messages),
            List.of(),
            request.params()),
        Set.of("chat"),
        0,
        Set.of(),
        costCeilingMicros,
        Set.of(),
        clock.now().plus(requestDeadline),
        true,
        streaming ? Mode.STREAM : Mode.BATCH,
        Optional.empty(),
        Optional.empty());
  }
}
