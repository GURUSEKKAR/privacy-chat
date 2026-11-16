package com.secretchat.client;

import com.secretchat.client.api.ApiClient;
import com.secretchat.client.api.ApiException;
import com.secretchat.client.mls.MlsEngine;
import com.secretchat.client.mls.MlsIdentity;
import com.secretchat.client.mls.RoomGroup;
import com.secretchat.client.state.ChatState;
import com.secretchat.client.state.ChatState.DisplayedMessage;
import com.secretchat.client.storage.FingerprintPins;
import com.secretchat.client.storage.LocalVault;
import com.secretchat.client.websocket.StompClient;
import com.secretchat.protocol.AuthDtos;
import com.secretchat.protocol.AuthDtos.ChallengeResponse;
import com.secretchat.protocol.AuthDtos.TokenResponse;
import com.secretchat.protocol.Fingerprints;
import com.secretchat.protocol.MessageDtos.ChatEvent;
import com.secretchat.protocol.MessageDtos.EventType;
import com.secretchat.protocol.MessageDtos.MessageView;
import com.secretchat.protocol.MessageDtos.SendMessageFrame;
import com.secretchat.protocol.RoomDtos.ClaimedKeyPackage;
import com.secretchat.protocol.RoomDtos.CommitPurpose;
import com.secretchat.protocol.RoomDtos.CreateRoomRequest;
import com.secretchat.protocol.RoomDtos.InvitationView;
import com.secretchat.protocol.RoomDtos.MemberView;
import com.secretchat.protocol.RoomDtos.MlsCommitRequest;
import com.secretchat.protocol.RoomDtos.MlsEventView;
import com.secretchat.protocol.RoomDtos.RoomSessionView;
import com.secretchat.protocol.RoomDtos.RoomView;
import com.secretchat.protocol.Usernames;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bouncycastle.mls.codec.KeyPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless client core used by the JavaFX UI and the end-to-end tests. All encryption and
 * decryption happens here; the server only ever sees MLS ciphertext and public key material.
 */
public class SecretChatClient implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(SecretChatClient.class);
  private static final int KEY_PACKAGES = 5;

  /** UI callbacks. Invoked on a background thread. */
  public interface Listener {
    /** Return only after rendering; an exception withholds the view receipt. */
    default void onMessage(DisplayedMessage message) {}

    default void onMessageStatus(UUID roomId, UUID messageId, ChatState.Status status) {}

    default void onRoomChanged(UUID roomId, EventType reason) {}

    default void onWarning(String warning) {}
  }

  private final ApiClient api;
  private final LocalVault vault;
  private final FingerprintPins pins;
  private final ChatState chat = new ChatState();
  private final Map<UUID, RoomSessionView> sessions = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<CompletableFuture<ChatEvent>> pendingSends =
      new ConcurrentLinkedQueue<>();
  private final ExecutorService events =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "secretchat-events");
        t.setDaemon(true);
        return t;
      });
  private final Object lock = new Object();
  private volatile Listener listener = new Listener() {};
  private volatile MlsEngine mls;
  private volatile StompClient stomp;
  private volatile String username;

  public SecretChatClient(URI baseUri, LocalVault vault, FingerprintPins pins) {
    this.api = new ApiClient(ClientConfiguration.validateServer(baseUri));
    this.vault = vault;
    this.pins = pins;
  }

  public void setListener(Listener listener) {
    this.listener = listener == null ? new Listener() {} : listener;
  }

  public ApiClient api() {
    return api;
  }

  public ChatState chat() {
    return chat;
  }

  public TokenResponse tokens() {
    return api.tokens();
  }

  public UUID userId() {
    return api.tokens() == null ? null : api.tokens().userId();
  }

  public String username() {
    return username;
  }

  public String deviceFingerprint() {
    return mls == null ? null : mls.identity().fingerprint();
  }

  public boolean isDeviceApproved() {
    return api.tokens() != null && "APPROVED".equals(api.tokens().deviceStatus());
  }

  // ------------------------------------------------------------------ account / device

  /** Creates the account and its first (auto-approved) device, then goes online. */
  public TokenResponse register(String rawUsername, char[] password, String deviceName) {
    synchronized (lock) {
      String name = Usernames.normalize(rawUsername);
      MlsIdentity identity = MlsIdentity.generate();
      ChallengeResponse ch = api.challenge();
      byte[] sig =
          identity.sign(
              AuthDtos.LOGIN_SIGNATURE_LABEL,
              AuthDtos.loginSignatureContent(ch.challengeId(), ch.nonce(), name));
      TokenResponse t =
          api.register(
              new AuthDtos.RegisterRequest(
                  name,
                  new String(password),
                  new AuthDtos.NewDevice(deviceName, b64(identity.publicKey())),
                  ch.challengeId(),
                  b64(sig)));
      vault.save(name, t.userId(), t.deviceId(), identity, password);
      afterLogin(name, identity, t);
      return t;
    }
  }

  /**
   * Logs in with this machine's vaulted device key, or enrolls this machine as a new PENDING device
   * when no vault exists. A PENDING device can't access rooms until approved from another device.
   */
  public TokenResponse login(String rawUsername, char[] password, String deviceNameIfNew) {
    synchronized (lock) {
      String name = Usernames.normalize(rawUsername);
      Optional<LocalVault.Loaded> loaded = vault.load(name, password);
      MlsIdentity identity =
          loaded.map(LocalVault.Loaded::identity).orElseGet(MlsIdentity::generate);
      ChallengeResponse ch = api.challenge();
      byte[] sig =
          identity.sign(
              AuthDtos.LOGIN_SIGNATURE_LABEL,
              AuthDtos.loginSignatureContent(ch.challengeId(), ch.nonce(), name));
      AuthDtos.LoginRequest req =
          loaded.isPresent()
              ? new AuthDtos.LoginRequest(
                  name,
                  new String(password),
                  loaded.get().deviceId(),
                  null,
                  ch.challengeId(),
                  b64(sig))
              : new AuthDtos.LoginRequest(
                  name,
                  new String(password),
                  null,
                  new AuthDtos.NewDevice(
                      deviceNameIfNew == null ? "desktop" : deviceNameIfNew,
                      b64(identity.publicKey())),
                  ch.challengeId(),
                  b64(sig));
      TokenResponse t = api.login(req);
      if (loaded.isEmpty()) {
        vault.save(name, t.userId(), t.deviceId(), identity, password);
      }
      afterLogin(name, identity, t);
      return t;
    }
  }

  private void afterLogin(String name, MlsIdentity identity, TokenResponse t) {
    this.username = name;
    this.mls = new MlsEngine(identity, t.userId());
    if ("APPROVED".equals(t.deviceStatus())) {
      goOnline();
    }
  }

  /** After this device got approved elsewhere, refresh tokens and go online. */
  public void refreshApproval() {
    synchronized (lock) {
      TokenResponse t = api.refresh();
      if ("APPROVED".equals(t.deviceStatus()) && stomp == null) {
        goOnline();
      }
    }
  }

  private void goOnline() {
    api.uploadKeyPackages(mls.freshKeyPackages(KEY_PACKAGES));
    connectSocket();
  }

  public void connectSocket() {
    if (stomp != null && stomp.isOpen()) {
      return;
    }
    URI base = api.baseUri();
    String scheme = "https".equals(base.getScheme()) ? "wss" : "ws";
    URI ws = URI.create(scheme + "://" + base.getAuthority() + "/ws");
    stomp =
        StompClient.connect(
            ws,
            api.accessToken(),
            StompClient.handler(this::onFrame, this::onStompError, this::onSocketClosed));
    stomp.subscribe("chat", "/user/queue/chat");
  }

  public boolean isOnline() {
    return stomp != null && stomp.isOpen();
  }

  /**
   * Simulates/handles a dropped socket: every room session ends server-side (view-once boundary).
   */
  public void disconnectSocket() {
    StompClient s = stomp;
    stomp = null;
    if (s != null) {
      s.close();
    }
    sessions.keySet().forEach(chat::clearRoom);
    sessions.clear();
  }

  public void approveDevice(UUID deviceId, String fingerprintShownOnNewDevice) {
    api.approveDevice(deviceId, fingerprintShownOnNewDevice);
  }

  // ------------------------------------------------------------------ rooms

  public RoomView createRoom(CreateRoomRequest req) {
    synchronized (lock) {
      RoomView room = api.createRoom(req);
      mls.createGroup(room.id());
      return room;
    }
  }

  public InvitationView invite(UUID roomId, String inviteeUsername) {
    return api.invite(roomId, inviteeUsername);
  }

  public List<InvitationView> myInvitations() {
    return api.myInvites();
  }

  public InvitationView acceptInvitation(UUID invitationId, String roomPassword) {
    synchronized (lock) {
      // Make sure fresh KeyPackages (whose private keys we hold in memory) are on the server.
      api.uploadKeyPackages(mls.freshKeyPackages(1));
      return api.acceptInvite(invitationId, roomPassword);
    }
  }

  /**
   * Owner: adds every invitee who accepted. Each claimed KeyPackage is checked locally: valid
   * signature, credential = invitee, and the signature-key fingerprint must match both the
   * server-reported fingerprint and any pinned one.
   */
  public int addAcceptedMembers(UUID roomId) {
    synchronized (lock) {
      RoomGroup group = requireGroup(roomId);
      List<InvitationView> accepted =
          api.roomInvites(roomId).stream().filter(i -> "ACCEPTED".equals(i.status())).toList();
      if (accepted.isEmpty()) {
        return 0;
      }
      // Check an existing pin before claiming a single-use package, so explicit verification can
      // be followed by a safe retry without exhausting the invitee's key material.
      for (InvitationView invitation : accepted) {
        if (pins.check(invitation.inviteeUserId(), invitation.acceptedDeviceFingerprint())
            == FingerprintPins.Result.MISMATCH) {
          throw new KeySubstitutionException(
              invitation.inviteeUserId(),
              pins.pinned(invitation.inviteeUserId()).orElse("?"),
              invitation.acceptedDeviceFingerprint());
        }
      }
      List<KeyPackage> kps = new ArrayList<>();
      List<UUID> invitationIds = new ArrayList<>();
      for (InvitationView inv : accepted) {
        ClaimedKeyPackage claimed = api.claimKeyPackage(roomId, inv.id());
        KeyPackage kp =
            MlsEngine.decodeKeyPackage(Base64.getDecoder().decode(claimed.keyPackage()));
        verifyKeyPackage(kp, claimed);
        kps.add(kp);
        invitationIds.add(inv.id());
      }
      RoomView room = api.room(roomId);
      RoomGroup.PreparedCommit prepared = group.prepareAdd(kps);
      api.commit(
          roomId,
          new MlsCommitRequest(
              room.generation(),
              prepared.fromEpoch(),
              CommitPurpose.ADD,
              b64(prepared.commit()),
              b64(prepared.welcome()),
              invitationIds,
              null,
              false));
      group.applyOwnCommit(prepared);
      return kps.size();
    }
  }

  private void verifyKeyPackage(KeyPackage kp, ClaimedKeyPackage claimed) {
    synchronized (MlsIdentity.suite()) {
      try {
        if (!kp.verify()) {
          throw new KeySubstitutionException(
              claimed.userId(), claimed.deviceFingerprint(), "invalid KeyPackage signature");
        }
      } catch (java.io.IOException e) {
        throw new KeySubstitutionException(
            claimed.userId(), claimed.deviceFingerprint(), "unverifiable KeyPackage");
      }
      String identity =
          new String(kp.getLeafNode().getCredential().getIdentity(), StandardCharsets.UTF_8);
      if (!claimed.userId().toString().equals(identity)) {
        throw new KeySubstitutionException(claimed.userId(), claimed.userId().toString(), identity);
      }
      String actual = Fingerprints.of(kp.getLeafNode().getSignatureKey());
      if (!Fingerprints.matches(actual, claimed.deviceFingerprint())) {
        throw new KeySubstitutionException(claimed.userId(), claimed.deviceFingerprint(), actual);
      }
      checkPin(claimed.userId(), actual);
    }
  }

  private void checkPin(UUID userId, String fingerprint) {
    switch (pins.check(userId, fingerprint)) {
      case FIRST_USE -> pins.pin(userId, fingerprint);
      case MATCH -> {}
      case MISMATCH ->
          throw new KeySubstitutionException(userId, pins.pinned(userId).orElse("?"), fingerprint);
    }
  }

  /** Explicit user decision after out-of-band verification of a changed fingerprint. */
  public void trustFingerprint(UUID userId, String fingerprint) {
    pins.pin(userId, fingerprint);
  }

  public List<MemberView> members(UUID roomId) {
    return api.members(roomId);
  }

  /**
   * Opens a viewing session: joins the MLS group if needed (Welcome), catches up on commits,
   * finishes an owner rekey if the room is locked, then fetches pending messages.
   */
  public RoomSessionView enterRoom(UUID roomId, String roomPassword) {
    synchronized (lock) {
      connectSocket();
      RoomView room = api.room(roomId);
      boolean owner = room.ownerUserId().equals(userId());
      if (owner && "LOCKED_PENDING_REKEY".equals(room.state())) {
        completeRekey(roomId);
        room = api.room(roomId);
      }
      if (!owner && mls.group(roomId) == null) {
        joinFromWelcome(room);
      }
      if (owner && mls.group(roomId) == null) {
        throw new IllegalStateException(
            "This device lost the room's MLS state (client restarted). Reset the room to rekey.");
      }
      RoomSessionView s = api.openSession(roomId, roomPassword);
      sessions.put(roomId, s);
      catchUp(roomId);
      return s;
    }
  }

  private void joinFromWelcome(RoomView room) {
    var welcome = api.welcome(room.id());
    RoomGroup g = mls.join(room.id(), Base64.getDecoder().decode(welcome.welcome()));
    // The owner's key inside the group must be the owner device the server announced (and pinned).
    String ownerFp = g.fingerprintOf(room.ownerUserId());
    if (ownerFp == null || !Fingerprints.matches(ownerFp, room.ownerDeviceFingerprint())) {
      mls.forget(room.id());
      throw new KeySubstitutionException(
          room.ownerUserId(), room.ownerDeviceFingerprint(), ownerFp);
    }
    checkPin(room.ownerUserId(), ownerFp);
  }

  /** Applies pending commits and decrypts backlog in epoch order. */
  private void catchUp(UUID roomId) {
    RoomGroup g = requireGroup(roomId);
    RoomSessionView s = sessions.get(roomId);
    List<MessageView> backlog =
        s == null
            ? List.of()
            : new ArrayList<>(
                api.pendingMessages(roomId, s.sessionId()).stream()
                    .sorted(Comparator.comparing(MessageView::createdAt))
                    .toList());
    List<MlsEventView> commits = api.mlsEvents(roomId, g.epoch());
    for (MlsEventView e : commits) {
      if (e.epoch() <= g.epoch()) {
        continue;
      }
      // Decrypt what belongs to the epoch we're leaving before advancing.
      List<MessageView> now = backlog.stream().filter(m -> m.epoch() < e.epoch()).toList();
      now.forEach(m ->
          receive(
              m.roomId(),
              m.messageId(),
              m.senderUserId(),
              m.senderUsername(),
              m.ciphertext(),
              m.createdAt(),
              m.expiresAt()));
      backlog.removeAll(now);
      g.handleCommit(Base64.getDecoder().decode(e.commit()), e.epoch());
    }
    verifyRoster(roomId, g);
    backlog.forEach(m ->
        receive(
            m.roomId(),
            m.messageId(),
            m.senderUserId(),
            m.senderUsername(),
            m.ciphertext(),
            m.createdAt(),
            m.expiresAt()));
  }

  /** Every MLS leaf must belong to an ACTIVE member the server lists, and vice versa. */
  private void verifyRoster(UUID roomId, RoomGroup g) {
    // Membership endpoints describe the latest epoch, not an intermediate catch-up epoch.
    if (api.room(roomId).mlsEpoch() != g.epoch()) return;
    List<MemberView> listed = api.members(roomId);
    if (api.room(roomId).mlsEpoch() != g.epoch()) return;
    com.secretchat.client.mls.GroupRosterVerifier.verify(g, listed);
    listed.forEach(member -> checkPin(member.userId(), member.deviceFingerprint()));
  }

  public void leaveRoom(UUID roomId) {
    synchronized (lock) {
      RoomSessionView s = sessions.remove(roomId);
      chat.clearRoom(roomId);
      if (s != null) {
        try {
          api.endSession(roomId, s.sessionId());
        } catch (ApiException e) {
          log.debug("endSession failed: {}", e.code());
        }
      }
    }
  }

  public Optional<RoomSessionView> session(UUID roomId) {
    return Optional.ofNullable(sessions.get(roomId));
  }

  // ------------------------------------------------------------------ messaging

  /** Encrypts and sends. Retries once after re-syncing if the epoch moved underneath us. */
  public ChatEvent send(UUID roomId, String text) {
    synchronized (lock) {
      for (int attempt = 0; ; attempt++) {
        ChatEvent reply = sendOnce(roomId, text);
        if (reply.type() == EventType.SENT) {
          chat.add(
              new DisplayedMessage(
                  reply.messageId(),
                  roomId,
                  userId(),
                  username,
                  true,
                  text,
                  reply.createdAt(),
                  reply.expiresAt(),
                  ChatState.Status.VIEWED));
          return reply;
        }
        if (attempt == 0 && "WRONG_EPOCH".equals(reply.code())) {
          catchUp(roomId);
          continue;
        }
        throw new ApiException(0, reply.code(), reply.detail());
      }
    }
  }

  private ChatEvent sendOnce(UUID roomId, String text) {
    RoomGroup g = requireGroup(roomId);
    verifyRoster(roomId, g);
    RoomSessionView s = sessions.get(roomId);
    if (s == null) {
      throw new IllegalStateException("Enter the room first");
    }
    byte[] payload = ApiClient.JSON.writeValueAsBytes(Map.of("v", 1, "text", text));
    byte[] aad = (roomId + "|" + s.generation()).getBytes(StandardCharsets.UTF_8);
    byte[] ct = g.encrypt(aad, payload);
    SendMessageFrame frame =
        new SendMessageFrame(
            s.sessionId(), s.generation(), g.epoch(), UUID.randomUUID().toString(), b64(ct));
    CompletableFuture<ChatEvent> reply = new CompletableFuture<>();
    pendingSends.add(reply);
    stomp.send("/app/rooms/" + roomId + "/send", ApiClient.JSON.writeValueAsString(frame));
    try {
      return reply.get(15, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      pendingSends.remove(reply);
      throw new ApiException(0, "TIMEOUT", "No reply from server");
    } catch (Exception e) {
      throw new ApiException(0, "SEND_FAILED", e.getMessage());
    }
  }

  private void receive(
      UUID roomId,
      UUID messageId,
      UUID senderUserId,
      String senderName,
      String ciphertext,
      java.time.Instant createdAt,
      java.time.Instant expiresAt) {
    RoomSessionView s = sessions.get(roomId);
    RoomGroup g = mls.group(roomId);
    if (s == null || g == null) {
      return;
    }
    var existing =
        chat.messages(roomId).stream().filter(m -> messageId.equals(m.messageId())).findFirst();
    if (existing.isPresent()) {
      if (existing.get().status() == ChatState.Status.UNSEEN)
        displayAndAcknowledge(roomId, messageId, existing.get(), s);
      return;
    }
    RoomGroup.Decrypted d;
    try {
      d = g.decrypt(Base64.getDecoder().decode(ciphertext));
    } catch (RuntimeException e) {
      listener.onWarning("Could not decrypt message " + messageId + ": " + e.getMessage());
      return;
    }
    if (!d.senderUserId().equals(senderUserId)) {
      listener.onWarning("Sender mismatch for message " + messageId + " (server label rejected)");
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> body = ApiClient.JSON.readValue(d.plaintext(), Map.class);
    java.util.Arrays.fill(d.plaintext(), (byte) 0);
    DisplayedMessage m =
        new DisplayedMessage(
            messageId,
            roomId,
            senderUserId,
            senderName,
            false,
            String.valueOf(body.get("text")),
            createdAt,
            expiresAt,
            ChatState.Status.UNSEEN);
    chat.add(m);
    displayAndAcknowledge(roomId, messageId, m, s);
  }

  private void displayAndAcknowledge(
      UUID roomId, UUID messageId, DisplayedMessage m, RoomSessionView s) {
    listener.onMessage(m);
    // Rendered -> acknowledge (a display receipt, not proof of human attention).
    try {
      var ack = api.acknowledgeView(roomId, messageId, s.sessionId());
      m.setStatus(ack.deleted() ? ChatState.Status.DELETED_ON_SERVER : ChatState.Status.VIEWED);
      listener.onMessageStatus(roomId, messageId, m.status());
    } catch (ApiException e) {
      listener.onWarning("View receipt rejected: " + e.code());
    }
  }

  /** Re-fetches pending ciphertext for an open session (e.g. after reconnect). */
  public void refresh(UUID roomId) {
    synchronized (lock) {
      catchUp(roomId);
    }
  }

  // ------------------------------------------------------------------ reset / rekey

  public void resetRoomNow(UUID roomId) {
    synchronized (lock) {
      api.resetRoom(roomId);
      sessions.remove(roomId);
      chat.clearRoom(roomId);
      completeRekey(roomId);
    }
  }

  /**
   * Owner-only rekey after a reset: an MLS Remove commit of every other leaf when this device still
   * holds the group, otherwise a brand-new group. Until this succeeds the server keeps the room
   * LOCKED_PENDING_REKEY and refuses all sends and joins.
   */
  public void completeRekey(UUID roomId) {
    synchronized (lock) {
      RoomView room = api.room(roomId);
      if (!"LOCKED_PENDING_REKEY".equals(room.state())) {
        return;
      }
      RoomGroup g = mls.group(roomId);
      if (g != null && g.epoch() == room.mlsEpoch()) {
        RoomGroup.PreparedCommit prepared =
            g.roster().size() > 1 ? g.prepareRemoveAllOthers() : g.prepareUpdate();
        api.commit(
            roomId,
            new MlsCommitRequest(
                room.generation(),
                prepared.fromEpoch(),
                CommitPurpose.RESET_REKEY,
                b64(prepared.commit()),
                null,
                null,
                null,
                false));
        g.applyOwnCommit(prepared);
      } else {
        mls.forget(roomId);
        mls.createGroup(roomId);
        api.commit(
            roomId,
            new MlsCommitRequest(
                room.generation(),
                room.mlsEpoch(),
                CommitPurpose.RESET_REKEY,
                null,
                null,
                null,
                null,
                true));
      }
    }
  }

  public RoomGroup group(UUID roomId) {
    return mls == null ? null : mls.group(roomId);
  }

  // ------------------------------------------------------------------ socket events

  private void onFrame(StompClient.Frame frame) {
    ChatEvent e;
    try {
      e = ApiClient.JSON.readValue(frame.body(), ChatEvent.class);
    } catch (RuntimeException ex) {
      return;
    }
    if (e.type() == EventType.SENT || (e.type() == EventType.ERROR && e.roomId() == null)) {
      CompletableFuture<ChatEvent> f = pendingSends.poll();
      if (f != null) {
        f.complete(e);
      }
      return;
    }
    events.submit(() -> handleEvent(e));
  }

  private void handleEvent(ChatEvent e) {
    try {
      synchronized (lock) {
        switch (e.type()) {
          case MESSAGE -> {
            RoomGroup g = mls.group(e.roomId());
            if (g != null
                && e.epoch() != null
                && e.epoch() > g.epoch()
                && sessions.containsKey(e.roomId())) {
              catchUp(e.roomId()); // commit not processed yet; backlog fetch includes this message
            } else {
              receive(
                  e.roomId(),
                  e.messageId(),
                  e.senderUserId(),
                  e.senderUsername(),
                  e.ciphertext(),
                  e.createdAt(),
                  e.expiresAt());
            }
          }
          case MESSAGE_DELETED -> {
            // Server copy is gone; our in-memory copy remains visible only until session end.
            chat.markStatus(e.roomId(), e.messageId(), ChatState.Status.DELETED_ON_SERVER);
            listener.onMessageStatus(e.roomId(), e.messageId(), ChatState.Status.DELETED_ON_SERVER);
          }
          case MLS_COMMIT -> {
            if (mls.group(e.roomId()) != null && sessions.containsKey(e.roomId())) {
              catchUp(e.roomId());
            } else if (mls.group(e.roomId()) != null) {
              RoomGroup g = mls.group(e.roomId());
              for (MlsEventView ev : api.mlsEvents(e.roomId(), g.epoch())) {
                g.handleCommit(Base64.getDecoder().decode(ev.commit()), ev.epoch());
              }
            }
            listener.onRoomChanged(e.roomId(), e.type());
          }
          case WELCOME -> listener.onRoomChanged(e.roomId(), e.type());
          case ROOM_RESET, MEMBERSHIP_REVOKED -> {
            sessions.remove(e.roomId());
            chat.clearRoom(e.roomId());
            mls.forget(e.roomId());
            listener.onRoomChanged(e.roomId(), e.type());
          }
          case REKEY_REQUIRED -> {
            RoomView current = api.room(e.roomId());
            // Reset notifications are asynchronous. A locally completed rekey must not have its
            // newly opened session cleared by a delayed notification from that same reset.
            if (!"LOCKED_PENDING_REKEY".equals(current.state())
                || !java.util.Objects.equals(e.generation(), current.generation())) {
              break;
            }
            sessions.remove(e.roomId());
            chat.clearRoom(e.roomId());
            completeRekey(e.roomId());
            listener.onRoomChanged(e.roomId(), e.type());
          }
          default -> {}
        }
      }
    } catch (RuntimeException ex) {
      listener.onWarning("Event " + e.type() + " failed: " + ex.getMessage());
    }
  }

  private void onStompError(StompClient.Frame frame) {
    listener.onWarning(
        "Server closed the channel: " + frame.headers().getOrDefault("message", "error"));
  }

  private void onSocketClosed() {
    // The server ends all room sessions of this auth session when the socket drops.
    sessions.keySet().forEach(chat::clearRoom);
    sessions.clear();
    CompletableFuture<ChatEvent> f;
    while ((f = pendingSends.poll()) != null) {
      f.completeExceptionally(new IllegalStateException("socket closed"));
    }
  }

  // ------------------------------------------------------------------ helpers

  private RoomGroup requireGroup(UUID roomId) {
    RoomGroup g = mls.group(roomId);
    if (g == null) {
      throw new IllegalStateException("No MLS state for room " + roomId);
    }
    return g;
  }

  /** Waits (for tests/UI) until a predicate on chat state holds. */
  public boolean awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.getAsBoolean();
  }

  private static String b64(byte[] b) {
    return b == null ? null : Base64.getEncoder().encodeToString(b);
  }

  /** Quits: ends sessions, wipes decrypted messages and MLS state, closes the socket, logs out. */
  @Override
  public void close() {
    synchronized (lock) {
      List.copyOf(sessions.keySet()).forEach(this::leaveRoom);
      chat.clearAll();
      if (mls != null) {
        mls.forgetAll();
      }
      StompClient s = stomp;
      stomp = null;
      if (s != null) {
        s.close();
      }
      try {
        api.logout();
      } catch (ApiException ignored) {
        // already logged out
      }
      events.shutdownNow();
    }
  }
}
