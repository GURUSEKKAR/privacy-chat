package com.secretchat.client.ui;

import com.secretchat.client.SecretChatClient;
import com.secretchat.client.state.ChatState;
import com.secretchat.client.state.ChatState.DisplayedMessage;
import com.secretchat.protocol.MessageDtos.EventType;
import com.secretchat.protocol.RoomDtos.MemberView;
import com.secretchat.protocol.RoomDtos.RoomView;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;

/**
 * Chat screen. Shows only the in-memory, decrypted messages of the current session with their
 * server state (unseen / viewed / deleted / expired) and the membership reset countdown.
 */
public class ChatController {
  @FXML private Label title;
  @FXML private Label countdown;
  @FXML private Label connection;
  @FXML private ListView<DisplayedMessage> messages;
  @FXML private ListView<MemberView> members;
  @FXML private TextField input;
  @FXML private Button resetButton;

  private AppContext ctx;
  private UUID roomId;
  private Instant nextResetAt;
  private Timeline ticker;

  void init(AppContext ctx, UUID roomId, String displayCode) {
    this.ctx = ctx;
    this.roomId = roomId;
    title.setText("Room " + displayCode);
    messages.setCellFactory(lv ->
        new ListCell<>() {
          @Override
          protected void updateItem(DisplayedMessage m, boolean empty) {
            super.updateItem(m, empty);
            setText(
                empty || m == null
                    ? null
                    : (m.own() ? "me" : m.senderUsername())
                        + ": "
                        + m.text()
                        + "   ["
                        + label(m)
                        + "]");
          }
        });
    members.setCellFactory(lv ->
        new ListCell<>() {
          @Override
          protected void updateItem(MemberView m, boolean empty) {
            super.updateItem(m, empty);
            setText(empty || m == null ? null : m.username() + " (" + m.role() + ")");
            setTooltip(
                empty || m == null
                    ? null
                    : new javafx.scene.control.Tooltip(m.deviceFingerprint()));
          }
        });
    ctx.client().setListener(new UiListener());
    loadRoom();
    ctx.async(
        () -> ctx.client().enterRoom(roomId, askPasswordIfNeeded()),
        s -> {
          connection.setText(
              "Connected (session " + s.sessionId().toString().substring(0, 8) + ")");
          render();
          loadRoom();
        });
    ticker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> tick()));
    ticker.setCycleCount(Timeline.INDEFINITE);
    ticker.play();
  }

  private final class UiListener implements SecretChatClient.Listener {
    @Override
    public void onMessage(DisplayedMessage message) {
      // Returning from this callback permits the display receipt. Wait for the FX update.
      var displayed = new java.util.concurrent.CompletableFuture<Void>();
      Platform.runLater(() -> {
        try {
          if (!roomId.equals(message.roomId())) {
            throw new IllegalStateException("Message is not in the displayed room");
          }
          render();
          messages.applyCss();
          messages.layout();
          displayed.complete(null);
        } catch (RuntimeException e) {
          displayed.completeExceptionally(e);
        }
      });
      try {
        displayed.get(5, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Display interrupted", e);
      } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
        throw new IllegalStateException("Display did not complete; receipt withheld", e);
      }
    }

    @Override
    public void onMessageStatus(UUID r, UUID messageId, ChatState.Status status) {
      Platform.runLater(ChatController.this::render);
    }

    @Override
    public void onRoomChanged(UUID r, EventType reason) {
      if (!r.equals(roomId)) {
        return;
      }
      Platform.runLater(() -> {
        if (reason == EventType.ROOM_RESET || reason == EventType.MEMBERSHIP_REVOKED) {
          stopTicker();
          ctx.info("Your membership in this room ended (room reset or removal).");
          ctx.showRooms();
        } else {
          loadRoom();
        }
      });
    }

    @Override
    public void onWarning(String warning) {
      Platform.runLater(() -> connection.setText("Warning: " + warning));
    }
  }

  /** Runs on the IO thread; shows a dialog on the FX thread if the room has a password. */
  private String askPasswordIfNeeded() {
    RoomView r = ctx.client().api().room(roomId);
    if (!r.passwordProtected()) {
      return null;
    }
    String[] out = new String[1];
    CountDownLatch latch = new CountDownLatch(1);
    Platform.runLater(() -> {
      TextInputDialog d = new TextInputDialog();
      d.setHeaderText(null);
      d.setContentText("Room password");
      out[0] = d.showAndWait().orElse(null);
      latch.countDown();
    });
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return out[0];
  }

  private void loadRoom() {
    ctx.async(
        () -> ctx.client().api().room(roomId),
        r -> {
          nextResetAt = r.nextResetAt();
          resetButton.setVisible("OWNER".equals(r.myRole()));
        });
    ctx.async(
        () -> ctx.client().members(roomId),
        list -> members.setItems(FXCollections.observableArrayList(list)));
  }

  private void tick() {
    if (!ctx.client().isOnline()) connection.setText("Disconnected — use Reconnect");
    Instant now = Instant.now();
    ctx.client().chat().messages(roomId).stream()
        .filter(m -> m.status() == ChatState.Status.UNSEEN || m.status() == ChatState.Status.VIEWED)
        .filter(m -> m.expiresAt() != null && !now.isBefore(m.expiresAt()))
        .forEach(m -> m.setStatus(ChatState.Status.EXPIRED));
    if (nextResetAt != null) {
      Duration left = Duration.between(now, nextResetAt);
      countdown.setText(
          left.isNegative()
              ? "Membership reset due"
              : String.format("Members reset in %d:%02d", left.toMinutes(), left.toSecondsPart()));
    }
    render();
  }

  private void render() {
    messages.setItems(FXCollections.observableArrayList(ctx.client().chat().messages(roomId)));
  }

  private static String label(DisplayedMessage m) {
    return switch (m.status()) {
      case UNSEEN -> "unseen";
      case VIEWED -> "viewed";
      case DELETED_ON_SERVER -> "deleted from server";
      case EXPIRED -> "expired";
    };
  }

  @FXML
  void onSend() {
    String text = input.getText();
    if (text == null || text.isBlank()) {
      return;
    }
    input.clear();
    ctx.async(() -> ctx.client().send(roomId, text), e -> render());
  }

  @FXML
  void onReset() {
    ctx.async(
        () -> {
          ctx.client().resetRoomNow(roomId);
          return null;
        },
        v -> {
          stopTicker();
          ctx.info(
              "Room reset. Everyone except you was removed; invite them again for the new cycle.");
          ctx.showRooms();
        });
  }

  @FXML
  void onReconnect() {
    connection.setText("Reconnecting…");
    ctx.async(
        () -> {
          if (ctx.client().session(roomId).isPresent()) ctx.client().refresh(roomId);
          else ctx.client().enterRoom(roomId, askPasswordIfNeeded());
          return null;
        },
        ignored -> {
          connection.setText("Connected");
          render();
          loadRoom();
        });
  }

  @FXML
  void onLeave() {
    stopTicker();
    ctx.async(
        () -> {
          ctx.client().leaveRoom(roomId);
          return null;
        },
        v -> ctx.showRooms());
  }

  private void stopTicker() {
    if (ticker != null) {
      ticker.stop();
    }
  }
}
