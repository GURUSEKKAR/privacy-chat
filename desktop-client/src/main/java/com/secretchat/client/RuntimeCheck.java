package com.secretchat.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;

/** Verifies a packaged runtime without showing windows, signing in, or contacting any server. */
public final class RuntimeCheck {
  private RuntimeCheck() {}

  public static int run() {
    var result = new CompletableFuture<Boolean>();
    try {
      Platform.startup(() -> {
        try {
          for (String screen : new String[] {"login", "rooms", "chat"}) {
            var location = RuntimeCheck.class.getResource("/fxml/" + screen + ".fxml");
            if (location == null) throw new IllegalStateException("Missing screen resource");
            new FXMLLoader(location).load();
          }
          var identity = com.secretchat.client.mls.MlsIdentity.generate();
          byte[] content =
              "packaged runtime verification".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          byte[] signature = identity.sign("RuntimeCheck", content);
          if (!identity.verify(identity.publicKey(), "RuntimeCheck", content, signature))
            throw new IllegalStateException("Cryptography verification failed");
          identity.destroy();
          tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"ok\":true}");
          result.complete(true);
        } catch (Throwable error) {
          result.completeExceptionally(error);
        }
      });
      result.get(20, TimeUnit.SECONDS);
      System.out.println(
          "{\"ok\":true,\"screens_loaded\":3,\"crypto_verified\":true,\"java\":\""
              + System.getProperty("java.version")
              + "\"}");
      return 0;
    } catch (Exception error) {
      System.err.println(
          "Packaged runtime verification failed: " + error.getClass().getSimpleName());
      return 1;
    } finally {
      Platform.exit();
    }
  }
}
