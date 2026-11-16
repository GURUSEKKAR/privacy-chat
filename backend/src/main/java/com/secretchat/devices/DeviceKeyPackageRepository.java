package com.secretchat.devices;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceKeyPackageRepository extends JpaRepository<DeviceKeyPackage, UUID> {

  /** Locks the oldest available KeyPackage so two owners can never claim the same one. */
  @Query(
      value =
          "select * from device_key_packages where device_id = :deviceId and consumed_at is null"
              + " and expires_at > :now order by created_at limit 1 for update skip locked",
      nativeQuery = true)
  List<DeviceKeyPackage> lockAvailable(@Param("deviceId") UUID deviceId, @Param("now") Instant now);

  @Modifying
  @Query("delete from DeviceKeyPackage k where k.deviceId = :deviceId and k.consumedAt is null")
  int deleteUnconsumed(@Param("deviceId") UUID deviceId);

  @Modifying
  @Query("delete from DeviceKeyPackage k where k.expiresAt <= :now or k.consumedAt is not null")
  int purgeUsedOrExpired(@Param("now") Instant now);
}
