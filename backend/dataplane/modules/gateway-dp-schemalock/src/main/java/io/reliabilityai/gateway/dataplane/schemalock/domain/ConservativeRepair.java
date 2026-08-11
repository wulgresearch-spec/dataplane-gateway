package io.reliabilityai.gateway.dataplane.schemalock.domain;

/**
 * The closed, value-preserving repair allow-list (Doc 17 §23/§23.1, SL-D3). Repair is <b>strictly
 * non-fabricating</b>: it performs only provably-safe, semantics-preserving <em>envelope</em>
 * normalizations and <b>never</b> invents, coerces, defaults, or alters a JSON value/key/type —
 * anything beyond that becomes a guided retry (Doc 17 §24), never a "creative repair" (SL-A14).
 *
 * <p>This class implements <b>NR-1</b> (Doc 17 §23.1): strip insignificant leading/trailing
 * whitespace and a code-fence wrapper (```` ```json … ``` ````) around a single unambiguous JSON
 * document. It operates purely on the text envelope and cannot touch any inner JSON value — this is
 * verified by the value-preservation property tests (Doc 17 §23.1). <b>NR-2…NR-5</b>
 * (newline/Unicode/escape/formatting canonicalization) are <em>parse-based</em> normalizations of
 * the already-parsed JSON and are performed by the JSON validator/formatter adapter behind {@code
 * SchemaValidatorPort} (they are value-identity reformattings of the parsed document, Doc 17
 * §23.1), not in this envelope-level, parser-free core. Deterministic (Doc 17 §28).
 */
public final class ConservativeRepair {

  private static final String FENCE = "```";

  private ConservativeRepair() {}

  /**
   * Applies NR-1 envelope normalization (Doc 17 §23.1): trims surrounding whitespace and strips a
   * single code-fence wrapper. Never alters any JSON value, key, or type.
   *
   * @param raw the raw provider output text
   * @return the envelope-normalized text (JSON content byte-identical)
   */
  public static String stripEnvelope(final String raw) {
    if (raw == null) {
      return null;
    }
    final String trimmed = raw.strip();
    if (!trimmed.startsWith(FENCE)) {
      return trimmed;
    }
    final int firstNewline = trimmed.indexOf('\n');
    if (firstNewline < 0) {
      return trimmed; // malformed single-line fence — leave untouched (never guess)
    }
    final String afterOpen = trimmed.substring(firstNewline + 1);
    final int closeFence = afterOpen.lastIndexOf(FENCE);
    if (closeFence < 0) {
      return trimmed; // no closing fence — not an unambiguous wrapper; leave untouched
    }
    return afterOpen.substring(0, closeFence).strip();
  }
}
