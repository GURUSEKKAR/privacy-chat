package com.secretchat.websocket;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.auth.PrincipalAuthentication;
import com.secretchat.common.ApiException;
import com.secretchat.messages.MessageService;
import com.secretchat.messages.RoomSessionService;
import com.secretchat.protocol.MessageDtos.ChatEvent;
import com.secretchat.protocol.MessageDtos.SendMessageFrame;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Encrypted sends over STOMP. The sender is always the socket's authenticated principal. */
@Controller
public class ChatStompController {
  private final MessageService messages;
  private final RoomSessionService roomSessions;
  private final Validator validator;
  private final WsSessionRegistry registry;

  public ChatStompController(
      MessageService messages,
      RoomSessionService roomSessions,
      Validator validator,
      WsSessionRegistry registry) {
    this.messages = messages;
    this.roomSessions = roomSessions;
    this.validator = validator;
    this.registry = registry;
  }

  @MessageMapping("/rooms/{roomId}/send")
  @SendToUser(destinations = ChatNotifier.USER_QUEUE, broadcast = false)
  public ChatEvent send(
      @DestinationVariable UUID roomId, @Payload SendMessageFrame frame, Principal principal) {
    Set<ConstraintViolation<SendMessageFrame>> violations = validator.validate(frame);
    if (!violations.isEmpty()) {
      throw ApiException.badRequest("VALIDATION_FAILED", "Invalid message frame");
    }
    try {
      return messages.send(principal(principal), roomId, frame);
    } catch (ApiException e) {
      return ChatEvent.error(null, frame.idempotencyKey(), e.code(), e.getMessage());
    }
  }

  @MessageExceptionHandler
  @SendToUser(destinations = ChatNotifier.USER_QUEUE, broadcast = false)
  public ChatEvent onError(Exception e) {
    if (e instanceof ApiException api) {
      return ChatEvent.error(null, null, api.code(), api.getMessage());
    }
    return ChatEvent.error(null, null, "SEND_FAILED", "Message rejected");
  }

  /** Socket closed: end every room session opened under that auth session (view-once boundary). */
  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    if (event.getUser() instanceof PrincipalAuthentication auth) {
      // A delayed disconnect from the old socket must not terminate a replacement connection.
      if (!registry.hasOtherAuthSession(
          auth.getPrincipal().authSessionId(), event.getSessionId())) {
        roomSessions.endAllForAuthSession(auth.getPrincipal().authSessionId());
      }
    }
  }

  private static AuthPrincipal principal(Principal principal) {
    if (principal instanceof PrincipalAuthentication auth) {
      return auth.getPrincipal();
    }
    throw ApiException.unauthorized();
  }
}
