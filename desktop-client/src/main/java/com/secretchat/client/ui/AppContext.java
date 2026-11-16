package com.secretchat.client.ui;

import com.secretchat.client.SecretChatClient;
import com.secretchat.client.storage.FingerprintPins;
import com.secretchat.client.storage.LocalVault;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/** Shared UI state: the client core, navigation and a background executor for network calls. */
public final class AppContext {
  private final Stage stage;
  private final SecretChatClient client;
  private final ExecutorService io =
      Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "secretchat-ui-io");
        t.setDaemon(true);
        return t;
      });

  AppContext(Stage stage, URI server, Path dataDir) {
    this.stage = stage;
    this.client =
        new SecretChatClient(
            server, new LocalVault(dataDir), new FingerprintPins(dataDir.resolve("pins.json")));
  }

  public SecretChatClient client() {
    return client;
  }

  public void showLogin() {
    show("/fxml/login.fxml", c -> ((LoginController) c).init(this));
  }

  public void showRooms() {
    show("/fxml/rooms.fxml", c -> ((RoomsController) c).init(this));
  }

  public void showChat(UUID roomId, String displayCode) {
    show("/fxml/chat.fxml", c -> ((ChatController) c).init(this, roomId, displayCode));
  }

  private void show(String fxml, Consumer<Object> init) {
    try {
      FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
      Parent root = loader.load();
      init.accept(loader.getController());
      stage.setScene(new Scene(root, 900, 620));
      stage.show();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load " + fxml, e);
    }
  }

  /** Runs a network call off the FX thread; delivers result or error back on the FX thread. */
  public <T> void async(Supplier<T> work, Consumer<T> onSuccess) {
    async(work, onSuccess, this::handleFailure);
  }

  public <T> void async(
      Supplier<T> work, Consumer<T> onSuccess, Consumer<RuntimeException> onFailure) {
    io.submit(() -> {
      try {
        T result = work.get();
        Platform.runLater(() -> onSuccess.accept(result));
      } catch (RuntimeException e) {
        Platform.runLater(() -> onFailure.accept(e));
      }
    });
  }

  private void handleFailure(RuntimeException failure) {
    if (failure instanceof com.secretchat.client.KeySubstitutionException changed
        && changed.actual() != null
        && changed.actual().replaceAll("\\s+", "").matches("[0-9a-fA-F]{40}")) {
      var dialog = new javafx.scene.control.TextInputDialog();
      dialog.setHeaderText("Device identity changed");
      dialog.setContentText(
          "Verify this fingerprint with the person through another trusted channel, then type it here:\n"
              + changed.actual());
      dialog
          .showAndWait()
          .ifPresent(entered -> {
            if (com.secretchat.protocol.Fingerprints.matches(entered, changed.actual())) {
              client.trustFingerprint(changed.userId(), changed.actual());
              info("Verified identity saved. Retry the action.");
            } else error("Fingerprint did not match. Trust was not changed.");
          });
      return;
    }
    error(failure.getMessage());
  }

  public void error(String message) {
    Alert a = new Alert(Alert.AlertType.ERROR, message == null ? "Unexpected error" : message);
    a.setHeaderText(null);
    a.showAndWait();
  }

  public void info(String message) {
    Alert a = new Alert(Alert.AlertType.INFORMATION, message);
    a.setHeaderText(null);
    a.showAndWait();
  }

  void shutdown() {
    try {
      client.close();
    } finally {
      io.shutdownNow();
    }
  }
}
