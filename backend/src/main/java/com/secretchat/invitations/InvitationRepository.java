package com.secretchat.invitations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
  List<Invitation> findByInviteeUserIdAndStatusIn(UUID userId, List<Invitation.Status> statuses);

  List<Invitation> findByRoomIdAndStatusIn(UUID roomId, List<Invitation.Status> statuses);

  boolean existsByRoomIdAndInviteeUserIdAndStatusIn(
      UUID roomId, UUID userId, List<Invitation.Status> statuses);

  @Modifying
  @Query(
      "update Invitation i set i.status = com.secretchat.invitations.Invitation.Status.INVALIDATED"
          + " where i.roomId = :roomId and i.status in"
          + " (com.secretchat.invitations.Invitation.Status.PENDING,"
          + " com.secretchat.invitations.Invitation.Status.ACCEPTED)")
  int invalidateOpen(@Param("roomId") UUID roomId);

  @Modifying
  @Query(
      "update Invitation i set i.status = com.secretchat.invitations.Invitation.Status.EXPIRED"
          + " where i.expiresAt <= :now and i.status in"
          + " (com.secretchat.invitations.Invitation.Status.PENDING,"
          + " com.secretchat.invitations.Invitation.Status.ACCEPTED)")
  int expireDue(@Param("now") Instant now);
}
