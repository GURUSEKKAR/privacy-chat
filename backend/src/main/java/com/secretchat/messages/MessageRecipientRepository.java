package com.secretchat.messages;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRecipientRepository extends JpaRepository<MessageRecipient, UUID> {
  List<MessageRecipient> findByMessageId(UUID messageId);

  Optional<MessageRecipient> findByMessageIdAndRecipientUserId(UUID messageId, UUID userId);

  List<MessageRecipient> findByRecipientUserIdAndMessageIdIn(UUID userId, List<UUID> messageIds);

  /**
   * Marks viewed rows of an ended session as consumed (bookkeeping; delivery is already blocked).
   */
  @Modifying
  @Query(
      "update MessageRecipient r set r.consumedAt = :now"
          + " where r.firstViewSessionId in :sessionIds and r.consumedAt is null")
  int consumeForSessions(@Param("sessionIds") List<UUID> sessionIds, @Param("now") Instant now);

  /** Unviewed rows of a user's undeleted messages in a room (for revocation). */
  @Query(
      "select r from MessageRecipient r, Message m where r.messageId = m.id and m.roomId = :roomId"
          + " and m.deletedAt is null and r.recipientUserId = :userId and r.viewedAt is null")
  List<MessageRecipient> findUnviewedInRoom(
      @Param("roomId") UUID roomId, @Param("userId") UUID userId);
}
