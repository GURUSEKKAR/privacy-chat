package com.secretchat.client.storage;

import com.secretchat.protocol.Fingerprints;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trust-on-first-use pins of other users' device fingerprints (public data). A later mismatch is
 * treated as possible server-side key substitution and requires explicit user intervention.
 */
public final class FingerprintPins {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public enum Result {
    FIRST_USE,
    MATCH,
    MISMATCH
  }

  private final Path file;
  private final Map<UUID, String> pins = new HashMap<>();

  public FingerprintPins(Path file) {
    this.file = file;
    if (file != null && Files.isRegularFile(file)) {
      try {
        pins.putAll(
            JSON.readValue(
                Files.readString(file, StandardCharsets.UTF_8),
                new TypeReference<Map<UUID, String>>() {}));
      } catch (IOException e) {
        throw new IllegalStateException("Could not read fingerprint pins", e);
      }
    }
  }

  public synchronized Result check(UUID userId, String fingerprint) {
    String pinned = pins.get(userId);
    if (pinned == null) {
      return Result.FIRST_USE;
    }
    return Fingerprints.matches(pinned, fingerprint) ? Result.MATCH : Result.MISMATCH;
  }

  public synchronized Optional<String> pinned(UUID userId) {
    return Optional.ofNullable(pins.get(userId));
  }

  /** Records (or, after explicit user approval, replaces) a pin. */
  public synchronized void pin(UUID userId, String fingerprint) {
    pins.put(userId, fingerprint);
    if (file != null) {
      try {
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(pins), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new IllegalStateException("Could not write fingerprint pins", e);
      }
    }
  }
}
