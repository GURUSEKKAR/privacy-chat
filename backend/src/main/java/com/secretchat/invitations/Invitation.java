package com.secretchat.invitations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
public class Invitation {
  public enum Status {
    PENDING,
    /** Invitee accepted on an approved device; owner must now add it via MLS. */
    ACCEPTED,
    COMPLETED,
    DECLINED,
    EXPIRED,
    INVALIDATED
  }

  @Id private UUID id;

  @Column(name = "room_id", nullable = false)
  private UUID roomId;

  @Column(name = "invitee_user_id", nullable = false)
  private UUID inviteeUserId;

  @Column(name = "invited_by_user_id", nullable = false)
  private UUID invitedByUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(nullable = false)
  private int generation;

  @Column(name = "accepted_device_id")
  private UUID acceptedDeviceId;

  protected Invitation() {}

  public Invitation(
      UUID roomId,
      UUID inviteeUserId,
      UUID invitedBy,
      int generation,
      Instant now,
      Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.roomId = roomId;
    this.inviteeUserId = inviteeUserId;
    this.invitedByUserId = invitedBy;
    this.generation = generation;
    this.status = Status.PENDING;
    this.createdAt = now;
    this.expiresAt = expiresAt;
  }

  public boolean isOpen() {
    return status == Status.PENDING || status == Status.ACCEPTED;
  }

  public void accept(UUID deviceId) {
    status = Status.ACCEPTED;
    acceptedDeviceId = deviceId;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoomId() {
    return roomId;
  }

  public UUID getInviteeUserId() {
    return inviteeUserId;
  }

  public Status getStatus() {
    return status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public int getGeneration() {
    return generation;
  }

  public UUID getAcceptedDeviceId() {
    return acceptedDeviceId;
  }
}
