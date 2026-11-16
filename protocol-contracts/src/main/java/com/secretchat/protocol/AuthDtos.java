package com.secretchat.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Account and session DTOs. Passwords travel only over TLS and are never echoed back. */
public final class AuthDtos {
  private AuthDtos() {}

  /** Signing context for device proof-of-possession signatures (MLS SignWithLabel label). */
  public static final String LOGIN_SIGNATURE_LABEL = "SecretChatDeviceLogin-v1";

  public record ChallengeResponse(UUID challengeId, String nonce, Instant expiresAt) {}

  /** A device the client wants to enroll. The public key is the MLS Ed25519 signature key. */
  public record NewDevice(
      @NotBlank @Size(max = 64) String name, @NotBlank @Size(max = 128) String publicKey) {}

  public record RegisterRequest(
      @NotBlank @Size(min = 3, max = 32) String username,
      @NotBlank @Size(min = 12, max = 128) String password,
      @NotNull @Valid NewDevice device,
      @NotNull UUID challengeId,
      @NotBlank @Size(max = 256) String signature) {}

  /**
   * Login always proves possession of a device key. Either {@code deviceId} (an enrolled device) or
   * {@code newDevice} (enroll a new, PENDING device) must be supplied.
   */
  public record LoginRequest(
      @NotBlank @Size(max = 64) String username,
      @NotBlank @Size(max = 128) String password,
      UUID deviceId,
      @Valid NewDevice newDevice,
      @NotNull UUID challengeId,
      @NotBlank @Size(max = 256) String signature) {}

  public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {}

  public record TokenResponse(
      String accessToken,
      String refreshToken,
      Instant accessExpiresAt,
      UUID userId,
      String username,
      UUID deviceId,
      String deviceStatus,
      String deviceFingerprint) {}

  /** Bytes a device signs to log in: binds challenge, nonce and the normalized username. */
  public static byte[] loginSignatureContent(UUID challengeId, String nonce, String username) {
    return ("login|" + challengeId + "|" + nonce + "|" + username)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
