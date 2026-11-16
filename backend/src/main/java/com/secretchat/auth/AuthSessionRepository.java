package com.secretchat.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
  Optional<AuthSession> findByTokenHash(String tokenHash);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  Optional<AuthSession> findByRefreshHash(String refreshHash);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  Optional<AuthSession> findByPreviousRefreshHash(String refreshHash);

  List<AuthSession> findByDeviceIdAndRevokedAtIsNull(UUID deviceId);

  @Modifying
  @Query("delete from AuthSession s where s.refreshExpiresAt < :cutoff or s.revokedAt < :cutoff")
  int purgeOlderThan(@Param("cutoff") Instant cutoff);
}
