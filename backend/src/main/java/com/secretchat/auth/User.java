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
