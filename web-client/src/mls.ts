import {
  createGroup,
  generateKeyPackageWithKey,
  defaultCapabilities,
  defaultLifetime,
  getCiphersuiteImpl,
  getCiphersuiteFromName,
  joinGroup,
  emptyPskIndex,
  createCommit,
  createApplicationMessage,
  decodeMlsMessage,
  encodeMlsMessage,
  processMessage,
  acceptAll,
  type ClientState,
  type KeyPackage,
  type PrivateKeyPackage,
  type ClientConfig,
  zeroOutUint8Array,
} from "ts-mls";
import { defaultClientConfig } from "ts-mls/clientConfig.js";
import {
  decodeKeyPackage,
  encodeKeyPackage,
  makeKeyPackageRef,
  verifyKeyPackage,
} from "ts-mls/keyPackage.js";
import { decodeWelcome, encodeWelcome } from "ts-mls/welcome.js";
import { unprotectPrivateMessage } from "ts-mls/messageProtection.js";
import { toLeafIndex } from "ts-mls/treemath.js";
import { getCredentialFromLeafIndex } from "ts-mls/ratchetTree.js";
import { b64, unb64, utf8, text, fingerprint } from "./bytes";
import type { Identity } from "./vault";
import type { Member } from "./types";
export const suiteReady = getCiphersuiteImpl(
  getCiphersuiteFromName("MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519"),
);
type Pending = { publicPackage: KeyPackage; privatePackage: PrivateKeyPackage };
export class Mls {
  groups = new Map<string, ClientState>();
  pending = new Map<string, Pending>();
  pins = new Map<string, string>();
  private config: ClientConfig;
  constructor(
    readonly identity: Identity,
    readonly userId: string,
  ) {
    const saved = localStorage.getItem("privacy-chat.pins." + userId);
    if (saved) this.pins = new Map(Object.entries(JSON.parse(saved)));
    this.config = {
      ...defaultClientConfig,
      keyRetentionConfig: {
        retainKeysForEpochs: 1,
        retainKeysForGenerations: 0,
        maximumForwardRatchetSteps: 200,
      },
      authService: {
        validateCredential: async (credential, key) =>
          credential.credentialType === "basic" &&
          this.pins.get(text.decode(credential.identity)) ===
            (await fingerprint(key)),
      },
    };
  }
  async pin(user: string, fp: string) {
    const existing = this.pins.get(user);
    if (existing && existing !== fp)
      throw new Error(
        "Device fingerprint changed. Verify it out of band before using this room.",
      );
    this.pins.set(user, fp);
    localStorage.setItem(
      "privacy-chat.pins." + this.userId,
      JSON.stringify(Object.fromEntries(this.pins)),
    );
  }
  async packages(count = 8): Promise<string[]> {
    if (this.pending.size + count > 256)
      throw new Error(
        "Too many pending key packages. Reopen the browser to start a fresh device session.",
      );
    const cs = await suiteReady;
    const out: string[] = [];
    for (let i = 0; i < count; i++) {
      const p = await generateKeyPackageWithKey(
        { credentialType: "basic", identity: utf8.encode(this.userId) },
        defaultCapabilities(),
        defaultLifetime,
        [],
        this.identity,
        cs,
      );
      const encoded = b64(encodeKeyPackage(p.publicPackage));
      this.pending.set(encoded, p);
      out.push(encoded);
    }
    return out;
  }
  private async selfPackage() {
    const cs = await suiteReady;
    return generateKeyPackageWithKey(
      { credentialType: "basic", identity: utf8.encode(this.userId) },
      defaultCapabilities(),
      defaultLifetime,
      [],
      this.identity,
      cs,
    );
  }
  async create(room: string) {
    await this.pin(this.userId, await fingerprint(this.identity.publicKey));
    const p = await this.selfPackage();
    this.groups.set(
      room,
      await createGroup(
        crypto.getRandomValues(new Uint8Array(16)),
        p.publicPackage,
        p.privatePackage,
        [],
        await suiteReady,
        this.config,
      ),
    );
  }
  async join(room: string, encoded: string, members: Member[]) {
    for (const m of members.filter((m) => m.state === "ACTIVE"))
      await this.pin(m.userId, m.deviceFingerprint);
    const bytes = unb64(encoded);
    const decoded = decodeWelcome(bytes, 0);
    if (!decoded || decoded[1] !== bytes.length)
      throw new Error("Malformed MLS Welcome");
    const cs = await suiteReady;
    for (const [key, p] of this.pending) {
      const ref = await makeKeyPackageRef(p.publicPackage, cs.hash);
      if (!decoded[0].secrets.some((s) => b64(s.newMember) === b64(ref)))
        continue;
      const state = await joinGroup(
        decoded[0],
        p.publicPackage,
        p.privatePackage,
        emptyPskIndex,
        cs,
        undefined,
        undefined,
        this.config,
      );
      this.groups.set(room, state);
      this.pending.delete(key);
      p.privatePackage.initPrivateKey.fill(0);
      return;
    }
    throw new Error(
      "This browser no longer has the invitation keys. Ask the owner to reset and invite you again.",
    );
  }
  require(room: string): ClientState {
    const s = this.groups.get(room);
    if (!s)
      throw new Error(
        "Room keys were lost when this browser restarted. The owner must reset the room and invite members again.",
      );
    return s;
  }
  epoch(room: string): number {
    return Number(this.require(room).groupContext.epoch);
  }
  async verify(room: string, members: Member[]) {
    const state = this.require(room);
    const leaves = state.ratchetTree
      .filter((n) => n?.nodeType === "leaf")
      .map((n) => (n!.nodeType === "leaf" ? n!.leaf : null))
      .filter((n) => n !== null);
    const active = members.filter((m) => m.state === "ACTIVE");
    if (leaves.length !== active.length)
      throw new Error("MLS roster does not match active room membership.");
    const found = new Set<string>();
    for (const leaf of leaves) {
      if (leaf.credential.credentialType !== "basic")
        throw new Error("Unsupported MLS credential");
      const user = text.decode(leaf.credential.identity);
      if (found.has(user)) throw new Error("Duplicate MLS member");
      found.add(user);
      const member = active.find((m) => m.userId === user);
      const fp = await fingerprint(leaf.signaturePublicKey);
      if (!member || member.deviceFingerprint !== fp)
        throw new Error(
          "MLS device fingerprint does not match the enrolled device.",
        );
      await this.pin(user, fp);
    }
  }
  async prepareAdd(
    room: string,
    claimed: {
      userId: string;
      deviceFingerprint: string;
      keyPackage: string;
    }[],
  ) {
    const cs = await suiteReady;
    const keys: KeyPackage[] = [];
    for (const c of claimed) {
      const bytes = unb64(c.keyPackage);
      const decoded = decodeKeyPackage(bytes, 0);
      if (
        !decoded ||
        decoded[1] !== bytes.length ||
        !(await verifyKeyPackage(decoded[0], cs.signature))
      )
        throw new Error("Invalid MLS KeyPackage signature");
      const kp = decoded[0];
      if (
        kp.cipherSuite !== cs.name ||
        kp.leafNode.credential.credentialType !== "basic" ||
        text.decode(kp.leafNode.credential.identity) !== c.userId ||
        (await fingerprint(kp.leafNode.signaturePublicKey)) !==
          c.deviceFingerprint
      )
        throw new Error("Invitee KeyPackage identity mismatch");
      await this.pin(c.userId, c.deviceFingerprint);
      keys.push(kp);
    }
    const result = await createCommit(
      { state: this.require(room), cipherSuite: cs },
      {
        extraProposals: keys.map((keyPackage) => ({
          proposalType: "add",
          add: { keyPackage },
        })),
        ratchetTreeExtension: true,
        wireAsPublicMessage: true,
      },
    );
    return {
      commit: b64(encodeMlsMessage(result.commit)),
      welcome: result.welcome ? b64(encodeWelcome(result.welcome)) : null,
      apply: () => {
        this.groups.set(room, result.newState);
        result.consumed.forEach(zeroOutUint8Array);
      },
    };
  }
  async prepareRemove(room: string, userId: string) {
    const state = this.require(room);
    const node = state.ratchetTree.findIndex(
      (n) =>
        n?.nodeType === "leaf" &&
        n.leaf.credential.credentialType === "basic" &&
        text.decode(n.leaf.credential.identity) === userId,
    );
    if (node < 0 || userId === this.userId)
      throw new Error("Select an active non-owner member.");
    const result = await createCommit(
      { state, cipherSuite: await suiteReady },
      {
        extraProposals: [
          { proposalType: "remove", remove: { removed: node / 2 } },
        ],
        ratchetTreeExtension: true,
        wireAsPublicMessage: true,
      },
    );
    return {
      commit: b64(encodeMlsMessage(result.commit)),
      apply: () => {
        this.groups.set(room, result.newState);
        result.consumed.forEach(zeroOutUint8Array);
      },
    };
  }
  async commit(room: string, encoded: string, expected: number) {
    const bytes = unb64(encoded);
    const decoded = decodeMlsMessage(bytes, 0);
    if (
      !decoded ||
      decoded[1] !== bytes.length ||
      !["mls_private_message", "mls_public_message"].includes(
        decoded[0].wireformat,
      )
    )
      throw new Error("Invalid MLS commit");
    if (this.epoch(room) + 1 !== expected)
      throw new Error("Unexpected MLS epoch");
    const result = await processMessage(
      decoded[0] as Parameters<typeof processMessage>[0],
      this.require(room),
      emptyPskIndex,
      acceptAll,
      await suiteReady,
    );
    if (
      result.kind !== "newState" ||
      Number(result.newState.groupContext.epoch) !== expected
    )
      throw new Error("Commit did not advance the epoch");
    this.groups.set(room, result.newState);
    result.consumed.forEach(zeroOutUint8Array);
  }
  async encrypt(room: string, generation: number, value: string) {
    const clear = utf8.encode(JSON.stringify({ v: 1, text: value }));
    try {
      const result = await createApplicationMessage(
        this.require(room),
        clear,
        await suiteReady,
        utf8.encode(room + "|" + generation),
      );
      this.groups.set(room, result.newState);
      result.consumed.forEach(zeroOutUint8Array);
      return b64(
        encodeMlsMessage({
          wireformat: "mls_private_message",
          version: "mls10",
          privateMessage: result.privateMessage,
        }),
      );
    } finally {
      clear.fill(0);
    }
  }
  async decrypt(
    room: string,
    generation: number,
    encoded: string,
    claimedSender: string,
  ): Promise<string> {
    const bytes = unb64(encoded);
    const decoded = decodeMlsMessage(bytes, 0);
    if (
      !decoded ||
      decoded[1] !== bytes.length ||
      decoded[0].wireformat !== "mls_private_message"
    )
      throw new Error("Expected an MLS PrivateMessage");
    const s = this.require(room),
      pm = decoded[0].privateMessage;
    const old =
      pm.epoch === s.groupContext.epoch
        ? null
        : s.historicalReceiverData.get(pm.epoch);
    if (pm.epoch !== s.groupContext.epoch && !old)
      throw new Error("Message epoch is outside the retained keys");
    const tree = old?.ratchetTree ?? s.ratchetTree;
    const result = await unprotectPrivateMessage(
      old?.senderDataSecret ?? s.keySchedule.senderDataSecret,
      pm,
      old?.secretTree ?? s.secretTree,
      tree,
      old?.groupContext ?? s.groupContext,
      s.clientConfig.keyRetentionConfig,
      await suiteReady,
    );
    const content = result.content.content;
    if (
      content.contentType !== "application" ||
      content.sender.senderType !== "member"
    )
      throw new Error("Not an authenticated application message");
    const credential = getCredentialFromLeafIndex(
      tree,
      toLeafIndex(content.sender.leafIndex),
    );
    if (
      credential.credentialType !== "basic" ||
      text.decode(credential.identity) !== claimedSender ||
      text.decode(content.authenticatedData) !== room + "|" + generation
    )
      throw new Error("Authenticated sender or room binding mismatch");
    if (old)
      s.historicalReceiverData.set(pm.epoch, {
        ...old,
        secretTree: result.tree,
      });
    else this.groups.set(room, { ...s, secretTree: result.tree });
    result.consumed.forEach(zeroOutUint8Array);
    const clear = content.applicationData;
    try {
      const payload = JSON.parse(text.decode(clear));
      if (payload.v !== 1 || typeof payload.text !== "string")
        throw new Error("Invalid application payload");
      return payload.text;
    } finally {
      clear.fill(0);
    }
  }
  clear() {
    this.identity.signKey.fill(0);
    this.groups.clear();
    this.pending.clear();
  }
}
