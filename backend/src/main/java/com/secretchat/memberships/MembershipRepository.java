package com.secretchat.memberships;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
  Optional<Membership> findByRoomIdAndUserId(UUID roomId, UUID userId);

  List<Membership> findByRoomIdAndState(UUID roomId, Membership.State state);

  List<Membership> findByRoomId(UUID roomId);

  List<Membership> findByUserIdAndStateIn(UUID userId, List<Membership.State> states);

  @Query(
      value =
          """
          select count(*) from memberships m where m.room_id = :roomId and m.generation = :generation
            and (m.state = 'ACTIVE' or (m.state = 'PENDING_KEYS' and exists (
              select 1 from invitations i where i.room_id = m.room_id and i.generation = m.generation
                and i.invitee_user_id = m.user_id and i.status = 'ACCEPTED' and i.expires_at > :now)))
          """,
      nativeQuery = true)
  long countReservedSeats(UUID roomId, int generation, Instant now);
}
