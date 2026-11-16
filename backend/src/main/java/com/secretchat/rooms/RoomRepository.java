package com.secretchat.rooms;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, UUID> {

  /** Row lock serializing all state/epoch/generation transitions of one room. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Room r where r.id = :id")
  Optional<Room> lockById(@Param("id") UUID id);

  @Query(
      value =
          "select id from rooms where state = 'ACTIVE' and next_reset_at <= :now"
              + " order by next_reset_at limit :limit for update skip locked",
      nativeQuery = true)
  List<UUID> lockDueForReset(@Param("now") Instant now, @Param("limit") int limit);

  boolean existsByDisplayCode(String displayCode);
}
