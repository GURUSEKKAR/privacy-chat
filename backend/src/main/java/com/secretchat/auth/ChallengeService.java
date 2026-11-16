package com.secretchat.auth;

import com.secretchat.common.Hashing;
import com.secretchat.config.AppProperties;
import com.secretchat.protocol.AuthDtos.ChallengeResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Single-use, short-lived login nonces for device proof-of-possession (single node v1). */
@Service
public class ChallengeService {
  private record Challenge(String nonce, Instant expiresAt) {}

  private final ConcurrentHashMap<UUID, Challenge> challenges = new ConcurrentHashMap<>();
  private final Clock clock;
  private final AppProperties props;

  public ChallengeService(Clock clock, AppProperties props) {
    this.clock = clock;
    this.props = props;
  }

  public synchronized ChallengeResponse issue() {
    Instant now = clock.instant();
    if (challenges.size() >= props.maxPendingChallenges()) {
      challenges.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
      if (challenges.size() >= props.maxPendingChallenges()) {
        throw com.secretchat.common.ApiException.rateLimited();
      }
    }
    UUID id = UUID.randomUUID();
    String nonce = Base64.getEncoder().encodeToString(Hashing.randomBytes(32));
    Instant expiresAt = now.plus(props.challengeTtl());
    challenges.put(id, new Challenge(nonce, expiresAt));
    return new ChallengeResponse(id, nonce, expiresAt);
  }

  /** Removes the challenge (single use) and returns its nonce if still valid. */
  public Optional<String> consume(UUID challengeId) {
    Challenge c = challenges.remove(challengeId);
    if (c == null || !clock.instant().isBefore(c.expiresAt())) {
      return Optional.empty();
    }
    return Optional.of(c.nonce());
  }
}
