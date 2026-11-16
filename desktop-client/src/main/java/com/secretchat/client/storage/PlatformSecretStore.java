package com.secretchat.client.storage;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Optional macOS Keychain storage; commands travel on stdin, never in process arguments. */
public interface PlatformSecretStore {
  void put(String account, String value);

  Optional<String> get(String account);

  void delete(String account);

  static PlatformSecretStore macKeychain() {
    return new PlatformSecretStore() {
      private String run(String command, boolean missingAllowed) {
        try {
          var process =
              new ProcessBuilder("/usr/bin/security", "-i")
                  .redirectError(ProcessBuilder.Redirect.DISCARD)
                  .start();
          try (var input = process.getOutputStream()) {
            input.write((command + "\nquit\n").getBytes(StandardCharsets.UTF_8));
          }
          if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Keychain request timed out");
          }
          String output =
              new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
          // Interactive security reports per-command failure in stderr; verify writes by readback.
          return output;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Keychain interrupted", e);
        } catch (java.io.IOException e) {
          throw new IllegalStateException("Keychain unavailable", e);
        }
      }

      private String quoted(String s) {
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\0') >= 0)
          throw new IllegalArgumentException("Invalid Keychain identifier");
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
      }

      public void put(String account, String value) {
        run(
            "add-generic-password -U -s com.secretchat.vault -a "
                + quoted(account)
                + " -w "
                + quoted(value),
            false);
        if (!get(account).filter(value::equals).isPresent())
          throw new IllegalStateException("Keychain write failed");
      }

      public Optional<String> get(String account) {
        // Reading the password produces just the value; security's interactive prompt is on stderr.
        String value =
            run(
                "find-generic-password -s com.secretchat.vault -a " + quoted(account) + " -w",
                true);
        return value.matches("[A-Za-z0-9+/=]{40,64}") ? Optional.of(value) : Optional.empty();
      }

      public void delete(String account) {
        run("delete-generic-password -s com.secretchat.vault -a " + quoted(account), true);
      }
    };
  }
}
