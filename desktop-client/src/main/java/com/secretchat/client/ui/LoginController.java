package com.secretchat.client.ui;

import com.secretchat.protocol.AuthDtos.TokenResponse;
import com.secretchat.protocol.Usernames;
import java.util.Arrays;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class LoginController {
  @FXML private TextField username;
  @FXML private PasswordField password;
  @FXML private TextField deviceName;
  @FXML private Label status;

  @FXML private Button signIn;
  @FXML private Button createAccount;
  @FXML private Button checkApproval;

  private AppContext ctx;

  void init(AppContext ctx) {
    this.ctx = ctx;
    deviceName.setText(System.getProperty("user.name", "desktop") + "-desktop");
  }

  @FXML
  void onLogin() {
    if (!validate(false)) return;
    setBusy(true);
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
        this::after,
        this::failed);
  }

  @FXML
  void onRegister() {
    if (!validate(true)) return;
    setBusy(true);
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
        this::after,
        this::failed);
  }

  private void after(TokenResponse t) {
    setBusy(false);
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
    if (ctx.client().tokens() == null) {
      status.setText("Create an account or sign in before checking device approval.");
      return;
    }
    setBusy(true);
    status.setText("Checking approval…");
    ctx.async(
        () -> {
          ctx.client().refreshApproval();
          return ctx.client().isDeviceApproved();
        },
        approved -> {
          setBusy(false);
          if (approved) {
            ctx.showRooms();
          } else {
            status.setText(
                "Still pending approval. Fingerprint: " + ctx.client().deviceFingerprint());
          }
        },
        this::failed);
  }

  private void setBusy(boolean busy) {
    signIn.setDisable(busy);
    createAccount.setDisable(busy);
    checkApproval.setDisable(
        busy || ctx.client().tokens() == null || ctx.client().isDeviceApproved());
  }

  private void failed(RuntimeException failure) {
    setBusy(false);
    status.setText(
        failure.getMessage() == null
            ? "Unable to complete this request. Please try again."
            : failure.getMessage());
  }

  private boolean validate(boolean registering) {
    String user = Usernames.normalize(username.getText());
    if (!Usernames.isValidNormalized(user)) {
      status.setText(
          "Enter a username with 3–32 characters: letters, numbers, underscores, dots or hyphens. Start with a letter or number.");
      username.requestFocus();
      return false;
    }
    int length = password.getText().length();
    if (password.getText().isBlank() || length > 128 || (registering && length < 12)) {
      status.setText(
          registering
              ? "Enter a password with 12–128 characters."
              : "Enter your password (up to 128 characters).");
      password.requestFocus();
      return false;
    }
    if (deviceName.getText().isBlank() || deviceName.getText().length() > 64) {
      status.setText("Enter a device name with 1–64 characters.");
      deviceName.requestFocus();
      return false;
    }
    username.setText(user);
    return true;
  }
}
