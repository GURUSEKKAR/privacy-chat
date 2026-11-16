package com.secretchat.memberships;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per (room, user). {@code deviceId} is the single device entitled to the room's MLS state;
 * {@code joinedEpoch} is the first epoch whose messages this member may receive.
 */
@Entity
@Table(name = "memberships")
public class Membership {
  public enum Role {
    OWNER,
    MEMBER
  }

  public enum State {
    /** Invite accepted; waiting for the owner's MLS Add commit + Welcome. */
    PENDING_KEYS,
    ACTIVE,
    REVOKED
  }

  @Id private UUID id;

  @Column(name = "room_id", nullable = false)
  private UUID roomId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "device_id")
  private UUID deviceId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private State state;

  @Column(name = "joined_at")
  private Instant joinedAt;

  @Column(name = "joined_epoch", nullable = false)
  private long joinedEpoch;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoke_reason", length = 32)
  private String revokeReason;

  @Column(nullable = false)
  private int generation;

  protected Membership() {}

  public static Membership owner(UUID roomId, UUID userId, UUID deviceId, Instant now) {
    Membership m = new Membership();
    m.id = UUID.randomUUID();
    m.roomId = roomId;
    m.userId = userId;
    m.deviceId = deviceId;
    m.role = Role.OWNER;
    m.state = State.ACTIVE;
    m.joinedAt = now;
    m.joinedEpoch = 0;
    m.generation = 1;
    return m;
  }

  public static Membership pending(UUID roomId, UUID userId) {
    Membership m = new Membership();
    m.id = UUID.randomUUID();
    m.roomId = roomId;
    m.userId = userId;
    m.role = Role.MEMBER;
    m.state = State.REVOKED;
    m.joinedEpoch = Long.MAX_VALUE;
    return m;
  }

  /** (Re)enters PENDING_KEYS for a new invitation acceptance in the given generation. */
  public void awaitKeys(UUID deviceId, int generation) {
    if (role == Role.OWNER) {
      throw new IllegalStateException("owner membership is immutable");
    }
    this.deviceId = deviceId;
    this.generation = generation;
    this.state = State.PENDING_KEYS;
    this.joinedAt = null;
    this.joinedEpoch = Long.MAX_VALUE;
    this.revokedAt = null;
    this.revokeReason = null;
  }

  public void activate(long epoch, Instant now) {
    state = State.ACTIVE;
    joinedEpoch = epoch;
    joinedAt = now;
  }

  public void revoke(String reason, Instant now) {
    if (role == Role.OWNER) {
      throw new IllegalStateException("owner membership cannot be revoked");
    }
    state = State.REVOKED;
    revokedAt = now;
    revokeReason = reason;
  }

  /** Owner keeps membership across resets; only the generation is advanced. */
  public void carryOwnerInto(int generation) {
    this.generation = generation;
  }

  public boolean isActive() {
    return state == State.ACTIVE;
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

  public Role getRole() {
    return role;
  }

  public State getState() {
    return state;
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }

  public long getJoinedEpoch() {
    return joinedEpoch;
  }

  public int getGeneration() {
    return generation;
  }
}
