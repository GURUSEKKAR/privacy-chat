package com.secretchat.rooms;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.common.ApiException;
import com.secretchat.memberships.Membership;
import com.secretchat.memberships.MembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Central server-side authorization for room resources. Identity always comes from the
 * authenticated principal, never from request fields. Rooms the caller has no relation to are
 * reported as 404 so room ids cannot be enumerated.
 */
@Component
public class RoomAccess {
  private final RoomRepository rooms;
  private final MembershipRepository memberships;
  private final Clock clock;

  public RoomAccess(RoomRepository rooms, MembershipRepository memberships, Clock clock) {
    this.rooms = rooms;
    this.memberships = memberships;
    this.clock = clock;
  }

  public record Access(Room room, Membership membership) {}

  /** Any membership row (including PENDING_KEYS) bound to this device. */
  public Access requireRelated(AuthPrincipal p, UUID roomId, boolean lock) {
    requireApprovedDevice(p);
    // Always lock the room before loading membership so commits/resets cannot leave a cached
    // pre-lock membership authorizing a send after revocation.
    Room room =
        (lock ? rooms.lockById(roomId) : rooms.findById(roomId))
            .orElseThrow(ApiException::notFound);
    Membership m =
        memberships.findByRoomIdAndUserId(roomId, p.userId()).orElseThrow(ApiException::notFound);
    if (m.getState() == Membership.State.REVOKED) {
      throw ApiException.forbidden("NOT_A_MEMBER", "Membership revoked or expired");
    }
    if (m.getDeviceId() == null || !m.getDeviceId().equals(p.deviceId())) {
      throw ApiException.forbidden("WRONG_DEVICE", "This device is not entitled to the room");
    }
    return new Access(room, m);
  }

  /** ACTIVE membership of this device in the room's current generation. */
  public Access requireActiveMember(AuthPrincipal p, UUID roomId, boolean lock) {
    Access a = requireRelated(p, roomId, lock);
    requireRoomActive(a.room(), clock.instant());
    if (!a.membership().isActive() || a.membership().getGeneration() != a.room().getGeneration()) {
      throw ApiException.forbidden("NOT_A_MEMBER", "Not an active member");
    }
    return a;
  }

  /** Owner on the owner's registered device. */
  public Access requireOwner(AuthPrincipal p, UUID roomId, boolean lock) {
    Access a = requireRelated(p, roomId, lock);
    if (!a.room().isOwner(p.userId()) || !a.room().getOwnerDeviceId().equals(p.deviceId())) {
      throw ApiException.forbidden("OWNER_ONLY", "Only the room owner's device may do this");
    }
    return a;
  }

  public static void requireApprovedDevice(AuthPrincipal p) {
    if (p == null) {
      throw ApiException.unauthorized();
    }
    if (!p.deviceApproved()) {
      throw ApiException.forbidden("DEVICE_NOT_APPROVED", "Approved device required");
    }
  }

  public static void requireRoomActive(Room room) {
    if (room.getState() != RoomState.ACTIVE) {
      throw ApiException.conflict("RESET_IN_PROGRESS", "Room is resetting or awaiting owner rekey");
    }
  }

  /** The deadline is an access boundary even if the reset worker has not run yet. */
  public static void requireRoomActive(Room room, Instant now) {
    requireRoomActive(room);
    if (!now.isBefore(room.getNextResetAt())) {
      throw ApiException.conflict("RESET_IN_PROGRESS", "Room cycle has expired; awaiting reset");
    }
  }
}
