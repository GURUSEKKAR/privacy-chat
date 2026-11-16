package com.secretchat.rooms;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.crypto_relay.MlsRelayService;
import com.secretchat.expiry.ResetService;
import com.secretchat.invitations.InvitationService;
import com.secretchat.messages.MessageService;
import com.secretchat.messages.RoomSession;
import com.secretchat.messages.RoomSessionService;
import com.secretchat.protocol.MessageDtos.MessageView;
import com.secretchat.protocol.MessageDtos.ViewAckRequest;
import com.secretchat.protocol.MessageDtos.ViewAckResult;
import com.secretchat.protocol.RoomDtos.ClaimedKeyPackage;
import com.secretchat.protocol.RoomDtos.CreateRoomRequest;
import com.secretchat.protocol.RoomDtos.InvitationView;
import com.secretchat.protocol.RoomDtos.InviteRequest;
import com.secretchat.protocol.RoomDtos.MemberView;
import com.secretchat.protocol.RoomDtos.MlsCommitRequest;
import com.secretchat.protocol.RoomDtos.MlsCommitResult;
import com.secretchat.protocol.RoomDtos.MlsEventView;
import com.secretchat.protocol.RoomDtos.OpenSessionRequest;
import com.secretchat.protocol.RoomDtos.ResetResult;
import com.secretchat.protocol.RoomDtos.RoomSessionView;
import com.secretchat.protocol.RoomDtos.RoomView;
import com.secretchat.protocol.RoomDtos.WelcomeView;
import com.secretchat.receipts.ReceiptService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {
  private final RoomService rooms;
  private final InvitationService invitations;
  private final RoomSessionService roomSessions;
  private final MessageService messages;
  private final ReceiptService receipts;
  private final MlsRelayService mls;
  private final ResetService resets;

  public RoomController(
      RoomService rooms,
      InvitationService invitations,
      RoomSessionService roomSessions,
      MessageService messages,
      ReceiptService receipts,
      MlsRelayService mls,
      ResetService resets) {
    this.rooms = rooms;
    this.invitations = invitations;
    this.roomSessions = roomSessions;
    this.messages = messages;
    this.receipts = receipts;
    this.mls = mls;
    this.resets = resets;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoomView create(
      @AuthenticationPrincipal AuthPrincipal p, @Valid @RequestBody CreateRoomRequest req) {
    return rooms.create(p, req);
  }

  @GetMapping
  public List<RoomView> mine(@AuthenticationPrincipal AuthPrincipal p) {
    return rooms.listMine(p);
  }

  @GetMapping("/{roomId}")
  public RoomView get(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID roomId) {
    return rooms.get(p, roomId);
  }

  @GetMapping("/{roomId}/members")
  public List<MemberView> members(
      @AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID roomId) {
    return rooms.members(p, roomId);
  }

  // --- invitations (owner side) ---

  @PostMapping("/{roomId}/invites")
  @ResponseStatus(HttpStatus.CREATED)
  public InvitationView invite(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID roomId,
      @Valid @RequestBody InviteRequest req) {
    return invitations.invite(p, roomId, req.username());
  }

  @GetMapping("/{roomId}/invites")
  public List<InvitationView> roomInvites(
      @AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID roomId) {
    return invitations.forRoom(p, roomId);
  }

  @PostMapping("/{roomId}/invites/{invitationId}/key-package")
  public ClaimedKeyPackage claimKeyPackage(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID roomId,
      @PathVariable UUID invitationId) {
    return invitations.claimKeyPackage(p, roomId, invitationId);
  }

  // --- viewing sessions and messages ---

  @PostMapping("/{roomId}/sessions")
  @ResponseStatus(HttpStatus.CREATED)
  public RoomSessionView openSession(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID roomId,
      @Valid @RequestBody(required = false) OpenSessionRequest req) {
    RoomSession s = roomSessions.open(p, roomId, req == null ? null : req.roomPassword());
    return new RoomSessionView(s.getId(), s.getRoomId(), s.getRoomGeneration(), s.getConnectedAt());
  }

  @DeleteMapping("/{roomId}/sessions/{sessionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void endSession(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID roomId,
      @PathVariable UUID sessionId) {
    roomSessions.end(p, roomId, sessionId);
  }

  @GetMapping("/{roomId}/messages")
  public List<MessageView> pending(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID roomId,
      @RequestParam UUID sessionId,
      @RequestParam(defaultValue = "true") boolean pending) {
    return messages.pending(p, roomId, sessionId);
  }

  @PostMapping("/{roomId}/messages/{messageId}/view")
  public ViewAckResult view(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID roomId,
      @PathVariable UUID messageId,
      @Valid @RequestBody ViewAckRequest req) {
    return receipts.acknowledgeView(p, roomId, messageId, req.sessionId());
  }

  // --- MLS control and reset ---

  @PostMapping("/{roomId}/mls/commit")
  public MlsCommitResult commit(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID roomId,
      @Valid @RequestBody MlsCommitRequest req) {
    return mls.commit(p, roomId, req);
  }

  @GetMapping("/{roomId}/mls/events")
  public List<MlsEventView> mlsEvents(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID roomId,
      @RequestParam(defaultValue = "-1") long afterEpoch) {
    return mls.eventsAfter(p, roomId, afterEpoch);
  }

  @GetMapping("/{roomId}/mls/welcome")
  public WelcomeView welcome(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID roomId) {
    return mls.welcome(p, roomId);
  }

  @PostMapping("/{roomId}/reset")
  public ResetResult reset(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID roomId) {
    return resets.resetNow(p, roomId);
  }
}
