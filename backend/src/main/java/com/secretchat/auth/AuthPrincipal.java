package com.secretchat.auth;

import java.security.Principal;
import java.util.UUID;

/**
 * Authenticated identity resolved from a bearer token. {@link #getName()} is the user id string,
 * which is also the STOMP user-destination name.
 */
public record AuthPrincipal(
    UUID userId, String username, UUID deviceId, boolean deviceApproved, UUID authSessionId)
    implements Principal {

  @Override
  public String getName() {
    return userId.toString();
  }
}
