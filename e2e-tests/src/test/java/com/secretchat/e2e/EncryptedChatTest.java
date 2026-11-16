package com.secretchat.e2e;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.client.SecretChatClient;
import com.secretchat.client.storage.FingerprintPins;
import com.secretchat.client.storage.LocalVault;
import com.secretchat.protocol.RoomDtos.*;
import com.secretchat.testsupport.IntegrationTestBase;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real client MLS, HTTP, STOMP, receipts and PostgreSQL, without a graphical desktop. */
class EncryptedChatTest extends IntegrationTestBase {
  @Test
  void verifiedFingerprintChangeCanBeRetriedWithoutConsumingAKeyPackage() {
    try (var a = client("pina");
        var b = client("pinb")) {
      a.register(uniqueName("pina"), PASSWORD.toCharArray(), "A");
      b.register(uniqueName("pinb"), PASSWORD.toCharArray(), "B");
      var room =
          a.createRoom(
              new CreateRoomRequest(null, null, 600, 3600, PrivacyMode.TEMPORARY_CATCH_UP));
      var invite = a.invite(room.id(), b.username());
      b.acceptInvitation(invite.id(), null);
      a.trustFingerprint(
          b.userId(), com.secretchat.client.mls.MlsIdentity.generate().fingerprint());
      assertThatThrownBy(() -> a.addAcceptedMembers(room.id()))
          .isInstanceOf(com.secretchat.client.KeySubstitutionException.class);
      a.trustFingerprint(b.userId(), b.deviceFingerprint());
      assertThat(a.addAcceptedMembers(room.id())).isEqualTo(1);
      b.enterRoom(room.id(), null);
      assertThat(b.group(room.id()).roster().values()).contains(a.userId(), b.userId());
    }
  }

  @Test
  void additionalDeviceNeedsApprovalAndDoesNotInheritHistoricalRoomRights() {
    String name = uniqueName("device");
    try (var approved = client("approved");
        var pending = client("pending")) {
      approved.register(name, PASSWORD.toCharArray(), "original");
      var room =
          approved.createRoom(
              new CreateRoomRequest(null, null, 600, 3600, PrivacyMode.TEMPORARY_CATCH_UP));
      pending.login(name, PASSWORD.toCharArray(), "new device");
      assertThat(pending.isDeviceApproved()).isFalse();
      approved.approveDevice(pending.tokens().deviceId(), pending.deviceFingerprint());
      pending.refreshApproval();
      assertThat(pending.isDeviceApproved()).isTrue();
      assertThat(pending.api().rooms()).isEmpty();
      assertThatThrownBy(() -> pending.api().room(room.id()))
          .isInstanceOf(com.secretchat.client.api.ApiException.class);
      approved.api().revokeDevice(pending.tokens().deviceId());
      assertThatThrownBy(() -> pending.api().devices())
          .isInstanceOf(com.secretchat.client.api.ApiException.class);
    }
  }

  @Test
  void ownerCanRecoverAfterRestartUsingResetAndFreshGroup() {
    String name = uniqueName("restart");
    java.util.UUID roomId;
    try (var original = client("restart")) {
      original.register(name, PASSWORD.toCharArray(), "owner");
      roomId =
          original
              .createRoom(
                  new CreateRoomRequest(null, null, 600, 3600, PrivacyMode.TEMPORARY_CATCH_UP))
              .id();
    }
    try (var restored = client("restart")) {
      restored.login(name, PASSWORD.toCharArray(), "owner");
      assertThatThrownBy(() -> restored.enterRoom(roomId, null))
          .isInstanceOf(IllegalStateException.class);
      restored.resetRoomNow(roomId);
      restored.enterRoom(roomId, null);
      assertThat(restored.group(roomId).roster().values()).containsExactly(restored.userId());
      assertThat(restored.api().room(roomId).generation()).isEqualTo(2);
      assertThat(restored.send(roomId, "new cycle").messageId()).isNotNull();
    }
  }

  @Test
  void failedDisplayWithholdsReceiptAndRefreshCanRetryWithoutDecryptingAgain() {
    try (var a = client("displaya");
        var b = client("displayb")) {
      a.register(uniqueName("displaya"), PASSWORD.toCharArray(), "A");
      b.register(uniqueName("displayb"), PASSWORD.toCharArray(), "B");
      var room =
          a.createRoom(
              new CreateRoomRequest(null, null, 600, 3600, PrivacyMode.TEMPORARY_CATCH_UP));
      var invitation = a.invite(room.id(), b.username());
      b.acceptInvitation(invitation.id(), null);
      a.addAcceptedMembers(room.id());
      a.enterRoom(room.id(), null);
      b.enterRoom(room.id(), null);
      var attempted = new java.util.concurrent.CountDownLatch(1);
      b.setListener(
          new SecretChatClient.Listener() {
            public void onMessage(com.secretchat.client.state.ChatState.DisplayedMessage message) {
              attempted.countDown();
              throw new IllegalStateException("simulated display failure");
            }
          });
      var message = a.send(room.id(), "display test");
      try {
        assertThat(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
      assertThat(
              jdbc.queryForObject(
                  "select viewed_at is null from message_recipients where message_id=? and recipient_user_id=?",
                  Boolean.class,
                  message.messageId(),
                  b.userId()))
          .isTrue();
      b.setListener(new SecretChatClient.Listener() {});
      b.refresh(room.id());
      assertThat(
              jdbc.queryForObject(
                  "select ciphertext is null from messages where id=?",
                  Boolean.class,
                  message.messageId()))
          .isTrue();
    }
  }

  @LocalServerPort int port;
  @TempDir Path temporary;

  private SecretChatClient client(String name) {
    Path directory = temporary.resolve(name);
    return new SecretChatClient(
        URI.create("http://localhost:" + port),
        new LocalVault(directory),
        new FingerprintPins(directory.resolve("pins.json")));
  }

  @Test
  void encryptedDeliveryConsumptionAndOfflineRecipient() {
    try (var a = client("a");
        var b = client("b");
        var c = client("c")) {
      a.register(uniqueName("alice"), PASSWORD.toCharArray(), "A");
      b.register(uniqueName("bob"), PASSWORD.toCharArray(), "B");
      c.register(uniqueName("carol"), PASSWORD.toCharArray(), "C");
      var room =
          a.createRoom(
              new CreateRoomRequest(null, null, 600, 3600, PrivacyMode.TEMPORARY_CATCH_UP));
      var ib = a.invite(room.id(), b.username());
      var ic = a.invite(room.id(), c.username());
      b.acceptInvitation(ib.id(), null);
      c.acceptInvitation(ic.id(), null);
      assertThat(a.addAcceptedMembers(room.id())).isEqualTo(2);
      a.enterRoom(room.id(), null);
      b.enterRoom(room.id(), null);
      var sent = a.send(room.id(), "integration test message");
      assertThat(
              b.awaitCondition(
                  () -> b.chat().contains(room.id(), sent.messageId()), Duration.ofSeconds(10)))
          .isTrue();
      assertThat(b.chat().messages(room.id()).getFirst().text())
          .isEqualTo("integration test message");
      byte[] stored =
          jdbc.queryForObject(
              "select ciphertext from messages where id = ?", byte[].class, sent.messageId());
      assertThat(new String(stored, java.nio.charset.StandardCharsets.UTF_8))
          .doesNotContain("integration test message");
      b.leaveRoom(room.id());
      assertThat(b.chat().messages(room.id())).isEmpty();
      b.enterRoom(room.id(), null);
      assertThat(b.chat().messages(room.id())).isEmpty();
      c.enterRoom(room.id(), null);
      assertThat(
              c.awaitCondition(
                  () -> c.chat().contains(room.id(), sent.messageId()), Duration.ofSeconds(10)))
          .isTrue();
      assertThat(c.chat().messages(room.id()).getFirst().text())
          .isEqualTo("integration test message");
      assertThat(
              jdbc.queryForObject(
                  "select ciphertext is null from messages where id = ?",
                  Boolean.class,
                  sent.messageId()))
          .isTrue();
    }
  }
}
