package com.secretchat.client.mls;

import com.secretchat.protocol.Fingerprints;
import java.util.Arrays;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.mls.crypto.MlsCipherSuite;

/**
 * The device's long-term MLS signature identity (Ed25519). The private key stays on this device: it
 * is only ever written to disk encrypted (see {@code LocalVault}).
 */
public final class MlsIdentity {
  public static final short SUITE_ID = MlsCipherSuite.MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

  private final MlsCipherSuite suite;
  private final byte[] signaturePrivate;
  private final byte[] signaturePublic;

  private MlsIdentity(MlsCipherSuite suite, byte[] signaturePrivate, byte[] signaturePublic) {
    this.suite = suite;
    this.signaturePrivate = signaturePrivate;
    this.signaturePublic = signaturePublic;
  }

  public static MlsCipherSuite suite() {
    try {
      return MlsCipherSuite.getSuite(SUITE_ID);
    } catch (Exception e) {
      throw new IllegalStateException("MLS cipher suite unavailable", e);
    }
  }

  public static MlsIdentity generate() {
    MlsCipherSuite suite = suite();
    AsymmetricCipherKeyPair kp = suite.generateSignatureKeyPair();
    return new MlsIdentity(
        suite,
        suite.serializeSignaturePrivateKey(kp.getPrivate()),
        suite.serializeSignaturePublicKey(kp.getPublic()));
  }

  public static MlsIdentity restore(byte[] signaturePrivate, byte[] signaturePublic) {
    return new MlsIdentity(suite(), signaturePrivate.clone(), signaturePublic.clone());
  }

  public byte[] sign(String label, byte[] content) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      try {
        return suite.signWithLabel(signaturePrivate, label, content);
      } catch (Exception e) {
        throw new IllegalStateException("signing failed", e);
      }
    }
  }

  public boolean verify(byte[] publicKey, String label, byte[] content, byte[] signature) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      try {
        return suite.verifyWithLabel(publicKey, label, content, signature);
      } catch (Exception e) {
        return false;
      }
    }
  }

  public MlsCipherSuite getSuite() {
    return suite;
  }

  public byte[] publicKey() {
    return signaturePublic.clone();
  }

  /** Raw private key bytes, only for encrypting into the local vault. */
  public byte[] exportPrivateForVault() {
    return signaturePrivate.clone();
  }

  byte[] signaturePrivate() {
    return signaturePrivate;
  }

  public String fingerprint() {
    return Fingerprints.of(signaturePublic);
  }

  /** Best-effort wipe of the in-memory private key. */
  public void destroy() {
    Arrays.fill(signaturePrivate, (byte) 0);
  }
}
