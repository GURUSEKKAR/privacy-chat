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
  @FXML private Label passwordHint;

  @FXML private Button signIn;
  @FXML private Button createAccount;
  @FXML private Button checkApproval;

  private AppContext ctx;

  void init(AppContext ctx) {
    this.ctx = ctx;
    password
        .textProperty()
        .addListener((observable, previous, current) -> {
          passwordHint.setText(current.length() + " characters · New accounts need 12–128");
          status.setText("");
        });
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
    } else if (t.deviceStatus().startsWith("MFA_") || "SESSION_CONFLICT".equals(t.deviceStatus())) {
      mfa(t.deviceStatus());
    } else {
      status.setText(
          "This device is awaiting approval. On an approved device, approve fingerprint:\n"
              + t.deviceFingerprint());
    }
  }

  private void mfa(String state) {
    if ("MFA_SETUP_APPROVAL".equals(state)) {
      status.setText(
          "Set up Google Authenticator by signing in on the original approved browser or app first. Then sign in here with its code.");
      return;
    }
    setBusy(true);
    if ("MFA_SETUP".equals(state)) {
      ctx.async(
          () -> ctx.client().api().mfaSetup(),
          setup -> {
            setBusy(false);
            promptCode(
                "In Google Authenticator choose + then Enter a setup key. Account: "
                    + username.getText()
                    + ". Type: Time based. Setup key: "
                    + setup.secret());
          },
          this::failed);
    } else if ("SESSION_CONFLICT".equals(state)) {
      ctx.async(() -> ctx.client().api().mfaState(), this::afterMfa, this::failed);
    } else {
      setBusy(false);
      promptCode("Enter the current 6-digit Google Authenticator code or an unused recovery code.");
    }
  }

  private void promptCode(String message) {
    var dialog = new javafx.scene.control.TextInputDialog();
    dialog.setTitle("Authenticator verification");
    dialog.setHeaderText("Verify your sign-in");
    dialog.setContentText(message);
    dialog
        .showAndWait()
        .ifPresent(code -> {
          setBusy(true);
          ctx.async(
              () -> ctx.client().api().mfaVerify(code.trim()),
              this::afterMfa,
              failure -> {
                failed(failure);
                status.setText(
                    failure.getMessage()
                        + " Click Check approval to retry authenticator verification.");
              });
        });
  }

  private void afterMfa(com.secretchat.client.api.ApiClient.MfaState result) {
    setBusy(false);
    if (!result.recoveryCodes().isEmpty()) {
      var area = new javafx.scene.control.TextArea(String.join("\n", result.recoveryCodes()));
      area.setEditable(false);
      var saved =
          new javafx.scene.control.CheckBox("I saved these recovery codes somewhere private.");
      var dialog = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
      dialog.setTitle("Save recovery codes");
      dialog.setHeaderText(
          "These codes are shown only once. Each code can replace one authenticator code.");
      dialog.getDialogPane().setContent(new javafx.scene.layout.VBox(10, area, saved));
      dialog
          .getDialogPane()
          .lookupButton(javafx.scene.control.ButtonType.OK)
          .disableProperty()
          .bind(saved.selectedProperty().not());
      dialog.showAndWait();
    }
    if ("SESSION_CONFLICT".equals(result.status())) {
      String previous =
          result.previousSessions().stream()
              .map(s -> s.deviceName() + " — signed in " + s.signedInAt())
              .collect(java.util.stream.Collectors.joining("\n"));
      var dialog =
          new javafx.scene.control.Alert(
              javafx.scene.control.Alert.AlertType.CONFIRMATION,
              previous + "\nRevoke the previous session and sign in on this device?",
              javafx.scene.control.ButtonType.YES,
              javafx.scene.control.ButtonType.NO);
      dialog.setHeaderText("Another session is active");
      if (dialog.showAndWait().orElse(javafx.scene.control.ButtonType.NO)
          == javafx.scene.control.ButtonType.YES) {
        setBusy(true);
        ctx.async(() -> ctx.client().api().replaceSession(), this::afterMfa, this::failed);
      } else
        status.setText("Previous session kept. Sign out here or click Check approval to continue.");
    } else {
      setBusy(true);
      ctx.async(
          () -> {
            ctx.client().refreshApproval();
            return ctx.client().tokens();
          },
          this::after,
          this::failed);
    }
  }

  @FXML
  void onCheckApproval() {
    if (ctx.client().tokens() == null) {
      status.setText("Create an account or sign in before checking device approval.");
      return;
    }
    if (ctx.client().tokens().deviceStatus().startsWith("MFA_")
        || "SESSION_CONFLICT".equals(ctx.client().tokens().deviceStatus())) {
      mfa(ctx.client().tokens().deviceStatus());
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
              ? "Enter a password with 12–128 characters. You entered " + length + "."
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
