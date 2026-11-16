package com.secretchat.devices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A device identity: the public MLS signature key (Ed25519). Private keys never reach here. */
@Entity
@Table(name = "devices")
public class Device {
  public enum Status {
    PENDING,
    APPROVED,
    REVOKED
  }

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(name = "identity_public_key", nullable = false)
  private byte[] identityPublicKey;

  @Column(nullable = false, length = 64)
  private String fingerprint;

  @Column(nullable = false, length = 16)
  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "approved_by_device_id")
  private UUID approvedByDeviceId;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected Device() {}

  public Device(
      UUID id,
      UUID userId,
      String name,
      byte[] identityPublicKey,
      String fingerprint,
      Instant now) {
    this.id = id;
    this.userId = userId;
    this.name = name;
    this.identityPublicKey = identityPublicKey.clone();
    this.fingerprint = fingerprint;
    this.status = Status.PENDING;
    this.createdAt = now;
  }

  public void approve(UUID byDeviceId, Instant now) {
    if (status != Status.PENDING) {
      throw new IllegalStateException("device not pending");
    }
    status = Status.APPROVED;
    approvedAt = now;
    approvedByDeviceId = byDeviceId;
  }

  public void revoke(Instant now) {
    status = Status.REVOKED;
    revokedAt = now;
  }

  public boolean isApproved() {
    return status == Status.APPROVED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getName() {
    return name;
  }

  public byte[] getIdentityPublicKey() {
    return identityPublicKey.clone();
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public Status getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
