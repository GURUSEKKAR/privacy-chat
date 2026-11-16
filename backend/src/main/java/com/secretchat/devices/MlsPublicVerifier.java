package com.secretchat.devices;

import com.secretchat.common.ApiException;
import com.secretchat.protocol.AuthDtos;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.bouncycastle.mls.codec.KeyPackage;
import org.bouncycastle.mls.codec.MLSInputStream;
import org.bouncycastle.mls.crypto.MlsCipherSuite;
import org.springframework.stereotype.Component;

/**
 * Server-side checks on *public* MLS material only: device proof-of-possession signatures and
 * KeyPackage validity/binding. The server holds no MLS private state and cannot decrypt.
 */
@Component
public class MlsPublicVerifier {
  /** v1 supports exactly one suite: X25519 / AES-128-GCM / SHA-256 / Ed25519. */
  public static final short SUITE_ID = MlsCipherSuite.MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

  private static final int ED25519_PUBLIC_KEY_BYTES = 32;

  private final MlsCipherSuite suite;

  public MlsPublicVerifier() {
    try {
      this.suite = MlsCipherSuite.getSuite(SUITE_ID);
    } catch (Exception e) {
      throw new IllegalStateException("MLS suite unavailable", e);
    }
  }

  public void requireValidPublicKey(byte[] publicKey) {
    if (publicKey == null || publicKey.length != ED25519_PUBLIC_KEY_BYTES) {
      throw ApiException.badRequest(
          "INVALID_DEVICE_KEY", "Device key must be a 32-byte Ed25519 key");
    }
  }

  public boolean verifyLoginSignature(
      byte[] publicKey, UUID challengeId, String nonce, String username, byte[] signature) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      try {
        return suite.verifyWithLabel(
            publicKey,
            AuthDtos.LOGIN_SIGNATURE_LABEL,
            AuthDtos.loginSignatureContent(challengeId, nonce, username),
            signature);
      } catch (Exception e) {
        return false;
      }
    }
  }

  /**
   * Parses and verifies a KeyPackage and checks it belongs to this exact device: same suite, valid
   * signatures/lifetime, signature key equal to the enrolled identity key, and a basic credential
   * whose identity is the user id. A server-substituted key would therefore fail here, and clients
   * independently re-check fingerprints (defense in depth).
   */
  public void requireValidKeyPackage(byte[] encoded, UUID userId, byte[] deviceIdentityKey) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      KeyPackage kp;
      try {
        kp = (KeyPackage) MLSInputStream.decode(encoded, KeyPackage.class);
      } catch (Exception e) {
        throw ApiException.badRequest("INVALID_KEY_PACKAGE", "KeyPackage could not be parsed");
      }
      try {
        if (kp.getSuite().getSuiteID() != SUITE_ID) {
          throw ApiException.badRequest("INVALID_KEY_PACKAGE", "Unsupported cipher suite");
        }
        if (!kp.verify() || !kp.getLeafNode().verifyLifetime()) {
          throw ApiException.badRequest(
              "INVALID_KEY_PACKAGE", "KeyPackage signature/lifetime invalid");
        }
        if (!Arrays.equals(kp.getLeafNode().getSignatureKey(), deviceIdentityKey)) {
          throw ApiException.badRequest(
              "KEY_MISMATCH", "KeyPackage is not bound to this device key");
        }
        byte[] identity = kp.getLeafNode().getCredential().getIdentity();
        if (identity == null
            || !userId.toString().equals(new String(identity, StandardCharsets.UTF_8))) {
          throw ApiException.badRequest("KEY_MISMATCH", "KeyPackage credential is not this user");
        }
      } catch (ApiException e) {
        throw e;
      } catch (Exception e) {
        throw ApiException.badRequest("INVALID_KEY_PACKAGE", "KeyPackage verification failed");
      }
    }
  }

  /**
   * Application messages must be MLS PrivateMessages: MLSMessage = version(uint16=1) ||
   * wire_format(uint16=2, mls_private_message).
   */
  public boolean isPrivateMessage(byte[] mlsMessage) {
    return mlsMessage.length > 4
        && mlsMessage[0] == 0
        && mlsMessage[1] == 1
        && mlsMessage[2] == 0
        && mlsMessage[3] == 2;
  }
}
