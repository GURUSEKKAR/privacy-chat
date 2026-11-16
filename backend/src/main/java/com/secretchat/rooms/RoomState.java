package com.secretchat.rooms;

import java.util.EnumSet;
import java.util.Set;

/**
 * Room lifecycle. Only the transitions encoded here are legal:
 *
 * <pre>
 * ACTIVE -> RESET_PENDING -> LOCKED_PENDING_REKEY -> ACTIVE
 * </pre>
 */
public enum RoomState {
  ACTIVE,
  RESET_PENDING,
  LOCKED_PENDING_REKEY;

  public Set<RoomState> allowedNext() {
    return switch (this) {
      case ACTIVE -> EnumSet.of(RESET_PENDING);
      case RESET_PENDING -> EnumSet.of(LOCKED_PENDING_REKEY);
      case LOCKED_PENDING_REKEY -> EnumSet.of(ACTIVE);
    };
  }

  public boolean canTransitionTo(RoomState next) {
    return allowedNext().contains(next);
  }
}
