package com.secretchat.client.storage;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.client.mls.MlsIdentity;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalVaultTest {
  @Test
  void fingerprintMismatchDoesNotSilentlyReplacePinnedIdentity() {
    var user = UUID.randomUUID();
    var first = MlsIdentity.generate().fingerprint();
    var different = MlsIdentity.generate().fingerprint();
    var file = directory.resolve("pins.json");
    var pins = new FingerprintPins(file);
    assertThat(pins.check(user, first)).isEqualTo(FingerprintPins.Result.FIRST_USE);
    pins.pin(user, first);
    assertThat(pins.check(user, different)).isEqualTo(FingerprintPins.Result.MISMATCH);
    assertThat(new FingerprintPins(file).pinned(user)).contains(first);
  }

  @TempDir Path directory;

  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "secretchat.keychain.integration",
      matches = "true")
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
  void nativeMacKeychainRoundTrip() {
    var store = PlatformSecretStore.macKeychain();
    String account = "acceptance-" + UUID.randomUUID();
    String secret = Base64.getEncoder().encodeToString(new byte[32]);
    try {
      store.put(account, secret);
      assertThat(store.get(account)).contains(secret);
    } finally {
      store.delete(account);
    }
    assertThat(store.get(account)).isEmpty();
  }

  @Test
  void encryptedFallbackNeverPersistsPlaintextPrivateKeys() throws Exception {
    var vault = new LocalVault(directory, null);
    var identity = MlsIdentity.generate();
    var user = UUID.randomUUID();
    var device = UUID.randomUUID();
    var password = "vault-password-123".toCharArray();
    vault.save("alice", user, device, identity, password);
    String saved = Files.readString(directory.resolve("alice/device.json"));
    assertThat(saved)
        .doesNotContain(Base64.getEncoder().encodeToString(identity.exportPrivateForVault()));
    assertThat(vault.load("alice", password).orElseThrow().identity().publicKey())
        .isEqualTo(identity.publicKey());
    assertThatThrownBy(() -> vault.load("alice", "wrong-password".toCharArray()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> vault.exists("../escape"))
        .isInstanceOf(IllegalArgumentException.class);
    vault.delete("alice");
    assertThat(vault.exists("alice")).isFalse();
  }

  @Test
  void platformBackedWrappingKeyIsRequiredAndNotWrittenToDisk() throws Exception {
    Map<String, String> secrets = new HashMap<>();
    var platform =
        new PlatformSecretStore() {
          public void put(String account, String value) {
            secrets.put(account, value);
          }

          public Optional<String> get(String account) {
            return Optional.ofNullable(secrets.get(account));
          }

          public void delete(String account) {
            secrets.remove(account);
          }
        };
    var vault = new LocalVault(directory, platform);
    var identity = MlsIdentity.generate();
    vault.save(
        "alice", UUID.randomUUID(), UUID.randomUUID(), identity, "unused-password".toCharArray());
    assertThat(secrets).hasSize(1);
    String saved = Files.readString(directory.resolve("alice/device.json"));
    assertThat(saved).contains("\"version\":2").doesNotContain(secrets.values().iterator().next());
    assertThat(vault.load("alice", new char[0]).orElseThrow().identity().publicKey())
        .isEqualTo(identity.publicKey());
    secrets.clear();
    assertThatThrownBy(() -> vault.load("alice", new char[0]))
        .isInstanceOf(IllegalStateException.class);
  }
}
