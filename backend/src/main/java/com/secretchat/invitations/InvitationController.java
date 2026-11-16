package com.secretchat.invitations;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.protocol.RoomDtos.AcceptInviteRequest;
import com.secretchat.protocol.RoomDtos.InvitationView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Invitee side of invitations. */
@RestController
@RequestMapping("/api/v1/invites")
public class InvitationController {
  private final InvitationService service;

  public InvitationController(InvitationService service) {
    this.service = service;
  }

  @GetMapping
  public List<InvitationView> mine(@AuthenticationPrincipal AuthPrincipal p) {
    return service.mine(p);
  }

  @PostMapping("/{invitationId}/accept")
  public InvitationView accept(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID invitationId,
      @Valid @RequestBody(required = false) AcceptInviteRequest req) {
    return service.accept(p, invitationId, req == null ? null : req.roomPassword());
  }

  @PostMapping("/{invitationId}/decline")
  public InvitationView decline(
      @AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID invitationId) {
    return service.decline(p, invitationId);
  }
}
