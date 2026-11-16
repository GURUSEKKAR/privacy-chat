package com.secretchat.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Token generation and one-way hashing for bearer/refresh tokens (high-entropy secrets). */
public final class Hashing {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Hashing() {}

  public static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static byte[] randomBytes(int n) {
    byte[] bytes = new byte[n];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  public static String sha256Hex(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] decodeBase64(String value, String field) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest("INVALID_BASE64", field + " is not valid base64");
    }
  }

  public static String b64(byte[] value) {
    return value == null ? null : Base64.getEncoder().encodeToString(value);
  }
}
