package com.secretchat.messages;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A viewing session "S" of a user/device in a room. View-once is scoped to this session. */
@Entity
@Table(name = "room_sessions")
public class RoomSession {
  @Id private UUID id;

  @Column(name = "room_id", nullable = false)
  private UUID roomId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(name = "auth_session_id", nullable = false)
  private UUID authSessionId;

  @Column(name = "connected_at", nullable = false)
  private Instant connectedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "room_generation", nullable = false)
  private int roomGeneration;

  protected RoomSession() {}

  public RoomSession(
      UUID roomId, UUID userId, UUID deviceId, UUID authSessionId, int generation, Instant now) {
    this.id = UUID.randomUUID();
    this.roomId = roomId;
    this.userId = userId;
    this.deviceId = deviceId;
    this.authSessionId = authSessionId;
    this.roomGeneration = generation;
    this.connectedAt = now;
  }

  public boolean isOpen() {
    return endedAt == null;
  }

  public void end(Instant now) {
    if (endedAt == null) {
      endedAt = now;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoomId() {
    return roomId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getDeviceId() {
    return deviceId;
  }

  public UUID getAuthSessionId() {
    return authSessionId;
  }

  public Instant getConnectedAt() {
    return connectedAt;
  }

  public int getRoomGeneration() {
    return roomGeneration;
  }
}
