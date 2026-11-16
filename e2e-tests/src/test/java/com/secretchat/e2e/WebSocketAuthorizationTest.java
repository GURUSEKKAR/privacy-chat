package com.secretchat.e2e;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.client.websocket.StompClient;
import com.secretchat.testsupport.IntegrationTestBase;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class WebSocketAuthorizationTest extends IntegrationTestBase {
  @LocalServerPort int port;

  private URI socket() {
    return URI.create("ws://localhost:" + port + "/ws");
  }

  @Test
  void unauthenticatedHandshakeIsRejected() {
    assertThatThrownBy(() ->
            StompClient.connect(
                socket(), "invalid", StompClient.handler(f -> {}, f -> {}, () -> {})))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void arbitrarySubscriptionsAndBrokerWritesAreDenied() throws Exception {
    var a = register("socket");
    for (String destination :
        new String[] {"/topic/rooms", "/queue/chat", "/user/other/queue/chat"}) {
      var denied = new CountDownLatch(1);
      var socket =
          StompClient.connect(
              socket(),
              a.tokens().accessToken(),
              StompClient.handler(f -> {}, f -> denied.countDown(), denied::countDown));
      try {
        socket.subscribe("bad", destination);
        assertThat(denied.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        socket.close();
      }
    }
    var denied = new CountDownLatch(1);
    var socket =
        StompClient.connect(
            socket(),
            a.tokens().accessToken(),
            StompClient.handler(f -> {}, f -> denied.countDown(), denied::countDown));
    try {
      socket.send("/topic/rooms", "{}");
      assertThat(denied.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      socket.close();
    }
  }

  @Test
  void revokedAndExpiredSessionsLoseSockets() throws Exception {
    var a = register("expired");
    var closed = new CountDownLatch(1);
    var socket =
        StompClient.connect(
            socket(),
            a.tokens().accessToken(),
            StompClient.handler(f -> {}, f -> {}, closed::countDown));
    try {
      clock.advance(Duration.ofMinutes(16));
      socket.subscribe("chat", "/user/queue/chat");
      assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      socket.close();
      clock.reset();
    }
    var b = register("revoked");
    var revoked = new CountDownLatch(1);
    var second =
        StompClient.connect(
            socket(),
            b.tokens().accessToken(),
            StompClient.handler(f -> {}, f -> {}, revoked::countDown));
    try {
      auth.logout(b.principal().authSessionId());
      assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      second.close();
    }
  }
}
