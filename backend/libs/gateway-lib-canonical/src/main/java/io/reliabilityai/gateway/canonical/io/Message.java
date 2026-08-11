package io.reliabilityai.gateway.canonical.io;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A single provider-neutral conversation message within a {@link CanonicalRequest} (Doc 25 §6).
 * Content-bearing and never persisted (Doc 33 §10.2).
 *
 * @param role the message role (e.g. system, user, assistant, tool)
 * @param content the message content
 */
public record Message(String role, String content) {

  /** Compact constructor validating role and content presence. */
  public Message {
    Preconditions.requireNonBlank(role, "role");
    Preconditions.requireNonNull(content, "content");
  }
}
