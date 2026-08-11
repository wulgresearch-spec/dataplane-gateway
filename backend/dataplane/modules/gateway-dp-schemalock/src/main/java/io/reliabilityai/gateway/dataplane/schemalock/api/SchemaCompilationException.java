package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A caller-schema compilation failure (Doc 17 §9/§25 {@code SCHEMA_INVALID}) — an
 * unsupported/ambiguous construct, a non-2020-12 dialect, a meta-schema-invalid document, or a
 * prohibited remote {@code $ref} (Doc 17 §33). This is a <b>caller error, failed fast at
 * compile</b> (never a runtime surprise at generation time). Content-free: it carries a neutral
 * reason, never schema text.
 */
public final class SchemaCompilationException extends Exception {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a schema-compilation failure.
   *
   * @param reason a content-free reason (no schema text)
   */
  public SchemaCompilationException(final String reason) {
    super(Preconditions.requireNonBlank(reason, "reason"));
  }
}
