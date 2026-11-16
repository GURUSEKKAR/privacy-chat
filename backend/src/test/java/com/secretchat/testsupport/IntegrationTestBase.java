package com.secretchat.testsupport;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.AuthService;
import com.secretchat.auth.ChallengeService;
import com.secretchat.crypto_relay.MlsRelayService;
import com.secretchat.invitations.InvitationService;
import com.secretchat.messages.MessageService;
import com.secretchat.messages.RoomSessionService;
import com.secretchat.protocol.AuthDtos;
import com.secretchat.protocol.AuthDtos.ChallengeResponse;
import com.secretchat.protocol.AuthDtos.TokenResponse;
import com.secretchat.protocol.MessageDtos.ChatEvent;
import com.secretchat.protocol.MessageDtos.SendMessageFrame;
import com.secretchat.protocol.RoomDtos.CommitPurpose;
import com.secretchat.protocol.RoomDtos.CreateRoomRequest;
import com.secretchat.protocol.RoomDtos.InvitationView;
import com.secretchat.protocol.RoomDtos.MlsCommitRequest;
import com.secretchat.protocol.RoomDtos.PrivacyMode;
import com.secretchat.protocol.RoomDtos.RoomView;
import com.secretchat.receipts.ReceiptService;
import com.secretchat.rooms.RoomRepository;
import com.secretchat.rooms.RoomService;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.mls.crypto.MlsCipherSuite;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Service-level integration tests on a real PostgreSQL. MLS payloads here are opaque byte strings
 * with valid MLS headers: the server never parses or decrypts them, which is exactly the point.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfra.class)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
  protected static final String PASSWORD = "correct horse battery staple";
  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired protected AuthService auth;
  @Autowired protected ChallengeService challenges;
  @Autowired protected RoomService rooms;
  @Autowired protected RoomRepository roomRepo;
  @Autowired protected InvitationService invitations;
  @Autowired protected RoomSessionService roomSessions;
  @Autowired protected MessageService messages;
  @Autowired protected ReceiptService receipts;
  @Autowired protected MlsRelayService mls;
  @Autowired protected MutableClock clock;
  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void resetClock() {
    clock.reset();
  }

  /** A registered user with an approved first device and its signing key. */
  public record TestUser(
      String username,
      TokenResponse tokens,
      AuthPrincipal principal,
      byte[] signPriv,
      byte[] signPub) {}

  protected static MlsCipherSuite suite() {
    try {
      return MlsCipherSuite.getSuite(MlsCipherSuite.MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  protected static String uniqueName(String prefix) {
    return (prefix + SEQ.incrementAndGet() + "x" + Long.toString(System.nanoTime() % 100000, 36))
        .toLowerCase();
  }

  protected TestUser register(String prefix) {
    String name = uniqueName(prefix);
    MlsCipherSuite s = suite();
    AsymmetricCipherKeyPair kp = s.generateSignatureKeyPair();
    byte[] priv = s.serializeSignaturePrivateKey(kp.getPrivate());
    byte[] pub = s.serializeSignaturePublicKey(kp.getPublic());
    ChallengeResponse ch = challenges.issue();
    TokenResponse t =
        auth.register(
            new AuthDtos.RegisterRequest(
                name,
                PASSWORD,
                new AuthDtos.NewDevice("test", b64(pub)),
                ch.challengeId(),
                b64(sign(priv, ch, name))),
            "127.0.0.1");
    return new TestUser(name, t, auth.authenticate(t.accessToken()).orElseThrow(), priv, pub);
  }

  protected static byte[] sign(byte[] priv, ChallengeResponse ch, String username) {
    // Bouncy Castle suites share mutable cryptographic primitives across threads.
    synchronized (suite()) {
      try {
        return suite()
            .signWithLabel(
                priv,
                AuthDtos.LOGIN_SIGNATURE_LABEL,
                AuthDtos.loginSignatureContent(ch.challengeId(), ch.nonce(), username));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  protected RoomView createRoom(
      TestUser owner, int ttlSeconds, int resetSeconds, String roomPassword) {
    return rooms.create(
        owner.principal(),
        new CreateRoomRequest(
            null, roomPassword, ttlSeconds, resetSeconds, PrivacyMode.TEMPORARY_CATCH_UP));
  }

  /** Owner invites, member accepts, owner commits an (opaque) MLS Add. Returns new epoch. */
  protected long addMember(TestUser owner, UUID roomId, TestUser member, String roomPassword) {
    InvitationView inv = invitations.invite(owner.principal(), roomId, member.username());
    invitations.accept(member.principal(), inv.id(), roomPassword);
    RoomView r = rooms.get(owner.principal(), roomId);
    return mls.commit(
            owner.principal(),
            roomId,
            new MlsCommitRequest(
                r.generation(),
                r.mlsEpoch(),
                CommitPurpose.ADD,
                b64(fakeCommit()),
                b64(new byte[] {0, 1, 0, 3, 9}),
                List.of(inv.id()),
                null,
                false))
        .epoch();
  }

  protected long removeMember(TestUser owner, UUID roomId, TestUser member) {
    RoomView r = rooms.get(owner.principal(), roomId);
    return mls.commit(
            owner.principal(),
            roomId,
            new MlsCommitRequest(
                r.generation(),
                r.mlsEpoch(),
                CommitPurpose.REMOVE,
                b64(fakeCommit()),
                null,
                null,
                List.of(member.principal().userId()),
                false))
        .epoch();
  }

  protected UUID openSession(TestUser u, UUID roomId) {
    return roomSessions.open(u.principal(), roomId, null).getId();
  }

  protected UUID openSession(TestUser u, UUID roomId, String password) {
    return roomSessions.open(u.principal(), roomId, password).getId();
  }

  protected ChatEvent send(TestUser u, UUID roomId, UUID sessionId, byte[] ciphertext) {
    RoomView r = rooms.get(u.principal(), roomId);
    return messages.send(
        u.principal(),
        roomId,
        new SendMessageFrame(
            sessionId,
            r.generation(),
            r.mlsEpoch(),
            UUID.randomUUID().toString(),
            b64(ciphertext)));
  }

  /** Bytes shaped like an MLS PrivateMessage (version mls10, wire_format private). */
  protected static byte[] fakeCiphertext(String marker) {
    byte[] body = ("opaque-" + marker).getBytes();
    byte[] out = new byte[4 + body.length];
    out[1] = 1;
    out[3] = 2;
    System.arraycopy(body, 0, out, 4, body.length);
    return out;
  }

  protected static byte[] fakeCommit() {
    return new byte[] {0, 1, 0, 1, 42, 42};
  }

  protected static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }
}
