package com.secretchat.client.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volatile, in-memory buffer of decrypted messages for the open room sessions. Never persisted;
 * cleared when the session ends, the room resets, or the client quits.
 */
public final class ChatState {
  public enum Status {
    UNSEEN,
    VIEWED,
    DELETED_ON_SERVER,
    EXPIRED
  }

  public static final class DisplayedMessage {
    private final UUID messageId;
    private final UUID roomId;
    private final UUID senderUserId;
    private final String senderUsername;
    private final boolean own;
    private final Instant createdAt;
    private final Instant expiresAt;
    private volatile String text;
    private volatile Status status;

    public DisplayedMessage(
        UUID messageId,
        UUID roomId,
        UUID senderUserId,
        String senderUsername,
        boolean own,
        String text,
        Instant createdAt,
        Instant expiresAt,
        Status status) {
      this.messageId = messageId;
      this.roomId = roomId;
      this.senderUserId = senderUserId;
      this.senderUsername = senderUsername;
      this.own = own;
      this.text = text;
      this.createdAt = createdAt;
      this.expiresAt = expiresAt;
      this.status = status;
    }

    public UUID messageId() {
      return messageId;
    }

    public UUID roomId() {
      return roomId;
    }

    public UUID senderUserId() {
      return senderUserId;
    }

    public String senderUsername() {
      return senderUsername;
    }

    public boolean own() {
      return own;
    }

    public String text() {
      return text;
    }

    public Instant createdAt() {
      return createdAt;
    }

    public Instant expiresAt() {
      return expiresAt;
    }

    public Status status() {
      return status;
    }

    public void setStatus(Status status) {
      this.status = status;
    }

    void wipe() {
      text = null;
    }
  }

  private final Map<UUID, List<DisplayedMessage>> rooms = new ConcurrentHashMap<>();

  public void add(DisplayedMessage m) {
    List<DisplayedMessage> list = rooms.computeIfAbsent(m.roomId(), k -> new ArrayList<>());
    synchronized (list) {
      if (list.stream()
          .noneMatch(x -> x.messageId() != null && x.messageId().equals(m.messageId()))) {
        list.add(m);
      }
    }
  }

  public List<DisplayedMessage> messages(UUID roomId) {
    List<DisplayedMessage> list = rooms.get(roomId);
    if (list == null) {
      return List.of();
    }
    synchronized (list) {
      return List.copyOf(list);
    }
  }

  public boolean contains(UUID roomId, UUID messageId) {
    return messages(roomId).stream().anyMatch(m -> messageId.equals(m.messageId()));
  }

  public void markStatus(UUID roomId, UUID messageId, Status status) {
    messages(roomId).stream()
        .filter(m -> messageId.equals(m.messageId()))
        .forEach(m -> m.setStatus(status));
  }

  /** Drops (and best-effort wipes) every decrypted message of the room. */
  public void clearRoom(UUID roomId) {
    List<DisplayedMessage> list = rooms.remove(roomId);
    if (list != null) {
      synchronized (list) {
        list.forEach(DisplayedMessage::wipe);
        list.clear();
      }
    }
  }

  public void clearAll() {
    List.copyOf(rooms.keySet()).forEach(this::clearRoom);
  }
}
