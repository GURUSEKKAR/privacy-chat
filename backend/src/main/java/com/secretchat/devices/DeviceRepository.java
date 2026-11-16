package com.secretchat.devices;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
  List<Device> findByUserIdOrderByCreatedAt(UUID userId);

  long countByUserIdAndStatus(UUID userId, Device.Status status);
}
