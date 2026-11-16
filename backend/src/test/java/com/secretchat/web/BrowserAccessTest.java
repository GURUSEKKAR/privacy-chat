package com.secretchat.web;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.common.ApiException;
import com.secretchat.testsupport.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class BrowserAccessTest extends IntegrationTestBase {
  @LocalServerPort int port;
  @Autowired BrowserSocketTickets tickets;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void websiteAssetsArePublicButApiAndTicketCreationRemainAuthenticated() throws Exception {
    var page =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("Secret Chat Room", "assets/");
    assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("script-src 'self'", "frame-ancestors 'none'")
        .doesNotContain("unsafe-inline");
    assertThat(page.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(
            http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/rooms"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode())
        .isEqualTo(401);
    assertThat(
            http.send(
                    HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/ws-ticket"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void ticketsAreSingleUseExpireAndCannotSurviveLogoutOrPendingApproval() {
    var user = register("ticket");
    String first = tickets.issue(user.principal());
    assertThat(tickets.consume(first)).isEqualTo(user.principal());
    assertThat(tickets.consume(first)).isNull();
    String superseded = tickets.issue(user.principal());
    String latest = tickets.issue(user.principal());
    assertThat(tickets.consume(superseded)).isNull();
    clock.advance(Duration.ofSeconds(31));
    assertThat(tickets.consume(latest)).isNull();
    String revoked = tickets.issue(user.principal());
    auth.logout(user.principal().authSessionId());
    assertThat(tickets.consume(revoked)).isNull();
    var pending =
        new AuthPrincipal(
            user.principal().userId(),
            user.username(),
            user.principal().deviceId(),
            false,
            user.principal().authSessionId());
    assertThatThrownBy(() -> tickets.issue(pending)).isInstanceOf(ApiException.class);
  }

  @Test
  void missingTicketsAndCrossOriginWebSocketsAreRejected() {
    assertThatThrownBy(() ->
            http.newWebSocketBuilder()
                .buildAsync(
                    URI.create("ws://localhost:" + port + "/ws/browser"),
                    new WebSocket.Listener() {})
                .join())
        .isInstanceOf(CompletionException.class);
    var user = register("origin");
    String ticket = tickets.issue(user.principal());
    assertThatThrownBy(() ->
            http.newWebSocketBuilder()
                .header("Origin", "https://untrusted.invalid")
                .buildAsync(
                    URI.create("ws://localhost:" + port + "/ws/browser?ticket=" + ticket),
                    new WebSocket.Listener() {})
                .join())
        .isInstanceOf(CompletionException.class);
  }
}
