package com.secretchat.rooms;

import com.secretchat.protocol.RoomDtos.PrivacyMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rooms")
public class Room {
  @Id private UUID id;

  @Column(name = "display_code", nullable = false, length = 32)
  private String displayCode;

  @Column(name = "owner_user_id", nullable = false)
  private UUID ownerUserId;

  @Column(name = "owner_device_id", nullable = false)
  private UUID ownerDeviceId;

  /** Argon2id hash of the optional room entry password; unrelated to any encryption key. */
  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "message_ttl_s", nullable = false)
  private int messageTtlSeconds;

  @Column(name = "reset_interval_s", nullable = false)
  private int resetIntervalSeconds;

  @Column(name = "next_reset_at", nullable = false)
  private Instant nextResetAt;

  @Column(nullable = false)
  private int generation;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private RoomState state;

  @Column(name = "mls_epoch", nullable = false)
  private long mlsEpoch;

  @Enumerated(EnumType.STRING)
  @Column(name = "privacy_mode", nullable = false, length = 32)
  private PrivacyMode privacyMode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Version private long version;

  protected Room() {}

  public Room(
      UUID id,
      String displayCode,
      UUID ownerUserId,
      UUID ownerDeviceId,
      String passwordHash,
      int messageTtlSeconds,
      int resetIntervalSeconds,
      PrivacyMode privacyMode,
      Instant now) {
    this.id = id;
    this.displayCode = displayCode;
    this.ownerUserId = ownerUserId;
    this.ownerDeviceId = ownerDeviceId;
    this.passwordHash = passwordHash;
    this.messageTtlSeconds = messageTtlSeconds;
    this.resetIntervalSeconds = resetIntervalSeconds;
    this.privacyMode = privacyMode;
    this.createdAt = now;
    this.generation = 1;
    this.state = RoomState.ACTIVE;
    this.mlsEpoch = 0;
    this.nextResetAt = now.plusSeconds(resetIntervalSeconds);
  }

  public void transitionTo(RoomState next) {
    if (!state.canTransitionTo(next)) {
      throw new IllegalStateException("Illegal room transition " + state + " -> " + next);
    }
    state = next;
  }

  public void incrementGeneration() {
    generation++;
  }

  public void advanceEpoch(long newEpoch) {
    mlsEpoch = newEpoch;
  }

  public void scheduleNextReset(Instant now) {
    nextResetAt = now.plusSeconds(resetIntervalSeconds);
  }

  public boolean isOwner(UUID userId) {
    return ownerUserId.equals(userId);
  }

  public UUID getId() {
    return id;
  }

  public String getDisplayCode() {
    return displayCode;
  }

  public UUID getOwnerUserId() {
    return ownerUserId;
  }

  public UUID getOwnerDeviceId() {
    return ownerDeviceId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public int getMessageTtlSeconds() {
    return messageTtlSeconds;
  }

  public int getResetIntervalSeconds() {
    return resetIntervalSeconds;
  }

  public Instant getNextResetAt() {
    return nextResetAt;
  }

  public int getGeneration() {
    return generation;
  }

  public RoomState getState() {
    return state;
  }

  public long getMlsEpoch() {
    return mlsEpoch;
  }

  public PrivacyMode getPrivacyMode() {
    return privacyMode;
  }
}
