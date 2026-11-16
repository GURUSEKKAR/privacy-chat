package com.secretchat.messages;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable recipient snapshot row captured when the message was created. */
@Entity
@Table(name = "message_recipients")
public class MessageRecipient {
  public enum Eligibility {
    ELIGIBLE,
    INELIGIBLE
  }

  @Id private UUID id;

  @Column(name = "message_id", nullable = false)
  private UUID messageId;

  @Column(name = "recipient_user_id", nullable = false)
  private UUID recipientUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "eligibility_state", nullable = false, length = 16)
  private Eligibility eligibility;

  @Column(name = "ineligible_reason", length = 32)
  private String ineligibleReason;

  @Column(name = "viewed_at")
  private Instant viewedAt;

  @Column(name = "first_view_session_id")
  private UUID firstViewSessionId;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected MessageRecipient() {}

  public MessageRecipient(UUID messageId, UUID recipientUserId) {
    this.id = UUID.randomUUID();
    this.messageId = messageId;
    this.recipientUserId = recipientUserId;
    this.eligibility = Eligibility.ELIGIBLE;
  }

  /** Records the first view only; later acknowledgements never change it. */
  public boolean markViewed(UUID sessionId, Instant now) {
    if (viewedAt != null) {
      return false;
    }
    viewedAt = now;
    firstViewSessionId = sessionId;
    return true;
  }

  /** Revoked before viewing: excluded from completion without fabricating a view. */
  public void markIneligible(String reason) {
    if (viewedAt == null) {
      eligibility = Eligibility.INELIGIBLE;
      ineligibleReason = reason;
    }
  }

  public UUID getMessageId() {
    return messageId;
  }

  public UUID getRecipientUserId() {
    return recipientUserId;
  }

  public Eligibility getEligibility() {
    return eligibility;
  }

  public Instant getViewedAt() {
    return viewedAt;
  }

  public UUID getFirstViewSessionId() {
    return firstViewSessionId;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }
}
