package com.secretchat.messages;

import com.secretchat.auth.AuthEvents;
import com.secretchat.auth.AuthPrincipal;
import com.secretchat.common.ApiException;
import com.secretchat.rooms.Room;
import com.secretchat.rooms.RoomAccess;
import com.secretchat.rooms.RoomPasswords;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens/ends viewing sessions. Ending a session is what makes viewed messages permanently
 * unavailable to that user (view-once semantics).
 */
@Service
public class RoomSessionService {
  private final RoomSessionRepository roomSessions;
  private final MessageRecipientRepository recipients;
  private final RoomAccess access;
  private final RoomPasswords roomPasswords;
  private final Clock clock;

  public RoomSessionService(
      RoomSessionRepository roomSessions,
      MessageRecipientRepository recipients,
      RoomAccess access,
      RoomPasswords roomPasswords,
      Clock clock) {
    this.roomSessions = roomSessions;
    this.recipients = recipients;
    this.access = access;
    this.roomPasswords = roomPasswords;
    this.clock = clock;
  }

  @Transactional(noRollbackFor = ApiException.class)
  public RoomSession open(AuthPrincipal p, UUID roomId, String roomPassword) {
    RoomAccess.Access a = access.requireActiveMember(p, roomId, true);
    Room room = a.room();
    RoomAccess.requireRoomActive(room);
    roomPasswords.require(p, room, roomPassword);
    endAllForUserInRoom(roomId, p.userId());
    RoomSession s =
        new RoomSession(
            roomId,
            p.userId(),
            p.deviceId(),
            p.authSessionId(),
            room.getGeneration(),
            clock.instant());
    return roomSessions.save(s);
  }

  /** Validates that a session id belongs to the caller, the room and the current generation. */
  public RoomSession requireOpen(AuthPrincipal p, Room room, UUID sessionId) {
    RoomSession s = roomSessions.findById(sessionId).orElseThrow(ApiException::notFound);
    if (!s.getUserId().equals(p.userId())
        || !s.getDeviceId().equals(p.deviceId())
        || !s.getAuthSessionId().equals(p.authSessionId())
        || !s.getRoomId().equals(room.getId())) {
      throw ApiException.forbidden("SESSION_MISMATCH", "Session does not belong to caller");
    }
    if (!s.isOpen() || s.getRoomGeneration() != room.getGeneration()) {
      throw ApiException.gone("SESSION_ENDED", "Room session ended");
    }
    return s;
  }

  @Transactional
  public void end(AuthPrincipal p, UUID roomId, UUID sessionId) {
    RoomSession s = roomSessions.findById(sessionId).orElseThrow(ApiException::notFound);
    if (!s.getUserId().equals(p.userId())
        || !s.getRoomId().equals(roomId)
        || !s.getDeviceId().equals(p.deviceId())
        || !s.getAuthSessionId().equals(p.authSessionId())) {
      throw ApiException.notFound();
    }
    endAll(List.of(s));
  }

  @Transactional
  public void endAllForAuthSession(UUID authSessionId) {
    endAll(roomSessions.findByAuthSessionIdAndEndedAtIsNull(authSessionId));
  }

  @Transactional
  public void endAllForUserInRoom(UUID roomId, UUID userId) {
    endAll(roomSessions.findByRoomIdAndUserIdAndEndedAtIsNull(roomId, userId));
  }

  @Transactional
  public List<RoomSession> endAllInRoom(UUID roomId) {
    List<RoomSession> open = roomSessions.findByRoomIdAndEndedAtIsNull(roomId);
    endAll(open);
    return open;
  }

  public List<RoomSession> openSessions(UUID roomId) {
    return roomSessions.findByRoomIdAndEndedAtIsNull(roomId);
  }

  private void endAll(List<RoomSession> list) {
    if (list.isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    list.forEach(s -> s.end(now));
    roomSessions.saveAllAndFlush(list);
    recipients.consumeForSessions(list.stream().map(RoomSession::getId).toList(), now);
  }

  @EventListener
  public void onAuthSessionEnded(AuthEvents.AuthSessionEnded e) {
    endAllForAuthSession(e.authSessionId());
  }

  @EventListener
  public void onDeviceRevoked(AuthEvents.DeviceSessionsRevoked e) {
    endAll(roomSessions.findByDeviceIdAndEndedAtIsNull(e.deviceId()));
  }
}
