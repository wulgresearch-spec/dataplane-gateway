package io.reliabilityai.gateway.dataplane.schemalock.api;

/** The structured-output request mode (Doc 17 §5/§8) — full batch or incremental streaming. */
public enum Mode {
  /** Full non-streaming generation, completion-validated (Doc 17 §8 batch). */
  BATCH,
  /** Streaming generation, incrementally validated + terminal-gated (Doc 17 §8/§18 streaming). */
  STREAM
}
