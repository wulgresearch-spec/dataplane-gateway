package io.reliabilityai.gateway.dataplane.memory.pii;

/**
 * What kind of sensitivity a detection represents (AD-029 §4).
 *
 * <p>Category answers "what regime governs this", which is a different question from severity's
 * "how badly would disclosure hurt". They are kept separate because they genuinely vary
 * independently: a patient identifier and a national identifier are both {@link
 * PiiSeverity#CRITICAL}, but one pulls in health-data obligations and the other does not, and a
 * policy that wants to refuse health data outright while permitting national identifiers cannot
 * express that against severity alone.
 */
public enum PiiCategory {

  /** Identity, contact and location data about a natural person. */
  PERSONAL,

  /** Payment instruments, accounts, and tax identity. */
  FINANCIAL,

  /** Health, treatment, and anything that identifies a patient. */
  HEALTH,

  /** Case references, legal-hold material, privileged matter. */
  LEGAL,

  /** Key material, tokens, and other things whose disclosure is immediately exploitable. */
  SECRET,

  /** Credentials that authenticate a principal — passwords and the like. */
  AUTHENTICATION,

  /** Internal business data: employee and customer identifiers, internal references. */
  BUSINESS_CONFIDENTIAL,

  /**
   * More than one category was found.
   *
   * <p><b>A summary value only.</b> It is never attached to a span and no rule may declare it: a
   * span has exactly one category, and this exists so that a caller asking "what is this document"
   * gets a single honest answer when the document is mixed. {@code PiiDetectionTest} asserts that
   * no built-in or compiled rule can carry it, because a rule that did would erase the very
   * distinction the category dimension exists to make.
   */
  MULTIPLE
}
