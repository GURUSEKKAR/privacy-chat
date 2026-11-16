package com.secretchat.auth;

import com.secretchat.common.ApiException;
import com.secretchat.common.Hashing;
import com.secretchat.common.RateLimiter;
import com.secretchat.config.AppProperties;
import com.secretchat.devices.Device;
import com.secretchat.devices.DeviceRepository;
import com.secretchat.devices.MlsPublicVerifier;
import com.secretchat.protocol.AuthDtos.LoginRequest;
import com.secretchat.protocol.AuthDtos.NewDevice;
import com.secretchat.protocol.AuthDtos.RegisterRequest;
import com.secretchat.protocol.AuthDtos.TokenResponse;
import com.secretchat.protocol.Fingerprints;
import com.secretchat.protocol.Usernames;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final int MAX_PENDING_DEVICES = 5;

  private final UserRepository users;
  private final DeviceRepository devices;
  private final AuthSessionRepository sessions;
  private final ChallengeService challenges;
  private final MlsPublicVerifier verifier;
  private final PasswordEncoder passwordEncoder;
  private final RateLimiter loginFailures;
  private final RateLimiter registrations;
  private final AppProperties props;
  private final Clock clock;
  private final ApplicationEventPublisher events;
  private final String dummyHash;
  private final MfaService mfa;

  public AuthService(
      UserRepository users,
      DeviceRepository devices,
      AuthSessionRepository sessions,
      ChallengeService challenges,
      MlsPublicVerifier verifier,
      PasswordEncoder passwordEncoder,
      @Qualifier("loginFailureLimiter") RateLimiter loginFailures,
      @Qualifier("registrationLimiter") RateLimiter registrations,
      AppProperties props,
      Clock clock,
      ApplicationEventPublisher events,
      MfaService mfa) {
    this.mfa = mfa;
    this.users = users;
    this.devices = devices;
    this.sessions = sessions;
    this.challenges = challenges;
    this.verifier = verifier;
    this.passwordEncoder = passwordEncoder;
    this.loginFailures = loginFailures;
    this.registrations = registrations;
    this.props = props;
    this.clock = clock;
    this.events = events;
    // Used to equalize timing when the username does not exist.
    this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
  }

  @Transactional
  public TokenResponse register(RegisterRequest req, String remoteAddr) {
    if (!registrations.tryAcquire("reg|" + remoteAddr)) {
      throw ApiException.rateLimited();
    }
    String username = Usernames.normalize(req.username());
    if (!Usernames.isValidNormalized(username)) {
      throw ApiException.badRequest(
          "INVALID_USERNAME", "Username must be 3-32 chars of a-z, 0-9, '_', '.', '-'");
    }
    byte[] publicKey = Hashing.decodeBase64(req.device().publicKey(), "device.publicKey");
    verifier.requireValidPublicKey(publicKey);
    requireDeviceSignature(req.challengeId(), username, publicKey, req.signature());

    if (users.findByUsername(username).isPresent()) {
      throw ApiException.conflict("USERNAME_TAKEN", "Username is not available");
    }
    Instant now = clock.instant();
    User user = new User(UUID.randomUUID(), username, passwordEncoder.encode(req.password()), now);
    Device device;
    try {
      users.saveAndFlush(user);
      device =
          new Device(
              UUID.randomUUID(),
              user.getId(),
              req.device().name(),
              publicKey,
              Fingerprints.of(publicKey),
              now);
      // The first device of a freshly registered account is trusted by definition.
      device.approve(null, now);
      devices.saveAndFlush(device);
    } catch (DataIntegrityViolationException e) {
      throw ApiException.conflict("USERNAME_TAKEN", "Username is not available");
    }
    return issueSession(user, device, now);
  }

  @Transactional(noRollbackFor = ApiException.class)
  public TokenResponse login(LoginRequest req, String remoteAddr) {
    String username = Usernames.normalize(req.username());
    String limiterKey = "login|" + username + "|" + remoteAddr;
    String userKey = "login-user|" + username;
    if (loginFailures.isBlocked(limiterKey) || loginFailures.isBlocked(userKey)) {
      throw ApiException.rateLimited();
    }
    Optional<String> nonce = challenges.consume(req.challengeId());
    Optional<User> maybeUser = users.findByUsername(username);
    boolean passwordOk =
        maybeUser
            .map(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
            .orElseGet(() -> {
              passwordEncoder.matches(req.password(), dummyHash);
              return false;
            });
    if (nonce.isEmpty() || !passwordOk || !maybeUser.get().isActive()) {
      fail(limiterKey, userKey);
    }
    User user = maybeUser.get();
    Instant now = clock.instant();
    byte[] signature = Hashing.decodeBase64(req.signature(), "signature");

    Device device;
    if (req.deviceId() != null) {
      device = devices.findById(req.deviceId()).orElse(null);
      if (device == null
          || !device.getUserId().equals(user.getId())
          || device.getStatus() == Device.Status.REVOKED
          || !verifier.verifyLoginSignature(
              device.getIdentityPublicKey(), req.challengeId(), nonce.get(), username, signature)) {
        fail(limiterKey, userKey);
      }
    } else if (req.newDevice() != null) {
      device =
          enrollPendingDevice(
              user, req.newDevice(), req.challengeId(), nonce.get(), signature, now);
      if (device == null) {
        fail(limiterKey, userKey);
      }
    } else {
      throw ApiException.badRequest("DEVICE_REQUIRED", "deviceId or newDevice is required");
    }
    loginFailures.reset(limiterKey);
    if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
      user.setPasswordHash(passwordEncoder.encode(req.password()));
    }
    return issueSession(user, device, now);
  }

  private Device enrollPendingDevice(
      User user, NewDevice nd, UUID challengeId, String nonce, byte[] signature, Instant now) {
    byte[] publicKey = Hashing.decodeBase64(nd.publicKey(), "newDevice.publicKey");
    verifier.requireValidPublicKey(publicKey);
    if (!verifier.verifyLoginSignature(
        publicKey, challengeId, nonce, user.getUsername(), signature)) {
      return null;
    }
    if (devices.countByUserIdAndStatus(user.getId(), Device.Status.PENDING)
        >= MAX_PENDING_DEVICES) {
      throw ApiException.conflict("TOO_MANY_PENDING_DEVICES", "Too many devices awaiting approval");
    }
    Device device =
        new Device(
            UUID.randomUUID(), user.getId(), nd.name(), publicKey, Fingerprints.of(publicKey), now);
    try {
      devices.saveAndFlush(device);
    } catch (DataIntegrityViolationException e) {
      throw ApiException.conflict("DEVICE_KEY_IN_USE", "Device key already enrolled");
    }
    log.info("Pending device enrolled for user {}", user.getId());
    return device;
  }

  private void fail(String limiterKey, String userKey) {
    loginFailures.tryAcquire(limiterKey);
    loginFailures.tryAcquire(userKey);
    throw ApiException.invalidCredentials();
  }

  private void requireDeviceSignature(
      UUID challengeId, String username, byte[] publicKey, String sig) {
    String nonce = challenges.consume(challengeId).orElseThrow(ApiException::invalidCredentials);
    byte[] signature = Hashing.decodeBase64(sig, "signature");
    if (!verifier.verifyLoginSignature(publicKey, challengeId, nonce, username, signature)) {
      throw ApiException.invalidCredentials();
    }
  }

  private TokenResponse issueSession(User user, Device device, Instant now) {
    String access = Hashing.newToken();
    String refresh = Hashing.newToken();
    Instant accessExp = now.plus(props.accessTokenTtl());
    AuthSession s =
        new AuthSession(
            UUID.randomUUID(),
            user.getId(),
            device.getId(),
            Hashing.sha256Hex(access),
            Hashing.sha256Hex(refresh),
            now,
            accessExp,
            now.plus(props.refreshTokenTtl()));
    sessions.save(s);
    return new TokenResponse(
        access,
        refresh,
        accessExp,
        user.getId(),
        user.getUsername(),
        device.getId(),
        mfa.status(user, device, s),
        device.getFingerprint());
  }

  /**
   * Rotates both tokens in place. Reuse of an already-rotated refresh token means it was stolen, so
   * every session of that device is revoked.
   */
  @Transactional(noRollbackFor = ApiException.class)
  public TokenResponse refresh(String refreshToken) {
    Instant now = clock.instant();
    String hash = Hashing.sha256Hex(refreshToken);
    Optional<AuthSession> current = sessions.findByRefreshHash(hash);
    if (current.isEmpty()) {
      sessions
          .findByPreviousRefreshHash(hash)
          .ifPresent(reused -> {
            log.warn("Refresh token reuse detected; revoking device {}", reused.getDeviceId());
            revokeDeviceSessions(reused.getDeviceId());
          });
      throw ApiException.unauthorized();
    }
    AuthSession s = current.get();
    if (s.getRevokedAt() != null || !now.isBefore(s.getRefreshExpiresAt())) {
      throw ApiException.unauthorized();
    }
    User user =
        users
            .findById(s.getUserId())
            .filter(User::isActive)
            .orElseThrow(ApiException::unauthorized);
    Device device =
        devices
            .findById(s.getDeviceId())
            .filter(d -> d.getStatus() != Device.Status.REVOKED)
            .orElseThrow(ApiException::unauthorized);
    if (mfa.gated(user)
        && !"APPROVED".equals(mfa.status(user, device, s))
        && !now.isBefore(s.getCreatedAt().plusSeconds(600))) {
      s.revoke(now);
      throw ApiException.unauthorized();
    }
    String access = Hashing.newToken();
    String refresh = Hashing.newToken();
    Instant accessExp = now.plus(props.accessTokenTtl());
    s.rotate(
        Hashing.sha256Hex(access),
        Hashing.sha256Hex(refresh),
        accessExp,
        now.plus(props.refreshTokenTtl()));
    return new TokenResponse(
        access,
        refresh,
        accessExp,
        user.getId(),
        user.getUsername(),
        device.getId(),
        mfa.status(user, device, s),
        device.getFingerprint());
  }

  @Transactional
  public void logout(UUID authSessionId) {
    sessions.findById(authSessionId).ifPresent(s -> s.revoke(clock.instant()));
    events.publishEvent(new AuthEvents.AuthSessionEnded(authSessionId));
  }

  @Transactional
  public void revokeDeviceSessions(UUID deviceId) {
    Instant now = clock.instant();
    sessions.findByDeviceIdAndRevokedAtIsNull(deviceId).forEach(s -> s.revoke(now));
    events.publishEvent(new AuthEvents.DeviceSessionsRevoked(deviceId));
  }

  /** Resolves a bearer token to a principal, rechecking user, session and device status. */
  @Transactional(readOnly = true)
  public Optional<AuthPrincipal> authenticate(String bearerToken) {
    Instant now = clock.instant();
    return sessions
        .findByTokenHash(Hashing.sha256Hex(bearerToken))
        .filter(s -> s.isUsable(now))
        .flatMap(this::principalFor);
  }

  /** Re-validates an existing session id (used on every WebSocket frame). */
  @Transactional(readOnly = true)
  public Optional<AuthPrincipal> revalidate(UUID authSessionId) {
    Instant now = clock.instant();
    return sessions
        .findById(authSessionId)
        .filter(s -> s.isUsable(now))
        .flatMap(this::principalFor);
  }

  private Optional<AuthPrincipal> principalFor(AuthSession s) {
    Optional<User> user = users.findById(s.getUserId()).filter(User::isActive);
    Optional<Device> device =
        devices.findById(s.getDeviceId()).filter(d -> d.getStatus() != Device.Status.REVOKED);
    if (user.isEmpty() || device.isEmpty()) {
      return Optional.empty();
    }
    if (mfa.gated(user.get())
        && !"APPROVED".equals(mfa.status(user.get(), device.get(), s))
        && !clock.instant().isBefore(s.getCreatedAt().plusSeconds(600))) return Optional.empty();
    return Optional.of(
        new AuthPrincipal(
            user.get().getId(),
            user.get().getUsername(),
            device.get().getId(),
            device.get().isApproved() && "APPROVED".equals(mfa.status(user.get(), device.get(), s)),
            s.getId()));
  }
}
