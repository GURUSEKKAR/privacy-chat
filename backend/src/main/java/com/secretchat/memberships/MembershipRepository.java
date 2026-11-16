package com.secretchat.memberships;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
  Optional<Membership> findByRoomIdAndUserId(UUID roomId, UUID userId);

  List<Membership> findByRoomIdAndState(UUID roomId, Membership.State state);

  List<Membership> findByRoomId(UUID roomId);

  List<Membership> findByUserIdAndStateIn(UUID userId, List<Membership.State> states);

  long countByRoomIdAndStateIn(UUID roomId, List<Membership.State> states);
}
