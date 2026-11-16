package com.secretchat.client.ui;

import com.secretchat.protocol.DeviceDtos.DeviceView;
import com.secretchat.protocol.RoomDtos.CreateRoomRequest;
import com.secretchat.protocol.RoomDtos.InvitationView;
import com.secretchat.protocol.RoomDtos.PrivacyMode;
import com.secretchat.protocol.RoomDtos.RoomView;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;

public class RoomsController {
  @FXML private Label whoami;
  @FXML private ListView<RoomView> rooms;
  @FXML private ListView<InvitationView> invitations;
  @FXML private ListView<DeviceView> devices;
  @FXML private TextField newRoomCode;
  @FXML private PasswordField newRoomPassword;
  @FXML private Spinner<Integer> ttlMinutes;
  @FXML private Spinner<Integer> resetMinutes;
  @FXML private TextField inviteUsername;

  private AppContext ctx;

  void init(AppContext ctx) {
    this.ctx = ctx;
    whoami.setText(
        "Signed in as "
            + ctx.client().username()
            + " — this device: "
            + ctx.client().deviceFingerprint());
    rooms.setCellFactory(lv ->
        new ListCell<>() {
          @Override
          protected void updateItem(RoomView r, boolean empty) {
            super.updateItem(r, empty);
            setText(
                empty || r == null
                    ? null
                    : r.displayCode()
                        + "  ["
                        + r.myRole()
                        + ", "
                        + r.state()
                        + "]  resets "
                        + r.nextResetAt());
          }
        });
    invitations.setCellFactory(lv ->
        new ListCell<>() {
          @Override
          protected void updateItem(InvitationView i, boolean empty) {
            super.updateItem(i, empty);
            setText(
                empty || i == null
                    ? null
                    : "Invite to " + i.roomDisplayCode() + " (expires " + i.expiresAt() + ")");
          }
        });
    devices.setCellFactory(lv ->
        new ListCell<>() {
          @Override
          protected void updateItem(DeviceView d, boolean empty) {
            super.updateItem(d, empty);
            setText(
                empty || d == null ? null : d.name() + " " + d.status() + "  " + d.fingerprint());
          }
        });
    onRefresh();
  }

  @FXML
  void onRefresh() {
    ctx.async(
        () -> ctx.client().api().rooms(),
        list -> rooms.setItems(FXCollections.observableArrayList(list)));
    ctx.async(
        () -> ctx.client().myInvitations(),
        list -> invitations.setItems(FXCollections.observableArrayList(list)));
    ctx.async(
        () -> ctx.client().api().devices(),
        list -> devices.setItems(FXCollections.observableArrayList(list)));
  }

  @FXML
  void onCreateRoom() {
    String code = newRoomCode.getText().isBlank() ? null : newRoomCode.getText().trim();
    String pw = newRoomPassword.getText().isEmpty() ? null : newRoomPassword.getText();
    int ttl = ttlMinutes.getValue() * 60;
    int reset = resetMinutes.getValue() * 60;
    ctx.async(
        () ->
            ctx.client()
                .createRoom(
                    new CreateRoomRequest(code, pw, ttl, reset, PrivacyMode.TEMPORARY_CATCH_UP)),
        r -> {
          newRoomPassword.clear();
          onRefresh();
        });
  }

  @FXML
  void onInvite() {
    RoomView r = rooms.getSelectionModel().getSelectedItem();
    if (r == null || inviteUsername.getText().isBlank()) {
      ctx.error("Select one of your rooms and enter a username.");
      return;
    }
    String user = inviteUsername.getText().trim();
    ctx.async(() -> ctx.client().invite(r.id(), user), i -> ctx.info("Invited " + user + "."));
  }

  @FXML
  void onAddAccepted() {
    RoomView r = rooms.getSelectionModel().getSelectedItem();
    if (r == null) {
      ctx.error("Select a room you own.");
      return;
    }
    ctx.async(
        () -> ctx.client().addAcceptedMembers(r.id()),
        n -> ctx.info(n + " member(s) added with fresh MLS keys."));
  }

  @FXML
  void onAccept() {
    InvitationView i = invitations.getSelectionModel().getSelectedItem();
    if (i == null) {
      return;
    }
    Optional<String> pw = ask("Room password (leave empty if none)");
    ctx.async(
        () -> ctx.client().acceptInvitation(i.id(), pw.filter(s -> !s.isEmpty()).orElse(null)),
        v -> onRefresh());
  }

  @FXML
  void onApproveDevice() {
    DeviceView d = devices.getSelectionModel().getSelectedItem();
    if (d == null || !"PENDING".equals(d.status())) {
      ctx.error("Select a PENDING device.");
      return;
    }
    ask("Type the fingerprint displayed on the NEW device")
        .ifPresent(fp ->
            ctx.async(
                () -> {
                  ctx.client().approveDevice(d.id(), fp);
                  return null;
                },
                v -> onRefresh()));
  }

  @FXML
  void onResetSelected() {
    RoomView room = rooms.getSelectionModel().getSelectedItem();
    if (room == null || !"OWNER".equals(room.myRole())) {
      ctx.error("Select a room you own.");
      return;
    }
    var confirmation =
        new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION,
            "Reset this room and remove every other member?",
            javafx.scene.control.ButtonType.OK,
            javafx.scene.control.ButtonType.CANCEL);
    if (confirmation.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
        != javafx.scene.control.ButtonType.OK) return;
    ctx.async(
        () -> {
          ctx.client().resetRoomNow(room.id());
          return null;
        },
        ignored -> onRefresh());
  }

  @FXML
  void onRevokeDevice() {
    DeviceView device = devices.getSelectionModel().getSelectedItem();
    if (device == null || device.id().equals(ctx.client().tokens().deviceId())) {
      ctx.error("Select another device. Revoke this device from a trusted second device.");
      return;
    }
    ctx.async(() -> ctx.client().api().revokeDevice(device.id()), ignored -> onRefresh());
  }

  @FXML
  void onEnter() {
    RoomView r = rooms.getSelectionModel().getSelectedItem();
    if (r != null) {
      ctx.showChat(r.id(), r.displayCode());
    }
  }

  private Optional<String> ask(String prompt) {
    TextInputDialog d = new TextInputDialog();
    d.setHeaderText(null);
    d.setContentText(prompt);
    return d.showAndWait();
  }

  static <T> List<T> safe(List<T> l) {
    return l == null ? List.of() : l;
  }
}
