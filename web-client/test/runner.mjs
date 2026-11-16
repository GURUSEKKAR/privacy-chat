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
    };
  },
  async create(p) {
    selected = await client.create(600, 3600, p.password ?? null);
    return selected;
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
