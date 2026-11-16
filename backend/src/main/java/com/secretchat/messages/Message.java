package com.secretchat.messages;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Opaque MLS PrivateMessage ciphertext + routing metadata. No plaintext ever. */
@Entity
@Table(name = "messages")
public class Message {
  @Id private UUID id;

  @Column(name = "room_id", nullable = false)
  private UUID roomId;

  @Column(nullable = false)
  private int generation;

  @Column(name = "sender_user_id", nullable = false)
  private UUID senderUserId;

  @Column(name = "sender_device_id", nullable = false)
  private UUID senderDeviceId;

  @Column(nullable = false)
  private long epoch;

  @Column(name = "ciphertext")
  private byte[] ciphertext;

  @Column(name = "idempotency_key", nullable = false, length = 64)
  private String idempotencyKey;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "delete_reason", length = 16)
  private String deleteReason;

  protected Message() {}

  public Message(
      UUID roomId,
      int generation,
      UUID senderUserId,
      UUID senderDeviceId,
      long epoch,
      byte[] ciphertext,
      String idempotencyKey,
      Instant createdAt,
      Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.roomId = roomId;
    this.generation = generation;
    this.senderUserId = senderUserId;
    this.senderDeviceId = senderDeviceId;
    this.epoch = epoch;
    this.ciphertext = ciphertext;
    this.idempotencyKey = idempotencyKey;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  /** Server time >= expires_at wins: the message is no longer deliverable. */
  public boolean isLive(Instant now) {
    return deletedAt == null && now.isBefore(expiresAt);
  }

  public void erase(String reason, Instant now) {
    if (deletedAt == null) {
      ciphertext = null;
      deletedAt = now;
      deleteReason = reason;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoomId() {
    return roomId;
  }

  public int getGeneration() {
    return generation;
  }

  public UUID getSenderUserId() {
    return senderUserId;
  }

  public UUID getSenderDeviceId() {
    return senderDeviceId;
  }

  public long getEpoch() {
    return epoch;
  }

  public byte[] getCiphertext() {
    return ciphertext;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public String getDeleteReason() {
    return deleteReason;
  }
}
