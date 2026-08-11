package io.reliabilityai.gateway.dataplane.ingress;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-free request counters, rendered as plain text by {@code /metrics} (Doc 27 OT-INV).
 *
 * <p>No Prometheus client, no registry, no dependency — the exposition format is a few lines of
 * {@code name value} that any scraper can read. Counters are passive: nothing in the request path
 * reads them, so removing this class would change no behaviour.
 */
public final class IngressMetrics {

  private final AtomicLong received = new AtomicLong();
  private final AtomicLong completed = new AtomicLong();
  private final AtomicLong refused = new AtomicLong();
  private final AtomicLong inFlight = new AtomicLong();
  private final Map<Integer, AtomicLong> byStatus = new ConcurrentHashMap<>();

  /** Records a request arriving. */
  void onReceived() {
    received.incrementAndGet();
    inFlight.incrementAndGet();
  }

  /**
   * Records a request finishing.
   *
   * @param status the HTTP status returned
   */
  void onCompleted(final int status) {
    inFlight.decrementAndGet();
    byStatus.computeIfAbsent(status, key -> new AtomicLong()).incrementAndGet();
    if (status >= 200 && status < 300) {
      completed.incrementAndGet();
    } else {
      refused.incrementAndGet();
    }
  }

  /**
   * The number of requests currently being served — the signal that reveals a leak.
   *
   * @return the in-flight count
   */
  public long inFlight() {
    return inFlight.get();
  }

  /**
   * The number of requests received since startup.
   *
   * @return the received count
   */
  public long received() {
    return received.get();
  }

  /**
   * Renders the counters in a scrape-friendly text format, with status codes in ascending order so
   * two scrapes of an unchanged process produce identical bytes.
   *
   * @return the exposition text
   */
  public String render() {
    final StringBuilder out = new StringBuilder(256);
    out.append("gateway_ingress_requests_received ").append(received.get()).append('\n');
    out.append("gateway_ingress_requests_completed ").append(completed.get()).append('\n');
    out.append("gateway_ingress_requests_refused ").append(refused.get()).append('\n');
    out.append("gateway_ingress_requests_in_flight ").append(inFlight.get()).append('\n');
    for (final Map.Entry<Integer, AtomicLong> entry : new TreeMap<>(byStatus).entrySet()) {
      out.append("gateway_ingress_responses{status=\"")
          .append(entry.getKey())
          .append("\"} ")
          .append(entry.getValue().get())
          .append('\n');
    }
    return out.toString();
  }
}
