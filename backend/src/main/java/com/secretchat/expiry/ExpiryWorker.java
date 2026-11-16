package com.secretchat.expiry;

import com.secretchat.auth.AuthSessionRepository;
import com.secretchat.config.AppProperties;
import com.secretchat.devices.DeviceKeyPackageRepository;
import com.secretchat.invitations.InvitationRepository;
import com.secretchat.messages.MessageRepository;
import com.secretchat.messages.RoomSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cleanup worker. It is NOT the security boundary: every read/send path checks TTL itself. Each
 * batch is its own short transaction using FOR UPDATE SKIP LOCKED, so crashes and retries are safe
 * and a purged message can never be resurrected.
 */
@Component
public class ExpiryWorker {
  private static final Logger log = LoggerFactory.getLogger(ExpiryWorker.class);

  private final MessageRepository messages;
  private final InvitationRepository invitations;
  private final DeviceKeyPackageRepository keyPackages;
  private final AuthSessionRepository authSessions;
  private final RoomSessionRepository roomSessions;
  private final TransactionTemplate tx;
  private final AppProperties props;
  private final Clock clock;

  public ExpiryWorker(
      MessageRepository messages,
      InvitationRepository invitations,
      DeviceKeyPackageRepository keyPackages,
      AuthSessionRepository authSessions,
      RoomSessionRepository roomSessions,
      TransactionTemplate tx,
      AppProperties props,
      Clock clock) {
    this.messages = messages;
    this.invitations = invitations;
    this.keyPackages = keyPackages;
    this.authSessions = authSessions;
    this.roomSessions = roomSessions;
    this.tx = tx;
    this.props = props;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${secretchat.worker.expiry-delay-ms:5000}",
      initialDelayString = "${secretchat.worker.initial-delay-ms:5000}")
  public void run() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.warn("Expiry pass failed, will retry: {}", e.getClass().getSimpleName());
    }
  }

  /** One full pass; returns the number of message rows erased by TTL. */
  public int runOnce() {
    int erased = 0;
    while (true) {
      Integer n =
          tx.execute(status -> {
            Instant now = clock.instant();
            List<UUID> due = messages.lockDueForExpiry(now, props.workerBatchSize());
            return due.isEmpty() ? 0 : messages.eraseExpired(due, now);
          });
      if (n == null || n == 0) {
        break;
      }
      erased += n;
    }
    tx.executeWithoutResult(status -> {
      Instant now = clock.instant();
      messages.purgeErasedBefore(now.minus(props.deletedMetadataRetention()));
      invitations.expireDue(now);
      keyPackages.purgeUsedOrExpired(now);
      authSessions.purgeOlderThan(now.minus(Duration.ofDays(1)));
      roomSessions.purgeEndedBefore(now.minus(Duration.ofDays(1)));
    });
    return erased;
  }
}
