package com.secretchat.web;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.AuthService;
import com.secretchat.common.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Single-use, 30-second browser handshake tickets; never account access tokens in URLs. */
@Service
public class BrowserSocketTickets {
  private record Ticket(UUID session, Instant expires) {}

  private final Map<String, Ticket> pending = new HashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;
  private final AuthService auth;

  public BrowserSocketTickets(Clock clock, AuthService auth) {
    this.clock = clock;
    this.auth = auth;
  }

  public synchronized String issue(AuthPrincipal principal) {
    if (!principal.deviceApproved())
      throw ApiException.forbidden("DEVICE_NOT_APPROVED", "Approved device required");
    Instant now = clock.instant();
    pending.values().removeIf(t -> !t.expires().isAfter(now));
    // A single login needs at most one outstanding connection attempt.
    pending.values().removeIf(t -> t.session().equals(principal.authSessionId()));
    if (pending.size() >= 5000)
      throw new ApiException(
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          "TICKET_CAPACITY",
          "Try connecting again shortly");
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    pending.put(token, new Ticket(principal.authSessionId(), now.plusSeconds(30)));
    return token;
  }

  public synchronized AuthPrincipal consume(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
    Ticket ticket = pending.remove(token);
    if (ticket == null || !ticket.expires().isAfter(clock.instant())) return null;
    return auth.revalidate(ticket.session()).filter(AuthPrincipal::deviceApproved).orElse(null);
  }
}
