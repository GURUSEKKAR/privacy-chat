package com.secretchat.crypto_relay;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.common.ApiException;
import com.secretchat.common.Hashing;
import com.secretchat.invitations.Invitation;
import com.secretchat.invitations.InvitationRepository;
import com.secretchat.memberships.Membership;
import com.secretchat.memberships.MembershipRepository;
import com.secretchat.memberships.MembershipRevoker;
import com.secretchat.protocol.MessageDtos.ChatEvent;
import com.secretchat.protocol.MessageDtos.EventType;
import com.secretchat.protocol.RoomDtos.CommitPurpose;
import com.secretchat.protocol.RoomDtos.MlsCommitRequest;
import com.secretchat.protocol.RoomDtos.MlsCommitResult;
import com.secretchat.protocol.RoomDtos.MlsEventView;
import com.secretchat.protocol.RoomDtos.WelcomeView;
import com.secretchat.rooms.Room;
import com.secretchat.rooms.RoomAccess;
import com.secretchat.rooms.RoomState;
import com.secretchat.websocket.ChatNotifier;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders opaque MLS control messages. Only the owner's approved device may commit (v1), and every
 * commit must build on the room's current epoch, so concurrent commits cannot fork the group: the
 * loser gets 409 WRONG_EPOCH and must re-sync.
 */
@Service
public class MlsRelayService {
  private static final int MAX_MLS_BYTES = 256 * 1024;

  private final MlsEventRepository events;
  private final MembershipRepository memberships;
  private final InvitationRepository invitations;
  private final MembershipRevoker revoker;
  private final RoomAccess access;
  private final ChatNotifier notifier;
  private final Clock clock;

  public MlsRelayService(
      MlsEventRepository events,
      MembershipRepository memberships,
      InvitationRepository invitations,
      MembershipRevoker revoker,
      RoomAccess access,
      ChatNotifier notifier,
      Clock clock) {
    this.events = events;
    this.memberships = memberships;
    this.invitations = invitations;
    this.revoker = revoker;
    this.access = access;
    this.notifier = notifier;
    this.clock = clock;
  }

  @Transactional
  public MlsCommitResult commit(AuthPrincipal p, UUID roomId, MlsCommitRequest req) {
    RoomAccess.Access a = access.requireOwner(p, roomId, true);
    Room room = a.room();
    Instant now = clock.instant();
    if (req.generation() != room.getGeneration()) {
      throw ApiException.conflict("STALE_GENERATION", "Room generation changed");
    }
    byte[] commit = decodeMls(req.commit(), "commit");
    byte[] welcome = decodeMls(req.welcome(), "welcome");
    CommitPurpose purpose = req.purpose();

    long newEpoch;
    if (purpose == CommitPurpose.RESET_REKEY) {
      if (room.getState() != RoomState.LOCKED_PENDING_REKEY) {
        throw ApiException.conflict("NOT_LOCKED", "Room is not awaiting a rekey");
      }
      if (req.newGroup()) {
        if (commit != null || welcome != null) {
          throw ApiException.badRequest(
              "INVALID_REKEY", "A fresh group carries no commit or welcome");
        }
        newEpoch = 0;
      } else {
        requireEpoch(room, req.fromEpoch());
        requireCommit(commit);
        newEpoch = room.getMlsEpoch() + 1;
      }
    } else {
      RoomAccess.requireRoomActive(room, clock.instant());
      requireEpoch(room, req.fromEpoch());
      requireCommit(commit);
      if (req.newGroup()) {
        throw ApiException.badRequest("INVALID_COMMIT", "newGroup is only valid for RESET_REKEY");
      }
      newEpoch = room.getMlsEpoch() + 1;
    }

    List<Membership> added = new ArrayList<>();
    List<Invitation> completed = new ArrayList<>();
    List<Membership> removed = new ArrayList<>();
    switch (purpose) {
      case ADD -> {
        if (welcome == null
            || req.addedInvitationIds() == null
            || req.addedInvitationIds().isEmpty()) {
          throw ApiException.badRequest("INVALID_COMMIT", "ADD requires a welcome and invitations");
        }
        for (UUID invitationId : new HashSet<>(req.addedInvitationIds())) {
          Invitation inv =
              invitations
                  .findById(invitationId)
                  .filter(i -> i.getRoomId().equals(roomId))
                  .orElseThrow(ApiException::notFound);
          if (inv.getStatus() != Invitation.Status.ACCEPTED
              || inv.getGeneration() != room.getGeneration()
              || !now.isBefore(inv.getExpiresAt())) {
            throw ApiException.conflict(
                "INVITATION_NOT_ACCEPTED", "Invitation is not awaiting keys");
          }
          Membership m =
              memberships
                  .findByRoomIdAndUserId(roomId, inv.getInviteeUserId())
                  .filter(x -> x.getState() == Membership.State.PENDING_KEYS)
                  .filter(x -> inv.getAcceptedDeviceId().equals(x.getDeviceId()))
                  .orElseThrow(() ->
                      ApiException.conflict("MEMBERSHIP_NOT_PENDING", "Member not awaiting keys"));
          added.add(m);
          completed.add(inv);
        }
      }
      case REMOVE -> {
        if (req.removedUserIds() == null || req.removedUserIds().isEmpty()) {
          throw ApiException.badRequest("INVALID_COMMIT", "REMOVE requires removedUserIds");
        }
        for (UUID userId : new HashSet<>(req.removedUserIds())) {
          Membership m =
              memberships
                  .findByRoomIdAndUserId(roomId, userId)
                  .filter(Membership::isActive)
                  .filter(x -> x.getRole() != Membership.Role.OWNER)
                  .orElseThrow(ApiException::notFound);
          removed.add(m);
        }
      }
      case UPDATE, RESET_REKEY -> {
        if (welcome != null) {
          throw ApiException.badRequest("INVALID_COMMIT", "Unexpected welcome");
        }
      }
    }

    if (purpose == CommitPurpose.RESET_REKEY) {
      // Old-cycle control events are no longer needed by anyone.
      events.deleteBeforeGeneration(roomId, room.getGeneration());
    }
    long seq = events.maxSequence(roomId) + 1;
    events.saveAndFlush(
        new MlsEvent(
            roomId,
            room.getGeneration(),
            seq,
            newEpoch,
            purpose.name(),
            commit,
            welcome,
            p.deviceId(),
            now));
    room.advanceEpoch(newEpoch);

    Set<UUID> skipNotify = new HashSet<>();
    skipNotify.add(p.userId());
    for (Membership m : added) {
      m.activate(newEpoch, now);
      memberships.save(m);
      skipNotify.add(m.getUserId());
    }
    completed.forEach(i -> i.setStatus(Invitation.Status.COMPLETED));
    for (Membership m : removed) {
      revoker.revoke(room, m, "REMOVED");
    }
    if (purpose == CommitPurpose.RESET_REKEY) {
      room.transitionTo(RoomState.ACTIVE);
      room.scheduleNextReset(now);
    }

    ChatEvent commitEvent =
        ChatEvent.control(EventType.MLS_COMMIT, roomId, room.getGeneration(), newEpoch);
    for (Membership m : memberships.findByRoomIdAndState(roomId, Membership.State.ACTIVE)) {
      if (m.getDeviceId() != null && !skipNotify.contains(m.getUserId())) {
        notifier.toDevice(m.getUserId(), m.getDeviceId(), commitEvent);
      }
    }
    ChatEvent welcomeEvent =
        ChatEvent.control(EventType.WELCOME, roomId, room.getGeneration(), newEpoch);
    added.forEach(m -> notifier.toDevice(m.getUserId(), m.getDeviceId(), welcomeEvent));
    return new MlsCommitResult(room.getGeneration(), newEpoch, seq, room.getState().name());
  }

  /** Commits after the caller's join epoch in the current generation, in order. */
  @Transactional(readOnly = true)
  public List<MlsEventView> eventsAfter(AuthPrincipal p, UUID roomId, long afterEpoch) {
    RoomAccess.Access a = access.requireActiveMember(p, roomId, false);
    long from = Math.max(afterEpoch, a.membership().getJoinedEpoch());
    return events.findAfter(roomId, a.room().getGeneration(), from).stream()
        .filter(e -> e.getOpaqueCommit() != null)
        .map(e ->
            new MlsEventView(
                e.getSequenceNo(),
                e.getGeneration(),
                e.getEpoch(),
                e.getEventType(),
                Hashing.b64(e.getOpaqueCommit())))
        .toList();
  }

  /** The Welcome that added this member (encrypted to its KeyPackage; opaque to the server). */
  @Transactional(readOnly = true)
  public WelcomeView welcome(AuthPrincipal p, UUID roomId) {
    RoomAccess.Access a = access.requireActiveMember(p, roomId, false);
    if (a.membership().getRole() == Membership.Role.OWNER) {
      throw ApiException.notFound();
    }
    MlsEvent e =
        events
            .findByRoomIdAndGenerationAndEpoch(
                roomId, a.room().getGeneration(), a.membership().getJoinedEpoch())
            .filter(x -> x.getWelcome() != null)
            .orElseThrow(ApiException::notFound);
    return new WelcomeView(e.getGeneration(), e.getEpoch(), Hashing.b64(e.getWelcome()));
  }

  private static void requireEpoch(Room room, Long fromEpoch) {
    if (fromEpoch == null || fromEpoch != room.getMlsEpoch()) {
      throw ApiException.conflict("WRONG_EPOCH", "Commit does not build on the current epoch");
    }
  }

  private static void requireCommit(byte[] commit) {
    // MLSMessage: version=mls10 (0x0001), wire_format public(0x0001) or private(0x0002).
    if (commit == null
        || commit.length < 4
        || commit[0] != 0
        || commit[1] != 1
        || commit[2] != 0
        || (commit[3] != 1 && commit[3] != 2)) {
      throw ApiException.badRequest("INVALID_COMMIT", "Commit must be an MLS handshake message");
    }
  }

  private static byte[] decodeMls(String b64, String field) {
    if (b64 == null || b64.isEmpty()) {
      return null;
    }
    byte[] bytes = Hashing.decodeBase64(b64, field);
    if (bytes.length > MAX_MLS_BYTES) {
      throw ApiException.badRequest("PAYLOAD_SIZE", field + " too large");
    }
    return bytes;
  }
}
