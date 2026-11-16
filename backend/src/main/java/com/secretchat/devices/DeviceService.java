package com.secretchat.devices;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.AuthService;
import com.secretchat.common.ApiException;
import com.secretchat.common.Hashing;
import com.secretchat.config.AppProperties;
import com.secretchat.protocol.DeviceDtos.DeviceView;
import com.secretchat.protocol.Fingerprints;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {
  private final DeviceRepository devices;
  private final DeviceKeyPackageRepository keyPackages;
  private final MlsPublicVerifier verifier;
  private final AuthService authService;
  private final AppProperties props;
  private final Clock clock;

  public DeviceService(
      DeviceRepository devices,
      DeviceKeyPackageRepository keyPackages,
      MlsPublicVerifier verifier,
      AuthService authService,
      AppProperties props,
      Clock clock) {
    this.devices = devices;
    this.keyPackages = keyPackages;
    this.verifier = verifier;
    this.authService = authService;
    this.props = props;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<DeviceView> list(AuthPrincipal p) {
    return devices.findByUserIdOrderByCreatedAt(p.userId()).stream()
        .map(DeviceService::view)
        .toList();
  }

  /**
   * An already-approved device of the same user approves a PENDING device after the user compared
   * fingerprints out of band. A mismatching fingerprint is rejected (possible key substitution).
   */
  @Transactional
  public DeviceView approve(AuthPrincipal p, UUID deviceId, String fingerprint) {
    requireApproved(p);
    Device target = ownDevice(p, deviceId);
    if (target.getStatus() != Device.Status.PENDING) {
      throw ApiException.conflict("DEVICE_NOT_PENDING", "Device is not awaiting approval");
    }
    if (!Fingerprints.matches(target.getFingerprint(), fingerprint)) {
      throw ApiException.forbidden(
          "FINGERPRINT_MISMATCH", "Fingerprint does not match the device key");
    }
    target.approve(p.deviceId(), clock.instant());
    return view(target);
  }

  @Transactional
  public DeviceView revoke(AuthPrincipal p, UUID deviceId) {
    Device target = ownDevice(p, deviceId);
    // A pending device may only revoke itself; revoking others requires an approved device.
    if (!deviceId.equals(p.deviceId())) {
      requireApproved(p);
    }
    if (target.getStatus() != Device.Status.REVOKED) {
      target.revoke(clock.instant());
      keyPackages.deleteUnconsumed(target.getId());
      authService.revokeDeviceSessions(target.getId());
    }
    return view(target);
  }

  /** Replaces this device's unconsumed KeyPackages after validating each one. */
  @Transactional
  public int replaceKeyPackages(AuthPrincipal p, List<String> encoded) {
    requireApproved(p);
    Device device = devices.findById(p.deviceId()).orElseThrow(ApiException::unauthorized);
    Instant now = clock.instant();
    List<byte[]> decoded =
        encoded.stream().map(s -> Hashing.decodeBase64(s, "keyPackage")).toList();
    for (byte[] kp : decoded) {
      if (kp.length > 16_384) {
        throw ApiException.badRequest("INVALID_KEY_PACKAGE", "KeyPackage too large");
      }
      verifier.requireValidKeyPackage(kp, p.userId(), device.getIdentityPublicKey());
    }
    keyPackages.deleteUnconsumed(device.getId());
    Instant exp = now.plus(props.keyPackageTtl());
    decoded.forEach(kp -> keyPackages.save(new DeviceKeyPackage(device.getId(), kp, now, exp)));
    return decoded.size();
  }

  /** Atomically claims one KeyPackage of an approved device (single use). */
  @Transactional
  public Optional<DeviceKeyPackage> claimKeyPackage(UUID deviceId) {
    Instant now = clock.instant();
    return keyPackages.lockAvailable(deviceId, now).stream()
        .findFirst()
        .map(kp -> {
          kp.consume(now);
          return kp;
        });
  }

  public Optional<Device> find(UUID deviceId) {
    return devices.findById(deviceId);
  }

  private Device ownDevice(AuthPrincipal p, UUID deviceId) {
    return devices
        .findById(deviceId)
        .filter(d -> d.getUserId().equals(p.userId()))
        .orElseThrow(ApiException::notFound);
  }

  private static void requireApproved(AuthPrincipal p) {
    if (!p.deviceApproved()) {
      throw ApiException.forbidden("DEVICE_NOT_APPROVED", "Approved device required");
    }
  }

  static DeviceView view(Device d) {
    return new DeviceView(
        d.getId(),
        d.getName(),
        d.getStatus().name(),
        d.getFingerprint(),
        d.getCreatedAt(),
        d.getApprovedAt(),
        d.getRevokedAt());
  }
}
