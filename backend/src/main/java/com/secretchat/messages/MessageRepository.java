package com.secretchat.messages;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Message m where m.id = :id")
  Optional<Message> lockById(@Param("id") UUID id);

  Optional<Message> findBySenderDeviceIdAndIdempotencyKey(UUID deviceId, String key);

  /** Candidate messages for pending delivery; the full predicate is applied in code. */
  @Query(
      "select m from Message m where m.roomId = :roomId and m.generation = :generation"
          + " and m.deletedAt is null and m.expiresAt > :now order by m.createdAt")
  List<Message> findLive(
      @Param("roomId") UUID roomId, @Param("generation") int generation, @Param("now") Instant now);

  @Query("select m from Message m where m.roomId = :roomId and m.deletedAt is null")
  List<Message> findUndeletedInRoom(@Param("roomId") UUID roomId);

  /** Due-for-expiry rows, locked so concurrent workers never double-process (SKIP LOCKED). */
  @Query(
      value =
          "select id from messages where deleted_at is null and expires_at <= :now"
              + " order by expires_at limit :limit for update skip locked",
      nativeQuery = true)
  List<UUID> lockDueForExpiry(@Param("now") Instant now, @Param("limit") int limit);

  @Modifying
  @Query(
      value =
          "update messages set ciphertext = null, deleted_at = :now, delete_reason = 'EXPIRED'"
              + " where id in (:ids) and deleted_at is null",
      nativeQuery = true)
  int eraseExpired(@Param("ids") List<UUID> ids, @Param("now") Instant now);

  /** Physical purge of erased rows (recipient rows cascade). */
  @Modifying
  @Query(
      value = "delete from messages where deleted_at is not null and deleted_at <= :cutoff",
      nativeQuery = true)
  int purgeErasedBefore(@Param("cutoff") Instant cutoff);

  @Modifying
  @Query(value = "delete from messages where room_id = :roomId", nativeQuery = true)
  int deleteAllInRoom(@Param("roomId") UUID roomId);

  long countByRoomIdAndGeneration(UUID roomId, int generation);
}
