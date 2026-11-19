import { fileURLToPath } from "node:url";
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
const storage = new Map();
globalThis.localStorage = {
  getItem: (k) => storage.get(k) ?? null,
  setItem: (k, v) => storage.set(k, String(v)),
  removeItem: (k) => storage.delete(k),
};
const server = await createServer({
  root: fileURLToPath(new URL("..", import.meta.url)),
  logLevel: "silent",
  server: { middlewareMode: true },
});
after(() => server.close());
const { validateAccount, utf8, b64 } =
  await server.ssrLoadModule("/src/bytes.ts");
const { Mls, suiteReady, FingerprintChangedError, activeFingerprints } =
  await server.ssrLoadModule("/src/mls.ts");
const { loadIdentity, saveIdentity } =
  await server.ssrLoadModule("/src/vault.ts");
const { ChatClient } = await server.ssrLoadModule("/src/client.ts");
test("an unreadable backlog item cannot block or consume later messages", async () => {
  const client = new ChatClient("https://example.invalid");
  client.room = { id: "room", generation: 1 };
  client.sessionId = "viewing-session";
  const expiresAt = new Date(Date.now() + 30_000).toISOString();
  const acknowledged = [];
  client.mls = {
    async decrypt(_room, _generation, ciphertext) {
      if (ciphertext === "old") throw new Error("Old epoch");
      return "current message";
    },
  };
  client.api.call = async (path, method) => {
    if (method === "POST") {
      acknowledged.push(path);
      return;
    }
    return ["old", "current"].map((messageId) => ({
      messageId,
      ciphertext: messageId,
      expiresAt,
    }));
  };
  client.onDisplay = async () => true;
  await client.readPending();
  assert.equal(client.messages.get("current").cleartext, "current message");
  assert.equal(client.messages.has("old"), false);
  assert.deepEqual(acknowledged, ["/api/v1/rooms/room/messages/current/view"]);
});
test("account validation accepts exactly 12 characters and rejects 11", () => {
  assert.ok(validateAccount("guru.r.c", "12345678901", "browser", true));
  assert.equal(
    validateAccount("guru.r.c", "123456789012", "browser", true),
    null,
  );
  assert.equal(
    validateAccount("guru.r.c", "oldpassword", "browser", false),
    null,
  );
});
test("changed member keys stay blocked until explicit review, with stale review rejected", async () => {
  const cs = await suiteReady;
  const mls = new Mls(await cs.signature.keygen(), crypto.randomUUID());
  const previous = Array(10).fill("AAAA").join(" ");
  const current = Array(10).fill("BBBB").join(" ");
  await mls.pin("member", previous);
  await assert.rejects(mls.pin("member", current), FingerprintChangedError);
  assert.equal(mls.pins.get("member"), previous);
  await assert.rejects(
    mls.trustChangedFingerprint("member", current, previous),
  );
  await assert.rejects(
    mls.trustChangedFingerprint("member", previous, "invalid"),
  );
  assert.equal(mls.pins.get("member"), previous);
  await mls.trustChangedFingerprint("member", previous, current);
  await mls.pin("member", current);
  const restored = new Mls(mls.identity, mls.userId);
  assert.equal(restored.pins.get("member"), current);
});
test("browser vault rejects wrong passwords without changing encrypted storage", async () => {
  const cs = await suiteReady,
    id = await cs.signature.keygen();
  await saveIdentity("vaultuser", "private test password", id);
  const before = storage.get("privacy-chat.identity.vaultuser");
  assert.ok(!before.includes(b64(id.signKey)));
  await assert.rejects(loadIdentity("vaultuser", "wrong password"));
  assert.equal(storage.get("privacy-chat.identity.vaultuser"), before);
  assert.deepEqual(
    (await loadIdentity("vaultuser", "private test password")).publicKey,
    id.publicKey,
  );
});
test("MLS authenticates sender, rejects tampering and replays, and clears old members after reset", async () => {
  const cs = await suiteReady;
  const a = new Mls(await cs.signature.keygen(), "a-user"),
    b = new Mls(await cs.signature.keygen(), "b-user");
  const room = crypto.randomUUID();
  await a.create(room);
  const [kp] = await b.packages(1);
  const { fingerprint } = await server.ssrLoadModule("/src/bytes.ts");
  const afp = await fingerprint(a.identity.publicKey),
    bfp = await fingerprint(b.identity.publicKey);
  const prepared = await a.prepareAdd(room, [
    { userId: "b-user", deviceFingerprint: bfp, keyPackage: kp },
  ]);
  prepared.apply();
  const members = [
    { userId: "a-user", state: "ACTIVE", deviceFingerprint: afp },
    { userId: "b-user", state: "ACTIVE", deviceFingerprint: bfp },
  ];
  await b.join(room, prepared.welcome, activeFingerprints(members), 1);
  await a.verify(room, members);
  await b.verify(room, members);
  const message = await a.encrypt(room, 1, "hello from the browser");
  await assert.rejects(b.decrypt(room, 1, message, "wrong-sender"));
  assert.equal(
    await b.decrypt(room, 1, message, "a-user"),
    "hello from the browser",
  );
  await assert.rejects(b.decrypt(room, 1, message, "a-user"));
  const raw = Uint8Array.from(atob(await a.encrypt(room, 1, "tamper")), (c) =>
    c.charCodeAt(0),
  );
  raw[raw.length - 3] ^= 1;
  await assert.rejects(b.decrypt(room, 1, b64(raw), "a-user"));
  await a.create(room);
  const after = await a.encrypt(room, 2, "fresh cycle");
  await assert.rejects(b.decrypt(room, 2, after, "a-user"));
});

test("an existing browser member can catch up and send after a third member joins", async () => {
  const cs = await suiteReady;
  const { fingerprint } = await server.ssrLoadModule("/src/bytes.ts");
  const [owner, member, newcomer] = await Promise.all(
    Array.from(
      { length: 3 },
      async () => new Mls(await cs.signature.keygen(), crypto.randomUUID()),
    ),
  );
  const room = {
    id: crypto.randomUUID(),
    generation: 1,
    state: "ACTIVE",
    myMembershipState: "ACTIVE",
    mlsEpoch: 1,
  };
  const roster = await Promise.all(
    [owner, member, newcomer].map(async (mls) => ({
      userId: mls.userId,
      state: "ACTIVE",
      deviceFingerprint: await fingerprint(mls.identity.publicKey),
    })),
  );
  async function add(mls, index) {
    const [keyPackage] = await mls.packages(1);
    const prepared = await owner.prepareAdd(room.id, [
      { ...roster[index], keyPackage },
    ]);
    prepared.apply();
    await mls.join(
      room.id,
      prepared.welcome,
      activeFingerprints(roster.slice(0, index + 1)),
      index,
    );
    return prepared.commit;
  }
  await owner.create(room.id);
  await add(member, 1);
  const original = await member.encrypt(room.id, 1, "before the third member");
  assert.equal(
    await owner.decrypt(room.id, 1, original, member.userId),
    "before the third member",
  );
  const commit = await add(newcomer, 2);
  const client = new ChatClient("https://example.invalid");
  client.mls = member;
  client.room = room;
  client.sessionId = "member-viewing-session";
  let listedMembers = roster.map((entry, index) =>
    index === 2
      ? { ...entry, deviceFingerprint: roster[0].deviceFingerprint }
      : entry,
  );
  const events = [{ generation: 1, epoch: 2, eventType: "ADD", commit }];
  let currentEpoch = 2;
  client.api.call = async (path) => {
    if (path.endsWith("/members")) return listedMembers;
    if (path.includes("/mls/events?")) return events;
    return { ...room, mlsEpoch: currentEpoch };
  };
  await assert.rejects(client.catchUp(), /Could not validate credential/);
  assert.equal(member.epoch(room.id), 1);
  assert.equal(member.pins.has(newcomer.userId), false);
  listedMembers = roster;
  await client.catchUp();
  assert.equal(member.pins.get(newcomer.userId), roster[2].deviceFingerprint);
  let sent;
  client.socket = {
    connected: true,
    publish({ body }) {
      sent = JSON.parse(body);
      client.event({
        type: "SENT",
        idempotencyKey: sent.idempotencyKey,
        messageId: "message",
      });
    },
  };
  await client.send("after the third member");
  assert.equal(sent.epoch, 2);
  for (const recipient of [owner, newcomer]) {
    assert.equal(
      await recipient.decrypt(room.id, 1, sent.ciphertext, member.userId),
      "after the third member",
    );
  }

  // The member goes offline through both an Add and a Remove. The removed
  // device is unknown to the member and is absent from the current roster.
  const transient = new Mls(await cs.signature.keygen(), crypto.randomUUID());
  const transientMember = {
    userId: transient.userId,
    state: "ACTIVE",
    deviceFingerprint: await fingerprint(transient.identity.publicKey),
  };
  const [keyPackage] = await transient.packages(1);
  const added = await owner.prepareAdd(room.id, [
    { ...transientMember, keyPackage },
  ]);
  added.apply();
  const removed = await owner.prepareRemove(room.id, transient.userId);
  removed.apply();
  currentEpoch = 4;
  events.push(
    {
      generation: 1,
      epoch: 3,
      eventType: "ADD",
      commit: added.commit,
      rosterFingerprints: activeFingerprints([...roster, transientMember]),
    },
    { generation: 1, epoch: 4, eventType: "REMOVE", commit: removed.commit },
  );
  await client.catchUp();
  await client.send("after the offline membership changes");
  assert.equal(sent.epoch, 4);
  assert.equal(
    await owner.decrypt(room.id, 1, sent.ciphertext, member.userId),
    "after the offline membership changes",
  );
  assert.equal(member.pins.has(transient.userId), false);
});
