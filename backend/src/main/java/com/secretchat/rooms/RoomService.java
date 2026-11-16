package com.secretchat.rooms;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.User;
import com.secretchat.auth.UserRepository;
import com.secretchat.common.ApiException;
import com.secretchat.common.Hashing;
import com.secretchat.devices.Device;
import com.secretchat.devices.DeviceRepository;
import com.secretchat.memberships.Membership;
import com.secretchat.memberships.MembershipRepository;
import com.secretchat.protocol.RoomDtos.CreateRoomRequest;
import com.secretchat.protocol.RoomDtos.MemberView;
import com.secretchat.protocol.RoomDtos.PrivacyMode;
import com.secretchat.protocol.RoomDtos.RoomView;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {
  private static final Pattern DISPLAY_CODE = Pattern.compile("^[A-Za-z0-9-]{4,32}$");
  private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

  private final RoomRepository rooms;
  private final MembershipRepository memberships;
  private final UserRepository users;
  private final DeviceRepository devices;
  private final RoomAccess access;
  private final RoomPasswords roomPasswords;
  private final Clock clock;

  public RoomService(
      RoomRepository rooms,
      MembershipRepository memberships,
      UserRepository users,
      DeviceRepository devices,
      RoomAccess access,
      RoomPasswords roomPasswords,
      Clock clock) {
    this.rooms = rooms;
    this.memberships = memberships;
    this.users = users;
    this.devices = devices;
    this.access = access;
    this.roomPasswords = roomPasswords;
    this.clock = clock;
  }

  @Transactional
  public RoomView create(AuthPrincipal p, CreateRoomRequest req) {
    RoomAccess.requireApprovedDevice(p);
    String code = req.displayCode();
    if (code == null || code.isBlank()) {
      code = randomCode();
    } else if (!DISPLAY_CODE.matcher(code).matches()) {
      throw ApiException.badRequest(
          "INVALID_DISPLAY_CODE", "Display code must be 4-32 of A-Z a-z 0-9 -");
    }
    Instant now = clock.instant();
    Room room =
        new Room(
            UUID.randomUUID(),
            code,
            p.userId(),
            p.deviceId(),
            roomPasswords.hash(req.roomPassword()),
            req.messageTtlSeconds(),
            req.resetIntervalSeconds(),
            req.privacyMode() == null ? PrivacyMode.TEMPORARY_CATCH_UP : req.privacyMode(),
            now);
    if (req.name() != null && !req.name().isBlank()) room.setName(req.name().trim());
    try {
      rooms.saveAndFlush(room);
    } catch (DataIntegrityViolationException e) {
      throw ApiException.conflict("DISPLAY_CODE_TAKEN", "Display code is not available");
    }
    Membership owner = Membership.owner(room.getId(), p.userId(), p.deviceId(), now);
    memberships.save(owner);
    return view(room, owner);
  }

  @Transactional
  public RoomView update(
      AuthPrincipal p, UUID roomId, com.secretchat.protocol.RoomDtos.UpdateRoomRequest req) {
    var a = access.requireOwner(p, roomId, true);
    if (req.name() == null || req.name().isBlank() || req.name().trim().length() > 80)
      throw ApiException.badRequest("INVALID_NAME", "Group name must contain 1–80 characters.");
    String password = req.roomPassword();
    if (req.changePassword() && password != null && !password.isEmpty() && password.length() < 8)
      throw ApiException.badRequest(
          "INVALID_ROOM_PASSWORD",
          "Group password must contain at least 8 characters, or be removed.");
    a.room()
        .updateSettings(
            req.name().trim(),
            req.messageTtlSeconds(),
            req.resetIntervalSeconds(),
            req.changePassword(),
            req.changePassword() ? roomPasswords.hash(password) : null,
            clock.instant());
    return view(a.room(), a.membership());
  }

  @Transactional(readOnly = true)
  public List<RoomView> listMine(AuthPrincipal p) {
    RoomAccess.requireApprovedDevice(p);
    return memberships
        .findByUserIdAndStateIn(
            p.userId(), List.of(Membership.State.ACTIVE, Membership.State.PENDING_KEYS))
        .stream()
        .filter(m -> p.deviceId().equals(m.getDeviceId()))
        .map(m -> rooms.findById(m.getRoomId()).map(r -> view(r, m)).orElse(null))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  @Transactional(readOnly = true)
  public RoomView get(AuthPrincipal p, UUID roomId) {
    RoomAccess.Access a = access.requireRelated(p, roomId, false);
    return view(a.room(), a.membership());
  }

  @Transactional(readOnly = true)
  public List<MemberView> members(AuthPrincipal p, UUID roomId) {
    RoomAccess.Access a = access.requireActiveMember(p, roomId, false);
    List<Membership> active = memberships.findByRoomIdAndState(roomId, Membership.State.ACTIVE);
    Map<UUID, User> byId =
        users.findAllById(active.stream().map(Membership::getUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    Map<UUID, Device> deviceById =
        devices.findAllById(active.stream().map(Membership::getDeviceId).toList()).stream()
            .collect(Collectors.toMap(Device::getId, Function.identity()));
    return active.stream()
        .filter(m -> m.getGeneration() == a.room().getGeneration())
        .map(m ->
            new MemberView(
                m.getUserId(),
                byId.get(m.getUserId()).getUsername(),
                m.getRole().name(),
                m.getState().name(),
                m.getJoinedAt(),
                m.getJoinedEpoch(),
                deviceById.containsKey(m.getDeviceId())
                    ? deviceById.get(m.getDeviceId()).getFingerprint()
                    : null))
        .toList();
  }

  public RoomView view(Room r, Membership m) {
    String ownerFingerprint =
        devices.findById(r.getOwnerDeviceId()).map(Device::getFingerprint).orElse(null);
    return new RoomView(
        r.getId(),
        r.getDisplayCode(),
        r.getOwnerUserId(),
        r.getOwnerDeviceId(),
        ownerFingerprint,
        m == null ? null : m.getRole().name(),
        m == null ? null : m.getState().name(),
        r.getState().name(),
        r.getGeneration(),
        r.getMlsEpoch(),
        r.getMessageTtlSeconds(),
        r.getResetIntervalSeconds(),
        r.getNextResetAt(),
        r.getPrivacyMode(),
        r.getPasswordHash() != null,
        r.getName());
  }

  private String randomCode() {
    for (int attempt = 0; attempt < 10; attempt++) {
      byte[] bytes = Hashing.randomBytes(10);
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(CODE_ALPHABET[(b & 0xff) % CODE_ALPHABET.length]);
      }
      String code = sb.toString();
      if (!rooms.existsByDisplayCode(code)) {
        return code;
      }
    }
    throw ApiException.conflict("DISPLAY_CODE_TAKEN", "Could not allocate a display code");
  }
}
