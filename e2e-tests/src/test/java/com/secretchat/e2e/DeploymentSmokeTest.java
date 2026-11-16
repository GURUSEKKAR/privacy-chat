package com.secretchat.e2e;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.client.SecretChatClient;
import com.secretchat.client.storage.*;
import com.secretchat.protocol.RoomDtos.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.UUID;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the packaged Docker application, HTTPS and WSS with certificate validation. */
@EnabledIfSystemProperty(named = "secretchat.deployment", matches = "true")
class DeploymentSmokeTest {
  @TempDir Path directory;

  @Test
  void validatedHttpsAndWssCarryEncryptedConversation() throws Exception {
    var old = SSLContext.getDefault();
    var trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    try (var input = Files.newInputStream(Path.of("../.local/tls/localhost.crt"))) {
      trust.setCertificateEntry(
          "local-server", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trust);
    var ssl = SSLContext.getInstance("TLSv1.3");
    ssl.init(null, factory.getTrustManagers(), null);
    SSLContext.setDefault(ssl);
    URI uri =
        URI.create(System.getProperty("secretchat.deployment.url", "https://localhost:18443"));
    try (var a =
            new SecretChatClient(
                uri,
                new LocalVault(directory.resolve("a")),
                new FingerprintPins(directory.resolve("a-pins.json")));
        var b =
            new SecretChatClient(
                uri,
                new LocalVault(directory.resolve("b")),
                new FingerprintPins(directory.resolve("b-pins.json")))) {
      var http = HttpClient.newBuilder().sslContext(ssl).build();
      var health =
          http.send(
              HttpRequest.newBuilder(uri.resolve("/actuator/health")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(health.sslSession().orElseThrow().getProtocol()).isEqualTo("TLSv1.3");
      assertThat(
              http.send(
                      HttpRequest.newBuilder(uri.resolve("/actuator/metrics")).GET().build(),
                      HttpResponse.BodyHandlers.discarding())
                  .statusCode())
          .isEqualTo(401);
      String suffix = UUID.randomUUID().toString().substring(0, 8);
      a.register("tlsa" + suffix, "deployment-test-password".toCharArray(), "TLS A");
      b.register("tlsb" + suffix, "deployment-test-password".toCharArray(), "TLS B");
      var room =
          a.createRoom(new CreateRoomRequest(null, null, 60, 600, PrivacyMode.TEMPORARY_CATCH_UP));
      var invitation = a.invite(room.id(), b.username());
      b.acceptInvitation(invitation.id(), null);
      a.addAcceptedMembers(room.id());
      a.enterRoom(room.id(), null);
      b.enterRoom(room.id(), null);
      var sent = a.send(room.id(), "TLS deployment verification");
      assertThat(
              b.awaitCondition(
                  () -> b.chat().contains(room.id(), sent.messageId()), Duration.ofSeconds(10)))
          .isTrue();
      b.leaveRoom(room.id());
      b.enterRoom(room.id(), null);
      assertThat(b.chat().messages(room.id())).isEmpty();
    } finally {
      SSLContext.setDefault(old);
    }
  }
}
