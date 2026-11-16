package com.secretchat.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Test clock: follows real time plus a manually advanced offset. */
public final class MutableClock extends Clock {
  private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
  private final AtomicReference<Instant> fixed = new AtomicReference<>();

  public void setInstant(Instant instant) {
    fixed.set(instant);
    offset.set(Duration.ZERO);
  }

  public void advance(Duration d) {
    offset.updateAndGet(o -> o.plus(d));
  }

  public void reset() {
    fixed.set(null);
    offset.set(Duration.ZERO);
  }

  @Override
  public Instant instant() {
    Instant value = fixed.get();
    return (value == null ? Instant.now() : value).plus(offset.get());
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
