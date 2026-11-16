package com.secretchat.crypto_relay;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MlsEventRepository extends JpaRepository<MlsEvent, UUID> {

  @Query("select coalesce(max(e.sequenceNo), 0) from MlsEvent e where e.roomId = :roomId")
  long maxSequence(@Param("roomId") UUID roomId);

  @Query(
      "select e from MlsEvent e where e.roomId = :roomId and e.generation = :generation"
          + " and e.epoch > :afterEpoch order by e.sequenceNo")
  List<MlsEvent> findAfter(
      @Param("roomId") UUID roomId,
      @Param("generation") int generation,
      @Param("afterEpoch") long afterEpoch);

  Optional<MlsEvent> findByRoomIdAndGenerationAndEpoch(UUID roomId, int generation, long epoch);

  @Modifying
  @Query("delete from MlsEvent e where e.roomId = :roomId and e.generation < :generation")
  int deleteBeforeGeneration(@Param("roomId") UUID roomId, @Param("generation") int generation);
}
