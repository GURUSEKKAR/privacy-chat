package com.secretchat.expiry;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only aggregate counts are exported. No room, user, payload or credential labels. */
@Component
public class BacklogMetrics {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final AtomicLong ttl = new AtomicLong();
  private final AtomicLong reset = new AtomicLong();
  private final AtomicLong rekey = new AtomicLong();
  private final AtomicLong lastSuccess = new AtomicLong();
  private final MeterRegistry registry;

  public BacklogMetrics(JdbcTemplate jdbc, Clock clock, MeterRegistry registry) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.registry = registry;
    registry.gauge("secretchat.ttl.backlog", ttl);
    registry.gauge("secretchat.reset.backlog", reset);
    registry.gauge("secretchat.rekey.locked", rekey);
    registry.gauge("secretchat.backlog.last.success.seconds", lastSuccess);
  }

  @Scheduled(
      fixedDelayString = "${secretchat.worker.metrics-delay-ms:5000}",
      initialDelayString = "${secretchat.worker.initial-delay-ms:5000}")
  public void refresh() {
    try {
      var now = java.sql.Timestamp.from(clock.instant());
      ttl.set(
          jdbc.queryForObject(
              "select count(*) from messages where deleted_at is null and expires_at <= ?",
              Long.class,
              now));
      reset.set(
          jdbc.queryForObject(
              "select count(*) from rooms where state = 'ACTIVE' and next_reset_at <= ?",
              Long.class,
              now));
      rekey.set(
          jdbc.queryForObject(
              "select count(*) from rooms where state = 'LOCKED_PENDING_REKEY'", Long.class));
      lastSuccess.set(clock.instant().getEpochSecond());
    } catch (RuntimeException e) {
      registry.counter("secretchat.backlog.refresh.failures").increment();
    }
  }
}
