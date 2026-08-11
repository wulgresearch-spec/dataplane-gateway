package io.reliabilityai.gateway.dataplane.app.pipeline;

/**
 * What kind of call the caller made — the ingress endpoint's own answer, not an inference.
 *
 * <p>Governance gates several capabilities by operation: whether a tenant may generate images,
 * submit batches, run fine-tuning. Those gates need to know which operation is in flight, and the
 * only place that knows without guessing is the front door, because the operation <em>is</em> the
 * endpoint that was called. Deriving it later from the request's shape — "there is an image in the
 * messages, so this must be image generation" — would be a guess, and a guess in an admission gate
 * is a policy that fires on the wrong requests.
 *
 * <p>Deliberately an enum rather than a string: a closed set means a new operation cannot be
 * introduced without every capability mapping being revisited, which is the correct amount of
 * friction for something that decides what a tenant is allowed to do.
 */
public enum Operation {

  /** A conversational completion. */
  CHAT,

  /** A vector embedding request. */
  EMBEDDING,

  /** An image generation request. */
  IMAGE_GENERATION,

  /** An audio transcription or synthesis request. */
  AUDIO,

  /** A fine-tuning job submission. */
  FINE_TUNING,

  /** A batch submission. */
  BATCH
}
