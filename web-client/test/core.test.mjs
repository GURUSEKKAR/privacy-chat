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
const { Mls, suiteReady, FingerprintChangedError } =
  await server.ssrLoadModule("/src/mls.ts");
const { loadIdentity, saveIdentity } =
  await server.ssrLoadModule("/src/vault.ts");
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
  await b.join(room, prepared.welcome, members);
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
