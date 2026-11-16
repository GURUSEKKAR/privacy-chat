package com.secretchat.client.ui;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.testsupport.IntegrationTestBase;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Opt-in real JavaFX acceptance: mvn verify -Dsecretchat.gui=true (requires a display). */
@EnabledIfSystemProperty(named = "secretchat.gui", matches = "true")
class GuiAcceptanceTest extends IntegrationTestBase {
  @LocalServerPort int port;
  @TempDir Path data;

  record Screen(Stage stage, AppContext context) {}

  private <T> T fx(Callable<T> action) throws Exception {
    var task = new FutureTask<T>(action);
    Platform.runLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  private void await(Callable<Boolean> condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < end) {
      fx(() -> {
        for (var w : List.copyOf(Window.getWindows())) {
          if (w.getScene() != null && w.getScene().getRoot() instanceof DialogPane pane) {
            var button = pane.lookupButton(ButtonType.OK);
            if (button instanceof Button b) {
              System.out.println("GUI dialog: " + pane.getContentText());
              b.fire();
            }
          }
        }
        return null;
      });
      if (condition.call()) return;
      Thread.sleep(40);
    }
    fail("GUI acceptance condition timed out");
  }

  private Screen screen(String name) throws Exception {
    return fx(() -> {
      var stage = new Stage();
      var context =
          new AppContext(stage, URI.create("http://localhost:" + port), data.resolve(name));
      context.showLogin();
      return new Screen(stage, context);
    });
  }

  private void text(Screen s, String id, String value) throws Exception {
    fx(() -> {
      ((TextInputControl) s.stage().getScene().lookup("#" + id)).setText(value);
      return null;
    });
  }

  private void button(Screen s, String label) throws Exception {
    Button control =
        fx(() ->
            (Button)
                s.stage().getScene().getRoot().lookupAll(".button").stream()
                    .filter(n -> n instanceof Button b && label.equals(b.getText()))
                    .findFirst()
                    .orElseThrow());
    // Modal handlers run a nested FX event loop; do not wait for fire() to return.
    Platform.runLater(() -> {
      if (label.equals("Enter room") || label.equals("Add accepted members")) {
        ((ListView<?>) s.stage().getScene().lookup("#rooms")).getSelectionModel().selectFirst();
      }
      control.fire();
    });
  }

  private void register(Screen s, String name) throws Exception {
    text(s, "username", name);
    text(s, "password", PASSWORD);
    button(s, "Create account");
    await(() -> fx(() -> s.stage().getScene().lookup("#rooms") != null));
  }

  @SuppressWarnings("unchecked")
  private void select(Screen s, String id) throws Exception {
    fx(() -> {
      ((ListView<Object>) s.stage().getScene().lookup("#" + id)).getSelectionModel().selectFirst();
      return null;
    });
  }

  private void refreshRooms(Screen s) throws Exception {
    button(s, "Refresh");
    await(() ->
        fx(() -> !((ListView<?>) s.stage().getScene().lookup("#rooms")).getItems().isEmpty()));
    select(s, "rooms");
  }

  private void snapshot(Screen s, String name) throws Exception {
    fx(() -> {
      var image = s.stage().getScene().snapshot(null);
      var reader = image.getPixelReader();
      var bitmap =
          new java.awt.image.BufferedImage(
              (int) image.getWidth(),
              (int) image.getHeight(),
              java.awt.image.BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < image.getHeight(); y++)
        for (int x = 0; x < image.getWidth(); x++) bitmap.setRGB(x, y, reader.getArgb(x, y));
      var path = Path.of("target", "gui-evidence", name + ".png");
      java.nio.file.Files.createDirectories(path.getParent());
      javax.imageio.ImageIO.write(bitmap, "png", path.toFile());
      return null;
    });
  }

  @Test
  void realScreensRegisterInviteChatConsumeCatchUpAndReset() throws Exception {
    var ready = new java.util.concurrent.CountDownLatch(1);
    Platform.startup(() -> {
      Platform.setImplicitExit(false);
      ready.countDown();
    });
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    var initial = screen("a");
    assertThat(
            fx(() -> ((Button) initial.stage().getScene().lookup("#checkApproval")).isDisabled()))
        .isTrue();
    assertThatThrownBy(() -> initial.context().client().refreshApproval())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sign in");
    button(initial, "Create account");
    await(() ->
        fx(() ->
            ((Label) initial.stage().getScene().lookup("#status")).getText().contains("username")));
    text(initial, "username", "valid_user");
    text(initial, "password", "short");
    button(initial, "Create account");
    await(() ->
        fx(() ->
            ((Label) initial.stage().getScene().lookup("#status")).getText().contains("12–128")));
    assertThat(fx(() -> ((Label) initial.stage().getScene().lookup("#passwordHint")).getText()))
        .contains("5 characters");
    text(initial, "password", "123456789012");
    assertThat(fx(() -> ((Label) initial.stage().getScene().lookup("#passwordHint")).getText()))
        .contains("12 characters");
    assertThat(initial.context().client().tokens()).isNull();
    snapshot(initial, "login");
    String ownerName = uniqueName("guia");
    register(initial, ownerName);
    initial.context().shutdown();
    fx(() -> {
      initial.stage().close();
      return null;
    });
    Screen a = screen("a"), b = screen("b"), c = screen("c");
    try {
      text(a, "username", ownerName);
      text(a, "password", PASSWORD);
      button(a, "Sign in");
      await(() -> fx(() -> a.stage().getScene().lookup("#rooms") != null));
      register(b, uniqueName("guib"));
      register(c, uniqueName("guic"));
      snapshot(a, "rooms");
      fx(() -> {
        ((TitledPane) a.stage().getScene().getRoot().lookup(".titled-pane")).setExpanded(true);
        return null;
      });
      text(a, "newRoomCode", uniqueName("demo"));
      button(a, "Create");
      await(() -> !a.context().client().api().rooms().isEmpty());
      refreshRooms(a);
      var room = a.context().client().api().rooms().getFirst();
      for (Screen member : List.of(b, c)) {
        text(a, "inviteUsername", member.context().client().username());
        button(a, "Invite");
        await(() -> !member.context().client().myInvitations().isEmpty());
        button(member, "Refresh");
        await(() ->
            fx(() ->
                !((ListView<?>) member.stage().getScene().lookup("#invitations"))
                    .getItems()
                    .isEmpty()));
        select(member, "invitations");
        button(member, "Accept selected");
        await(() ->
            a.context().client().api().roomInvites(room.id()).stream()
                .anyMatch(i ->
                    "ACCEPTED".equals(i.status())
                        && i.inviteeUserId().equals(member.context().client().userId())));
      }
      button(a, "Add accepted members");
      await(() -> a.context().client().members(room.id()).size() == 3);
      refreshRooms(a);
      refreshRooms(b);
      button(a, "Enter room");
      button(b, "Enter room");
      await(() ->
          a.context().client().session(room.id()).isPresent()
              && b.context().client().session(room.id()).isPresent());
      text(a, "input", "GUI acceptance message");
      button(a, "Send");
      await(() -> !b.context().client().chat().messages(room.id()).isEmpty());
      await(
          () -> fx(() -> !((Label) b.stage().getScene().lookup("#countdown")).getText().isBlank()));
      snapshot(b, "chat");
      button(b, "Leave");
      await(() -> fx(() -> b.stage().getScene().lookup("#rooms") != null));
      refreshRooms(b);
      button(b, "Enter room");
      await(() -> b.context().client().session(room.id()).isPresent());
      assertThat(b.context().client().chat().messages(room.id())).isEmpty();
      b.context().client().disconnectSocket();
      button(b, "Reconnect");
      await(() ->
          b.context().client().isOnline() && b.context().client().session(room.id()).isPresent());
      assertThat(b.context().client().chat().messages(room.id())).isEmpty();
      refreshRooms(c);
      button(c, "Enter room");
      await(() -> !c.context().client().chat().messages(room.id()).isEmpty());
      snapshot(c, "offline-catchup");
      button(a, "Reset room now");
      // Membership reads are intentionally blocked until the asynchronous owner rekey completes.
      await(() ->
          "ACTIVE".equals(a.context().client().api().room(room.id()).state())
              && a.context().client().members(room.id()).size() == 1);
      await(() ->
          fx(() ->
              a.stage().getScene().lookup("#rooms") != null
                  && b.stage().getScene().lookup("#rooms") != null
                  && c.stage().getScene().lookup("#rooms") != null));
      snapshot(a, "reset");
      assertThat(b.context().client().chat().messages(room.id())).isEmpty();
    } finally {
      for (Screen s : List.of(a, b, c)) {
        s.context().shutdown();
        fx(() -> {
          s.stage().close();
          return null;
        });
      }
      Platform.exit();
    }
  }
}
