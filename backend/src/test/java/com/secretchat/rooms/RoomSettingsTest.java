package com.secretchat.rooms;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.common.ApiException;
import com.secretchat.protocol.RoomDtos.*;
import com.secretchat.testsupport.IntegrationTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RoomSettingsTest extends IntegrationTestBase {
  @Autowired RoomPasswords passwords;

  @Test
  void ownerCanRenameChangeScheduleAndChangeOrRemovePassword() {
    clock.setInstant(java.time.Instant.parse("2026-11-17T00:00:00Z"));
    var owner = register("settings");
    var r =
        rooms.create(
            owner.principal(),
            new CreateRoomRequest(
                null, null, 600, 3600, PrivacyMode.TEMPORARY_CATCH_UP, "Weekend plans"));
    assertThat(r.name()).isEqualTo("Weekend plans");
    String code = r.displayCode();
    var originalDeadline = r.nextResetAt();
    clock.advance(Duration.ofSeconds(30));
    var same =
        rooms.update(
            owner.principal(), r.id(), new UpdateRoomRequest("New name", 120, 3600, false, null));
    assertThat(same.name()).isEqualTo("New name");
    assertThat(same.nextResetAt()).isEqualTo(originalDeadline);
    assertThat(same.displayCode()).isEqualTo(code);
    var changed =
        rooms.update(
            owner.principal(),
            r.id(),
            new UpdateRoomRequest("New name", 120, 1800, true, "new-group-password"));
    assertThat(changed.nextResetAt()).isEqualTo(clock.instant().plusSeconds(1800));
    assertThat(changed.passwordProtected()).isTrue();
    var entity = roomRepo.findById(r.id()).orElseThrow();
    assertThat(entity.getPasswordHash()).doesNotContain("new-group-password");
    assertThatThrownBy(() -> passwords.require(owner.principal(), entity, "wrong-password"))
        .isInstanceOf(ApiException.class);
    passwords.require(owner.principal(), entity, "new-group-password");
    var removed =
        rooms.update(
            owner.principal(), r.id(), new UpdateRoomRequest("No password", 60, 1800, true, null));
    assertThat(removed.passwordProtected()).isFalse();
  }

  @Test
  void membersCannotChangeSettingsAndExpiredCycleCannotBeExtended() {
    var owner = register("settingsowner");
    var member = register("settingsmember");
    var r = createRoom(owner, 600, 3600, null);
    addMember(owner, r.id(), member, null);
    var update = new UpdateRoomRequest("Unauthorized", 120, 1800, false, null);
    assertThatThrownBy(() -> rooms.update(member.principal(), r.id(), update))
        .isInstanceOf(ApiException.class);
    assertThat(rooms.get(owner.principal(), r.id()).messageTtlSeconds()).isEqualTo(600);
    clock.advance(Duration.ofSeconds(3601));
    assertThatThrownBy(() -> rooms.update(owner.principal(), r.id(), update))
        .isInstanceOf(ApiException.class);
  }
}
