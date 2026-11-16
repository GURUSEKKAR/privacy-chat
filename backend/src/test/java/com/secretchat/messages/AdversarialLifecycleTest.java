package com.secretchat.messages;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.common.ApiException;
import com.secretchat.expiry.ResetService;
import com.secretchat.protocol.MessageDtos.*;
import com.secretchat.protocol.RoomDtos.*;
import com.secretchat.testsupport.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdversarialLifecycleTest extends IntegrationTestBase {
  @Test
  void pendingInvitationsCannotOverfillRoomWhenAccepted() {
    var owner = register("capacity");
    var room = createRoom(owner, 600, 3600, null);
    var members = new java.util.ArrayList<TestUser>();
    var invites = new java.util.ArrayList<InvitationView>();
    for (int i = 0; i < 16; i++) {
      var member = register("seat");
      members.add(member);
      invites.add(invitations.invite(owner.principal(), room.id(), member.username()));
    }
    for (int i = 0; i < 15; i++)
      invitations.accept(members.get(i).principal(), invites.get(i).id(), null);
    assertThatThrownBy(
            () -> invitations.accept(members.get(15).principal(), invites.get(15).id(), null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("limit");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from memberships where room_id=? and state in ('ACTIVE','PENDING_KEYS')",
                Long.class,
                room.id()))
        .isEqualTo(16);
  }

  @Autowired ResetService resets;

  private List<Boolean> race(Callable<Boolean> left, Callable<Boolean> right) throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(() -> {
            start.await();
            return left.call();
          });
      var b =
          executor.submit(() -> {
            start.await();
            return right.call();
          });
      start.countDown();
      return List.of(
          a.get(15, java.util.concurrent.TimeUnit.SECONDS),
          b.get(15, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @Test
  void simultaneousViewsEraseExactlyOnce() throws Exception {
    var a = register("racea");
    var b = register("raceb");
    var c = register("racec");
    UUID id = createRoom(a, 600, 3600, null).id();
    addMember(a, id, b, null);
    addMember(a, id, c, null);
    var sb = openSession(b, id);
    var sc = openSession(c, id);
    var m = send(a, id, openSession(a, id), fakeCiphertext("race"));
    var results =
        race(
            () -> receipts.acknowledgeView(b.principal(), id, m.messageId(), sb).deleted(),
            () -> receipts.acknowledgeView(c.principal(), id, m.messageId(), sc).deleted());
    assertThat(results).contains(true);
    assertThat(
            jdbc.queryForObject(
                "select ciphertext is null from messages where id = ?",
                Boolean.class,
                m.messageId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from message_recipients where message_id = ? and viewed_at is not null",
                Long.class,
                m.messageId()))
        .isEqualTo(3);
  }

  @Test
  void concurrentCommitsHaveOneWinner() throws Exception {
    var a = register("commit");
    var r = createRoom(a, 600, 3600, null);
    var request =
        new MlsCommitRequest(
            r.generation(),
            r.mlsEpoch(),
            CommitPurpose.UPDATE,
            b64(fakeCommit()),
            null,
            null,
            null,
            false);
    Callable<Boolean> commit =
        () -> {
          try {
            mls.commit(a.principal(), r.id(), request);
            return true;
          } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("WRONG_EPOCH");
            return false;
          }
        };
    assertThat(race(commit, commit)).containsExactlyInAnyOrder(true, false);
    assertThat(rooms.get(a.principal(), r.id()).mlsEpoch()).isEqualTo(1);
  }

  @Test
  void resetRacingSendLeavesNoOldGenerationContent() throws Exception {
    var a = register("reseta");
    var b = register("resetb");
    var r = createRoom(a, 600, 3600, null);
    addMember(a, r.id(), b, null);
    var sb = openSession(b, r.id());
    race(
        () -> {
          resets.resetNow(a.principal(), r.id());
          return true;
        },
        () -> {
          try {
            send(b, r.id(), sb, fakeCiphertext("race"));
            return true;
          } catch (ApiException expected) {
            return false;
          }
        });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messages where room_id = ?", Long.class, r.id()))
        .isZero();
    assertThatThrownBy(() -> messages.pending(b.principal(), r.id(), sb))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void expiryAtExactDeadlineDeniesViewAndDelivery() {
    clock.setInstant(Instant.parse("2026-11-16T00:00:00Z"));
    var a = register("bounda");
    var b = register("boundb");
    var r = createRoom(a, 60, 3600, null);
    addMember(a, r.id(), b, null);
    var sb = openSession(b, r.id());
    var m = send(a, r.id(), openSession(a, r.id()), fakeCiphertext("boundary"));
    clock.setInstant(m.expiresAt().minusNanos(1000));
    assertThat(messages.pending(b.principal(), r.id(), sb)).hasSize(1);
    clock.setInstant(m.expiresAt());
    assertThat(messages.pending(b.principal(), r.id(), sb)).isEmpty();
    assertThatThrownBy(() -> receipts.acknowledgeView(b.principal(), r.id(), m.messageId(), sb))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void revokedUnviewedRecipientIsExcludedWithoutFakeReceipt() {
    var a = register("revokea");
    var b = register("revokeb");
    var c = register("revokec");
    var r = createRoom(a, 600, 3600, null);
    addMember(a, r.id(), b, null);
    addMember(a, r.id(), c, null);
    var sb = openSession(b, r.id());
    var m = send(a, r.id(), openSession(a, r.id()), fakeCiphertext("revoked"));
    removeMember(a, r.id(), c);
    assertThat(receipts.acknowledgeView(b.principal(), r.id(), m.messageId(), sb).deleted())
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select viewed_at is null from message_recipients where message_id = ? and recipient_user_id = ?",
                Boolean.class,
                m.messageId(),
                c.principal().userId()))
        .isTrue();
    assertThatThrownBy(() -> openSession(c, r.id())).isInstanceOf(ApiException.class);
  }

  @Test
  void forgedSessionStaleFramesOversizedDataAndRetries() {
    var a = register("framea");
    var b = register("frameb");
    var r = createRoom(a, 600, 3600, null);
    addMember(a, r.id(), b, null);
    r = rooms.get(a.principal(), r.id());
    var current = r;
    var sa = openSession(a, r.id());
    var sb = openSession(b, r.id());
    String key = UUID.randomUUID().toString();
    var frame =
        new SendMessageFrame(sa, r.generation(), r.mlsEpoch(), key, b64(fakeCiphertext("same")));
    var first = messages.send(a.principal(), r.id(), frame);
    assertThat(messages.send(a.principal(), r.id(), frame).messageId())
        .isEqualTo(first.messageId());
    assertThatThrownBy(() -> messages.send(b.principal(), current.id(), frame))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
            messages.send(
                b.principal(),
                current.id(),
                new SendMessageFrame(sb, 0, current.mlsEpoch(), key, b64(fakeCiphertext("stale")))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
            messages.send(
                b.principal(),
                current.id(),
                new SendMessageFrame(
                    sb, current.generation(), 0L, key, b64(fakeCiphertext("epoch")))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
            messages.send(
                b.principal(),
                current.id(),
                new SendMessageFrame(
                    sb,
                    current.generation(),
                    current.mlsEpoch(),
                    key,
                    b64(new byte[com.secretchat.protocol.MessageDtos.MAX_CIPHERTEXT_BYTES + 1]))))
        .isInstanceOf(ApiException.class);
    var stranger = register("stranger");
    assertThatThrownBy(() -> rooms.get(stranger.principal(), current.id()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> messages.pending(stranger.principal(), current.id(), sa))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void strictLiveExcludesOfflineMembers() {
    var a = register("livea");
    var b = register("liveb");
    var c = register("livec");
    var r =
        rooms.create(
            a.principal(), new CreateRoomRequest(null, null, 600, 3600, PrivacyMode.STRICT_LIVE));
    addMember(a, r.id(), b, null);
    addMember(a, r.id(), c, null);
    var sb = openSession(b, r.id());
    var m = send(a, r.id(), openSession(a, r.id()), fakeCiphertext("live"));
    assertThat(messages.pending(c.principal(), r.id(), openSession(c, r.id()))).isEmpty();
    assertThat(receipts.acknowledgeView(b.principal(), r.id(), m.messageId(), sb).deleted())
        .isTrue();
  }

  @Test
  void roomPasswordAndExpiredInvitationsDoNotGrantEntry() {
    var a = register("passa");
    var b = register("passb");
    var r = createRoom(a, 60, 60, "room-password-123");
    var invitation = invitations.invite(a.principal(), r.id(), b.username());
    assertThatThrownBy(() -> invitations.accept(b.principal(), invitation.id(), "wrong-password"))
        .isInstanceOf(ApiException.class);
    clock.advance(Duration.ofSeconds(61));
    assertThatThrownBy(
            () -> invitations.accept(b.principal(), invitation.id(), "room-password-123"))
        .isInstanceOf(ApiException.class);
  }
}
