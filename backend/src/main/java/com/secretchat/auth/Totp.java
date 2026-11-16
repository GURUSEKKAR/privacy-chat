package com.secretchat.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** RFC 6238 SHA-1, six digits, 30 seconds; secrets are encrypted with user-bound AES-GCM. */
@Component
public class Totp {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private final String encryptionKey;

  public Totp(@Value("${SECRETCHAT_MFA_ENCRYPTION_KEY:}") String encryptionKey) {
    this.encryptionKey = encryptionKey;
  }

  public String newSecret() {
    byte[] bytes = new byte[20];
    RANDOM.nextBytes(bytes);
    StringBuilder s = new StringBuilder();
    int bits = 0, value = 0;
    for (byte b : bytes) {
      value = (value << 8) | (b & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        s.append(ALPHABET.charAt((value >>> bits) & 31));
      }
    }
    return s.toString();
  }

  private byte[] decode(String secret) {
    byte[] out = new byte[secret.length() * 5 / 8];
    int bits = 0, value = 0, offset = 0;
    for (char c : secret.toCharArray()) {
      int n = ALPHABET.indexOf(c);
      if (n < 0) throw new IllegalArgumentException();
      value = (value << 5) | n;
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        out[offset++] = (byte) (value >>> bits);
      }
    }
    return out;
  }

  public String code(String secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
      byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int p = h[h.length - 1] & 15;
      int n = ByteBuffer.wrap(h, p, 4).getInt() & 0x7fffffff;
      return String.format(java.util.Locale.ROOT, "%06d", n % 1000000);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Authenticator unavailable", e);
    }
  }

  public long match(String secret, String code, long epochSeconds, long last) {
    if (code == null || !code.matches("[0-9]{6}")) return -1;
    long step = epochSeconds / 30;
    for (long candidate = step - 1; candidate <= step + 1; candidate++)
      if (candidate > last
          && java.security.MessageDigest.isEqual(
              code(secret, candidate).getBytes(StandardCharsets.US_ASCII),
              code.getBytes(StandardCharsets.US_ASCII))) return candidate;
    return -1;
  }

  public String encrypt(String secret, UUID user) {
    byte[] iv = new byte[12];
    RANDOM.nextBytes(iv);
    byte[] ciphertext =
        crypt(Cipher.ENCRYPT_MODE, secret.getBytes(StandardCharsets.US_ASCII), iv, user);
    return Base64.getEncoder()
        .encodeToString(
            ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
  }

  public String decrypt(String envelope, UUID user) {
    byte[] data = Base64.getDecoder().decode(envelope);
    return new String(
        crypt(
            Cipher.DECRYPT_MODE,
            java.util.Arrays.copyOfRange(data, 12, data.length),
            java.util.Arrays.copyOf(data, 12),
            user),
        StandardCharsets.US_ASCII);
  }

  private byte[] crypt(int mode, byte[] data, byte[] iv, UUID user) {
    if (encryptionKey.length() < 32)
      throw new com.secretchat.common.ApiException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "MFA_NOT_CONFIGURED",
          "Administrator must configure the authenticator encryption key.");
    try {
      byte[] key =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(user.toString().getBytes(StandardCharsets.US_ASCII));
      return cipher.doFinal(data);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Authenticator secret unavailable", e);
    }
  }
}
