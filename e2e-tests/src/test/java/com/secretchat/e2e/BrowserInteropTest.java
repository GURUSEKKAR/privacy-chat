package com.secretchat.e2e;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.client.SecretChatClient;
import com.secretchat.client.api.ApiClient;
import com.secretchat.client.storage.FingerprintPins;
import com.secretchat.client.storage.LocalVault;
import com.secretchat.protocol.RoomDtos.*;
import com.secretchat.testsupport.IntegrationTestBase;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** Runs the actual TypeScript browser SDK against the real server and the Java MLS client. */
@EnabledIfSystemProperty(named = "secretchat.web", matches = "true")
class BrowserInteropTest extends IntegrationTestBase {
  @LocalServerPort int port;
  @TempDir Path data;

  private final class Browser implements AutoCloseable {
    private final Process process;
    private final BufferedReader input;
    private final BufferedWriter output;

    Browser(String name) throws Exception {
      process =
          new ProcessBuilder("node", "../web-client/test/runner.mjs")
              .redirectError(data.resolve(name + "-node.log").toFile())
              .start();
      input = process.inputReader();
      output = process.outputWriter();
    }

    JsonNode call(Map<String, Object> request) throws Exception {
      output.write(ApiClient.JSON.writeValueAsString(request));
      output.newLine();
      output.flush();
      long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
      while (!input.ready() && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
      assertThat(input.ready()).as("TypeScript client must reply before its deadline").isTrue();
      JsonNode response = ApiClient.JSON.readTree(input.readLine());
      assertThat(response.get("ok").asBoolean())
          .as(response.has("error") ? response.get("error").asString() : "Browser command")
          .isTrue();
      return response.get("value");
    }

    JsonNode register(String user) throws Exception {
      return call(
          Map.of(
              "command",
              "auth",
              "origin",
              "http://localhost:" + port,
              "user",
              user,
              "password",
              PASSWORD));
    }

    @Override
    public void close() {
      try {
        call(Map.of("command", "stop"));
      } catch (Exception ignored) {
      } finally {
        process.destroy();
        try {
          if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          process.destroyForcibly();
        }
      }
    }
  }

  @Test
  @Timeout(90)
  void desktopOwnerAndBrowserMemberExchangeAuthenticatedEncryptedMessages() throws Exception {
    try (var desktop =
            new SecretChatClient(
                URI.create("http://localhost:" + port),
                new LocalVault(data.resolve("desktop")),
                new FingerprintPins(data.resolve("pins.json")));
        var browser = new Browser("member")) {
      desktop.register(uniqueName("desktopweb"), PASSWORD.toCharArray(), "Java desktop");
      String browserName = uniqueName("browser");
      browser.register(browserName);
      var room =
          desktop.createRoom(
              new CreateRoomRequest(null, null, 600, 3600, PrivacyMode.TEMPORARY_CATCH_UP));
      var invite = desktop.invite(room.id(), browserName);
      browser.call(
          Map.of(
              "command",
              "accept",
              "invite",
              ApiClient.JSON.readTree(ApiClient.JSON.writeValueAsString(invite))));
      assertThat(desktop.addAcceptedMembers(room.id())).isEqualTo(1);
      desktop.enterRoom(room.id(), null);
      browser.call(Map.of("command", "enter", "room", room.id().toString()));
      desktop.send(room.id(), "Java to browser interoperability");
      var received = browser.call(Map.of("command", "poll"));
      assertThat(received.toString()).contains("Java to browser interoperability");
      browser.call(Map.of("command", "send", "text", "Browser to Java interoperability"));
      assertThat(
              desktop.awaitCondition(
                  () ->
                      desktop.chat().messages(room.id()).stream()
                          .anyMatch(m -> m.text().equals("Browser to Java interoperability")),
                  Duration.ofSeconds(10)))
          .isTrue();
      browser.call(Map.of("command", "leave"));
      browser.call(Map.of("command", "enter", "room", room.id().toString()));
      assertThat(browser.call(Map.of("command", "poll")).size()).isZero();
    }
  }

  @Test
  @Timeout(90)
  void browserOwnerAddsDesktopMemberThenResetExcludesOldMembers() throws Exception {
    try (var browser = new Browser("owner");
        var desktop =
            new SecretChatClient(
                URI.create("http://localhost:" + port),
                new LocalVault(data.resolve("member")),
                new FingerprintPins(data.resolve("member-pins.json")))) {
      browser.register(uniqueName("webowner"));
      desktop.register(uniqueName("javamember"), PASSWORD.toCharArray(), "Java member");
      var room = browser.call(Map.of("command", "create", "password", "protected-room-passphrase"));
      String id = room.get("id").asString();
      var invite =
          browser.call(Map.of("command", "invite", "room", id, "user", desktop.username()));
      desktop.acceptInvitation(
          java.util.UUID.fromString(invite.get("id").asString()), "protected-room-passphrase");
      browser.call(Map.of("command", "add", "room", id));
      browser.call(Map.of("command", "enter", "room", id, "password", "protected-room-passphrase"));
      desktop.enterRoom(java.util.UUID.fromString(id), "protected-room-passphrase");
      browser.call(Map.of("command", "send", "text", "Browser owner fresh epoch"));
      assertThat(
              desktop.awaitCondition(
                  () ->
                      desktop.chat().messages(java.util.UUID.fromString(id)).stream()
                          .anyMatch(m -> m.text().equals("Browser owner fresh epoch")),
                  Duration.ofSeconds(10)))
          .isTrue();
      browser.call(Map.of("command", "reset", "room", id));
      browser.call(Map.of("command", "enter", "room", id, "password", "protected-room-passphrase"));
      browser.call(Map.of("command", "send", "text", "New cycle excludes old members"));
      assertThatThrownBy(() -> desktop.api().room(java.util.UUID.fromString(id)))
          .isInstanceOf(com.secretchat.client.api.ApiException.class);
    }
  }

  @Test
  @Timeout(90)
  void browserPushDeliversWithoutPollingAndOwnerRemovesOnlySelectedMember() throws Exception {
    try (var owner = new Browser("fast-owner");
        var removed = new Browser("fast-removed");
        var retained = new Browser("fast-retained")) {
      var a = owner.register(uniqueName("fastowner"));
      var b = removed.register(uniqueName("fastmember"));
      var c = retained.register(uniqueName("fastretained"));
      var room = owner.call(Map.of("command", "create", "name", "Project team"));
      String id = room.get("id").asString();
      assertThat(room.get("name").asString()).isEqualTo("Project team");
      for (var entry :
          java.util.List.of(java.util.Map.entry(removed, b), java.util.Map.entry(retained, c))) {
        owner.call(
            Map.of(
                "command",
                "invite",
                "room",
                id,
                "user",
                entry.getValue().get("username").asString()));
        var inv = entry.getKey().call(Map.of("command", "invites")).get(0);
        entry.getKey().call(Map.of("command", "accept", "invite", inv));
      }
      owner.call(Map.of("command", "add", "room", id));
      for (var client : java.util.List.of(owner, removed, retained))
        client.call(Map.of("command", "enter", "room", id));
      long start = System.currentTimeMillis();
      owner.call(Map.of("command", "send", "text", "Instant WebSocket message"));
      var delivered =
          retained.call(Map.of("command", "waitMessage", "text", "Instant WebSocket message"));
      long latency = delivered.get("receivedAt").asLong() - start;
      System.out.println(
          "Local WebSocket send-to-decrypted-receipt latency: "
              + latency
              + " ms (no REST polling timer)");
      assertThat(latency).isLessThan(1000);
      var updated =
          owner.call(
              Map.of(
                  "command",
                  "settings",
                  "room",
                  id,
                  "settings",
                  Map.of(
                      "name",
                      "Renamed team",
                      "messageTtlSeconds",
                      120,
                      "resetIntervalSeconds",
                      1800,
                      "changePassword",
                      false)));
      assertThat(updated.get("name").asString()).isEqualTo("Renamed team");
      owner.call(Map.of("command", "remove", "room", id, "user", b.get("userId").asString()));
      owner.call(Map.of("command", "send", "text", "Remaining member only"));
      retained.call(Map.of("command", "waitMessage", "text", "Remaining member only"));
      assertThat(removed.call(Map.of("command", "rooms")).size()).isZero();
      // A timer/server reset can happen while the owner has the settings dialog open.
      owner.call(Map.of("command", "serverReset", "room", id));
      var lockedSettings =
          owner.call(
              Map.of(
                  "command",
                  "settings",
                  "room",
                  id,
                  "settings",
                  Map.of(
                      "name",
                      "Saved during reset",
                      "messageTtlSeconds",
                      60,
                      "resetIntervalSeconds",
                      600,
                      "changePassword",
                      true,
                      "roomPassword",
                      "new-room-password")));
      assertThat(lockedSettings.get("state").asString()).isEqualTo("LOCKED_PENDING_REKEY");
      assertThat(lockedSettings.get("name").asString()).isEqualTo("Saved during reset");
      owner.call(Map.of("command", "enter", "room", id, "password", "new-room-password"));
      owner.call(Map.of("command", "send", "text", "Recovered pending reset"));
      owner.call(Map.of("command", "serverReset", "room", id));
      owner.call(Map.of("command", "reset", "room", id));
      owner.call(Map.of("command", "enter", "room", id, "password", "new-room-password"));
      owner.call(Map.of("command", "send", "text", "Explicit reset also recovers"));
    }
  }
}
