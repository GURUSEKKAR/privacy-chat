package com.secretchat.protocol;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Room, membership, invitation and MLS control DTOs. */
public final class RoomDtos {
  private RoomDtos() {}

  public enum PrivacyMode {
    /** Default: members who were ACTIVE when a message was sent may fetch it until TTL. */
    TEMPORARY_CATCH_UP,
    /** Only members with an open room session at send time are recipients. */
    STRICT_LIVE
  }

  public record CreateRoomRequest(
      @Size(min = 4, max = 32) String displayCode,
      @Size(min = 8, max = 128) String roomPassword,
      @NotNull @Min(10) @Max(86_400) Integer messageTtlSeconds,
      @NotNull @Min(60) @Max(604_800) Integer resetIntervalSeconds,
      PrivacyMode privacyMode,
      @Size(max = 80) String name) {
    public CreateRoomRequest(
        String displayCode,
        String roomPassword,
        Integer messageTtlSeconds,
        Integer resetIntervalSeconds,
        PrivacyMode privacyMode) {
      this(displayCode, roomPassword, messageTtlSeconds, resetIntervalSeconds, privacyMode, null);
    }
  }

  public record UpdateRoomRequest(
      @NotBlank @Size(max = 80) String name,
      @NotNull @Min(10) @Max(86_400) Integer messageTtlSeconds,
      @NotNull @Min(60) @Max(604_800) Integer resetIntervalSeconds,
      boolean changePassword,
      @Size(max = 128) String roomPassword) {}

  public record RoomView(
      UUID id,
      String displayCode,
      UUID ownerUserId,
      UUID ownerDeviceId,
      String ownerDeviceFingerprint,
      String myRole,
      String myMembershipState,
      String state,
      int generation,
      long mlsEpoch,
      int messageTtlSeconds,
      int resetIntervalSeconds,
      Instant nextResetAt,
      PrivacyMode privacyMode,
      boolean passwordProtected,
      String name) {}

  public record MemberView(
      UUID userId,
      String username,
      String role,
      String state,
      Instant joinedAt,
      long joinedEpoch,
      String deviceFingerprint) {}

  public record InviteRequest(@NotBlank @Size(max = 64) String username) {}

  public record AcceptInviteRequest(@Size(max = 128) String roomPassword) {}

  public record InvitationView(
      UUID id,
      UUID roomId,
      String roomDisplayCode,
      UUID inviteeUserId,
      String inviteeUsername,
      String status,
      Instant expiresAt,
      int generation,
      UUID acceptedDeviceId,
      String acceptedDeviceFingerprint) {}

  /** A claimed (now consumed) KeyPackage of an invitee's approved device. */
  public record ClaimedKeyPackage(
      UUID invitationId, UUID userId, UUID deviceId, String deviceFingerprint, String keyPackage) {}

  public record OpenSessionRequest(@Size(max = 128) String roomPassword) {}

  public record RoomSessionView(UUID sessionId, UUID roomId, int generation, Instant connectedAt) {}

  public enum CommitPurpose {
    ADD,
    REMOVE,
    UPDATE,
    RESET_REKEY
  }

  /**
   * Owner-submitted MLS epoch change. {@code fromEpoch} must equal the room's current epoch. For
   * RESET_REKEY the owner may instead set {@code newGroup=true} and start a fresh MLS group at
   * epoch 0 (no commit bytes) when it no longer holds the previous group state.
   */
  public record MlsCommitRequest(
      @NotNull Integer generation,
      @NotNull Long fromEpoch,
      @NotNull CommitPurpose purpose,
      @Size(max = 350_000) String commit,
      @Size(max = 350_000) String welcome,
      List<UUID> addedInvitationIds,
      List<UUID> removedUserIds,
      boolean newGroup) {}

  public record MlsCommitResult(int generation, long epoch, long sequenceNo, String roomState) {}

  public record MlsEventView(
      long sequenceNo,
      int generation,
      long epoch,
      String eventType,
      String commit,
      Map<String, String> rosterFingerprints) {}

  public record WelcomeView(
      int generation, long epoch, String welcome, Map<String, String> rosterFingerprints) {}

  public record ResetResult(int generation, String state, Instant nextResetAt) {}
}
