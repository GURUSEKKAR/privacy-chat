package com.secretchat.client.mls;

import com.secretchat.protocol.Fingerprints;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bouncycastle.mls.TreeKEM.LeafIndex;
import org.bouncycastle.mls.TreeKEM.LeafNode;
import org.bouncycastle.mls.codec.AuthenticatedContent;
import org.bouncycastle.mls.codec.ContentType;
import org.bouncycastle.mls.codec.KeyPackage;
import org.bouncycastle.mls.codec.MLSInputStream;
import org.bouncycastle.mls.codec.MLSMessage;
import org.bouncycastle.mls.codec.MLSOutputStream;
import org.bouncycastle.mls.codec.Proposal;
import org.bouncycastle.mls.codec.WireFormat;
import org.bouncycastle.mls.crypto.MlsCipherSuite;
import org.bouncycastle.mls.crypto.Secret;
import org.bouncycastle.mls.protocol.Group;

/**
 * One room's MLS group. Retention bound: the current epoch plus exactly one previous epoch (to
 * decrypt messages that were in flight while a commit landed). Older epoch secrets are dropped.
 */
public final class RoomGroup {
  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
  private static final Method UNPROTECT_TO_AUTH;
  private static final Method VERIFY_AUTH;

  static {
    try {
      UNPROTECT_TO_AUTH = Group.class.getDeclaredMethod("unprotectToContentAuth", MLSMessage.class);
      UNPROTECT_TO_AUTH.setAccessible(true);
      VERIFY_AUTH = Group.class.getDeclaredMethod("verifyAuth", AuthenticatedContent.class);
      VERIFY_AUTH.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** A locally prepared commit; becomes effective only after the server accepts it. */
  public record PreparedCommit(Group next, byte[] commit, byte[] welcome, long fromEpoch) {}

  public record Decrypted(UUID senderUserId, byte[] plaintext, long epoch) {}

  private final UUID roomId;
  private final MlsCipherSuite suite;
  private final MlsIdentity identity;
  private Group current;
  private Group previous;

  RoomGroup(UUID roomId, Group group, MlsCipherSuite suite, MlsIdentity identity) {
    this.roomId = roomId;
    this.current = group;
    this.suite = suite;
    this.identity = identity;
  }

  public UUID roomId() {
    return roomId;
  }

  public synchronized long epoch() {
    return current.getEpoch();
  }

  public synchronized PreparedCommit prepareAdd(List<KeyPackage> keyPackages) {
    List<Proposal> proposals = new ArrayList<>();
    keyPackages.forEach(kp -> {
      try {
        proposals.add(Proposal.add(kp));
      } catch (java.io.IOException e) {
        throw new MlsException("Invalid KeyPackage", e);
      }
    });
    return prepare(proposals);
  }

  /** Removes every member except this device (used for the owner-only reset rekey). */
  public synchronized PreparedCommit prepareRemoveAllOthers() {
    List<Proposal> proposals = new ArrayList<>();
    for (Map.Entry<Integer, UUID> e : roster().entrySet()) {
      if (e.getKey() != current.getIndex().value()) {
        proposals.add(Proposal.remove(new LeafIndex(e.getKey())));
      }
    }
    return prepare(proposals);
  }

  public synchronized PreparedCommit prepareRemove(List<UUID> userIds) {
    List<Proposal> proposals = new ArrayList<>();
    for (Map.Entry<Integer, UUID> e : roster().entrySet()) {
      if (userIds.contains(e.getValue()) && e.getKey() != current.getIndex().value()) {
        proposals.add(Proposal.remove(new LeafIndex(e.getKey())));
      }
    }
    if (proposals.isEmpty()) {
      throw new MlsException("None of the users are in the group");
    }
    return prepare(proposals);
  }

  /** A pure key update (fresh path secrets, post-compromise security). */
  public synchronized PreparedCommit prepareUpdate() {
    return prepare(new ArrayList<>());
  }

  private PreparedCommit prepare(List<Proposal> proposals) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      try {
        byte[] entropy = new byte[suite.getKDF().getHashLength()];
        RANDOM.nextBytes(entropy);
        Group.GroupWithMessage r =
            current.commit(
                new Secret(entropy),
                new Group.CommitOptions(proposals, true, true, null),
                new Group.MessageOptions(),
                new Group.CommitParameters(Group.NORMAL_COMMIT_PARAMS));
        byte[] commit = MLSOutputStream.encode(r.message);
        byte[] welcome =
            r.message.welcome == null ? null : MLSOutputStream.encode(r.message.welcome);
        return new PreparedCommit(r.group, commit, welcome, current.getEpoch());
      } catch (Exception e) {
        throw new MlsException("Commit preparation failed: " + e.getMessage(), e);
      }
    }
  }

  /** Server accepted our commit: advance, keeping one previous epoch for in-flight messages. */
  public synchronized void applyOwnCommit(PreparedCommit prepared) {
    if (prepared.fromEpoch() != current.getEpoch()) {
      throw new MlsException("Prepared commit is stale");
    }
    previous = current;
    current = prepared.next();
  }

  /** Processes a commit created by the owner. Rejects anything not building on our epoch. */
  public synchronized void handleCommit(byte[] commitBytes, long expectedNewEpoch) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      if (expectedNewEpoch != current.getEpoch() + 1) {
        throw new MlsException(
            "Commit epoch " + expectedNewEpoch + " does not follow " + current.getEpoch());
      }
      try {
        Group next =
            current.handle(
                commitBytes, null, new Group.CommitParameters(Group.NORMAL_COMMIT_PARAMS));
        if (next == null || next.getEpoch() != expectedNewEpoch) {
          throw new MlsException("Commit did not advance to the expected epoch");
        }
        previous = current;
        current = next;
      } catch (MlsException e) {
        throw e;
      } catch (Exception e) {
        throw new MlsException("Commit rejected: " + e.getMessage(), e);
      }
    }
  }

  /** Encrypts application data as an MLS PrivateMessage. */
  public synchronized byte[] encrypt(byte[] aad, byte[] plaintext) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      try {
        MLSMessage m = current.protect(aad, plaintext, 0);
        if (m.wireFormat != WireFormat.mls_private_message) {
          throw new MlsException("Refusing to send non-PrivateMessage application data");
        }
        return MLSOutputStream.encode(m);
      } catch (MlsException e) {
        throw e;
      } catch (Exception e) {
        throw new MlsException("Encryption failed", e);
      }
    }
  }

  /**
   * Decrypts a PrivateMessage and returns the *cryptographically authenticated* sender (from the
   * MLS leaf credential), so a server cannot relabel who sent a message.
   */
  public synchronized Decrypted decrypt(byte[] ciphertext) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite) {
      MLSMessage msg;
      try {
        msg = (MLSMessage) MLSInputStream.decode(ciphertext, MLSMessage.class);
      } catch (Exception e) {
        throw new MlsException("Malformed MLS message", e);
      }
      if (msg.wireFormat != WireFormat.mls_private_message) {
        throw new MlsException("Application data was not a PrivateMessage");
      }
      MlsException last = null;
      for (Group g : previous == null ? List.of(current) : List.of(current, previous)) {
        try {
          AuthenticatedContent auth = (AuthenticatedContent) UNPROTECT_TO_AUTH.invoke(g, msg);
          if (!(Boolean) VERIFY_AUTH.invoke(g, auth)) {
            throw new MlsException("Message signature failed to verify");
          }
          if (auth.getContent().getContentType() != ContentType.APPLICATION) {
            throw new MlsException("Not an application message");
          }
          LeafIndex sender = auth.getContent().getSender().getSender();
          LeafNode leaf = g.getTree().getLeafNode(sender);
          UUID senderId =
              UUID.fromString(
                  new String(leaf.getCredential().getIdentity(), StandardCharsets.UTF_8));
          return new Decrypted(senderId, auth.getContent().getContentBytes(), g.getEpoch());
        } catch (InvocationTargetException e) {
          last = new MlsException("Decryption failed: " + e.getCause().getMessage(), e.getCause());
        } catch (MlsException e) {
          last = e;
        } catch (Exception e) {
          last = new MlsException("Decryption failed", e);
        }
      }
      throw last;
    }
  }

  /** Leaf index -> user id for all non-blank leaves. */
  public synchronized Map<Integer, UUID> roster() {
    Map<Integer, UUID> out = new LinkedHashMap<>();
    long leaves = current.getTree().getSize().leafCount();
    for (int i = 0; i < leaves; i++) {
      LeafIndex idx = new LeafIndex(i);
      if (!current.getTree().hasLeaf(idx)) {
        continue;
      }
      LeafNode leaf = current.getTree().getLeafNode(idx);
      if (leaf == null) {
        continue;
      }
      out.put(
          i,
          UUID.fromString(new String(leaf.getCredential().getIdentity(), StandardCharsets.UTF_8)));
    }
    return out;
  }

  /** Fingerprint of the signature key that the group tree holds for a user (null if absent). */
  public synchronized String fingerprintOf(UUID userId) {
    long leaves = current.getTree().getSize().leafCount();
    for (int i = 0; i < leaves; i++) {
      LeafIndex idx = new LeafIndex(i);
      if (current.getTree().hasLeaf(idx)) {
        LeafNode leaf = current.getTree().getLeafNode(idx);
        if (leaf != null
            && userId
                .toString()
                .equals(new String(leaf.getCredential().getIdentity(), StandardCharsets.UTF_8))) {
          return Fingerprints.of(leaf.getSignatureKey());
        }
      }
    }
    return null;
  }

  public MlsIdentity identity() {
    return identity;
  }

  synchronized void destroy() {
    current = null;
    previous = null;
  }
}
