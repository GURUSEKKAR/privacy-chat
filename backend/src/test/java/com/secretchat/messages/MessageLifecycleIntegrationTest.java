package com.secretchat.messages;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.common.ApiException;
import com.secretchat.expiry.ExpiryWorker;
import com.secretchat.expiry.ResetService;
import com.secretchat.protocol.RoomDtos.*;
import com.secretchat.testsupport.IntegrationTestBase;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MessageLifecycleIntegrationTest extends IntegrationTestBase {
  @Autowired ExpiryWorker expiry;
  @Autowired ResetService resets;

  @Test
  void viewOnceOfflineCatchupLateJoinAndAllViewedErasure() {
    var a = register("owner");
    var b = register("member");
    var c = register("offline");
    var d = register("late");
    var room = createRoom(a, 600, 3600, null);
    UUID id = room.id();
    addMember(a, id, b, null);
    addMember(a, id, c, null);
    UUID sa = openSession(a, id);
    UUID sb = openSession(b, id);
    var message = send(a, id, sa, fakeCiphertext("once"));
    assertThat(messages.pending(b.principal(), id, sb)).hasSize(1);
    var first = receipts.acknowledgeView(b.principal(), id, message.messageId(), sb);
    // PostgreSQL timestamps retain microseconds; Linux clocks can provide nanoseconds.
    assertThat(receipts.acknowledgeView(b.principal(), id, message.messageId(), sb).viewedAt())
        .isEqualTo(first.viewedAt().truncatedTo(ChronoUnit.MICROS));
    roomSessions.end(b.principal(), id, sb);
    UUID sb2 = openSession(b, id);
    assertThat(messages.pending(b.principal(), id, sb2)).isEmpty();
    assertThatThrownBy(() -> receipts.acknowledgeView(b.principal(), id, message.messageId(), sb2))
        .isInstanceOf(ApiException.class);
    addMember(a, id, d, null);
    assertThat(messages.pending(d.principal(), id, openSession(d, id))).isEmpty();
    UUID sc = openSession(c, id);
    assertThat(messages.pending(c.principal(), id, sc)).hasSize(1);
    assertThat(receipts.acknowledgeView(c.principal(), id, message.messageId(), sc).deleted())
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select ciphertext is null from messages where id = ?",
                Boolean.class,
                message.messageId()))
        .isTrue();
  }

  @Test
  void ttlIsEnforcedBeforeCleanupAndCleanupIsRepeatable() {
    var a = register("ttl");
    var b = register("ttl");
    UUID id = createRoom(a, 60, 3600, null).id();
    addMember(a, id, b, null);
    UUID sa = openSession(a, id);
    UUID sb = openSession(b, id);
    var m = send(a, id, sa, fakeCiphertext("ttl"));
    clock.advance(Duration.ofSeconds(61));
    assertThat(messages.pending(b.principal(), id, sb)).isEmpty();
    assertThatThrownBy(() -> receipts.acknowledgeView(b.principal(), id, m.messageId(), sb))
        .isInstanceOf(ApiException.class);
    assertThat(expiry.runOnce()).isPositive();
    assertThat(expiry.runOnce()).isZero();
  }

  @Test
  void overdueCycleBlocksAccessBeforeWorkerRuns() {
    var a = register("deadline");
    var b = register("deadline");
    UUID id = createRoom(a, 600, 60, null).id();
    addMember(a, id, b, null);
    UUID sa = openSession(a, id);
    UUID sb = openSession(b, id);
    clock.advance(Duration.ofSeconds(61));
    assertThatThrownBy(() -> messages.pending(b.principal(), id, sb))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> send(a, id, sa, fakeCiphertext("overdue")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> openSession(b, id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> invitations.invite(a.principal(), id, register("late").username()))
        .isInstanceOf(ApiException.class);
    assertThat(resets.resetIfDue(id)).isTrue();
  }

  @Test
  void resetRevokesMembersPreservesOwnerAndRequiresRekey() {
    var a = register("reset");
    var b = register("reset");
    UUID id = createRoom(a, 600, 3600, null).id();
    addMember(a, id, b, null);
    UUID sa = openSession(a, id);
    UUID sb = openSession(b, id);
    send(a, id, sa, fakeCiphertext("reset"));
    clock.advance(Duration.ofSeconds(3601));
    assertThat(resets.resetIfDue(id)).isTrue();
    assertThat(resets.resetIfDue(id)).isFalse();
    var locked = rooms.get(a.principal(), id);
    assertThat(locked.state()).isEqualTo("LOCKED_PENDING_REKEY");
    assertThat(locked.generation()).isEqualTo(2);
    assertThatThrownBy(() -> openSession(a, id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> messages.pending(b.principal(), id, sb))
        .isInstanceOf(ApiException.class);
    assertThat(
            jdbc.queryForObject("select count(*) from messages where room_id = ?", Long.class, id))
        .isZero();
    mls.commit(
        a.principal(),
        id,
        new MlsCommitRequest(
            locked.generation(),
            locked.mlsEpoch(),
            CommitPurpose.RESET_REKEY,
            null,
            null,
            null,
            null,
            true));
    assertThat(rooms.get(a.principal(), id).state()).isEqualTo("ACTIVE");
    assertThat(rooms.members(a.principal(), id)).hasSize(1);
    assertThat(openSession(a, id)).isNotNull();
    addMember(a, id, b, null);
    assertThat(messages.pending(b.principal(), id, openSession(b, id))).isEmpty();
  }
}
