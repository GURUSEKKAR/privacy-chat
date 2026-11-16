package com.secretchat.memberships;

import com.secretchat.messages.RoomSessionService;
import com.secretchat.protocol.MessageDtos.ChatEvent;
import com.secretchat.protocol.MessageDtos.EventType;
import com.secretchat.receipts.ReceiptService;
import com.secretchat.rooms.Room;
import com.secretchat.websocket.ChatNotifier;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Revokes one non-owner membership and every capability that hung off it. */
@Component
public class MembershipRevoker {
  private final MembershipRepository memberships;
  private final ReceiptService receipts;
  private final RoomSessionService roomSessions;
  private final ChatNotifier notifier;
  private final Clock clock;

  public MembershipRevoker(
      MembershipRepository memberships,
      ReceiptService receipts,
      RoomSessionService roomSessions,
      ChatNotifier notifier,
      Clock clock) {
    this.memberships = memberships;
    this.receipts = receipts;
    this.roomSessions = roomSessions;
    this.notifier = notifier;
    this.clock = clock;
  }

  /** Caller must hold the room row lock. */
  public void revoke(Room room, Membership m, String reason) {
    if (m.getRole() == Membership.Role.OWNER || m.getState() == Membership.State.REVOKED) {
      return;
    }
    m.revoke(reason, clock.instant());
    memberships.saveAndFlush(m);
    roomSessions.endAllForUserInRoom(room.getId(), m.getUserId());
    receipts.revokeRecipient(room.getId(), m.getUserId(), reason);
    if (m.getDeviceId() != null) {
      notifier.toDevice(
          m.getUserId(),
          m.getDeviceId(),
          ChatEvent.control(
              EventType.MEMBERSHIP_REVOKED,
              room.getId(),
              room.getGeneration(),
              room.getMlsEpoch()));
    }
  }
}
