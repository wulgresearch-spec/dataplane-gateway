package io.reliabilityai.gateway.dataplane.schemalock.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A schema identity = the SHA-256 content hash of the caller's schema text (Doc 17 §5/§10, SL-D5).
 * The compiled-schema cache is keyed on this content hash — <b>never</b> tenant identity (Doc 17
 * §10.1) — so two tenants with an identical schema safely share one immutable compiled artifact,
 * leaking nothing tenant-specific. Deterministic (Doc 17 §28).
 *
 * @param hash the lowercase hex SHA-256 of the schema text
 */
public record SchemaId(String hash) {

  /** Compact constructor validating the hash. */
  public SchemaId {
    Preconditions.requireNonBlank(hash, "hash");
  }

  /**
   * Computes the content-hash schema id from the schema text (Doc 17 §10).
   *
   * @param schemaText the caller's schema document text
   * @return the content-hash schema id
   */
  public static SchemaId of(final String schemaText) {
    Preconditions.requireNonNull(schemaText, "schemaText");
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(schemaText.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(digest.length * 2);
      for (final byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return new SchemaId(hex.toString());
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
