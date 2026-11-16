package com.secretchat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.secretchat.common.ApiException;
import com.secretchat.protocol.AuthDtos;
import com.secretchat.protocol.AuthDtos.ChallengeResponse;
import com.secretchat.protocol.AuthDtos.TokenResponse;
import com.secretchat.testsupport.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.mls.crypto.MlsCipherSuite;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class AuthIntegrationTest extends IntegrationTestBase {
  @Test
  void successfulLoginUpgradesOlderArgon2Parameters() {
    var user = register("upgrade");
    String legacy =
        org.springframework.security.crypto.argon2.Argon2PasswordEncoder
            .defaultsForSpringSecurity_v5_8()
            .encode(PASSWORD);
    jdbc.update("update users set password_hash=? where id=?", legacy, user.principal().userId());
    var challenge = challenges.issue();
    auth.login(
        new AuthDtos.LoginRequest(
            user.username(),
            PASSWORD,
            user.tokens().deviceId(),
            null,
            challenge.challengeId(),
            b64(sign(user.signPriv(), challenge, user.username()))),
        "127.0.0.1");
    assertThat(
            jdbc.queryForObject(
                "select password_hash from users where id=?",
                String.class,
                user.principal().userId()))
        .contains("m=19456,t=2,p=1")
        .isNotEqualTo(legacy);
  }

  @Test
  void roomSessionCannotBeReusedByAnotherLoginOnTheSameDevice() {
    var user = register("binding");
    var room = createRoom(user, 600, 3600, null);
    var session = openSession(user, room.id());
    var challenge = challenges.issue();
    var tokens =
        auth.login(
            new AuthDtos.LoginRequest(
                user.username(),
                PASSWORD,
                user.tokens().deviceId(),
                null,
                challenge.challengeId(),
                b64(sign(user.signPriv(), challenge, user.username()))),
            "127.0.0.1");
    var second = auth.authenticate(tokens.accessToken()).orElseThrow();
    assertThatThrownBy(() -> messages.pending(second, room.id(), session))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> roomSessions.end(second, room.id(), session))
        .isInstanceOf(ApiException.class);
    assertThat(messages.pending(user.principal(), room.id(), session)).isEmpty();
    var rotated = auth.refresh(user.tokens().refreshToken());
    assertThat(
            messages.pending(
                auth.authenticate(rotated.accessToken()).orElseThrow(), room.id(), session))
        .isEmpty();
  }

  @LocalServerPort int port;
  @Autowired UserRepository users;

  private final HttpClient http = HttpClient.newHttpClient();

  private int get(String path, String token) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  /** A-01: concurrent duplicate registrations produce exactly one account. */
  @Test
  void concurrentDuplicateRegistrationHasOneWinner() throws Exception {
    String name = uniqueName("dup");
    MlsCipherSuite s = suite();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Callable<Boolean>> calls = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      calls.add(() -> {
        AsymmetricCipherKeyPair kp = s.generateSignatureKeyPair();
        byte[] priv = s.serializeSignaturePrivateKey(kp.getPrivate());
        byte[] pub = s.serializeSignaturePublicKey(kp.getPublic());
        ChallengeResponse ch = challenges.issue();
        try {
          auth.register(
              new AuthDtos.RegisterRequest(
                  name.toUpperCase(),
                  PASSWORD,
                  new AuthDtos.NewDevice("d", b64(pub)),
                  ch.challengeId(),
                  b64(sign(priv, ch, name))),
              "10.0.0.1");
          return true;
        } catch (ApiException | org.springframework.dao.DataIntegrityViolationException e) {
          return false;
        }
      });
    }
    int ok = 0;
    for (Future<Boolean> f : pool.invokeAll(calls)) {
      if (f.get()) {
        ok++;
      }
    }
    pool.shutdown();
    assertThat(ok).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from users where username_normalized = ?", Long.class, name))
        .isEqualTo(1);
  }

  /** A-02: passwords stored only as Argon2id hashes. */
  @Test
  void passwordIsArgon2idHashed() {
    TestUser u = register("hash");
    String stored =
        jdbc.queryForObject(
            "select password_hash from users where id = ?", String.class, u.principal().userId());
    assertThat(stored)
        .startsWith("$argon2id$")
        .contains("m=19456,t=2,p=1")
        .doesNotContain(PASSWORD);
  }

  /** A-03: logout / revoked tokens get 401; refresh rotates and reuse revokes the device. */
  @Test
  void revokedAndRotatedTokensAreRejected() throws Exception {
    TestUser u = register("tok");
    assertThat(get("/api/v1/rooms", u.tokens().accessToken())).isEqualTo(200);

    TokenResponse rotated = auth.refresh(u.tokens().refreshToken());
    assertThat(get("/api/v1/rooms", u.tokens().accessToken())).isEqualTo(401);
    assertThat(get("/api/v1/rooms", rotated.accessToken())).isEqualTo(200);

    // Reusing the old refresh token = theft signal: all device sessions revoked.
    assertThatThrownBy(() -> auth.refresh(u.tokens().refreshToken()))
        .isInstanceOf(ApiException.class);
    assertThat(get("/api/v1/rooms", rotated.accessToken())).isEqualTo(401);

    TestUser v = register("tok");
    auth.logout(v.principal().authSessionId());
    assertThat(get("/api/v1/rooms", v.tokens().accessToken())).isEqualTo(401);
    assertThat(get("/api/v1/rooms", null)).isEqualTo(401);
  }

  /** A-04: credential guessing is throttled with a generic error. */
  @Test
  void loginIsRateLimited() {
    TestUser u = register("rl");
    int invalid = 0;
    int limited = 0;
    for (int i = 0; i < 8; i++) {
      ChallengeResponse ch = challenges.issue();
      try {
        auth.login(
            new AuthDtos.LoginRequest(
                u.username(),
                "wrong-password-123",
                u.tokens().deviceId(),
                null,
                ch.challengeId(),
                b64(sign(u.signPriv(), ch, u.username()))),
            "10.9.9.9");
      } catch (ApiException e) {
        if (e.code().equals("INVALID_CREDENTIALS")) {
          invalid++;
        } else if (e.code().equals("RATE_LIMITED")) {
          limited++;
        }
      }
    }
    assertThat(invalid).isEqualTo(5);
    assertThat(limited).isEqualTo(3);
  }

  /**
   * A-06 / A-07: a stolen password on an unknown device yields only a PENDING device with no room
   * access.
   */
  @Test
  void stolenPasswordOnUnknownDeviceCannotAccessRooms() throws Exception {
    TestUser victim = register("victim");
    MlsCipherSuite s = suite();
    AsymmetricCipherKeyPair kp = s.generateSignatureKeyPair();
    byte[] priv = s.serializeSignaturePrivateKey(kp.getPrivate());
    byte[] pub = s.serializeSignaturePublicKey(kp.getPublic());
    ChallengeResponse ch = challenges.issue();
    TokenResponse attacker =
        auth.login(
            new AuthDtos.LoginRequest(
                victim.username(),
                PASSWORD,
                null,
                new AuthDtos.NewDevice("evil", b64(pub)),
                ch.challengeId(),
                b64(sign(priv, ch, victim.username()))),
            "10.6.6.6");
    assertThat(attacker.deviceStatus()).isEqualTo("PENDING");
    assertThat(get("/api/v1/rooms", attacker.accessToken())).isEqualTo(403);
    assertThat(get("/api/v1/invites", attacker.accessToken())).isEqualTo(403);

    // Login with the victim's device id but the attacker's key: signature check fails.
    ChallengeResponse ch2 = challenges.issue();
    assertThatThrownBy(() ->
            auth.login(
                new AuthDtos.LoginRequest(
                    victim.username(),
                    PASSWORD,
                    victim.tokens().deviceId(),
                    null,
                    ch2.challengeId(),
                    b64(sign(priv, ch2, victim.username()))),
                "10.6.6.7"))
        .isInstanceOf(ApiException.class);

    // Approving with a wrong fingerprint is refused (key substitution guard).
    var devicesSvc = devices;
    assertThatThrownBy(
            () -> devicesSvc.approve(victim.principal(), attacker.deviceId(), "0000 0000"))
        .hasMessageContaining("Fingerprint");
    // Revoked device: its sessions die immediately.
    devicesSvc.revoke(victim.principal(), attacker.deviceId());
    assertThat(get("/api/v1/devices", attacker.accessToken())).isEqualTo(401);
  }

  @Autowired com.secretchat.devices.DeviceService devices;

  /** No private key column exists anywhere; device rows contain only 32-byte public keys. */
  @Test
  void onlyPublicKeysAreStored() {
    TestUser u = register("pub");
    byte[] stored =
        jdbc.queryForObject(
            "select identity_public_key from devices where id = ?",
            byte[].class,
            u.tokens().deviceId());
    assertThat(stored).isEqualTo(u.signPub()).hasSize(32);
    List<String> cols =
        jdbc.queryForList(
            "select column_name from information_schema.columns where table_schema='public'",
            String.class);
    assertThat(cols)
        .noneMatch(c -> c.contains("private") || c.contains("plaintext") || c.equals("body"));
  }
}
