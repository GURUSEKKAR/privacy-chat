package com.secretchat.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
  @Id private UUID id;

  @Column(name = "username_normalized", nullable = false, length = 32)
  private String username;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "totp_secret")
  private String totpSecret;

  @Column(name = "totp_enabled", nullable = false)
  private boolean totpEnabled;

  @Column(name = "totp_last_step", nullable = false)
  private long totpLastStep = -1;

  @Column(name = "recovery_hashes", nullable = false)
  private String recoveryHashes = "";

  @Column(name = "active_session_id")
  private UUID activeSessionId;

  public String getTotpSecret() {
    return totpSecret;
  }

  public void setTotpSecret(String value) {
    totpSecret = value;
  }

  public boolean isTotpEnabled() {
    return totpEnabled;
  }

  public void setTotpEnabled(boolean value) {
    totpEnabled = value;
  }

  public long getTotpLastStep() {
    return totpLastStep;
  }

  public void setTotpLastStep(long value) {
    totpLastStep = value;
  }

  public String getRecoveryHashes() {
    return recoveryHashes;
  }

  public void setRecoveryHashes(String value) {
    recoveryHashes = value;
  }

  public UUID getActiveSessionId() {
    return activeSessionId;
  }

  public void setActiveSessionId(UUID value) {
    activeSessionId = value;
  }

  protected User() {}

  public User(UUID id, String username, String passwordHash, Instant createdAt) {
    this.id = id;
    this.username = username;
    this.passwordHash = passwordHash;
    this.status = "ACTIVE";
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public boolean isActive() {
    return "ACTIVE".equals(status);
  }
}
