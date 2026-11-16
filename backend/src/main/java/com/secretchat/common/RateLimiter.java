package com.secretchat.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory fixed-window limiter (single-node v1). Keys must not contain secrets; callers
 * pass normalized usernames / ids / remote addresses.
 */
public class RateLimiter {
  private final int limit;
  private final Duration window;
  private final Clock clock;
  private final int maxTrackedKeys;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public RateLimiter(int limit, Duration window, Clock clock) {
    this(limit, window, clock, 100_000);
  }

  public RateLimiter(int limit, Duration window, Clock clock, int maxTrackedKeys) {
    if (limit < 1 || maxTrackedKeys < 1 || window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("Rate limiter bounds must be positive");
    }
    this.limit = limit;
    this.window = window;
    this.clock = clock;
    this.maxTrackedKeys = maxTrackedKeys;
  }

  private record Window(Instant start, int count) {}

  /** Records one event; returns false if the key exceeded its budget in the current window. */
  public synchronized boolean tryAcquire(String key) {
    Instant now = clock.instant();
    if (newKeyAtCapacity(key, now)) return false;
    Window old = windows.get(key);
    if (old == null || !now.isBefore(old.start().plus(window))) {
      windows.put(key, new Window(now, 1));
      return true;
    }
    if (old.count() >= limit) return false;
    windows.put(key, new Window(old.start(), old.count() + 1));
    return true;
  }

  private boolean newKeyAtCapacity(String key, Instant now) {
    if (windows.containsKey(key) || windows.size() < maxTrackedKeys) return false;
    windows.entrySet().removeIf(e -> !now.isBefore(e.getValue().start().plus(window)));
    return windows.size() >= maxTrackedKeys;
  }

  /** True if the key is currently over budget, without consuming. */
  public synchronized boolean isBlocked(String key) {
    Instant now = clock.instant();
    if (newKeyAtCapacity(key, now)) return true;
    Window w = windows.get(key);
    return w != null && now.isBefore(w.start().plus(window)) && w.count() >= limit;
  }

  public void reset(String key) {
    windows.remove(key);
  }
}
