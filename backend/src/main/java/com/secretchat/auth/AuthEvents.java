package com.secretchat.auth;

import java.util.UUID;

/** Domain events so other modules can tear down room/WebSocket sessions on revocation. */
public final class AuthEvents {
  private AuthEvents() {}

  /** One auth session ended (logout / revoked). */
  public record AuthSessionEnded(UUID authSessionId) {}

  /** Every session of a device was revoked (device revoked or refresh-token reuse detected). */
  public record DeviceSessionsRevoked(UUID deviceId) {}
}
