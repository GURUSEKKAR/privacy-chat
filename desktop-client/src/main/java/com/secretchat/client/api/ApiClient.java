package com.secretchat.client.api;

import com.secretchat.protocol.AuthDtos.ChallengeResponse;
import com.secretchat.protocol.AuthDtos.LoginRequest;
import com.secretchat.protocol.AuthDtos.RefreshRequest;
import com.secretchat.protocol.AuthDtos.RegisterRequest;
import com.secretchat.protocol.AuthDtos.TokenResponse;
import com.secretchat.protocol.DeviceDtos.ApproveDeviceRequest;
import com.secretchat.protocol.DeviceDtos.DeviceView;
import com.secretchat.protocol.DeviceDtos.KeyPackageUpload;
import com.secretchat.protocol.DeviceDtos.KeyPackageUploadResult;
import com.secretchat.protocol.MessageDtos.ErrorResponse;
import com.secretchat.protocol.MessageDtos.MessageView;
import com.secretchat.protocol.MessageDtos.ViewAckRequest;
import com.secretchat.protocol.MessageDtos.ViewAckResult;
import com.secretchat.protocol.RoomDtos.AcceptInviteRequest;
import com.secretchat.protocol.RoomDtos.ClaimedKeyPackage;
import com.secretchat.protocol.RoomDtos.CreateRoomRequest;
import com.secretchat.protocol.RoomDtos.InvitationView;
import com.secretchat.protocol.RoomDtos.InviteRequest;
import com.secretchat.protocol.RoomDtos.MemberView;
import com.secretchat.protocol.RoomDtos.MlsCommitRequest;
import com.secretchat.protocol.RoomDtos.MlsCommitResult;
import com.secretchat.protocol.RoomDtos.MlsEventView;
import com.secretchat.protocol.RoomDtos.OpenSessionRequest;
import com.secretchat.protocol.RoomDtos.ResetResult;
import com.secretchat.protocol.RoomDtos.RoomSessionView;
import com.secretchat.protocol.RoomDtos.RoomView;
import com.secretchat.protocol.RoomDtos.WelcomeView;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Typed REST client for the backend. Holds the current tokens in memory only. */
public class ApiClient {
  public static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final URI base;
  private final HttpClient http;
  private volatile TokenResponse tokens;

  public ApiClient(URI base) {
    this.base = base;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public URI baseUri() {
    return base;
  }

  public TokenResponse tokens() {
    return tokens;
  }

  public void setTokens(TokenResponse tokens) {
    this.tokens = tokens;
  }

  public String accessToken() {
    refreshIfNeeded();
    return tokens == null ? null : tokens.accessToken();
  }

  // ---- auth ----

  public ChallengeResponse challenge() {
    return send("POST", "/api/v1/auth/challenge", null, ChallengeResponse.class, false);
  }

  public TokenResponse register(RegisterRequest req) {
    TokenResponse t = send("POST", "/api/v1/auth/register", req, TokenResponse.class, false);
    tokens = t;
    return t;
  }

  public TokenResponse login(LoginRequest req) {
    TokenResponse t = send("POST", "/api/v1/auth/login", req, TokenResponse.class, false);
    tokens = t;
    return t;
  }

  public synchronized TokenResponse refresh() {
    TokenResponse t =
        send(
            "POST",
            "/api/v1/auth/refresh",
            new RefreshRequest(tokens.refreshToken()),
            TokenResponse.class,
            false);
    tokens = t;
    return t;
  }

  public void logout() {
    if (tokens != null) {
      try {
        send("POST", "/api/v1/auth/logout", null, Void.class, true);
      } finally {
        tokens = null;
      }
    }
  }

  // ---- devices ----

  public List<DeviceView> devices() {
    return sendList("GET", "/api/v1/devices", null, new TypeReference<>() {});
  }

  public DeviceView approveDevice(UUID deviceId, String fingerprint) {
    return send(
        "POST",
        "/api/v1/devices/" + deviceId + "/approve",
        new ApproveDeviceRequest(fingerprint),
        DeviceView.class,
        true);
  }

  public DeviceView revokeDevice(UUID deviceId) {
    return send("POST", "/api/v1/devices/" + deviceId + "/revoke", null, DeviceView.class, true);
  }

  public KeyPackageUploadResult uploadKeyPackages(List<String> keyPackages) {
    return send(
        "PUT",
        "/api/v1/devices/me/key-packages",
        new KeyPackageUpload(keyPackages),
        KeyPackageUploadResult.class,
        true);
  }

  // ---- rooms ----

  public RoomView createRoom(CreateRoomRequest req) {
    return send("POST", "/api/v1/rooms", req, RoomView.class, true);
  }

  public List<RoomView> rooms() {
    return sendList("GET", "/api/v1/rooms", null, new TypeReference<>() {});
  }

  public RoomView room(UUID roomId) {
    return send("GET", "/api/v1/rooms/" + roomId, null, RoomView.class, true);
  }

  public List<MemberView> members(UUID roomId) {
    return sendList("GET", "/api/v1/rooms/" + roomId + "/members", null, new TypeReference<>() {});
  }

  public InvitationView invite(UUID roomId, String username) {
    return send(
        "POST",
        "/api/v1/rooms/" + roomId + "/invites",
        new InviteRequest(username),
        InvitationView.class,
        true);
  }

  public List<InvitationView> roomInvites(UUID roomId) {
    return sendList("GET", "/api/v1/rooms/" + roomId + "/invites", null, new TypeReference<>() {});
  }

  public ClaimedKeyPackage claimKeyPackage(UUID roomId, UUID invitationId) {
    return send(
        "POST",
        "/api/v1/rooms/" + roomId + "/invites/" + invitationId + "/key-package",
        null,
        ClaimedKeyPackage.class,
        true);
  }

  public List<InvitationView> myInvites() {
    return sendList("GET", "/api/v1/invites", null, new TypeReference<>() {});
  }

  public InvitationView acceptInvite(UUID invitationId, String roomPassword) {
    return send(
        "POST",
        "/api/v1/invites/" + invitationId + "/accept",
        new AcceptInviteRequest(roomPassword),
        InvitationView.class,
        true);
  }

  public InvitationView declineInvite(UUID invitationId) {
    return send(
        "POST", "/api/v1/invites/" + invitationId + "/decline", null, InvitationView.class, true);
  }

  public RoomSessionView openSession(UUID roomId, String roomPassword) {
    return send(
        "POST",
        "/api/v1/rooms/" + roomId + "/sessions",
        new OpenSessionRequest(roomPassword),
        RoomSessionView.class,
        true);
  }

  public void endSession(UUID roomId, UUID sessionId) {
    send("DELETE", "/api/v1/rooms/" + roomId + "/sessions/" + sessionId, null, Void.class, true);
  }

  public List<MessageView> pendingMessages(UUID roomId, UUID sessionId) {
    return sendList(
        "GET",
        "/api/v1/rooms/" + roomId + "/messages?pending=true&sessionId=" + sessionId,
        null,
        new TypeReference<>() {});
  }

  public ViewAckResult acknowledgeView(UUID roomId, UUID messageId, UUID sessionId) {
    return send(
        "POST",
        "/api/v1/rooms/" + roomId + "/messages/" + messageId + "/view",
        new ViewAckRequest(sessionId),
        ViewAckResult.class,
        true);
  }

  public MlsCommitResult commit(UUID roomId, MlsCommitRequest req) {
    return send(
        "POST", "/api/v1/rooms/" + roomId + "/mls/commit", req, MlsCommitResult.class, true);
  }

  public List<MlsEventView> mlsEvents(UUID roomId, long afterEpoch) {
    return sendList(
        "GET",
        "/api/v1/rooms/" + roomId + "/mls/events?afterEpoch=" + afterEpoch,
        null,
        new TypeReference<>() {});
  }

  public WelcomeView welcome(UUID roomId) {
    return send("GET", "/api/v1/rooms/" + roomId + "/mls/welcome", null, WelcomeView.class, true);
  }

  public ResetResult resetRoom(UUID roomId) {
    return send("POST", "/api/v1/rooms/" + roomId + "/reset", null, ResetResult.class, true);
  }

  // ---- plumbing ----

  private void refreshIfNeeded() {
    TokenResponse t = tokens;
    if (t != null
        && t.accessExpiresAt() != null
        && Instant.now().isAfter(t.accessExpiresAt().minusSeconds(60))) {
      refresh();
    }
  }

  private <T> T send(String method, String path, Object body, Class<T> type, boolean auth) {
    String json = execute(method, path, body, auth);
    if (type == Void.class || json == null || json.isEmpty()) {
      return null;
    }
    return JSON.readValue(json, type);
  }

  private <T> List<T> sendList(
      String method, String path, Object body, TypeReference<List<T>> type) {
    String json = execute(method, path, body, true);
    return JSON.readValue(json, type);
  }

  private String execute(String method, String path, Object body, boolean auth) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json");
    if (auth) {
      String token = accessToken();
      if (token == null) {
        throw new ApiException(401, "UNAUTHENTICATED", "Not logged in");
      }
      b.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      b.header("Content-Type", "application/json");
      b.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    } else if (method.equals("POST") || method.equals("PUT")) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> res;
    try {
      res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new ApiException(
          0, "NETWORK", e.getMessage() == null ? "network error" : e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(0, "INTERRUPTED", "interrupted");
    }
    if (res.statusCode() / 100 != 2) {
      String code = "HTTP_" + res.statusCode();
      String msg = "";
      try {
        ErrorResponse er = JSON.readValue(res.body(), ErrorResponse.class);
        if (er != null && er.code() != null) {
          code = er.code();
          msg = er.message();
        }
      } catch (RuntimeException ignored) {
        // Non-JSON error body.
      }
      throw new ApiException(res.statusCode(), code, msg);
    }
    return res.body();
  }
}
