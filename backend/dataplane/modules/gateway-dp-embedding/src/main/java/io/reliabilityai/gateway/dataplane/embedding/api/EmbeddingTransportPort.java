package io.reliabilityai.gateway.dataplane.embedding.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only place in this module that may touch a network (AD-030 SS3.3).
 *
 * <p>Deliberately narrow, and deliberately owned here rather than reused from the request
 * pipeline's ProviderTransportPort. That port carries a credential lease, an attempt budget, a
 * pinned version and a route target, all of which belong to the inference path's routing and
 * reliability machinery. An embedding call has none of those concerns, and dragging them in would
 * couple this module to the whole request pipeline for no gain.
 *
 * <p>Narrowness is also what makes the adapters testable. A fake transport replaying recorded
 * provider responses exercises every line of an adapter, which matters because no real provider is
 * reachable from this environment — see AD-030 SS12.
 */
public interface EmbeddingTransportPort {

  /**
   * One HTTP exchange.
   *
   * @param call what to send
   * @return what came back
   * @throws EmbeddingTransportException when the exchange failed before a response
   */
  Exchange send(Call call);

  /**
   * An outbound call.
   *
   * @param method the HTTP method
   * @param url the absolute URL
   * @param headers the request headers, credentials included
   * @param body the encoded body
   * @param timeoutMillis how long to wait before giving up
   */
  record Call(
      String method, String url, Map<String, String> headers, byte[] body, long timeoutMillis) {

    /**
     * Copies the headers and the body so a caller cannot alter a call after handing it over.
     *
     * <p>{@code Map.copyOf} is deliberately not used for the headers. A null header value is a
     * supported input that the transport skips rather than sends, and {@code Map.copyOf} rejects
     * null values — so building a Call with an absent header would have started throwing
     * NullPointerException instead of omitting the header. The unmodifiable-wrapper form keeps that
     * behaviour and keeps header order stable for anything that reads them back.
     */
    public Call {
      // Assigned straight from the wrapper rather than through a ternary: SpotBugs recognises the
      // unmodifiable form only at the assignment it is judging, and a conditional whose branches
      // merge before the store defeats that.
      final Map<String, String> copiedHeaders = new LinkedHashMap<>();
      if (headers != null) {
        copiedHeaders.putAll(headers);
      }
      headers = Collections.unmodifiableMap(copiedHeaders);
      body = body == null ? new byte[0] : body.clone();
    }

    /**
     * The encoded body.
     *
     * @return a copy of the body bytes
     */
    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  /**
   * An inbound response.
   *
   * @param status the HTTP status
   * @param body the response body
   */
  record Exchange(int status, byte[] body) {

    /** Copies the body so a response cannot be altered after the transport returned it. */
    public Exchange {
      body = body == null ? new byte[0] : body.clone();
    }

    /**
     * The response body.
     *
     * @return a copy of the body bytes
     */
    @Override
    public byte[] body() {
      return body.clone();
    }
  }
}
