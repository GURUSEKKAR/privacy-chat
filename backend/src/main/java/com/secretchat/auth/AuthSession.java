package com.secretchat.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Server-side session for one user on one device. Only SHA-256 hashes of the bearer and refresh
 * tokens are stored. Refresh rotates both tokens in place so the session id stays stable.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {
  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "refresh_hash", nullable = false, length = 64)
  private String refreshHash;

  @Column(name = "previous_refresh_hash", length = 64)
  private String previousRefreshHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "refresh_expires_at", nullable = false)
  private Instant refreshExpiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "mfa_verified_at")
  private Instant mfaVerifiedAt;

  public Instant getMfaVerifiedAt() {
    return mfaVerifiedAt;
  }

  public void verifyMfa(Instant now) {
    mfaVerifiedAt = now;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  protected AuthSession() {}

  public AuthSession(
      UUID id,
      UUID userId,
      UUID deviceId,
      String tokenHash,
      String refreshHash,
      Instant createdAt,
      Instant expiresAt,
      Instant refreshExpiresAt) {
    this.id = id;
    this.userId = userId;
    this.deviceId = deviceId;
    this.tokenHash = tokenHash;
    this.refreshHash = refreshHash;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.refreshExpiresAt = refreshExpiresAt;
  }

  public boolean isUsable(Instant now) {
    return revokedAt == null && now.isBefore(expiresAt);
  }

  public void rotate(
      String newTokenHash, String newRefreshHash, Instant expiresAt, Instant refreshExpiresAt) {
    this.previousRefreshHash = this.refreshHash;
    this.tokenHash = newTokenHash;
    this.refreshHash = newRefreshHash;
    this.expiresAt = expiresAt;
    this.refreshExpiresAt = refreshExpiresAt;
  }

  public void revoke(Instant now) {
    if (revokedAt == null) {
      revokedAt = now;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getDeviceId() {
    return deviceId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRefreshExpiresAt() {
    return refreshExpiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
