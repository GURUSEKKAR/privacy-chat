package com.secretchat.client.ui;

import com.secretchat.client.storage.LocalVault;
import java.net.URI;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * JavaFX entry point. Server URL: {@code -Dsecretchat.server=https://chat.example.org} (default
 * http://localhost:8080 for local development). Data dir: {@code -Dsecretchat.dataDir}.
 */
public class SecretChatApp extends Application {
  private AppContext context;

  @Override
  public void start(Stage stage) {
    com.secretchat.client.ClientConfiguration.applyEnvironment();
    URI server =
        com.secretchat.client.ClientConfiguration.validateServer(
            URI.create(System.getProperty("secretchat.server", "http://localhost:8080")));
    String dir = System.getProperty("secretchat.dataDir");
    Path dataDir = dir == null ? LocalVault.defaultBaseDir() : Path.of(dir);
    context = new AppContext(stage, server, dataDir);
    stage.setTitle("Secret Chat Room");
    stage.setOnCloseRequest(e -> Platform.exit());
    context.showLogin();
  }

  @Override
  public void stop() {
    // Quitting ends room sessions and wipes all in-memory plaintext and MLS state.
    if (context != null) {
      context.shutdown();
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
