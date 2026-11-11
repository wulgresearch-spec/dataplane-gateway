package io.reliabilityai.gateway.dataplane.plugin.api;

/** Shared event-payload validation without expanding the public plugin-event API. */
final class PluginEventPayloadBounds {

  private PluginEventPayloadBounds() {}

  static void requireBounded(final String payload) {
    if (payload.length() > PluginEvent.MAX_PAYLOAD_LENGTH) {
      throw new IllegalArgumentException(
          "event payload exceeds " + PluginEvent.MAX_PAYLOAD_LENGTH);
    }
  }
}
