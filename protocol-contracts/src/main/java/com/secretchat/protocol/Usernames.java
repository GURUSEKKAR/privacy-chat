package com.secretchat.protocol;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared username normalization so client signatures and server uniqueness agree. */
public final class Usernames {
  private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9_.-]{2,31}$");

  private Usernames() {}

  /** NFKC + trim + lower-case (ROOT locale). Returns null for null input. */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    return Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
  }

  public static boolean isValidNormalized(String normalized) {
    return normalized != null && VALID.matcher(normalized).matches();
  }
}
