package com.secretchat.auth;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.common.ApiException;
import com.secretchat.protocol.AuthDtos;
import com.secretchat.testsupport.IntegrationTestBase;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "secretchat.mfa.required=true",
      "SECRETCHAT_MFA_ENCRYPTION_KEY=test-only-encryption-key-at-least-32-characters"
    })
class MfaIntegrationTest extends IntegrationTestBase {
  @Autowired MfaService mfa;
  @Autowired Totp totp;
  @Autowired UserRepository users;
  @Autowired AuthSessionRepository sessions;

  private AuthDtos.TokenResponse loginNew(TestUser u) {
    var kp = suite().generateSignatureKeyPair();
    byte[] priv = suite().serializeSignaturePrivateKey(kp.getPrivate()),
        pub = suite().serializeSignaturePublicKey(kp.getPublic());
    var c = challenges.issue();
    return auth.login(
        new AuthDtos.LoginRequest(
            u.username(),
            PASSWORD,
            null,
            new AuthDtos.NewDevice("New browser", b64(pub)),
            c.challengeId(),
            b64(sign(priv, c, u.username()))),
        "127.0.0.1");
  }

  private String code(String secret) {
    return totp.code(secret, clock.instant().getEpochSecond() / 30);
  }

  @Test
  void setupRequiresConfirmationAndProtectsAllAccountRoutes() {
    var u = register("mfasetup");
    assertThat(u.tokens().deviceStatus()).isEqualTo("MFA_SETUP");
    assertThat(u.principal().deviceApproved()).isFalse();
    assertThatThrownBy(() -> createRoom(u, 600, 3600, null)).isInstanceOf(ApiException.class);
    var setup = mfa.setup(u.principal());
    assertThat(mfa.setup(u.principal()).secret()).isEqualTo(setup.secret());
    assertThat(setup.uri()).contains("otpauth://totp/", "digits=6", "period=30");
    assertThat(users.findById(u.principal().userId()).orElseThrow().getTotpSecret())
        .doesNotContain(setup.secret());
    assertThatThrownBy(() -> mfa.verify(u.principal(), "bad-code"))
        .isInstanceOf(ApiException.class);
    var result = mfa.verify(u.principal(), code(setup.secret()));
    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(result.recoveryCodes()).hasSize(10);
    assertThat(auth.authenticate(u.tokens().accessToken()).orElseThrow().deviceApproved()).isTrue();
    assertThatThrownBy(() -> mfa.setup(u.principal())).isInstanceOf(ApiException.class);
  }

  @Test
  void secondFactorIsRequiredEvenForKnownDeviceAndReplacementIsExplicit() {
    var u = register("mfasession");
    var setup = mfa.setup(u.principal());
    mfa.verify(u.principal(), code(setup.secret()));
    var t = loginNew(u);
    var p = auth.authenticate(t.accessToken()).orElseThrow();
    assertThat(t.deviceStatus()).isEqualTo("MFA_REQUIRED");
    assertThat(p.deviceApproved()).isFalse();
    assertThatThrownBy(() -> mfa.replace(p)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> mfa.verify(p, code(setup.secret()))).isInstanceOf(ApiException.class);
    clock.advance(Duration.ofSeconds(30));
    var result = mfa.verify(p, code(setup.secret()));
    assertThat(result.status()).isEqualTo("SESSION_CONFLICT");
    assertThat(result.previousSessions()).hasSize(1);
    assertThat(auth.authenticate(u.tokens().accessToken()).orElseThrow().deviceApproved()).isTrue();
    assertThat(auth.authenticate(t.accessToken()).orElseThrow().deviceApproved()).isFalse();
    mfa.replace(p);
    assertThat(auth.authenticate(u.tokens().accessToken())).isEmpty();
    assertThat(auth.revalidate(u.principal().authSessionId())).isEmpty();
    assertThatThrownBy(() -> auth.refresh(u.tokens().refreshToken()))
        .isInstanceOf(ApiException.class);
    assertThat(auth.authenticate(t.accessToken()).orElseThrow().deviceApproved()).isTrue();
    // A known, approved device still needs a new code on the next password login.
    var c = challenges.issue();
    var known =
        auth.login(
            new AuthDtos.LoginRequest(
                u.username(),
                PASSWORD,
                u.principal().deviceId(),
                null,
                c.challengeId(),
                b64(sign(u.signPriv(), c, u.username()))),
            "127.0.0.1");
    assertThat(known.deviceStatus()).isEqualTo("MFA_REQUIRED");
  }

  @Test
  void recoveryCodesAreSingleUseAndVerificationAttemptsAreAccountLimited() {
    var u = register("mfarecover");
    var setup = mfa.setup(u.principal());
    var codes = mfa.verify(u.principal(), code(setup.secret())).recoveryCodes();
    var t = loginNew(u);
    var p = auth.authenticate(t.accessToken()).orElseThrow();
    assertThat(mfa.verify(p, codes.getFirst()).status()).isEqualTo("SESSION_CONFLICT");
    assertThatThrownBy(() -> mfa.verify(p, codes.getFirst())).isInstanceOf(ApiException.class);
    for (int i = 0; i < 4; i++)
      assertThatThrownBy(() -> mfa.verify(p, "not-a-code")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> mfa.verify(p, codes.get(1)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Too many");
  }

  @Test
  void pendingDeviceCannotBootstrapAnExistingUnconfiguredAccount() {
    var u = register("mfamigrate");
    var t = loginNew(u);
    var p = auth.authenticate(t.accessToken()).orElseThrow();
    assertThat(t.deviceStatus()).isEqualTo("MFA_SETUP_APPROVAL");
    assertThatThrownBy(() -> mfa.setup(p)).isInstanceOf(ApiException.class);
  }

  @Test
  void simultaneousReplacementLeavesExactlyOneAuthorizedSession() throws Exception {
    var u = register("mfarace");
    var setup = mfa.setup(u.principal());
    var recovery = mfa.verify(u.principal(), code(setup.secret())).recoveryCodes();
    var one = loginNew(u);
    var two = loginNew(u);
    var p1 = auth.authenticate(one.accessToken()).orElseThrow();
    var p2 = auth.authenticate(two.accessToken()).orElseThrow();
    mfa.verify(p1, recovery.get(0));
    mfa.verify(p2, recovery.get(1));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var tasks =
          List.of(p1, p2).stream()
              .map(p ->
                  pool.submit(() -> {
                    gate.await();
                    try {
                      mfa.replace(p);
                    } catch (ApiException ignored) {
                    }
                    return true;
                  }))
              .toList();
      gate.countDown();
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    }
    long active =
        List.of(one, two).stream()
            .filter(t ->
                auth.authenticate(t.accessToken())
                    .filter(AuthPrincipal::deviceApproved)
                    .isPresent())
            .count();
    assertThat(active).isEqualTo(1);
  }

  @org.springframework.boot.test.web.server.LocalServerPort int port;

  private java.net.http.HttpResponse<String> request(
      String path, String method, String token, String json) throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(
            java.net.URI.create("http://localhost:" + port + path));
    if (token != null) request.header("Authorization", "Bearer " + token);
    request
        .header("Content-Type", "application/json")
        .method(
            method,
            json == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(json));
    return java.net.http.HttpClient.newHttpClient()
        .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void realHttpRequiresMfaBeforeChatAndReturnsNoSetupSecretFromState() throws Exception {
    var u = register("mfahttp");
    String token = u.tokens().accessToken();
    assertThat(request("/api/v1/auth/mfa", "GET", null, null).statusCode()).isEqualTo(401);
    assertThat(request("/api/v1/rooms", "GET", token, null).statusCode()).isEqualTo(403);
    assertThat(request("/api/v1/ws-ticket", "POST", token, null).statusCode()).isEqualTo(403);
    var setup = mfa.setup(u.principal());
    var verified =
        request(
            "/api/v1/auth/mfa/verify",
            "POST",
            token,
            "{\"code\":\"" + code(setup.secret()) + "\"}");
    assertThat(verified.statusCode()).isEqualTo(200);
    assertThat(verified.body()).contains("recoveryCodes").doesNotContain(setup.secret());
    assertThat(request("/api/v1/auth/mfa", "GET", token, null).body())
        .doesNotContain(setup.secret(), "otpauth://");
    assertThat(request("/api/v1/rooms", "GET", token, null).statusCode()).isEqualTo(200);
    assertThat(
            request(
                    "/api/v1/auth/mfa/verify",
                    "POST",
                    token,
                    "{\"code\":\"" + code(setup.secret()) + "\"}")
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void incompleteSignInCannotBeKeptAliveByRefresh() {
    var u = register("mfaexpiry");
    clock.advance(Duration.ofSeconds(601));
    assertThat(auth.authenticate(u.tokens().accessToken())).isEmpty();
    assertThatThrownBy(() -> auth.refresh(u.tokens().refreshToken()))
        .isInstanceOf(ApiException.class);
  }
}
