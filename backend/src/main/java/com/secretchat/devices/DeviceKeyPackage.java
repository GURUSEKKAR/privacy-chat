package com.secretchat.devices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A public RFC 9420 KeyPackage, single use. */
@Entity
@Table(name = "device_key_packages")
public class DeviceKeyPackage {
  @Id private UUID id;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(name = "public_key_package", nullable = false)
  private byte[] publicKeyPackage;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected DeviceKeyPackage() {}

  public DeviceKeyPackage(UUID deviceId, byte[] keyPackage, Instant now, Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.deviceId = deviceId;
    this.publicKeyPackage = keyPackage.clone();
    this.createdAt = now;
    this.expiresAt = expiresAt;
  }

  public void consume(Instant now) {
    consumedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDeviceId() {
    return deviceId;
  }

  public byte[] getPublicKeyPackage() {
    return publicKeyPackage.clone();
  }
}
