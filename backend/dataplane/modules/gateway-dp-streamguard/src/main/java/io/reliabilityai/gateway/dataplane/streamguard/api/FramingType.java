package io.reliabilityai.gateway.dataplane.streamguard.api;

/**
 * The closed, provider-neutral transport-framing taxonomy (Doc 18 §6/§11, SG-D2). These are
 * <b>transport formats, not provider identities</b> — StreamGuard decodes "SSE," never "OpenAI"
 * (AD-007). A new provider maps to an existing or one new framing type, never a StreamGuard
 * rewrite. An unknown/unsupported framing fails closed ({@code UNSUPPORTED_FRAMING}, Doc 18 §11).
 */
public enum FramingType {
  /** Server-Sent Events (line-based; OpenAI/Anthropic/Azure/Gemini/OpenRouter/LiteLLM). */
  SSE,
  /** AWS binary event-stream (prelude + headers + payload + CRC; Bedrock). */
  AWS_EVENT_STREAM,
  /** A streamed JSON array delivered in chunks. */
  JSON_ARRAY_CHUNKED,
  /** Newline-delimited JSON. */
  NDJSON
}
