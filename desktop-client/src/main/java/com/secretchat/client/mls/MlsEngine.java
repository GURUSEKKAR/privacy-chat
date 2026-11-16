package com.secretchat.client.mls;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.mls.TreeKEM.LeafNode;
import org.bouncycastle.mls.TreeKEM.LifeTime;
import org.bouncycastle.mls.codec.Capabilities;
import org.bouncycastle.mls.codec.Credential;
import org.bouncycastle.mls.codec.Extension;
import org.bouncycastle.mls.codec.KeyPackage;
import org.bouncycastle.mls.codec.MLSInputStream;
import org.bouncycastle.mls.codec.MLSOutputStream;
import org.bouncycastle.mls.codec.Welcome;
import org.bouncycastle.mls.crypto.MlsCipherSuite;
import org.bouncycastle.mls.crypto.Secret;
import org.bouncycastle.mls.protocol.Group;

/**
 * Client-side MLS (RFC 9420) state for one device: a pool of unused KeyPackages (whose private
 * init/leaf keys never leave this process) and one {@link RoomGroup} per room. MLS group state is
 * deliberately held in memory only; nothing decrypted is ever persisted.
 */
public final class MlsEngine {
  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

  private record PendingKeyPackage(
      KeyPackage keyPackage, AsymmetricCipherKeyPair init, AsymmetricCipherKeyPair leaf) {}

  private final MlsIdentity identity;
  private final UUID userId;
  private final MlsCipherSuite suite;
  private final Map<String, PendingKeyPackage> pending = new ConcurrentHashMap<>();
  private final Map<UUID, RoomGroup> groups = new ConcurrentHashMap<>();

  public MlsEngine(MlsIdentity identity, UUID userId) {
    this.identity = identity;
    this.userId = userId;
    this.suite = identity.getSuite();
  }

  public UUID userId() {
    return userId;
  }

  public MlsIdentity identity() {
    return identity;
  }

  /** Generates fresh packages while retaining private keys for outstanding Welcome messages. */
  public List<String> freshKeyPackages(int count) {
    pending.entrySet().removeIf(e -> !e.getValue().keyPackage().getLeafNode().verifyLifetime());
    if (count < 1 || pending.size() + count > 256) {
      throw new MlsException(
          "Too many outstanding KeyPackages; process pending Welcome messages first");
    }
    List<String> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      PendingKeyPackage p = newKeyPackage();
      String encoded = Base64.getEncoder().encodeToString(encode(p.keyPackage()));
      pending.put(encoded, p);
      out.add(encoded);
    }
    return out;
  }

  private PendingKeyPackage newKeyPackage() {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      try {
        AsymmetricCipherKeyPair init = suite.getHPKE().generatePrivateKey();
        AsymmetricCipherKeyPair leaf = suite.getHPKE().generatePrivateKey();
        LeafNode node =
            new LeafNode(
                suite,
                suite.getHPKE().serializePublicKey(leaf.getPublic()),
                identity.publicKey(),
                Credential.forBasic(userId.toString().getBytes(StandardCharsets.UTF_8)),
                new Capabilities(),
                new LifeTime(),
                new ArrayList<Extension>(),
                identity.signaturePrivate());
        KeyPackage kp =
            new KeyPackage(
                suite,
                suite.getHPKE().serializePublicKey(init.getPublic()),
                node,
                new ArrayList<Extension>(),
                identity.signaturePrivate());
        return new PendingKeyPackage(kp, init, leaf);
      } catch (Exception e) {
        throw new MlsException("KeyPackage generation failed", e);
      }
    }
  }

  /** Owner: creates a brand-new group (epoch 0) containing only this device. */
  public RoomGroup createGroup(UUID roomId) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      try {
        PendingKeyPackage self = newKeyPackage();
        byte[] groupId = new byte[16];
        RANDOM.nextBytes(groupId);
        Group g =
            new Group(
                groupId,
                suite,
                self.leaf(),
                identity.signaturePrivate(),
                self.keyPackage().getLeafNode(),
                new ArrayList<Extension>());
        RoomGroup rg = new RoomGroup(roomId, g, suite, identity);
        groups.put(roomId, rg);
        return rg;
      } catch (Exception e) {
        throw new MlsException("Group creation failed", e);
      }
    }
  }

  /** Member: joins from a Welcome encrypted to one of our KeyPackages. */
  public RoomGroup join(UUID roomId, byte[] welcomeBytes) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      Welcome welcome;
      try {
        welcome = (Welcome) MLSInputStream.decode(welcomeBytes, Welcome.class);
      } catch (Exception e) {
        throw new MlsException("Malformed Welcome", e);
      }
      for (Iterator<Map.Entry<String, PendingKeyPackage>> it = pending.entrySet().iterator();
          it.hasNext(); ) {
        PendingKeyPackage p = it.next().getValue();
        try {
          if (welcome.find(p.keyPackage()) < 0) {
            continue;
          }
        } catch (java.io.IOException e) {
          continue;
        }
        try {
          Group g =
              new Group(
                  suite.getHPKE().serializePrivateKey(p.init().getPrivate()),
                  p.leaf(),
                  identity.signaturePrivate(),
                  p.keyPackage(),
                  welcome,
                  null,
                  new HashMap<Secret, byte[]>(),
                  new HashMap<Group.EpochRef, byte[]>());
          it.remove(); // KeyPackages are single use.
          RoomGroup rg = new RoomGroup(roomId, g, suite, identity);
          groups.put(roomId, rg);
          return rg;
        } catch (Exception e) {
          throw new MlsException("Welcome could not be processed", e);
        }
      }
      throw new MlsException("Welcome is not addressed to any of this device's KeyPackages");
    }
  }

  public RoomGroup group(UUID roomId) {
    return groups.get(roomId);
  }

  public void forget(UUID roomId) {
    RoomGroup g = groups.remove(roomId);
    if (g != null) {
      g.destroy();
    }
  }

  public void forgetAll() {
    groups.keySet().forEach(this::forget);
    pending.clear();
  }

  public static KeyPackage decodeKeyPackage(byte[] bytes) {
    try {
      return (KeyPackage) MLSInputStream.decode(bytes, KeyPackage.class);
    } catch (Exception e) {
      throw new MlsException("Malformed KeyPackage", e);
    }
  }

  static byte[] encode(Object writable) {
    try {
      return MLSOutputStream.encode(writable);
    } catch (Exception e) {
      throw new MlsException("MLS encoding failed", e);
    }
  }

  static boolean sameBytes(byte[] a, byte[] b) {
    return Arrays.equals(a, b);
  }
}
