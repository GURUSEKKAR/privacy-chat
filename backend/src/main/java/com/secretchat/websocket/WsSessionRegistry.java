package com.secretchat.websocket;

import com.secretchat.auth.AuthEvents;
import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.AuthService;
import com.secretchat.auth.PrincipalAuthentication;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Tracks live WebSocket sessions per device so ciphertext is routed to the exact entitled device's
 * sessions, and sockets can be closed when an auth session or device is revoked.
 */
@Component
public class WsSessionRegistry implements WebSocketHandlerDecoratorFactory {
  public record Entry(WebSocketSession session, AuthPrincipal principal) {}

  private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

  private final AuthService auth;

  public WsSessionRegistry(AuthService auth) {
    this.auth = auth;
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${secretchat.worker.auth-delay-ms:5000}")
  public void closeExpired() {
    closeWhere(entry -> auth.revalidate(entry.principal().authSessionId()).isEmpty());
  }

  @Override
  public WebSocketHandler decorate(WebSocketHandler handler) {
    return new WebSocketHandlerDecorator(handler) {
      @Override
      public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (session.getPrincipal() instanceof PrincipalAuthentication auth) {
          sessions.put(session.getId(), new Entry(session, auth.getPrincipal()));
        }
        super.afterConnectionEstablished(session);
      }

      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
          throws Exception {
        sessions.remove(session.getId());
        super.afterConnectionClosed(session, status);
      }
    };
  }

  public List<String> sessionIdsFor(UUID userId, UUID deviceId) {
    return sessions.entrySet().stream()
        .filter(e ->
            e.getValue().principal().userId().equals(userId)
                && e.getValue().principal().deviceId().equals(deviceId))
        .filter(e ->
            auth.revalidate(e.getValue().principal().authSessionId())
                .filter(AuthPrincipal::deviceApproved)
                .isPresent())
        .map(java.util.Map.Entry::getKey)
        .toList();
  }

  public int size() {
    return sessions.size();
  }

  public boolean hasOtherAuthSession(UUID authSessionId, String excludedSessionId) {
    return sessions.entrySet().stream()
        .anyMatch(entry ->
            !entry.getKey().equals(excludedSessionId)
                && entry.getValue().principal().authSessionId().equals(authSessionId));
  }

  @EventListener
  public void onAuthSessionEnded(AuthEvents.AuthSessionEnded e) {
    closeWhere(entry -> entry.principal().authSessionId().equals(e.authSessionId()));
  }

  @EventListener
  public void onDeviceRevoked(AuthEvents.DeviceSessionsRevoked e) {
    closeWhere(entry -> entry.principal().deviceId().equals(e.deviceId()));
  }

  private void closeWhere(java.util.function.Predicate<Entry> predicate) {
    sessions.values().stream()
        .filter(predicate)
        .forEach(entry -> {
          try {
            entry.session().close(CloseStatus.POLICY_VIOLATION);
          } catch (IOException ignored) {
            // Socket already gone.
          }
        });
  }
}
