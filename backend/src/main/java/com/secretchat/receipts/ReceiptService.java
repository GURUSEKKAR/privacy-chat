package com.secretchat.receipts;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.common.ApiException;
import com.secretchat.memberships.Membership;
import com.secretchat.memberships.MembershipRepository;
import com.secretchat.messages.Message;
import com.secretchat.messages.MessageRecipient;
import com.secretchat.messages.MessageRecipientRepository;
import com.secretchat.messages.MessageRepository;
import com.secretchat.messages.RoomSessionService;
import com.secretchat.protocol.MessageDtos.ChatEvent;
import com.secretchat.protocol.MessageDtos.EventType;
import com.secretchat.protocol.MessageDtos.ViewAckResult;
import com.secretchat.rooms.RoomAccess;
import com.secretchat.websocket.ChatNotifier;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** View receipts and the "all eligible recipients viewed" early-deletion rule. */
@Service
public class ReceiptService {
  private final MessageRepository messages;
  private final MessageRecipientRepository recipients;
  private final MembershipRepository memberships;
  private final RoomAccess access;
  private final RoomSessionService roomSessions;
  private final ChatNotifier notifier;
  private final Clock clock;

  public ReceiptService(
      MessageRepository messages,
      MessageRecipientRepository recipients,
      MembershipRepository memberships,
      RoomAccess access,
      RoomSessionService roomSessions,
      ChatNotifier notifier,
      Clock clock) {
    this.messages = messages;
    this.recipients = recipients;
    this.memberships = memberships;
    this.access = access;
    this.roomSessions = roomSessions;
    this.notifier = notifier;
    this.clock = clock;
  }

  /**
   * Idempotent VIEWED(messageId, sessionId). The message row is locked so concurrent
   * acknowledgements serialize: exactly one of them performs the deletion.
   */
  @Transactional
  public ViewAckResult acknowledgeView(
      AuthPrincipal p, UUID roomId, UUID messageId, UUID sessionId) {
    RoomAccess.Access a = access.requireActiveMember(p, roomId, true);
    Message m =
        messages
            .lockById(messageId)
            .filter(x -> x.getRoomId().equals(roomId))
            .orElseThrow(ApiException::notFound);
    Instant now = clock.instant();
    roomSessions.requireOpen(p, a.room(), sessionId);
    MessageRecipient r =
        recipients
            .findByMessageIdAndRecipientUserId(messageId, p.userId())
            .orElseThrow(ApiException::notFound);
    if (r.getEligibility() != MessageRecipient.Eligibility.ELIGIBLE) {
      throw ApiException.forbidden("NOT_A_RECIPIENT", "Not an eligible recipient");
    }
    if (r.getViewedAt() != null && !sessionId.equals(r.getFirstViewSessionId())) {
      throw ApiException.gone("CONSUMED", "Message was already viewed in another session");
    }
    if (r.getViewedAt() == null) {
      if (m.getGeneration() != a.room().getGeneration() || !m.isLive(now)) {
        throw ApiException.gone("EXPIRED", "Message expired or deleted");
      }
      r.markViewed(sessionId, now);
      recipients.saveAndFlush(r);
    }
    boolean deleted = completeIfAllViewed(m, now);
    return new ViewAckResult(messageId, r.getViewedAt(), deleted || m.getDeletedAt() != null);
  }

  /** Caller must hold the message row lock. Returns true if this call erased the ciphertext. */
  public boolean completeIfAllViewed(Message m, Instant now) {
    if (m.getDeletedAt() != null) {
      return false;
    }
    List<MessageRecipient> rows = recipients.findByMessageId(m.getId());
    boolean allViewed =
        rows.stream()
            .filter(x -> x.getEligibility() == MessageRecipient.Eligibility.ELIGIBLE)
            .allMatch(x -> x.getViewedAt() != null);
    if (!allViewed) {
      return false;
    }
    m.erase("ALL_VIEWED", now);
    messages.saveAndFlush(m);
    broadcastDeleted(m);
    return true;
  }

  /**
   * A recipient was revoked (removal or reset) before viewing: mark them ineligible without
   * fabricating a view, then let the remaining eligible recipients decide completion.
   */
  @Transactional
  public void revokeRecipient(UUID roomId, UUID userId, String reason) {
    Instant now = clock.instant();
    for (MessageRecipient r : recipients.findUnviewedInRoom(roomId, userId)) {
      Message m = messages.lockById(r.getMessageId()).orElse(null);
      if (m == null) {
        continue;
      }
      r.markIneligible(reason);
      recipients.saveAndFlush(r);
      completeIfAllViewed(m, now);
    }
  }

  private void broadcastDeleted(Message m) {
    ChatEvent event =
        new ChatEvent(
            EventType.MESSAGE_DELETED,
            m.getRoomId(),
            m.getId(),
            m.getGeneration(),
            m.getEpoch(),
            null,
            null,
            null,
            null,
            null,
            null,
            m.getDeleteReason(),
            null);
    for (Membership member :
        memberships.findByRoomIdAndState(m.getRoomId(), Membership.State.ACTIVE)) {
      if (member.getDeviceId() != null) {
        notifier.toDevice(member.getUserId(), member.getDeviceId(), event);
      }
    }
  }
}
