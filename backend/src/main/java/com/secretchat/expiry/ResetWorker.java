package com.secretchat.expiry;

import com.secretchat.rooms.RoomRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Finds rooms whose next_reset_at passed and resets each in its own transaction. */
@Component
public class ResetWorker {
  private static final Logger log = LoggerFactory.getLogger(ResetWorker.class);

  private final RoomRepository rooms;
  private final ResetService resets;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ResetWorker(
      RoomRepository rooms, ResetService resets, TransactionTemplate tx, Clock clock) {
    this.rooms = rooms;
    this.resets = resets;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${secretchat.worker.reset-delay-ms:5000}",
      initialDelayString = "${secretchat.worker.initial-delay-ms:5000}")
  public void run() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.warn("Reset pass failed, will retry: {}", e.getClass().getSimpleName());
    }
  }

  public int runOnce() {
    // Read candidates briefly; each reset re-locks and re-checks the room itself.
    List<UUID> due = tx.execute(s -> rooms.lockDueForReset(clock.instant(), 50));
    int count = 0;
    for (UUID roomId : due == null ? List.<UUID>of() : due) {
      try {
        if (resets.resetIfDue(roomId)) {
          count++;
        }
      } catch (RuntimeException e) {
        log.warn("Reset of room {} failed, will retry: {}", roomId, e.getClass().getSimpleName());
      }
    }
    return count;
  }
}
