package com.secretchat.websocket;

import com.secretchat.protocol.MessageDtos.ChatEvent;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Best-effort push to specific devices' WebSocket sessions on {@code /user/queue/chat}. Pushes are
 * deferred until the surrounding transaction commits so clients never see rolled-back state.
 * Correctness never depends on these pushes: every REST read re-applies the full predicate.
 */
@Component
public class ChatNotifier {
  public static final String USER_QUEUE = "/queue/chat";

  private final SimpMessagingTemplate template;
  private final WsSessionRegistry registry;

  public ChatNotifier(SimpMessagingTemplate template, WsSessionRegistry registry) {
    this.template = template;
    this.registry = registry;
  }

  public void toDevice(UUID userId, UUID deviceId, ChatEvent event) {
    afterCommit(() -> {
      for (String wsSessionId : registry.sessionIdsFor(userId, deviceId)) {
        SimpMessageHeaderAccessor h = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        h.setSessionId(wsSessionId);
        h.setLeaveMutable(true);
        template.convertAndSendToUser(userId.toString(), USER_QUEUE, event, h.getMessageHeaders());
      }
    });
  }

  public static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
