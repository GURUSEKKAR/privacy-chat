package com.secretchat.rooms;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.common.ApiException;
import com.secretchat.common.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Optional room entry password: a second factor checked server-side with Argon2id. It is never used
 * to derive or protect message keys, so knowing it cannot decrypt anything.
 */
@Component
public class RoomPasswords {
  private final PasswordEncoder encoder;
  private final RateLimiter failures;

  public RoomPasswords(PasswordEncoder encoder, Clock clock) {
    this.encoder = encoder;
    this.failures = new RateLimiter(5, Duration.ofMinutes(5), clock);
  }

  public String hash(String password) {
    return password == null || password.isEmpty() ? null : encoder.encode(password);
  }

  public void require(AuthPrincipal p, Room room, String supplied) {
    if (room.getPasswordHash() == null) {
      return;
    }
    String key = room.getId() + "|" + p.userId();
    if (failures.isBlocked(key)) {
      throw ApiException.rateLimited();
    }
    if (supplied == null || !encoder.matches(supplied, room.getPasswordHash())) {
      failures.tryAcquire(key);
      throw ApiException.forbidden("ROOM_PASSWORD_INVALID", "Room password required or invalid");
    }
    failures.reset(key);
  }
}
