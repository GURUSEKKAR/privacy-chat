package com.secretchat.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Human-comparable device fingerprint: first 20 bytes of SHA-256 over the device's public signature
 * key, rendered as 10 groups of 4 upper-case hex characters.
 */
public final class Fingerprints {
  private Fingerprints() {}

  public static String of(byte[] publicKey) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
      String hex = HexFormat.of().withUpperCase().formatHex(digest, 0, 20);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < hex.length(); i += 4) {
        if (i > 0) {
          sb.append(' ');
        }
        sb.append(hex, i, i + 4);
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Compares fingerprints ignoring whitespace and case. */
  public static boolean matches(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return canonical(a).equals(canonical(b));
  }

  private static String canonical(String s) {
    return s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }
}
