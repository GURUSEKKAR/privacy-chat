package com.secretchat.protocol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Device trust DTOs. Only public key material ever appears here. */
public final class DeviceDtos {
  private DeviceDtos() {}

  public record DeviceView(
      UUID id,
      String name,
      String status,
      String fingerprint,
      Instant createdAt,
      Instant approvedAt,
      Instant revokedAt) {}

  /** The approver must type/scan the fingerprint shown on the new device. */
  public record ApproveDeviceRequest(@NotBlank @Size(max = 128) String fingerprint) {}

  /** Replaces all unconsumed KeyPackages of the calling device. Base64 RFC 9420 KeyPackages. */
  public record KeyPackageUpload(@NotEmpty @Size(max = 20) List<@NotBlank String> keyPackages) {}

  public record KeyPackageUploadResult(int stored) {}
}
