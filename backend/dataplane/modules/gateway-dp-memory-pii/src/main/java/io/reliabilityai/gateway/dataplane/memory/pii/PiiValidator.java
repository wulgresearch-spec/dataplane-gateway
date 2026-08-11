package io.reliabilityai.gateway.dataplane.memory.pii;

/**
 * Checksum and range checks applied to a pattern match before it becomes a detection (AD-029 §6.2).
 *
 * <p><b>This is the difference between a regex list and a detector.</b> The pattern for a payment
 * card is thirteen to nineteen digits, which also matches order numbers, timestamps concatenated
 * together, telemetry counters and most log lines containing an identifier. Running Luhn over the
 * match removes roughly nine in ten of those without removing a single genuine card, because
 * genuine cards carry the check digit by construction. The same argument holds for IBAN's mod-97
 * and Aadhaar's Verhoeff digit.
 *
 * <p>Validators are an enum rather than a function so that a rule remains pure data.
 * Tenant-supplied rules must be describable, storable and reviewable; a rule holding a lambda is
 * none of those, and a rule holding arbitrary caller code is a remote execution primitive.
 *
 * <p>All of these are <em>necessary</em> conditions, never sufficient ones. A number passing Luhn
 * is a number that could be a card, not a number that is one.
 */
public enum PiiValidator {

  /** Accepts every match. For patterns specific enough that a checksum adds nothing. */
  NONE {
    @Override
    public boolean accepts(final String match) {
      return true;
    }
  },

  /** The Luhn check digit, as used by payment cards. */
  LUHN {
    @Override
    public boolean accepts(final String match) {
      final String digits = digitsOf(match);
      if (digits.length() < 13 || digits.length() > 19) {
        return false;
      }
      int sum = 0;
      boolean doubling = false;
      for (int i = digits.length() - 1; i >= 0; i--) {
        int value = digits.charAt(i) - '0';
        if (doubling) {
          value *= 2;
          if (value > 9) {
            value -= 9;
          }
        }
        sum += value;
        doubling = !doubling;
      }
      return sum % 10 == 0;
    }
  },

  /** The IBAN mod-97 check, computed without arbitrary-precision arithmetic. */
  IBAN_MOD97 {
    @Override
    public boolean accepts(final String match) {
      final String iban = match.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
      if (iban.length() < 15 || iban.length() > 34) {
        return false;
      }
      // Rotate the first four characters to the end, expand letters to numbers, then take mod 97
      // incrementally so the value never exceeds an int.
      final String rotated = iban.substring(4) + iban.substring(0, 4);
      long remainder = 0;
      for (int i = 0; i < rotated.length(); i++) {
        final char c = rotated.charAt(i);
        if (c >= '0' && c <= '9') {
          remainder = remainder * 10 + (c - '0');
        } else if (c >= 'A' && c <= 'Z') {
          remainder = remainder * 100 + (c - 'A' + 10);
        } else {
          return false;
        }
        remainder %= 97;
      }
      return remainder == 1;
    }
  },

  /** The Verhoeff check digit, as used by India's Aadhaar. */
  VERHOEFF {
    @Override
    public boolean accepts(final String match) {
      final String digits = digitsOf(match);
      if (digits.length() != 12 || digits.charAt(0) == '0' || digits.charAt(0) == '1') {
        // Aadhaar never begins with 0 or 1, which alone removes a fifth of random twelve-digit
        // runs.
        return false;
      }
      int check = 0;
      for (int i = 0; i < digits.length(); i++) {
        final int digit = digits.charAt(digits.length() - 1 - i) - '0';
        check = VERHOEFF_D[check][VERHOEFF_P[i % 8][digit]];
      }
      return check == 0;
    }
  },

  /**
   * Every dotted-quad component within 0..255, rejecting the many near-misses that are not
   * addresses.
   */
  IPV4_RANGE {
    @Override
    public boolean accepts(final String match) {
      final String[] parts = match.split("\\.");
      if (parts.length != 4) {
        return false;
      }
      for (final String part : parts) {
        if (part.isEmpty() || part.length() > 3) {
          return false;
        }
        int value = 0;
        for (int i = 0; i < part.length(); i++) {
          final char c = part.charAt(i);
          if (c < '0' || c > '9') {
            return false;
          }
          value = value * 10 + (c - '0');
        }
        if (value > 255) {
          return false;
        }
      }
      return true;
    }
  },

  /** Latitude within ±90 and longitude within ±180. */
  GPS_RANGE {
    @Override
    public boolean accepts(final String match) {
      final String[] parts = match.split(",");
      if (parts.length != 2) {
        return false;
      }
      try {
        final double latitude = Double.parseDouble(parts[0].trim());
        final double longitude = Double.parseDouble(parts[1].trim());
        // A bare "0, 0" is almost always a placeholder rather than the Gulf of Guinea.
        if (latitude == 0.0 && longitude == 0.0) {
          return false;
        }
        return latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0;
      } catch (final NumberFormatException notNumeric) {
        return false;
      }
    }
  },

  /** A date that a person could plausibly have been born on. */
  PLAUSIBLE_DATE {
    @Override
    public boolean accepts(final String match) {
      final String digits = digitsOf(match);
      if (digits.length() != 8) {
        return false;
      }
      // Accept either ordering; the pattern that feeds this does not disambiguate them.
      return plausible(digits, 0, 4, 4, 6, 6, 8) || plausible(digits, 4, 8, 2, 4, 0, 2);
    }

    private boolean plausible(
        final String digits,
        final int yearFrom,
        final int yearTo,
        final int monthFrom,
        final int monthTo,
        final int dayFrom,
        final int dayTo) {
      final int year = Integer.parseInt(digits.substring(yearFrom, yearTo));
      final int month = Integer.parseInt(digits.substring(monthFrom, monthTo));
      final int day = Integer.parseInt(digits.substring(dayFrom, dayTo));
      // 1900 is a floor rather than a truth; people born earlier exist, and the alternative is
      // matching every eight-digit build number ever written.
      return year >= 1900 && year <= 2026 && month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }
  };

  /** Verhoeff's dihedral group multiplication table. */
  private static final int[][] VERHOEFF_D = {
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
    {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
    {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
    {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
    {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
    {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
    {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
    {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
    {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
    {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
  };

  /** Verhoeff's permutation table. */
  private static final int[][] VERHOEFF_P = {
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
    {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
    {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
    {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
    {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
    {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
    {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
    {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
  };

  /**
   * Tests whether a match survives this check.
   *
   * @param match the matched text
   * @return true when the match may be a genuine instance of its type
   */
  public abstract boolean accepts(String match);

  /**
   * Strips everything that is not a digit.
   *
   * @param match the matched text
   * @return the digits, in order
   */
  static String digitsOf(final String match) {
    final StringBuilder digits = new StringBuilder(match.length());
    for (int i = 0; i < match.length(); i++) {
      final char c = match.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
      }
    }
    return digits.toString();
  }
}
