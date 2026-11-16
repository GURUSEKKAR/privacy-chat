package com.secretchat.websocket;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.AuthService;
import com.secretchat.auth.PrincipalAuthentication;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Inbound STOMP authorization. The socket itself was authenticated at the HTTP handshake (bearer
 * token, approved device). Here every CONNECT/SUBSCRIBE/SEND re-validates the auth session and only
 * two destinations are permitted:
 *
 * <ul>
 *   <li>SUBSCRIBE {@code /user/queue/chat} (private per-user queue)
 *   <li>SEND {@code /app/rooms/{uuid}/send}
 * </ul>
 *
 * Clients can never write to broker prefixes ({@code /queue}, {@code /topic}) or subscribe to
 * anything else, so there is no broad room broadcast to eavesdrop on.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {
  static final String SUBSCRIBE_DESTINATION = "/user/queue/chat";
  private static final Pattern SEND_DESTINATION =
      Pattern.compile("^/app/rooms/[0-9a-fA-F-]{36}/send$");

  private final AuthService authService;

  public StompAuthInterceptor(AuthService authService) {
    this.authService = authService;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    StompCommand command = accessor.getCommand();
    switch (command) {
      case CONNECT, STOMP -> requireValid(accessor);
      case SUBSCRIBE -> {
        requireValid(accessor);
        if (!SUBSCRIBE_DESTINATION.equals(accessor.getDestination())) {
          throw new MessageDeliveryException("Subscription destination not permitted");
        }
      }
      case SEND -> {
        requireValid(accessor);
        String dest = accessor.getDestination();
        if (dest == null || !SEND_DESTINATION.matcher(dest).matches()) {
          throw new MessageDeliveryException("Send destination not permitted");
        }
      }
      default -> {
        // UNSUBSCRIBE, DISCONNECT, ACK, NACK are harmless.
      }
    }
    return message;
  }

  private void requireValid(StompHeaderAccessor accessor) {
    if (!(accessor.getUser() instanceof PrincipalAuthentication auth)) {
      throw new MessageDeliveryException("Unauthenticated");
    }
    AuthPrincipal p = auth.getPrincipal();
    Optional<AuthPrincipal> current = authService.revalidate(p.authSessionId());
    if (current.isEmpty() || !current.get().deviceApproved()) {
      throw new MessageDeliveryException("Session revoked or device not approved");
    }
  }
}
