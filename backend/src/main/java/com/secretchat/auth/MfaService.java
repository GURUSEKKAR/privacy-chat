package com.secretchat.auth;

import com.secretchat.common.ApiException;
import com.secretchat.common.Hashing;
import com.secretchat.common.RateLimiter;
import com.secretchat.devices.Device;
import com.secretchat.devices.DeviceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {
  private final UserRepository users;
  private final AuthSessionRepository sessions;
  private final DeviceRepository devices;
  private final Totp totp;
  private final Clock clock;
  private final ApplicationEventPublisher events;
  private final boolean required;
  private final RateLimiter attempts;

  public MfaService(
      UserRepository users,
      AuthSessionRepository sessions,
      DeviceRepository devices,
      Totp totp,
      Clock clock,
      ApplicationEventPublisher events,
      @Value("${secretchat.mfa.required:true}") boolean required) {
    this.users = users;
    this.sessions = sessions;
    this.devices = devices;
    this.totp = totp;
    this.clock = clock;
    this.events = events;
    this.required = required;
    attempts = new RateLimiter(5, Duration.ofMinutes(5), clock);
  }

  public boolean gated(User user) {
    return required || user.isTotpEnabled();
  }

  public String status(User user, Device device, AuthSession session) {
    if (!gated(user)) return device.getStatus().name();
    if (!user.isTotpEnabled()) return device.isApproved() ? "MFA_SETUP" : "MFA_SETUP_APPROVAL";
    if (session.getMfaVerifiedAt() == null) return "MFA_REQUIRED";
    return session.getId().equals(user.getActiveSessionId()) ? "APPROVED" : "SESSION_CONFLICT";
  }

  public record Setup(String secret, String uri) {}

  public record ActiveSession(UUID id, String deviceName, Instant signedInAt, Instant expiresAt) {}

  public record State(
      String status, List<ActiveSession> previousSessions, List<String> recoveryCodes) {}

  private User locked(AuthPrincipal p) {
    return users.lockById(p.userId()).orElseThrow(ApiException::unauthorized);
  }

  private AuthSession own(AuthPrincipal p) {
    return sessions
        .findById(p.authSessionId())
        .filter(s -> s.getUserId().equals(p.userId()) && s.isUsable(clock.instant()))
        .orElseThrow(ApiException::unauthorized);
  }

  private void recent(AuthSession s) {
    if (!clock.instant().isBefore(s.getCreatedAt().plusSeconds(600)))
      throw ApiException.forbidden(
          "LOGIN_EXPIRED", "Sign out and sign in again to verify your authenticator.");
  }

  @Transactional
  public Setup setup(AuthPrincipal p) {
    User u = locked(p);
    AuthSession s = own(p);
    recent(s);
    if (u.isTotpEnabled())
      throw ApiException.conflict("MFA_ENABLED", "Authenticator already configured.");
    Device d = devices.findById(p.deviceId()).orElseThrow(ApiException::unauthorized);
    if (!d.isApproved())
      throw ApiException.forbidden(
          "TRUSTED_DEVICE_REQUIRED",
          "Set up the authenticator from your original approved device first.");
    // Reuse the pending secret so retrying a request never silently changes the scanned QR code.
    if (u.getTotpSecret() == null) u.setTotpSecret(totp.encrypt(totp.newSecret(), u.getId()));
    String secret = totp.decrypt(u.getTotpSecret(), u.getId());
    String label =
        java.net.URLEncoder.encode(
                "Null:" + u.getUsername(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
    return new Setup(
        secret,
        "otpauth://totp/"
            + label
            + "?secret="
            + secret
            + "&issuer=Null&algorithm=SHA1&digits=6&period=30");
  }

  @Transactional(noRollbackFor = ApiException.class)
  public State verify(AuthPrincipal p, String code) {
    User u = locked(p);
    AuthSession s = own(p);
    recent(s);
    if (!attempts.tryAcquire(u.getId().toString())) throw ApiException.rateLimited();
    if (u.getTotpSecret() == null)
      throw ApiException.badRequest("SETUP_REQUIRED", "Scan your authenticator QR code first.");
    boolean setup = !u.isTotpEnabled();
    Device d = devices.findById(p.deviceId()).orElseThrow(ApiException::unauthorized);
    if (setup && !d.isApproved())
      throw ApiException.forbidden(
          "TRUSTED_DEVICE_REQUIRED",
          "Use your original approved device to set up the authenticator.");
    long step =
        totp.match(
            totp.decrypt(u.getTotpSecret(), u.getId()),
            code,
            clock.instant().getEpochSecond(),
            u.getTotpLastStep());
    if (step < 0 && !setup) {
      String hash = Hashing.sha256Hex(code.trim().toUpperCase(java.util.Locale.ROOT));
      var hashes = new ArrayList<>(Arrays.asList(u.getRecoveryHashes().split(",")));
      if (!hashes.remove(hash))
        throw ApiException.badRequest(
            "INVALID_CODE", "Code is invalid or already used. Wait for a new code and retry.");
      u.setRecoveryHashes(String.join(",", hashes));
    } else if (step < 0)
      throw ApiException.badRequest(
          "INVALID_CODE", "Code is invalid or already used. Check your phone time and retry.");
    else u.setTotpLastStep(step);
    List<String> recovery = new ArrayList<>();
    if (setup) {
      u.setTotpEnabled(true);
      for (int i = 0; i < 10; i++)
        recovery.add(
            UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16)
                .toUpperCase(java.util.Locale.ROOT));
      u.setRecoveryHashes(String.join(",", recovery.stream().map(Hashing::sha256Hex).toList()));
    }
    s.verifyMfa(clock.instant());
    var previous = previous(p);
    if (previous.isEmpty()) activateLocked(u, s);
    return new State(previous.isEmpty() ? "APPROVED" : "SESSION_CONFLICT", previous, recovery);
  }

  private List<ActiveSession> previous(AuthPrincipal p) {
    return sessions.findByUserIdAndRevokedAtIsNull(p.userId()).stream()
        .filter(s ->
            !s.getId().equals(p.authSessionId())
                && clock.instant().isBefore(s.getRefreshExpiresAt()))
        .filter(s ->
            users
                .findById(p.userId())
                .map(u -> s.getId().equals(u.getActiveSessionId()))
                .orElse(false))
        .map(s ->
            new ActiveSession(
                s.getId(),
                devices.findById(s.getDeviceId()).map(Device::getName).orElse("Browser"),
                s.getCreatedAt(),
                s.getRefreshExpiresAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public State state(AuthPrincipal p) {
    User u = users.findById(p.userId()).orElseThrow(ApiException::unauthorized);
    AuthSession s = own(p);
    var d = devices.findById(p.deviceId()).orElseThrow(ApiException::unauthorized);
    return new State(
        status(u, d, s), s.getMfaVerifiedAt() != null ? previous(p) : List.of(), List.of());
  }

  @Transactional
  public State replace(AuthPrincipal p) {
    User u = locked(p);
    AuthSession s = own(p);
    if (!u.isTotpEnabled()
        || s.getMfaVerifiedAt() == null
        || !clock.instant().isBefore(s.getMfaVerifiedAt().plusSeconds(300)))
      throw ApiException.forbidden(
          "MFA_REQUIRED",
          "Verify a fresh authenticator code before replacing the previous session.");
    activateLocked(u, s);
    return new State("APPROVED", List.of(), List.of());
  }

  private void activateLocked(User u, AuthSession current) {
    for (AuthSession other : sessions.findByUserIdAndRevokedAtIsNull(u.getId())) {
      if (!other.getId().equals(current.getId())) {
        other.revoke(clock.instant());
        events.publishEvent(new AuthEvents.AuthSessionEnded(other.getId()));
      }
    }
    Device device = devices.findById(current.getDeviceId()).orElseThrow(ApiException::unauthorized);
    if (!device.isApproved()) device.approve(null, clock.instant());
    u.setActiveSessionId(current.getId());
  }
}
