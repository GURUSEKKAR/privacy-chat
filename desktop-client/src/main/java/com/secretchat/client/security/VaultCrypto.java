package com.secretchat.client.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Encrypts small secrets at rest with AES-256-GCM under a key derived from the user's password via
 * Argon2id (m=64 MiB, t=3, p=1). The derived key and plaintext buffers are wiped after use.
 */
public final class VaultCrypto {
  public static final int SALT_BYTES = 16;
  public static final int NONCE_BYTES = 12;
  private static final int MEMORY_KIB = 64 * 1024;
  private static final int ITERATIONS = 3;
  private static final SecureRandom RANDOM = new SecureRandom();

  private VaultCrypto() {}

  public record Sealed(byte[] salt, byte[] nonce, byte[] ciphertext) {}

  public static Sealed seal(char[] password, byte[] plaintext, byte[] aad) {
    byte[] salt = random(SALT_BYTES);
    byte[] nonce = random(NONCE_BYTES);
    byte[] key = deriveKey(password, salt);
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      c.updateAAD(aad);
      return new Sealed(salt, nonce, c.doFinal(plaintext));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("vault encryption failed", e);
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  /** Throws {@link VaultException} if the password is wrong or the data was tampered with. */
  public static byte[] open(char[] password, Sealed sealed, byte[] aad) {
    byte[] key = deriveKey(password, sealed.salt());
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, sealed.nonce()));
      c.updateAAD(aad);
      return c.doFinal(sealed.ciphertext());
    } catch (javax.crypto.AEADBadTagException e) {
      throw new VaultException("Wrong password or corrupted vault");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("vault decryption failed", e);
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  static byte[] deriveKey(char[] password, byte[] salt) {
    Argon2Parameters params =
        new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(MEMORY_KIB)
            .withIterations(ITERATIONS)
            .withParallelism(1)
            .build();
    Argon2BytesGenerator gen = new Argon2BytesGenerator();
    gen.init(params);
    byte[] pw = new String(password).getBytes(StandardCharsets.UTF_8);
    byte[] key = new byte[32];
    try {
      gen.generateBytes(pw, key);
      return key;
    } finally {
      Arrays.fill(pw, (byte) 0);
    }
  }

  private static byte[] random(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  public static class VaultException extends RuntimeException {
    public VaultException(String message) {
      super(message);
    }
  }
}
