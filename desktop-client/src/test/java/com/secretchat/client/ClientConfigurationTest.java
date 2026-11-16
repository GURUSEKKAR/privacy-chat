package com.secretchat.client;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ClientConfigurationTest {
  @Test
  void allowsTlsAndLocalDevelopmentButRejectsUnsafeCredentialDestinations() {
    for (String value :
        new String[] {
          "https://chat.example.test:8443",
          "http://localhost:8080",
          "http://127.0.0.1:8080",
          "http://[::1]:8080"
        })
      assertThat(ClientConfiguration.validateServer(URI.create(value)))
          .isEqualTo(URI.create(value));
    for (String value :
        new String[] {
          "http://chat.example.test",
          "https://user:password@chat.example.test",
          "https://chat.example.test/api",
          "https://chat.example.test?token=secret",
          "file:///tmp/backend"
        })
      assertThatThrownBy(() -> ClientConfiguration.validateServer(URI.create(value)))
          .isInstanceOf(IllegalArgumentException.class);
  }
}
