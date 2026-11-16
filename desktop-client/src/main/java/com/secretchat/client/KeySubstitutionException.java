package com.secretchat.client;

import java.util.UUID;

/**
 * A device fingerprint differs from the one pinned for that user, or from the key actually inside
 * the KeyPackage/group. The user must verify out of band and explicitly trust the new key.
 */
public class KeySubstitutionException extends RuntimeException {
  private final UUID userId;
  private final String expected;
  private final String actual;

  public KeySubstitutionException(UUID userId, String expected, String actual) {
    super(
        "Fingerprint for user " + userId + " changed: expected " + expected + " but got " + actual);
    this.userId = userId;
    this.expected = expected;
    this.actual = actual;
  }

  public UUID userId() {
    return userId;
  }

  public String expected() {
    return expected;
  }

  public String actual() {
    return actual;
  }
}
