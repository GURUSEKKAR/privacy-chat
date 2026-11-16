package com.secretchat.expiry;

import static org.assertj.core.api.Assertions.*;

import com.secretchat.testsupport.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "secretchat.metrics-token=operations-test-token-at-least-32-characters")
class OperationsTest extends IntegrationTestBase {
  @LocalServerPort int port;
  @Autowired BacklogMetrics backlog;
  @Autowired ExpiryWorker expiry;
  @Autowired ResetService resets;
  @Autowired MeterRegistry registry;

  private HttpResponse<String> get(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return HttpClient.newHttpClient()
        .send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void metricsRequireSeparateOperationsCredential() throws Exception {
    assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    assertThat(get("/actuator/metrics", null).statusCode()).isEqualTo(401);
    var user = register("metric");
    assertThat(get("/actuator/metrics", user.tokens().accessToken()).statusCode()).isEqualTo(401);
    assertThat(
            get("/actuator/metrics", "operations-test-token-at-least-32-characters").statusCode())
        .isEqualTo(200);
  }

  @Test
  void backlogsReflectExpiryAndLockedOwnerRekey() {
    var a = register("backloga");
    var b = register("backlogb");
    var r = createRoom(a, 60, 120, null);
    addMember(a, r.id(), b, null);
    send(a, r.id(), openSession(a, r.id()), fakeCiphertext("metrics"));
    clock.advance(Duration.ofSeconds(121));
    backlog.refresh();
    assertThat(registry.get("secretchat.ttl.backlog").gauge().value()).isPositive();
    assertThat(registry.get("secretchat.reset.backlog").gauge().value()).isPositive();
    expiry.runOnce();
    resets.resetIfDue(r.id());
    backlog.refresh();
    assertThat(registry.get("secretchat.rekey.locked").gauge().value()).isPositive();
    assertThat(registry.get("secretchat.backlog.last.success.seconds").gauge().value())
        .isPositive();
    assertThat(registry.get("secretchat.ttl.backlog").gauge().getId().getTags()).isEmpty();
  }
}
