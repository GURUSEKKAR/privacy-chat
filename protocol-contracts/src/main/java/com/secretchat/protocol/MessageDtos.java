package com.secretchat.protocol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Message envelope and event DTOs. Payloads are opaque MLS PrivateMessages; no plaintext and no
 * client-defined send time is trusted by the server.
 */
public final class MessageDtos {
  private MessageDtos() {}

  /** Maximum decoded ciphertext size accepted by the server. */
  public static final int MAX_CIPHERTEXT_BYTES = 65_536;

  /**
   * STOMP SEND body for {@code /app/rooms/{roomId}/send}. Sender identity comes from the principal.
   */
  public record SendMessageFrame(
      @NotNull UUID sessionId,
      @NotNull Integer generation,
      @NotNull Long epoch,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$") String idempotencyKey,
      @NotBlank @Size(max = 90_000) String ciphertext) {}

  public record ViewAckRequest(@NotNull UUID sessionId) {}

  public record ViewAckResult(UUID messageId, Instant viewedAt, boolean deleted) {}

  public record MessageView(
      UUID messageId,
      UUID roomId,
      int generation,
      long epoch,
      UUID senderUserId,
      String senderUsername,
      String ciphertext,
      Instant createdAt,
      Instant expiresAt) {}

  public enum EventType {
    /** Ciphertext for the receiving session. */
    MESSAGE,
    /** Acknowledges the sender's own SEND. */
    SENT,
    /** All eligible recipients viewed or TTL hit; drop the in-memory copy at session end. */
    MESSAGE_DELETED,
    /** A new MLS epoch exists; fetch and process commits. */
    MLS_COMMIT,
    /** The receiving device was added; fetch its Welcome. */
    WELCOME,
    /** Room reset happened: non-owner members are revoked. */
    ROOM_RESET,
    /** Owner must submit the MLS rekey before the room reopens. */
    REKEY_REQUIRED,
    MEMBERSHIP_REVOKED,
    ERROR
  }

  /** Server-to-client event on {@code /user/queue/chat}. */
  public record ChatEvent(
      EventType type,
      UUID roomId,
      UUID messageId,
      Integer generation,
      Long epoch,
      UUID senderUserId,
      String senderUsername,
      String ciphertext,
      Instant createdAt,
      Instant expiresAt,
      String idempotencyKey,
      String code,
      String detail) {

    public static ChatEvent control(EventType type, UUID roomId, Integer generation, Long epoch) {
      return new ChatEvent(
          type, roomId, null, generation, epoch, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent error(UUID roomId, String idempotencyKey, String code, String detail) {
      return new ChatEvent(
          EventType.ERROR,
          roomId,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          idempotencyKey,
          code,
          detail);
    }
  }

  public record ErrorResponse(int status, String code, String message) {}
}
