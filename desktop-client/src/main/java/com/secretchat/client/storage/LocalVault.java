package com.secretchat.client.storage;

import com.secretchat.client.mls.MlsIdentity;
import com.secretchat.client.security.VaultCrypto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-account device vault: {@code <baseDir>/<username>/device.json}. Holds only the device id,
 * public key and the AES-GCM-sealed signature private key. No messages, no MLS group state and no
 * plaintext history are ever written to disk.
 */
public final class LocalVault {
  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  record VaultFile(
      int version,
      String username,
      UUID userId,
      UUID deviceId,
      String publicKey,
      String salt,
      String nonce,
      String sealedPrivateKey) {}

  public record Loaded(UUID userId, UUID deviceId, MlsIdentity identity) {}

  private final Path baseDir;
  private final PlatformSecretStore platform;

  public LocalVault(Path baseDir) {
    this(
        baseDir,
        System.getProperty("os.name").startsWith("Mac")
                && baseDir
                    .toAbsolutePath()
                    .normalize()
                    .equals(defaultBaseDir().toAbsolutePath().normalize())
                && Boolean.parseBoolean(System.getProperty("secretchat.keychain", "true"))
            ? PlatformSecretStore.macKeychain()
            : null);
  }

  public LocalVault(Path baseDir, PlatformSecretStore platform) {
    this.baseDir = baseDir;
    this.platform = platform;
  }

  public static Path defaultBaseDir() {
    return Path.of(System.getProperty("user.home"), ".secretchat");
  }

  public boolean exists(String username) {
    return Files.isRegularFile(file(username));
  }

  public void save(
      String username, UUID userId, UUID deviceId, MlsIdentity identity, char[] password) {
    byte[] priv = identity.exportPrivateForVault();
    char[] wrapping = password;
    int version = 1;
    try {
      if (platform != null) {
        try {
          byte[] entropy = new byte[32];
          RANDOM.nextBytes(entropy);
          String secret =
              platform
                  .get(keychainAccount(username))
                  .orElseGet(() -> Base64.getEncoder().encodeToString(entropy));
          Arrays.fill(entropy, (byte) 0);
          platform.put(keychainAccount(username), secret);
          wrapping = secret.toCharArray();
          version = 2;
        } catch (RuntimeException unavailable) {
          // Password-encrypted fallback remains usable if Keychain access is unavailable.
        }
      }
      VaultCrypto.Sealed sealed = VaultCrypto.seal(wrapping, priv, aad(username, deviceId));
      Base64.Encoder b64 = Base64.getEncoder();
      VaultFile vf =
          new VaultFile(
              version,
              username,
              userId,
              deviceId,
              b64.encodeToString(identity.publicKey()),
              b64.encodeToString(sealed.salt()),
              b64.encodeToString(sealed.nonce()),
              b64.encodeToString(sealed.ciphertext()));
      Path target = file(username);
      Files.createDirectories(target.getParent());
      restrict(target.getParent(), "rwx------");
      Path tmp = Files.createTempFile(target.getParent(), "device", ".tmp");
      Files.writeString(tmp, JSON.writeValueAsString(vf), StandardCharsets.UTF_8);
      restrict(tmp, "rw-------");
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new IllegalStateException("Could not write device vault", e);
    } finally {
      Arrays.fill(priv, (byte) 0);
      if (wrapping != password) Arrays.fill(wrapping, '\0');
    }
  }

  public Optional<Loaded> load(String username, char[] password) {
    Path f = file(username);
    if (!Files.isRegularFile(f)) {
      return Optional.empty();
    }
    try {
      VaultFile vf = JSON.readValue(Files.readString(f, StandardCharsets.UTF_8), VaultFile.class);
      if (vf.version() != 1 && vf.version() != 2)
        throw new IllegalStateException("Unsupported vault version");
      Base64.Decoder d = Base64.getDecoder();
      char[] wrapping =
          vf.version() == 2
              ? (platform == null
                      ? Optional.<String>empty()
                      : platform.get(keychainAccount(username)))
                  .orElseThrow(
                      () -> new IllegalStateException("This vault requires its OS Keychain entry"))
                  .toCharArray()
              : password;
      byte[] priv = null;
      try {
        priv =
            VaultCrypto.open(
                wrapping,
                new VaultCrypto.Sealed(
                    d.decode(vf.salt()), d.decode(vf.nonce()), d.decode(vf.sealedPrivateKey())),
                aad(username, vf.deviceId()));
        return Optional.of(
            new Loaded(
                vf.userId(), vf.deviceId(), MlsIdentity.restore(priv, d.decode(vf.publicKey()))));
      } finally {
        if (priv != null) Arrays.fill(priv, (byte) 0);
        if (wrapping != password) Arrays.fill(wrapping, '\0');
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not read device vault", e);
    }
  }

  public void delete(String username) {
    try {
      Files.deleteIfExists(file(username));
      if (platform != null) platform.delete(keychainAccount(username));
    } catch (IOException e) {
      throw new IllegalStateException("Could not delete device vault", e);
    }
  }

  private Path file(String username) {
    if (!username.matches("^[a-z0-9_.-]{3,32}$")) {
      throw new IllegalArgumentException("invalid username");
    }
    return baseDir.resolve(username).resolve("device.json");
  }

  private String keychainAccount(String username) {
    return baseDir.toAbsolutePath().normalize() + "/" + username;
  }

  private static byte[] aad(String username, UUID deviceId) {
    return ("secretchat-vault-v1|" + username + "|" + deviceId).getBytes(StandardCharsets.UTF_8);
  }

  private static void restrict(Path p, String perms) {
    try {
      Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Non-POSIX file systems (Windows): rely on the user profile ACLs.
    }
  }
}
