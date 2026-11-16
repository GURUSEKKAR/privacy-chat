package com.secretchat.messages;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomSessionRepository extends JpaRepository<RoomSession, UUID> {
  List<RoomSession> findByRoomIdAndEndedAtIsNull(UUID roomId);

  List<RoomSession> findByRoomIdAndUserIdAndEndedAtIsNull(UUID roomId, UUID userId);

  List<RoomSession> findByAuthSessionIdAndEndedAtIsNull(UUID authSessionId);

  List<RoomSession> findByDeviceIdAndEndedAtIsNull(UUID deviceId);

  @Modifying
  @Query("delete from RoomSession s where s.endedAt is not null and s.endedAt < :cutoff")
  int purgeEndedBefore(@Param("cutoff") Instant cutoff);
}
