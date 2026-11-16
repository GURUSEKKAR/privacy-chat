package com.secretchat.messages;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.UserRepository;
import com.secretchat.common.ApiException;
import com.secretchat.common.Hashing;
import com.secretchat.common.RateLimiter;
import com.secretchat.devices.MlsPublicVerifier;
import com.secretchat.memberships.Membership;
import com.secretchat.memberships.MembershipRepository;
import com.secretchat.protocol.MessageDtos;
import com.secretchat.protocol.MessageDtos.ChatEvent;
import com.secretchat.protocol.MessageDtos.EventType;
import com.secretchat.protocol.MessageDtos.MessageView;
import com.secretchat.protocol.MessageDtos.SendMessageFrame;
import com.secretchat.protocol.RoomDtos.PrivacyMode;
import com.secretchat.receipts.ReceiptService;
import com.secretchat.rooms.Room;
import com.secretchat.rooms.RoomAccess;
import com.secretchat.websocket.ChatNotifier;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encrypted relay: validates, stores ciphertext + recipient snapshot, routes to entitled devices.
 */
@Service
public class MessageService {
  private final MessageRepository messages;
  private final MessageRecipientRepository recipients;
  private final MembershipRepository memberships;
  private final UserRepository users;
  private final RoomAccess access;
  private final RoomSessionService roomSessions;
  private final ReceiptService receipts;
  private final MlsPublicVerifier verifier;
  private final ChatNotifier notifier;
  private final RateLimiter sendLimiter;
  private final Clock clock;

  public MessageService(
      MessageRepository messages,
      MessageRecipientRepository recipients,
      MembershipRepository memberships,
      UserRepository users,
      RoomAccess access,
      RoomSessionService roomSessions,
      ReceiptService receipts,
      MlsPublicVerifier verifier,
      ChatNotifier notifier,
      @Qualifier("sendLimiter") RateLimiter sendLimiter,
      Clock clock) {
    this.messages = messages;
    this.recipients = recipients;
    this.memberships = memberships;
    this.users = users;
    this.access = access;
    this.roomSessions = roomSessions;
    this.receipts = receipts;
    this.verifier = verifier;
    this.notifier = notifier;
    this.sendLimiter = sendLimiter;
    this.clock = clock;
  }

  @Transactional
  public ChatEvent send(AuthPrincipal p, UUID roomId, SendMessageFrame f) {
    if (!sendLimiter.tryAcquire("send|" + p.userId())) {
      throw ApiException.rateLimited();
    }
    // Room row lock orders sends against commits/resets (no send in a stale epoch/generation).
    RoomAccess.Access a = access.requireActiveMember(p, roomId, true);
    Room room = a.room();
    RoomAccess.requireRoomActive(room);
    if (f.generation() != room.getGeneration()) {
      throw ApiException.conflict("STALE_GENERATION", "Room generation changed");
    }
    if (f.epoch() != room.getMlsEpoch()) {
      throw ApiException.conflict("WRONG_EPOCH", "Process pending MLS commits before sending");
    }
    RoomSession session = roomSessions.requireOpen(p, room, f.sessionId());

    Optional<Message> existing =
        messages.findBySenderDeviceIdAndIdempotencyKey(p.deviceId(), f.idempotencyKey());
    if (existing.isPresent()) {
      Message m = existing.get();
      if (!m.getRoomId().equals(roomId)) {
        throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key already used");
      }
      return sentEvent(m);
    }
    byte[] ciphertext = Hashing.decodeBase64(f.ciphertext(), "ciphertext");
    if (ciphertext.length == 0 || ciphertext.length > MessageDtos.MAX_CIPHERTEXT_BYTES) {
      throw ApiException.badRequest("PAYLOAD_SIZE", "Ciphertext size out of bounds");
    }
    if (!verifier.isPrivateMessage(ciphertext)) {
      throw ApiException.badRequest(
          "NOT_PRIVATE_MESSAGE", "Application data must be an MLS PrivateMessage");
    }
    Instant now = clock.instant();
    Message m =
        new Message(
            roomId,
            room.getGeneration(),
            p.userId(),
            p.deviceId(),
            room.getMlsEpoch(),
            ciphertext,
            f.idempotencyKey(),
            now,
            now.plusSeconds(room.getMessageTtlSeconds()));
    messages.saveAndFlush(m);

    List<Membership> snapshot = recipientSnapshot(room);
    for (Membership member : snapshot) {
      MessageRecipient r = new MessageRecipient(m.getId(), member.getUserId());
      if (member.getUserId().equals(p.userId())) {
        // The sender has, by definition, seen what they sent in this session.
        r.markViewed(session.getId(), now);
      }
      recipients.save(r);
    }
    recipients.flush();

    String senderName = p.username();
    ChatEvent delivery =
        new ChatEvent(
            EventType.MESSAGE,
            roomId,
            m.getId(),
            m.getGeneration(),
            m.getEpoch(),
            p.userId(),
            senderName,
            f.ciphertext(),
            m.getCreatedAt(),
            m.getExpiresAt(),
            null,
            null,
            null);
    Set<UUID> openUsers = openSessionUsers(roomId, room.getGeneration());
    for (Membership member : snapshot) {
      if (!member.getUserId().equals(p.userId()) && openUsers.contains(member.getUserId())) {
        notifier.toDevice(member.getUserId(), member.getDeviceId(), delivery);
      }
    }
    // Single-member room: sender is the only recipient, so the message is complete immediately.
    receipts.completeIfAllViewed(m, now);
    return sentEvent(m);
  }

  private List<Membership> recipientSnapshot(Room room) {
    List<Membership> active =
        memberships.findByRoomIdAndState(room.getId(), Membership.State.ACTIVE).stream()
            .filter(m -> m.getGeneration() == room.getGeneration() && m.getDeviceId() != null)
            .filter(m -> m.getJoinedEpoch() <= room.getMlsEpoch())
            .toList();
    if (room.getPrivacyMode() == PrivacyMode.STRICT_LIVE) {
      Set<UUID> live = openSessionUsers(room.getId(), room.getGeneration());
      return active.stream().filter(m -> live.contains(m.getUserId())).toList();
    }
    return active;
  }

  private Set<UUID> openSessionUsers(UUID roomId, int generation) {
    return roomSessions.openSessions(roomId).stream()
        .filter(s -> s.getRoomGeneration() == generation)
        .map(RoomSession::getUserId)
        .collect(Collectors.toSet());
  }

  /**
   * Pending ciphertext for this session, applying the canDeliver predicate from the blueprint
   * (section 8.1) to every candidate. TTL is enforced here synchronously, not only by the worker.
   */
  @Transactional(readOnly = true)
  public List<MessageView> pending(AuthPrincipal p, UUID roomId, UUID sessionId) {
    RoomAccess.Access a = access.requireActiveMember(p, roomId, false);
    Room room = a.room();
    RoomAccess.requireRoomActive(room);
    RoomSession session = roomSessions.requireOpen(p, room, sessionId);
    Instant now = clock.instant();
    List<Message> live = messages.findLive(roomId, room.getGeneration(), now);
    if (live.isEmpty()) {
      return List.of();
    }
    Map<UUID, MessageRecipient> mine =
        recipients
            .findByRecipientUserIdAndMessageIdIn(
                p.userId(), live.stream().map(Message::getId).toList())
            .stream()
            .collect(Collectors.toMap(MessageRecipient::getMessageId, Function.identity()));
    Map<UUID, String> names =
        users.findAllById(live.stream().map(Message::getSenderUserId).distinct().toList()).stream()
            .collect(Collectors.toMap(u -> u.getId(), u -> u.getUsername()));
    return live.stream()
        .filter(m -> canDeliver(a.membership(), session, m, mine.get(m.getId()), now))
        .map(m ->
            new MessageView(
                m.getId(),
                roomId,
                m.getGeneration(),
                m.getEpoch(),
                m.getSenderUserId(),
                names.get(m.getSenderUserId()),
                Hashing.b64(m.getCiphertext()),
                m.getCreatedAt(),
                m.getExpiresAt()))
        .toList();
  }

  /** Blueprint 8.1. Authentication, approved device, ACTIVE room and membership checked above. */
  static boolean canDeliver(
      Membership membership,
      RoomSession session,
      Message m,
      MessageRecipient receipt,
      Instant now) {
    if (!m.isLive(now)) {
      return false;
    }
    if (m.getGeneration() != session.getRoomGeneration()) {
      return false;
    }
    if (receipt == null || receipt.getEligibility() != MessageRecipient.Eligibility.ELIGIBLE) {
      return false;
    }
    if (m.getEpoch() < membership.getJoinedEpoch()) {
      return false;
    }
    if (m.getSenderUserId().equals(membership.getUserId())) {
      // Senders cannot decrypt their own MLS ciphertext; never re-deliver to them.
      return false;
    }
    return receipt.getViewedAt() == null || session.getId().equals(receipt.getFirstViewSessionId());
  }

  private static ChatEvent sentEvent(Message m) {
    return new ChatEvent(
        EventType.SENT,
        m.getRoomId(),
        m.getId(),
        m.getGeneration(),
        m.getEpoch(),
        m.getSenderUserId(),
        null,
        null,
        m.getCreatedAt(),
        m.getExpiresAt(),
        m.getIdempotencyKey(),
        null,
        null);
  }
}
