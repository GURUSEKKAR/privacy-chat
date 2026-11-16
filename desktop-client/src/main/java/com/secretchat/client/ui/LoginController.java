package com.secretchat.client.ui;

import com.secretchat.protocol.AuthDtos.TokenResponse;
import java.util.Arrays;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class LoginController {
  @FXML private TextField username;
  @FXML private PasswordField password;
  @FXML private TextField deviceName;
  @FXML private Label status;

  private AppContext ctx;

  void init(AppContext ctx) {
    this.ctx = ctx;
    deviceName.setText(System.getProperty("user.name", "desktop") + "-desktop");
  }

  @FXML
  void onLogin() {
    char[] pw = password.getText().toCharArray();
    String user = username.getText();
    String device = deviceName.getText();
    status.setText("Signing in…");
    ctx.async(
        () -> {
          try {
            return ctx.client().login(user, pw, device);
          } finally {
            Arrays.fill(pw, '\0');
          }
        },
        this::after);
  }

  @FXML
  void onRegister() {
    char[] pw = password.getText().toCharArray();
    String user = username.getText();
    String device = deviceName.getText();
    status.setText("Creating account…");
    ctx.async(
        () -> {
          try {
            return ctx.client().register(user, pw, device);
          } finally {
            Arrays.fill(pw, '\0');
          }
        },
        this::after);
  }

  private void after(TokenResponse t) {
    password.clear();
    if ("APPROVED".equals(t.deviceStatus())) {
      ctx.showRooms();
    } else {
      status.setText(
          "This device is awaiting approval. On an approved device, approve fingerprint:\n"
              + t.deviceFingerprint());
    }
  }

  @FXML
  void onCheckApproval() {
    ctx.async(
        () -> {
          ctx.client().refreshApproval();
          return ctx.client().isDeviceApproved();
        },
        approved -> {
          if (approved) {
            ctx.showRooms();
          } else {
            status.setText(
                "Still pending approval. Fingerprint: " + ctx.client().deviceFingerprint());
          }
        });
  }
}
