package com.secretchat.auth;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TotpTest {
  private final Totp totp = new Totp("test-only-encryption-key-at-least-32-characters");

  @Test
  void rfc6238Sha1VectorsReducedToSixDigitsAndReplayWindow() {
    String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    long[] times = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
    String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
    for (int i = 0; i < times.length; i++)
      assertThat(totp.code(secret, times[i] / 30)).isEqualTo(expected[i]);
    assertThat(totp.match(secret, "287082", 59, -1)).isEqualTo(1);
    assertThat(totp.match(secret, "287082", 59, 1)).isEqualTo(-1);
    assertThat(totp.match(secret, "287082", 150, -1)).isEqualTo(-1);
  }

  @Test
  void encryptionBindsSecretsToAccountAndUsesFreshNonces() {
    UUID user = UUID.randomUUID();
    String secret = totp.newSecret();
    String first = totp.encrypt(secret, user), second = totp.encrypt(secret, user);
    assertThat(first).isNotEqualTo(second).doesNotContain(secret);
    assertThat(totp.decrypt(first, user)).isEqualTo(secret);
    assertThatThrownBy(() -> totp.decrypt(first, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new Totp("").encrypt(secret, user))
        .isInstanceOf(com.secretchat.common.ApiException.class);
  }
}
