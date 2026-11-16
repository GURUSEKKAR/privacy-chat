package com.secretchat.expiry;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.invitations.InvitationRepository;
import com.secretchat.memberships.Membership;
import com.secretchat.memberships.MembershipRepository;
import com.secretchat.memberships.MembershipRevoker;
import com.secretchat.messages.MessageRepository;
import com.secretchat.messages.RoomSessionService;
import com.secretchat.protocol.MessageDtos.ChatEvent;
import com.secretchat.protocol.MessageDtos.EventType;
import com.secretchat.protocol.RoomDtos.ResetResult;
import com.secretchat.rooms.Room;
import com.secretchat.rooms.RoomAccess;
import com.secretchat.rooms.RoomRepository;
import com.secretchat.rooms.RoomState;
import com.secretchat.websocket.ChatNotifier;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-exempt membership reset (blueprint 8.3). All steps run in one transaction under the room
 * row lock, so a crash leaves either the old state or the fully LOCKED state, never a partial one.
 */
@Service
public class ResetService {
  private static final Logger log = LoggerFactory.getLogger(ResetService.class);

  private final RoomRepository rooms;
  private final MembershipRepository memberships;
  private final MembershipRevoker revoker;
  private final InvitationRepository invitations;
  private final MessageRepository messages;
  private final RoomSessionService roomSessions;
  private final RoomAccess access;
  private final ChatNotifier notifier;
  private final Clock clock;

  public ResetService(
      RoomRepository rooms,
      MembershipRepository memberships,
      MembershipRevoker revoker,
      InvitationRepository invitations,
      MessageRepository messages,
      RoomSessionService roomSessions,
      RoomAccess access,
      ChatNotifier notifier,
      Clock clock) {
    this.rooms = rooms;
    this.memberships = memberships;
    this.revoker = revoker;
    this.invitations = invitations;
    this.messages = messages;
    this.roomSessions = roomSessions;
    this.access = access;
    this.notifier = notifier;
    this.clock = clock;
  }

  /** Manual "Reset Room Now": identical invariants to the timed reset. */
  @Transactional
  public ResetResult resetNow(AuthPrincipal p, UUID roomId) {
    access.requireOwner(p, roomId, false);
    Room room = doReset(roomId, true);
    return new ResetResult(room.getGeneration(), room.getState().name(), room.getNextResetAt());
  }

  /** Timed reset for one due room, in its own transaction. Returns true if a reset happened. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean resetIfDue(UUID roomId) {
    Room before = rooms.findById(roomId).orElse(null);
    if (before == null) {
      return false;
    }
    int generation = before.getGeneration();
    Room after = doReset(roomId, false);
    return after.getGeneration() != generation;
  }

  private Room doReset(UUID roomId, boolean manual) {
    Room room = rooms.lockById(roomId).orElseThrow();
    Instant now = clock.instant();
    if (room.getState() != RoomState.ACTIVE) {
      if (manual) {
        RoomAccess.requireRoomActive(room);
      }
      return room;
    }
    if (!manual && now.isBefore(room.getNextResetAt())) {
      return room;
    }
    room.transitionTo(RoomState.RESET_PENDING);
    rooms.saveAndFlush(room);
    int oldGeneration = room.getGeneration();
    room.incrementGeneration();

    invitations.invalidateOpen(roomId);
    List<Membership> all = memberships.findByRoomId(roomId);
    for (Membership m : all) {
      if (m.getRole() == Membership.Role.OWNER) {
        m.carryOwnerInto(room.getGeneration());
        memberships.save(m);
      } else if (m.getState() == Membership.State.PENDING_KEYS) {
        m.revoke("RESET", now);
        memberships.save(m);
      } else {
        revoker.revoke(room, m, "RESET");
      }
    }
    roomSessions.endAllInRoom(roomId);
    // Previous room data is cleared: every message row of the room is physically removed.
    int deleted = messages.deleteAllInRoom(roomId);
    room.transitionTo(RoomState.LOCKED_PENDING_REKEY);
    rooms.saveAndFlush(room);
    log.info(
        "Room {} reset (generation {} -> {}, {} message rows removed); awaiting owner rekey",
        roomId,
        oldGeneration,
        room.getGeneration(),
        deleted);

    ChatEvent resetEvent =
        ChatEvent.control(EventType.ROOM_RESET, roomId, room.getGeneration(), room.getMlsEpoch());
    for (Membership m : all) {
      if (m.getDeviceId() != null && m.getRole() != Membership.Role.OWNER) {
        notifier.toDevice(m.getUserId(), m.getDeviceId(), resetEvent);
      }
    }
    notifier.toDevice(
        room.getOwnerUserId(),
        room.getOwnerDeviceId(),
        ChatEvent.control(
            EventType.REKEY_REQUIRED, roomId, room.getGeneration(), room.getMlsEpoch()));
    return room;
  }
}
