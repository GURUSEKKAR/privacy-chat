package com.secretchat.invitations;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.User;
import com.secretchat.auth.UserRepository;
import com.secretchat.common.ApiException;
import com.secretchat.common.Hashing;
import com.secretchat.config.AppProperties;
import com.secretchat.devices.Device;
import com.secretchat.devices.DeviceKeyPackage;
import com.secretchat.devices.DeviceService;
import com.secretchat.memberships.Membership;
import com.secretchat.memberships.MembershipRepository;
import com.secretchat.protocol.RoomDtos.ClaimedKeyPackage;
import com.secretchat.protocol.RoomDtos.InvitationView;
import com.secretchat.protocol.Usernames;
import com.secretchat.rooms.Room;
import com.secretchat.rooms.RoomAccess;
import com.secretchat.rooms.RoomPasswords;
import com.secretchat.rooms.RoomRepository;
import com.secretchat.rooms.RoomState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invite-by-user-ID flow: owner invites -> invitee accepts on an approved device (+ room password)
 * -> owner claims the device KeyPackage, verifies its fingerprint and commits an MLS Add.
 */
@Service
public class InvitationService {
  private static final List<Invitation.Status> OPEN =
      List.of(Invitation.Status.PENDING, Invitation.Status.ACCEPTED);

  private final InvitationRepository invitations;
  private final RoomRepository rooms;
  private final MembershipRepository memberships;
  private final UserRepository users;
  private final DeviceService deviceService;
  private final RoomAccess access;
  private final RoomPasswords roomPasswords;
  private final AppProperties props;
  private final Clock clock;

  public InvitationService(
      InvitationRepository invitations,
      RoomRepository rooms,
      MembershipRepository memberships,
      UserRepository users,
      DeviceService deviceService,
      RoomAccess access,
      RoomPasswords roomPasswords,
      AppProperties props,
      Clock clock) {
    this.invitations = invitations;
    this.rooms = rooms;
    this.memberships = memberships;
    this.users = users;
    this.deviceService = deviceService;
    this.access = access;
    this.roomPasswords = roomPasswords;
    this.props = props;
    this.clock = clock;
  }

  @Transactional
  public InvitationView invite(AuthPrincipal p, UUID roomId, String username) {
    RoomAccess.Access a = access.requireOwner(p, roomId, true);
    Room room = a.room();
    RoomAccess.requireRoomActive(room, clock.instant());
    User invitee =
        users
            .findByUsername(Usernames.normalize(username))
            .filter(User::isActive)
            .orElseThrow(() ->
                new ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "USER_NOT_FOUND",
                    "User not found"));
    if (invitee.getId().equals(p.userId())) {
      throw ApiException.badRequest("SELF_INVITE", "Owner is already a member");
    }
    memberships
        .findByRoomIdAndUserId(roomId, invitee.getId())
        .filter(m -> m.isActive() && m.getGeneration() == room.getGeneration())
        .ifPresent(m -> {
          throw ApiException.conflict("ALREADY_MEMBER", "User is already a member");
        });
    Instant now = clock.instant();
    if (memberships.countReservedSeats(roomId, room.getGeneration(), now)
        >= props.maxRoomMembers()) {
      throw ApiException.conflict("ROOM_FULL", "Room member limit reached");
    }
    Instant expires = now.plus(props.invitationTtl());
    if (room.getNextResetAt().isBefore(expires)) {
      expires = room.getNextResetAt();
    }
    Invitation inv =
        new Invitation(roomId, invitee.getId(), p.userId(), room.getGeneration(), now, expires);
    try {
      invitations.saveAndFlush(inv);
    } catch (DataIntegrityViolationException e) {
      throw ApiException.conflict("ALREADY_INVITED", "An open invitation already exists");
    }
    return view(inv, room, invitee, null);
  }

  @Transactional(readOnly = true)
  public List<InvitationView> mine(AuthPrincipal p) {
    RoomAccess.requireApprovedDevice(p);
    Instant now = clock.instant();
    return invitations
        .findByInviteeUserIdAndStatusIn(p.userId(), List.of(Invitation.Status.PENDING))
        .stream()
        .filter(i -> now.isBefore(i.getExpiresAt()))
        .map(i -> view(i, rooms.findById(i.getRoomId()).orElse(null), null, null))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<InvitationView> forRoom(AuthPrincipal p, UUID roomId) {
    RoomAccess.Access a = access.requireOwner(p, roomId, false);
    Instant now = clock.instant();
    return invitations.findByRoomIdAndStatusIn(roomId, OPEN).stream()
        .filter(
            i -> i.getGeneration() == a.room().getGeneration() && now.isBefore(i.getExpiresAt()))
        .map(i ->
            view(
                i,
                a.room(),
                users.findById(i.getInviteeUserId()).orElse(null),
                i.getAcceptedDeviceId() == null
                    ? null
                    : deviceService.find(i.getAcceptedDeviceId()).orElse(null)))
        .toList();
  }

  @Transactional(noRollbackFor = ApiException.class)
  public InvitationView accept(AuthPrincipal p, UUID invitationId, String roomPassword) {
    RoomAccess.requireApprovedDevice(p);
    Invitation inv =
        invitations
            .findById(invitationId)
            .filter(i -> i.getInviteeUserId().equals(p.userId()))
            .orElseThrow(ApiException::notFound);
    Room room = rooms.lockById(inv.getRoomId()).orElseThrow(ApiException::notFound);
    Instant now = clock.instant();
    if (room.getState() != RoomState.ACTIVE) {
      throw ApiException.conflict("RESET_IN_PROGRESS", "Room is resetting");
    }
    if (inv.getStatus() != Invitation.Status.PENDING) {
      throw ApiException.conflict("INVITATION_NOT_PENDING", "Invitation is no longer pending");
    }
    if (inv.getGeneration() != room.getGeneration()) {
      inv.setStatus(Invitation.Status.INVALIDATED);
      throw ApiException.conflict(
          "STALE_INVITATION", "Invitation belongs to a previous room cycle");
    }
    if (!now.isBefore(inv.getExpiresAt())) {
      inv.setStatus(Invitation.Status.EXPIRED);
      throw ApiException.gone("INVITATION_EXPIRED", "Invitation expired");
    }
    roomPasswords.require(p, room, roomPassword);
    // Invitations do not reserve seats. Enforce capacity again under the room lock at acceptance.
    if (memberships.countReservedSeats(room.getId(), room.getGeneration(), now)
        >= props.maxRoomMembers()) {
      throw ApiException.conflict("ROOM_FULL", "Room member limit reached");
    }
    inv.accept(p.deviceId());
    Membership m =
        memberships
            .findByRoomIdAndUserId(room.getId(), p.userId())
            .orElseGet(() -> Membership.pending(room.getId(), p.userId()));
    m.awaitKeys(p.deviceId(), room.getGeneration());
    memberships.save(m);
    return view(inv, room, null, null);
  }

  @Transactional
  public InvitationView decline(AuthPrincipal p, UUID invitationId) {
    Invitation inv =
        invitations
            .findById(invitationId)
            .filter(i -> i.getInviteeUserId().equals(p.userId()))
            .orElseThrow(ApiException::notFound);
    if (inv.getStatus() == Invitation.Status.PENDING) {
      inv.setStatus(Invitation.Status.DECLINED);
    }
    return view(inv, rooms.findById(inv.getRoomId()).orElse(null), null, null);
  }

  /** Owner claims one KeyPackage of the device that accepted the invitation. */
  @Transactional
  public ClaimedKeyPackage claimKeyPackage(AuthPrincipal p, UUID roomId, UUID invitationId) {
    RoomAccess.Access a = access.requireOwner(p, roomId, false);
    RoomAccess.requireRoomActive(a.room(), clock.instant());
    Invitation inv =
        invitations
            .findById(invitationId)
            .filter(i -> i.getRoomId().equals(roomId))
            .orElseThrow(ApiException::notFound);
    if (inv.getStatus() != Invitation.Status.ACCEPTED
        || inv.getGeneration() != a.room().getGeneration()
        || !clock.instant().isBefore(inv.getExpiresAt())) {
      throw ApiException.conflict("INVITATION_NOT_ACCEPTED", "Invitation is not awaiting keys");
    }
    Device device =
        deviceService
            .find(inv.getAcceptedDeviceId())
            .filter(Device::isApproved)
            .orElseThrow(
                () -> ApiException.forbidden("DEVICE_NOT_APPROVED", "Invitee device not approved"));
    DeviceKeyPackage kp =
        deviceService
            .claimKeyPackage(device.getId())
            .orElseThrow(() ->
                ApiException.conflict("NO_KEY_PACKAGE", "Invitee has no available KeyPackage"));
    return new ClaimedKeyPackage(
        inv.getId(),
        inv.getInviteeUserId(),
        device.getId(),
        device.getFingerprint(),
        Hashing.b64(kp.getPublicKeyPackage()));
  }

  private InvitationView view(Invitation i, Room room, User invitee, Device device) {
    return new InvitationView(
        i.getId(),
        i.getRoomId(),
        room == null ? null : room.getDisplayCode(),
        i.getInviteeUserId(),
        invitee == null ? null : invitee.getUsername(),
        i.getStatus().name(),
        i.getExpiresAt(),
        i.getGeneration(),
        i.getAcceptedDeviceId(),
        device == null ? null : device.getFingerprint());
  }
}
