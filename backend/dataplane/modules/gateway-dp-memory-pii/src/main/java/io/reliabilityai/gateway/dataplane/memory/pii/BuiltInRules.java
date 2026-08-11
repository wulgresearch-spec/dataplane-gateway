package io.reliabilityai.gateway.dataplane.memory.pii;

import java.util.List;

/**
 * The rules a deployment gets for doing nothing (AD-029 §6).
 *
 * <p>Three design commitments run through all of them.
 *
 * <p><b>Boundaries, not bare patterns.</b> Nearly every rule is fenced with lookaround so that a
 * ten-digit phone number does not match inside a twenty-digit transaction identifier. Unfenced
 * patterns are the single largest source of false positives in detectors of this kind.
 *
 * <p><b>Checksums where they exist.</b> Card numbers get Luhn, IBANs get mod-97, Aadhaar gets
 * Verhoeff, addresses get range checks. See {@link PiiValidator} for why this matters more than the
 * pattern does.
 *
 * <p><b>Context where checksums do not exist.</b> A medical record number has no checksum and no
 * distinctive shape — {@code A1234567} could be anything. Those rules require a nearby label, which
 * trades recall for precision deliberately: an unlabelled identifier will be missed. That is stated
 * here and in AD-029 §10 rather than hidden behind an impressive-sounding rule count.
 *
 * <p>Bounded repetition is used throughout, so no built-in pattern can backtrack catastrophically.
 * The step budget in {@link ScanBudget} exists for tenant-supplied rules, not for these.
 */
public final class BuiltInRules {

  private BuiltInRules() {}

  /**
   * Every built-in rule.
   *
   * @return the rules, which the compiler will order by priority
   */
  public static List<PiiRule> all() {
    return List.of(
        // ---- Contact -------------------------------------------------------------------------
        PiiRule.detecting(
                "builtin.email",
                PiiType.EMAIL,
                "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}"
                    + "(?:\\.[A-Za-z0-9-]{1,63}){0,4}\\.[A-Za-z]{2,24}(?![A-Za-z0-9.-])",
                PiiValidator.NONE)
            .scoring(0.95)
            .requiring("@"),

        // Phone numbers are split by shape rather than covered by one permissive pattern. A single
        // rule loose enough to match every national format also matches any run of grouped digits —
        // the first version of this rule reported a Luhn-failing card number and an Aadhaar-shaped
        // identifier as phone numbers. Each shape below is fenced so it cannot match inside a
        // longer
        // group run, which is what those false positives actually were.
        PiiRule.detecting(
                "builtin.phone.international",
                PiiType.PHONE,
                "(?<![\\w.+])\\+\\d{1,3}[ .-]?\\d{2,5}[ .-]?\\d{3,5}(?:[ .-]?\\d{1,5})?(?![\\w])(?!\\.\\d)",
                PiiValidator.NONE)
            .scoring(0.85, "phone", "mobile", "tel", "call", "contact")
            .requiring("+")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.phone.grouped",
                PiiType.PHONE,
                "(?<![\\w.])(?<!\\d[ .-])(?:\\(\\d{3}\\)[ .-]?|\\d{3}[ .-])\\d{3}[ .-]\\d{4}"
                    + "(?![\\w])(?!\\.\\d)(?![ .-]\\d)",
                PiiValidator.NONE)
            .scoring(0.75, "phone", "mobile", "tel", "call", "contact")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.phone.grouped.five",
                PiiType.PHONE,
                "(?<![\\w.])(?<!\\d[ .-])\\d{5}[ .-]\\d{5}(?![\\w])(?!\\.\\d)(?![ .-]\\d)",
                PiiValidator.NONE)
            .scoring(0.6, "phone", "mobile", "tel", "call", "contact")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.phone.plain",
                PiiType.PHONE,
                "(?<![\\w.+])(?:\\+\\d{1,3}[ -]?)?\\d{10}(?![\\w])(?!\\.\\d)",
                PiiValidator.NONE)
            .scoring(0.55, "phone", "mobile", "tel", "call", "contact")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.postal.address",
                PiiType.POSTAL_ADDRESS,
                "(?<!\\w)\\d{1,5}[ ,]+(?:[A-Z][A-Za-z]{1,20}[ ,]+){1,4}"
                    + "(?:Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Boulevard|Blvd|Drive|Dr|Court|Ct|Way)"
                    + "(?![A-Za-z])",
                PiiValidator.NONE)
            .scoring(0.75, "address", "residence", "lives at", "shipping", "billing")
            .requiringDigit(),

        // ---- Network -------------------------------------------------------------------------
        PiiRule.detecting(
                "builtin.ipv4",
                PiiType.IP_ADDRESS,
                // The trailing fence rejects a fifth component but tolerates a sentence-ending full
                // stop. The lookbehind list is the residual false-positive class: a section or
                // version number is shaped exactly like a dotted quad and no checksum separates
                // them, so the introducing word is the only signal available. See AD-029 §10.2.
                "(?<![\\d.])(?<![A-Za-z])"
                    + "(?<!section )(?<!version )(?<!chapter )(?<!clause )(?<!figure )"
                    + "(?<!table )(?<!step )(?<!item )(?<!para )(?<!rev )"
                    + "\\d{1,3}(?:\\.\\d{1,3}){3}(?!\\.?\\d)",
                PiiValidator.IPV4_RANGE)
            .scoring(0.9)
            .requiring(".")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.ipv6",
                PiiType.IP_ADDRESS,
                "(?<![:\\w])(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}(?![:\\w])",
                PiiValidator.NONE)
            .scoring(0.9)
            .requiring(":"),
        PiiRule.detecting(
                "builtin.mac",
                PiiType.MAC_ADDRESS,
                "(?<![\\w:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![\\w:-])",
                PiiValidator.NONE)
            .scoring(0.9)
            .requiring(":", "-"),

        // ---- Location and dates ---------------------------------------------------------------
        PiiRule.detecting(
                "builtin.gps",
                PiiType.GPS_COORDINATE,
                "(?<![\\d.-])[-+]?\\d{1,2}\\.\\d{3,8}\\s{0,3},\\s{0,3}[-+]?\\d{1,3}\\.\\d{3,8}"
                    + "(?!\\.?\\d)",
                PiiValidator.GPS_RANGE)
            .scoring(0.85, "location", "coordinates", "lat", "lon", "gps")
            .requiring(",")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.dob.iso",
                PiiType.DATE_OF_BIRTH,
                "(?<![\\d-])(?:19|20)\\d{2}[-/](?:0[1-9]|1[0-2])[-/](?:0[1-9]|[12]\\d|3[01])"
                    + "(?![\\d-])",
                PiiValidator.PLAUSIBLE_DATE)
            // A bare date is only a date of birth in context; without one this is any timestamp.
            .scoring(0.35, "dob", "date of birth", "born", "birthday", "birthdate")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.dob.civil",
                PiiType.DATE_OF_BIRTH,
                "(?<![\\d/-])(?:0[1-9]|[12]\\d|3[01])[-/](?:0[1-9]|1[0-2])[-/](?:19|20)\\d{2}"
                    + "(?![\\d/-])",
                PiiValidator.PLAUSIBLE_DATE)
            .scoring(0.35, "dob", "date of birth", "born", "birthday", "birthdate")
            .requiringDigit(),

        // ---- Government identity ---------------------------------------------------------------
        PiiRule.detecting(
                "builtin.aadhaar",
                PiiType.NATIONAL_ID,
                "(?<!\\d)[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)",
                PiiValidator.VERHOEFF)
            .scoring(0.9, "aadhaar", "uidai", "national id")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.ssn",
                PiiType.NATIONAL_ID,
                "(?<!\\d)(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}(?!\\d)",
                PiiValidator.NONE)
            .scoring(0.9, "ssn", "social security")
            .requiring("-")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.pan",
                PiiType.TAX_ID,
                "(?<![A-Za-z0-9])[A-Z]{5}\\d{4}[A-Z](?![A-Za-z0-9])",
                PiiValidator.NONE)
            .scoring(0.85, "pan", "tax", "income tax")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.passport",
                PiiType.PASSPORT_NUMBER,
                "(?<![A-Za-z0-9])[A-PR-WYa-pr-wy][1-9]\\d{6}(?![A-Za-z0-9])",
                PiiValidator.NONE)
            // Shape alone is far too common; this rule earns its severity only with a label nearby.
            .scoring(0.4, "passport")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.driver.license",
                PiiType.DRIVER_LICENSE,
                "(?i)(?:driver'?s?\\s{1,3}licen[cs]e|driving\\s{1,3}licen[cs]e|\\bDL)"
                    + "\\s{0,3}(?:no\\.?|number|#)?\\s{0,3}[:#-]?\\s{0,3}[A-Z0-9-]{6,20}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.8)
            .requiring("licen", "dl"),

        // ---- Financial
        // ---------------------------------------------------------------------------
        PiiRule.detecting(
                "builtin.credit.card",
                PiiType.CREDIT_CARD,
                "(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])",
                PiiValidator.LUHN)
            .scoring(0.9, "card", "visa", "mastercard", "amex", "payment")
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.iban",
                PiiType.BANK_ACCOUNT,
                "(?<![A-Za-z0-9])[A-Z]{2}\\d{2}[ ]?(?:[A-Z0-9]{4}[ ]?){2,7}[A-Z0-9]{1,4}"
                    + "(?![A-Za-z0-9])",
                PiiValidator.IBAN_MOD97)
            .scoring(0.95)
            .requiringDigit(),
        PiiRule.detecting(
                "builtin.bank.account",
                PiiType.BANK_ACCOUNT,
                "(?i)(?:account\\s{0,3}(?:no\\.?|number)|a/c)\\s{0,3}[:#-]?\\s{0,3}\\d{8,18}"
                    + "(?!\\d)",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.85)
            .requiring("account", "a/c"),
        PiiRule.detecting(
                "builtin.upi",
                PiiType.UPI_ID,
                "(?<![\\w.])[a-zA-Z0-9][a-zA-Z0-9._-]{1,64}@"
                    + "(?:okhdfcbank|oksbi|okaxis|okicici|paytm|ybl|upi|apl|ibl|axl|airtel|freecharge)"
                    + "(?![\\w.])",
                PiiValidator.NONE)
            .scoring(0.95)
            .requiring("@"),

        // ---- Health
        // --------------------------------------------------------------------------------
        PiiRule.detecting(
                "builtin.medical.record",
                PiiType.MEDICAL_RECORD_NUMBER,
                "(?i)(?:MRN|medical\\s{1,3}record\\s{1,3}(?:no\\.?|number|#))"
                    + "\\s{0,3}[:#-]?\\s{0,3}[A-Z0-9-]{4,20}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.9)
            .requiring("mrn", "medical record"),
        PiiRule.detecting(
                "builtin.patient.id",
                PiiType.PATIENT_ID,
                "(?i)patient\\s{0,3}(?:id|identifier|no\\.?|number|#)"
                    + "\\s{0,3}[:#-]?\\s{0,3}[A-Z0-9-]{3,20}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.9)
            .requiring("patient"),
        PiiRule.detecting(
                "builtin.insurance",
                PiiType.INSURANCE_NUMBER,
                "(?i)(?:policy|insurance|member)\\s{0,3}(?:id|no\\.?|number|#)"
                    + "\\s{0,3}[:#-]?\\s{0,3}[A-Z0-9-]{5,24}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.8)
            .requiring("policy", "insurance", "member"),

        // ---- Legal and internal
        // ---------------------------------------------------------------------
        PiiRule.detecting(
                "builtin.legal.case",
                PiiType.LEGAL_CASE_REFERENCE,
                "(?i)(?:case|matter|docket)\\s{0,3}(?:no\\.?|number|ref(?:erence)?|#)"
                    + "\\s{0,3}[:#-]?\\s{0,3}[A-Z0-9/-]{4,28}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.8)
            .requiring("case", "matter", "docket"),
        PiiRule.detecting(
                "builtin.employee.id",
                PiiType.EMPLOYEE_ID,
                "(?i)(?:employee|emp|staff)\\s{0,3}(?:id|no\\.?|number|#)"
                    + "\\s{0,3}[:#-]?\\s{0,3}[A-Z0-9-]{3,20}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.8)
            .requiring("employee", "emp", "staff"),
        PiiRule.detecting(
                "builtin.customer.id",
                PiiType.CUSTOMER_ID,
                "(?i)(?:customer|client|subscriber)\\s{0,3}(?:id|no\\.?|number|#)"
                    + "\\s{0,3}[:#-]?\\s{0,3}[A-Z0-9-]{3,20}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.8)
            .requiring("customer", "client", "subscriber"),

        // ---- Credentials and keys
        // ---------------------------------------------------------------------
        PiiRule.detecting(
                "builtin.credential",
                PiiType.CREDENTIAL,
                "(?i)(?:password|passwd|pwd|passphrase|secret)\\s{0,3}[:=]\\s{0,3}\\S{4,128}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.95)
            .requiring("password", "passwd", "pwd", "passphrase", "secret"),
        PiiRule.detecting(
                "builtin.api.key",
                PiiType.SECRET_KEY,
                "(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|bearer)"
                    + "\\s{0,3}[:=]?\\s{0,3}[A-Za-z0-9_\\-]{16,128}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.9)
            .requiring("api", "secret", "token", "bearer"),
        PiiRule.detecting(
                "builtin.private.key",
                PiiType.SECRET_KEY,
                "-----BEGIN (?:[A-Z]{1,12} ){0,3}PRIVATE KEY-----",
                PiiValidator.NONE)
            .scoring(1.0)
            .requiring("-----begin"),

        // ---- Names
        // ----------------------------------------------------------------------------------
        // Titled names only. General personal-name detection is not achievable with regular
        // expressions — see AD-029 §10.1, where this is recorded as the engine's largest recall gap
        // rather than papered over with a list of common first names.
        PiiRule.detecting(
                "builtin.name.titled",
                PiiType.NAME,
                "(?<![A-Za-z])(?:Mr|Mrs|Ms|Miss|Dr|Prof)\\.?\\s{1,2}"
                    + "[A-Z][a-z]{1,20}(?:\\s{1,2}[A-Z][a-z]{1,20}){0,2}(?![A-Za-z])",
                PiiValidator.NONE)
            .scoring(0.7)
            .requiring("mr", "ms", "miss", "dr", "prof"),
        PiiRule.detecting(
                "builtin.name.labelled",
                PiiType.NAME,
                "(?i)(?:full\\s{1,2}name|patient\\s{1,2}name|customer\\s{1,2}name|\\bname)"
                    + "\\s{0,3}[:=]\\s{0,3}[A-Z][A-Za-z'-]{1,24}(?:\\s{1,2}[A-Z][A-Za-z'-]{1,24}){0,3}",
                PiiValidator.NONE)
            .ignoringCase()
            .scoring(0.8)
            .requiring("name"));
  }
}
