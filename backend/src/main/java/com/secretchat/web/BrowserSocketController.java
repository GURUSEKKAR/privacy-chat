package com.secretchat.web;

import com.secretchat.auth.AuthPrincipal;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BrowserSocketController {
  private final BrowserSocketTickets tickets;

  public BrowserSocketController(BrowserSocketTickets tickets) {
    this.tickets = tickets;
  }

  @PostMapping("/api/v1/ws-ticket")
  public Map<String, Object> ticket(@AuthenticationPrincipal AuthPrincipal principal) {
    return Map.of("ticket", tickets.issue(principal), "expiresInSeconds", 30);
  }
}
