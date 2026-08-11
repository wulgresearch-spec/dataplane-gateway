package io.reliabilityai.gateway.dataplane.memory.pii;

/**
 * What a detection found (AD-029 §4).
 *
 * <p>Each type carries a default category and severity. A rule may override them — a tenant that
 * treats employee identifiers as {@link PiiSeverity#HIGH} is making a legitimate call about its own
 * data — but the defaults are what a deployment gets for doing nothing, so they are set to the
 * stricter reading wherever the answer is arguable.
 *
 * <p>The severities encode a specific judgement worth stating: identifiers that a person **cannot
 * change** (national identifier, passport, patient identifier) rank above identifiers they can
 * (email, phone). A leaked email address is a nuisance; a leaked national identifier is permanent.
 */
public enum PiiType {

  /**
   * A personal name. The weakest and noisiest detector here — see {@link #NAME} handling in AD-029
   * §6.3.
   */
  NAME(PiiCategory.PERSONAL, PiiSeverity.LOW),

  /** An email address. */
  EMAIL(PiiCategory.PERSONAL, PiiSeverity.MEDIUM),

  /** A telephone number. */
  PHONE(PiiCategory.PERSONAL, PiiSeverity.MEDIUM),

  /** A street or postal address. */
  POSTAL_ADDRESS(PiiCategory.PERSONAL, PiiSeverity.MEDIUM),

  /** A date of birth. */
  DATE_OF_BIRTH(PiiCategory.PERSONAL, PiiSeverity.HIGH),

  /** A latitude and longitude pair. */
  GPS_COORDINATE(PiiCategory.PERSONAL, PiiSeverity.MEDIUM),

  /** An IPv4 or IPv6 address. */
  IP_ADDRESS(PiiCategory.PERSONAL, PiiSeverity.LOW),

  /** A hardware address. */
  MAC_ADDRESS(PiiCategory.PERSONAL, PiiSeverity.LOW),

  /** A government identity number: Aadhaar, SSN, national insurance and equivalents. */
  NATIONAL_ID(PiiCategory.PERSONAL, PiiSeverity.CRITICAL),

  /** A passport number. */
  PASSPORT_NUMBER(PiiCategory.PERSONAL, PiiSeverity.CRITICAL),

  /** A driving licence number. */
  DRIVER_LICENSE(PiiCategory.PERSONAL, PiiSeverity.HIGH),

  /** A tax identity: PAN, TIN, VAT and equivalents. */
  TAX_ID(PiiCategory.FINANCIAL, PiiSeverity.CRITICAL),

  /** A payment card number. */
  CREDIT_CARD(PiiCategory.FINANCIAL, PiiSeverity.CRITICAL),

  /** A bank account, IBAN or routing identifier. */
  BANK_ACCOUNT(PiiCategory.FINANCIAL, PiiSeverity.CRITICAL),

  /** A unified payments interface handle. */
  UPI_ID(PiiCategory.FINANCIAL, PiiSeverity.HIGH),

  /** A medical record number. */
  MEDICAL_RECORD_NUMBER(PiiCategory.HEALTH, PiiSeverity.CRITICAL),

  /** A health insurance membership or policy number. */
  INSURANCE_NUMBER(PiiCategory.HEALTH, PiiSeverity.HIGH),

  /** A patient identifier. */
  PATIENT_ID(PiiCategory.HEALTH, PiiSeverity.CRITICAL),

  /** A legal case or matter reference. */
  LEGAL_CASE_REFERENCE(PiiCategory.LEGAL, PiiSeverity.HIGH),

  /** An internal employee identifier. */
  EMPLOYEE_ID(PiiCategory.BUSINESS_CONFIDENTIAL, PiiSeverity.LOW),

  /** An internal customer or account identifier. */
  CUSTOMER_ID(PiiCategory.BUSINESS_CONFIDENTIAL, PiiSeverity.LOW),

  /** A password or other authenticating credential. */
  CREDENTIAL(PiiCategory.AUTHENTICATION, PiiSeverity.CRITICAL),

  /** An API key, bearer token, or private key block. */
  SECRET_KEY(PiiCategory.SECRET, PiiSeverity.CRITICAL),

  /** Whatever a tenant or organization rule declares; category and severity come from the rule. */
  CUSTOM(PiiCategory.BUSINESS_CONFIDENTIAL, PiiSeverity.MEDIUM);

  private final PiiCategory defaultCategory;

  private final PiiSeverity defaultSeverity;

  /**
   * Creates a type.
   *
   * @param defaultCategory the regime this normally falls under
   * @param defaultSeverity the harm this normally represents
   */
  PiiType(final PiiCategory defaultCategory, final PiiSeverity defaultSeverity) {
    this.defaultCategory = defaultCategory;
    this.defaultSeverity = defaultSeverity;
  }

  /**
   * The category a rule gets unless it says otherwise.
   *
   * @return the default category
   */
  public PiiCategory defaultCategory() {
    return defaultCategory;
  }

  /**
   * The severity a rule gets unless it says otherwise.
   *
   * @return the default severity
   */
  public PiiSeverity defaultSeverity() {
    return defaultSeverity;
  }
}
