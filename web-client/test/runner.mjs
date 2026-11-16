import { createHmac } from "node:crypto";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";
import { createInterface } from "node:readline";
const data = new Map();
globalThis.localStorage = {
  getItem: (k) => data.get(k) ?? null,
  setItem: (k, v) => data.set(k, String(v)),
  removeItem: (k) => data.delete(k),
};
const root = fileURLToPath(new URL("..", import.meta.url));
const server = await createServer({
  root,
  logLevel: "silent",
  server: { middlewareMode: true },
});
const { ChatClient } = await server.ssrLoadModule("/src/client.ts");
let client;
let selected;
const operations = {
  async auth(p) {
    client = new ChatClient(p.origin);
    client.onDisplay = async () => true;
    const t = await client.authenticate(
      p.user,
      p.password,
      "Web integration device",
      true,
    );
    return {
      userId: t.userId,
      username: t.username,
      deviceId: t.deviceId,
      deviceFingerprint: t.deviceFingerprint,
      deviceStatus: t.deviceStatus,
    };
  },
  async mfaEnrollForCheck() {
    const { secret } = await client.api.call("/api/v1/auth/mfa/setup", "POST");
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    let value = 0,
      bits = 0;
    const bytes = [];
    for (const c of secret) {
      value = (value << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        bytes.push((value >>> bits) & 255);
      }
    }
    const counter = Buffer.alloc(8);
    counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
    const hash = createHmac("sha1", Buffer.from(bytes))
      .update(counter)
      .digest();
    const offset = hash[hash.length - 1] & 15;
    const code = String(
      (hash.readUInt32BE(offset) & 0x7fffffff) % 1000000,
    ).padStart(6, "0");
    const result = await client.api.call("/api/v1/auth/mfa/verify", "POST", {
      code,
    });
    if (result.status !== "APPROVED")
      throw new Error("Fresh test account should be active after enrollment");
    await client.api.refresh();
    await client.online();
    return {
      status: result.status,
      recoveryCount: result.recoveryCodes.length,
    };
  },
  async create(p) {
    selected = await client.create(600, 3600, p.password ?? null, p.name);
    return selected;
  },
  async remove(p) {
    return client.removeMember(
      await client.api.call("/api/v1/rooms/" + p.room),
      p.user,
    );
  },
  async settings(p) {
    return client.api.call("/api/v1/rooms/" + p.room, "PUT", p.settings);
  },
  async waitMessage(p) {
    const deadline = Date.now() + 3000;
    while (Date.now() < deadline) {
      const messages = [...client.messages.values()];
      if (messages.some((m) => m.cleartext === p.text))
        return {
          messages: messages.map((m) => ({ text: m.cleartext })),
          receivedAt: Date.now(),
        };
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    throw new Error(
      "No WebSocket-triggered message within 3 seconds; this check does not poll REST",
    );
  },
  async rooms() {
    return client.rooms();
  },
  async invites() {
    return client.api.call("/api/v1/invites");
  },
  async invite(p) {
    return client.api.call("/api/v1/rooms/" + p.room + "/invites", "POST", {
      username: p.user,
    });
  },
  async accept(p) {
    return client.accept(p.invite, null);
  },
  async add(p) {
    return client.addAccepted(await client.api.call("/api/v1/rooms/" + p.room));
  },
  async enter(p) {
    selected = await client.api.call("/api/v1/rooms/" + p.room);
    await client.enter(selected, p.password ?? null);
    return { epoch: client.mls.epoch(p.room), sessionId: client.sessionId };
  },
  async send(p) {
    return client.send(p.text);
  },
  async poll() {
    await client.catchUp();
    await client.readPending();
    return [...client.messages.values()].map((m) => ({
      id: m.messageId,
      text: m.cleartext,
      sender: m.senderUserId,
      acknowledged: m.acknowledged,
    }));
  },
  async leave() {
    await client.leave();
  },
  async reset(p) {
    await client.reset(await client.api.call("/api/v1/rooms/" + p.room));
  },
  async serverReset(p) {
    return client.api.call("/api/v1/rooms/" + p.room + "/reset", "POST");
  },
  async stop() {
    await client?.logout();
    await server.close();
    return "stopped";
  },
};
for await (const line of createInterface({ input: process.stdin })) {
  try {
    const p = JSON.parse(line);
    const value = await operations[p.command](p);
    process.stdout.write(
      JSON.stringify({ ok: true, value: value ?? null }, (_, v) =>
        typeof v === "bigint" ? v.toString() : v,
      ) + "\n",
    );
    if (p.command === "stop") break;
  } catch (e) {
    process.stdout.write(
      JSON.stringify({ ok: false, error: e.message }) + "\n",
    );
  }
}
await server.close();
process.exit(0);
