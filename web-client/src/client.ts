import { Client } from "@stomp/stompjs";
import { signWithLabel } from "ts-mls/crypto/signature.js";
import { Api } from "./api";
import { Mls, suiteReady, FingerprintChangedError } from "./mls";
import { b64, utf8, normalizeUser, validateAccount } from "./bytes";
import { loadIdentity, saveIdentity, type Identity } from "./vault";
import type { Room, Tokens, Member, Message, ChatEvent, Invite } from "./types";
export interface Displayed extends Message {
  cleartext: string;
  own: boolean;
  acknowledged: boolean;
}
export class ChatClient {
  readonly api: Api;
  mls: Mls | null = null;
  socket: Client | null = null;
  room: Room | null = null;
  sessionId: string | null = null;
  messages = new Map<string, Displayed>();
  onChange: () => void = () => {};
  onWarning: (message: string) => void = () => {};
  onDisplay: (message: Displayed) => Promise<boolean> = async () => false;
  private queue = Promise.resolve();
  private sends = new Map<
    string,
    {
      resolve: (e: ChatEvent) => void;
      reject: (error: Error) => void;
      timer: ReturnType<typeof setTimeout>;
    }
  >();
  constructor(origin?: string) {
    this.api = new Api(origin);
  }
  serialized<T>(work: () => Promise<T>): Promise<T> {
    const next = this.queue.then(work);
    this.queue = next.then(
      () => {},
      () => {},
    );
    return next;
  }
  async authenticate(
    user: string,
    password: string,
    device: string,
    register: boolean,
  ) {
    const invalid = validateAccount(user, password, device, register);
    if (invalid) throw new Error(invalid);
    user = normalizeUser(user);
    const cs = await suiteReady;
    let identity: Identity | null = register
      ? null
      : await loadIdentity(user, password);
    const existing = identity !== null;
    if (!identity) identity = await cs.signature.keygen();
    const challenge = await this.api.call<{
      challengeId: string;
      nonce: string;
    }>("/api/v1/auth/challenge", "POST", undefined, false);
    const signature = await signWithLabel(
      identity.signKey,
      "SecretChatDeviceLogin-v1",
      utf8.encode(`login|${challenge.challengeId}|${challenge.nonce}|${user}`),
      cs.signature,
    );
    const newDevice = { name: device, publicKey: b64(identity.publicKey) };
    const body = register
      ? {
          username: user,
          password,
          device: newDevice,
          challengeId: challenge.challengeId,
          signature: b64(signature),
        }
      : {
          username: user,
          password,
          deviceId: existing ? identity.deviceId : null,
          newDevice: existing ? null : newDevice,
          challengeId: challenge.challengeId,
          signature: b64(signature),
        };
    try {
      const t = await this.api.call<Tokens>(
        "/api/v1/auth/" + (register ? "register" : "login"),
        "POST",
        body,
        false,
      );
      identity.userId = t.userId;
      identity.deviceId = t.deviceId;
      await saveIdentity(user, password, identity);
      this.api.tokens = t;
      this.mls = new Mls(identity, t.userId);
      if (t.deviceStatus === "APPROVED") await this.online();
      return t;
    } catch (e) {
      identity.signKey.fill(0);
      throw e;
    }
  }
  async online() {
    if (!this.mls || this.api.tokens?.deviceStatus !== "APPROVED")
      throw new Error("This browser device needs approval.");
    await Promise.all([this.publishPackages(), this.connect()]);
  }
  async publishPackages(count = 2) {
    if (!this.mls) throw new Error("Sign in first.");
    await this.api.call("/api/v1/devices/me/key-packages", "PUT", {
      keyPackages: await this.mls.packages(count),
    });
  }
  async checkApproval() {
    await this.api.refresh();
    if (this.api.tokens?.deviceStatus === "APPROVED") await this.online();
  }
  async connect() {
    if (this.socket?.connected) return;
    if (this.socket) await this.socket.deactivate();
    const token = this.api.tokens?.accessToken;
    if (!token) throw new Error("Sign in first.");
    const ticket = await this.api.call<{ ticket: string }>(
      "/api/v1/ws-ticket",
      "POST",
    );
    const url = new URL("/ws/browser", this.api.origin);
    url.searchParams.set("ticket", ticket.ticket);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        void this.socket?.deactivate();
        reject(new Error("Unable to connect to chat. Try Reconnect."));
      }, 12_000);
      const socket = new Client({
        brokerURL: url.href,
        connectHeaders: { Authorization: "Bearer " + token },
        reconnectDelay: 0,
        heartbeatIncoming: 10_000,
        heartbeatOutgoing: 10_000,
        logRawCommunication: false,
        debug: () => {},
      });
      this.socket = socket;
      socket.onConnect = () => {
        clearTimeout(timer);
        socket.subscribe("/user/queue/chat", (frame) => {
          try {
            this.event(JSON.parse(frame.body));
          } catch {
            this.onWarning("An invalid chat event was rejected.");
          }
        });
        resolve();
      };
      socket.onStompError = () => {
        clearTimeout(timer);
        reject(new Error("Chat authorization failed. Sign in again."));
      };
      socket.onWebSocketClose = () => {
        this.messages.clear();
        this.sessionId = null;
        this.onChange();
        clearTimeout(timer);
        reject(new Error("Chat connection closed. Use Reconnect."));
        for (const p of this.sends.values()) {
          clearTimeout(p.timer);
          p.reject(new Error("Connection closed before send confirmation."));
        }
        this.sends.clear();
        this.onWarning("Chat connection closed. Use Reconnect.");
      };
      socket.activate();
    });
  }
  private event(e: ChatEvent) {
    if (e.idempotencyKey && (e.type === "SENT" || e.type === "ERROR")) {
      const p = this.sends.get(e.idempotencyKey);
      if (p) {
        clearTimeout(p.timer);
        this.sends.delete(e.idempotencyKey);
        if (e.type === "SENT") p.resolve(e);
        else p.reject(new Error(e.detail || "Message was rejected."));
      }
      return;
    }
    if (
      this.room?.id === e.roomId &&
      ["ROOM_RESET", "MEMBERSHIP_REVOKED"].includes(e.type)
    ) {
      this.messages.clear();
      this.sessionId = null;
      if (this.mls) this.mls.groups.delete(e.roomId);
      this.onWarning(
        "The room was reset or your membership ended. Its displayed messages have been cleared.",
      );
      this.onChange();
    } else if (
      e.type === "MESSAGE" &&
      this.room?.id === e.roomId &&
      this.sessionId
    ) {
      void this.serialized(async () => {
        if (!this.room || !this.sessionId || this.room.id !== e.roomId) return;
        if (this.mls!.epoch(e.roomId) !== e.epoch) await this.catchUp();
        // REST rechecks recipient/session eligibility, so delayed push cannot replay a viewed message after re-entry.
        await this.readPending();
        this.onChange();
      }).catch((error) => this.onWarning(error.message));
    } else if (
      e.type === "MLS_COMMIT" &&
      this.room?.id === e.roomId &&
      this.sessionId
    ) {
      void this.serialized(() => this.catchUp()).catch((error) =>
        this.onWarning(error.message),
      );
    } else if (e.type === "ERROR")
      this.onWarning(e.detail || "Chat request failed.");
    else this.onChange();
  }
  async rooms(): Promise<Room[]> {
    return this.api.call("/api/v1/rooms");
  }
  async members(room: string): Promise<Member[]> {
    return this.api.call("/api/v1/rooms/" + room + "/members");
  }
  async create(
    ttl: number,
    reset: number,
    roomPassword: string | null,
    name?: string,
  ) {
    const room = await this.api.call<Room>("/api/v1/rooms", "POST", {
      messageTtlSeconds: ttl,
      resetIntervalSeconds: reset,
      roomPassword,
      privacyMode: "TEMPORARY_CATCH_UP",
      name,
    });
    await this.mls!.create(room.id);
    return room;
  }
  async enter(room: Room, password: string | null) {
    await this.leave();
    await this.connect();
    room = await this.api.call<Room>("/api/v1/rooms/" + room.id);
    if (room.myMembershipState === "PENDING_KEYS")
      throw new Error(
        "Waiting for the owner to add your accepted invitation to the encrypted group.",
      );
    if (room.state === "LOCKED_PENDING_REKEY" && room.myRole === "OWNER") {
      await this.finishReset(room);
      room = await this.api.call<Room>("/api/v1/rooms/" + room.id);
    }
    if (room.state !== "ACTIVE")
      throw new Error(
        "The owner must reset/rekey this room before it can be entered.",
      );
    if (!this.mls!.groups.has(room.id)) {
      if (room.myRole === "OWNER")
        throw new Error(
          "Room keys were lost after restart. Use Reset room, then invite members again.",
        );
      const welcome = await this.api.call<{ welcome: string }>(
        "/api/v1/rooms/" + room.id + "/mls/welcome",
      );
      await this.mls!.join(
        room.id,
        welcome.welcome,
        await this.members(room.id),
      );
    }
    const session = await this.api.call<{ sessionId: string }>(
      "/api/v1/rooms/" + room.id + "/sessions",
      "POST",
      { roomPassword: password },
    );
    this.room = room;
    this.sessionId = session.sessionId;
    try {
      await this.catchUp();
      await this.readPending();
    } catch (e) {
      await this.leave();
      throw e;
    }
  }
  async stableRoster(room: Room) {
    const members = await this.members(room.id);
    const after = await this.api.call<Room>("/api/v1/rooms/" + room.id);
    if (
      room.mlsEpoch !== after.mlsEpoch ||
      this.mls!.epoch(room.id) !== after.mlsEpoch
    )
      throw new Error(
        "Membership changed during verification. Refresh and try again.",
      );
    await this.mls!.verify(room.id, members);
  }
  async catchUp() {
    if (!this.room || !this.sessionId) return;
    const current = await this.api.call<Room>("/api/v1/rooms/" + this.room.id);
    if (
      current.generation !== this.room.generation ||
      current.myMembershipState !== "ACTIVE" ||
      current.state !== "ACTIVE"
    ) {
      this.messages.clear();
      this.sessionId = null;
      this.room = current;
      throw new Error("This room cycle ended. Reset/rekey is required.");
    }
    const events = await this.api.call<{ epoch: number; commit: string }[]>(
      "/api/v1/rooms/" +
        current.id +
        "/mls/events?afterEpoch=" +
        this.mls!.epoch(current.id),
    );
    for (const e of events)
      if (e.epoch > this.mls!.epoch(current.id))
        await this.mls!.commit(current.id, e.commit, e.epoch);
    this.room = current;
    await this.stableRoster(current);
  }
  async readPending() {
    if (!this.room || !this.sessionId) return;
    const r = this.room;
    const session = this.sessionId;
    const pending = await this.api.call<Message[]>(
      "/api/v1/rooms/" + r.id + "/messages?pending=true&sessionId=" + session,
    );
    for (const m of pending) {
      if (Date.parse(m.expiresAt) <= Date.now()) continue;
      let displayed = this.messages.get(m.messageId);
      if (!displayed) {
        const cleartext = await this.mls!.decrypt(
          r.id,
          r.generation,
          m.ciphertext,
          m.senderUserId,
        );
        displayed = { ...m, cleartext, own: false, acknowledged: false };
        this.messages.set(m.messageId, displayed);
      }
      if (!displayed.acknowledged && (await this.onDisplay(displayed))) {
        await this.api.call(
          `/api/v1/rooms/${r.id}/messages/${m.messageId}/view`,
          "POST",
          { sessionId: session },
        );
        displayed.acknowledged = true;
      }
    }
    for (const [id, m] of this.messages)
      if (Date.parse(m.expiresAt) <= Date.now()) this.messages.delete(id);
  }
  async send(value: string) {
    if (!value.trim()) throw new Error("Write a message first.");
    if (utf8.encode(value).length > 32_000)
      throw new Error("Message is too long.");
    if (!this.room || !this.sessionId) throw new Error("Enter a room first.");
    if (!this.socket?.connected)
      throw new Error("Use Reconnect before sending.");
    const r = this.room,
      key = crypto.randomUUID();
    const ciphertext = await this.mls!.encrypt(r.id, r.generation, value);
    const response = await new Promise<ChatEvent>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.sends.delete(key);
        reject(
          new Error("Send confirmation timed out. Refresh before retrying."),
        );
      }, 15_000);
      this.sends.set(key, { resolve, reject, timer });
      this.socket!.publish({
        destination: "/app/rooms/" + r.id + "/send",
        body: JSON.stringify({
          sessionId: this.sessionId,
          generation: r.generation,
          epoch: this.mls!.epoch(r.id),
          idempotencyKey: key,
          ciphertext,
        }),
      });
    });
    const message = {
      ...response,
      cleartext: value,
      own: true,
      acknowledged: true,
    };
    this.messages.set(response.messageId, message);
    this.onChange();
    return message;
  }
  async addAccepted(room: Room) {
    if (room.myRole !== "OWNER")
      throw new Error("Only the owner can add members.");
    this.mls!.require(room.id);
    const events = await this.api.call<{ epoch: number; commit: string }[]>(
      "/api/v1/rooms/" +
        room.id +
        "/mls/events?afterEpoch=" +
        this.mls!.epoch(room.id),
    );
    for (const e of events) await this.mls!.commit(room.id, e.commit, e.epoch);
    const invites = (
      await this.api.call<Invite[]>("/api/v1/rooms/" + room.id + "/invites")
    ).filter((i) => i.status === "ACCEPTED");
    if (!invites.length) throw new Error("No accepted invitations yet.");
    for (const i of invites) {
      const pinned = this.mls!.pins.get(i.inviteeUserId);
      if (pinned && pinned !== i.acceptedDeviceFingerprint)
        throw new FingerprintChangedError(
          i.inviteeUserId,
          pinned,
          i.acceptedDeviceFingerprint,
        );
    }
    const claimed = [];
    for (const i of invites)
      claimed.push(
        await this.api.call<{
          userId: string;
          deviceFingerprint: string;
          keyPackage: string;
        }>(`/api/v1/rooms/${room.id}/invites/${i.id}/key-package`, "POST"),
      );
    const prepared = await this.mls!.prepareAdd(room.id, claimed);
    room = await this.api.call<Room>("/api/v1/rooms/" + room.id);
    await this.api.call("/api/v1/rooms/" + room.id + "/mls/commit", "POST", {
      generation: room.generation,
      fromEpoch: this.mls!.epoch(room.id),
      purpose: "ADD",
      commit: prepared.commit,
      welcome: prepared.welcome,
      addedInvitationIds: invites.map((i) => i.id),
      removedUserIds: [],
      newGroup: false,
    });
    prepared.apply();
  }
  async removeMember(room: Room, userId: string) {
    if (room.myRole !== "OWNER")
      throw new Error("Only the owner can remove members.");
    if (userId === this.api.tokens?.userId)
      throw new Error("The owner cannot remove themselves.");
    const current = await this.api.call<Room>("/api/v1/rooms/" + room.id);
    const events = await this.api.call<{ epoch: number; commit: string }[]>(
      `/api/v1/rooms/${room.id}/mls/events?afterEpoch=${this.mls!.epoch(room.id)}`,
    );
    for (const event of events)
      if (event.epoch > this.mls!.epoch(room.id))
        await this.mls!.commit(room.id, event.commit, event.epoch);
    const prepared = await this.mls!.prepareRemove(room.id, userId);
    await this.api.call(`/api/v1/rooms/${room.id}/mls/commit`, "POST", {
      generation: current.generation,
      fromEpoch: this.mls!.epoch(room.id),
      purpose: "REMOVE",
      commit: prepared.commit,
      welcome: null,
      addedInvitationIds: [],
      removedUserIds: [userId],
      newGroup: false,
    });
    prepared.apply();
    if (this.room?.id === room.id)
      this.room = await this.api.call<Room>("/api/v1/rooms/" + room.id);
  }
  async reset(room: Room) {
    await this.leave();
    room = await this.api.call<Room>("/api/v1/rooms/" + room.id);
    if (room.state === "ACTIVE") {
      try {
        await this.api.call("/api/v1/rooms/" + room.id + "/reset", "POST");
      } catch (error) {
        // The timer may reset the room between our GET and POST.
        const current = await this.api.call<Room>("/api/v1/rooms/" + room.id);
        if (current.state !== "LOCKED_PENDING_REKEY") throw error;
      }
      room = await this.api.call<Room>("/api/v1/rooms/" + room.id);
    }
    await this.finishReset(room);
  }
  private async finishReset(room: Room) {
    if (room.myRole !== "OWNER" || room.state !== "LOCKED_PENDING_REKEY")
      throw new Error("Only the owner can finish a pending room reset.");
    this.mls!.groups.delete(room.id);
    await this.mls!.create(room.id);
    await this.api.call("/api/v1/rooms/" + room.id + "/mls/commit", "POST", {
      generation: room.generation,
      fromEpoch: room.mlsEpoch,
      purpose: "RESET_REKEY",
      commit: null,
      welcome: null,
      addedInvitationIds: [],
      removedUserIds: [],
      newGroup: true,
    });
  }
  async accept(invite: Invite, password: string | null) {
    await this.publishPackages(1);
    return this.api.call("/api/v1/invites/" + invite.id + "/accept", "POST", {
      roomPassword: password,
    });
  }
  async leave() {
    const room = this.room,
      session = this.sessionId;
    this.messages.clear();
    this.room = null;
    this.sessionId = null;
    if (room && session)
      await this.api.call(
        "/api/v1/rooms/" + room.id + "/sessions/" + session,
        "DELETE",
      );
  }
  async logout() {
    try {
      await this.leave();
      await this.api.call("/api/v1/auth/logout", "POST");
    } finally {
      await this.socket?.deactivate();
      this.socket = null;
      this.api.tokens = null;
      this.mls?.clear();
      this.mls = null;
      this.messages.clear();
    }
  }
}
