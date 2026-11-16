import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
const child = spawn(
  process.execPath,
  [fileURLToPath(new URL("./runner.mjs", import.meta.url))],
  { stdio: ["pipe", "pipe", "inherit"] },
);
const lines = createInterface({ input: child.stdout })[Symbol.asyncIterator]();
async function call(command, data = {}) {
  child.stdin.write(JSON.stringify({ command, ...data }) + "\n");
  const result = await Promise.race([
    lines.next(),
    new Promise((_, reject) => {
      const t = setTimeout(
        () => reject(new Error("Local website check timed out")),
        25000,
      );
      t.unref();
    }),
  ]);
  if (result.done) throw new Error("Web client stopped early");
  const reply = JSON.parse(result.value);
  if (!reply.ok) throw new Error(reply.error);
  return reply.value;
}
try {
  const user = "webcheck-" + crypto.randomUUID().slice(0, 16);
  const auth = await call("auth", {
    origin: "http://localhost:5173",
    user,
    password: crypto.randomUUID(),
  });
  if (auth.deviceStatus === "MFA_SETUP") {
    const verified = await call("mfaEnrollForCheck");
    assert.equal(verified.recoveryCount, 10);
  }
  const room = await call("create");
  await call("enter", { room: room.id });
  await call("send", { text: "Local proxy encryption verification" });
  const messages = await call("poll");
  assert.ok(
    messages.some((m) => m.text === "Local proxy encryption verification"),
  );
  await call("leave");
  await call("enter", { room: room.id });
  assert.equal((await call("poll")).length, 0);
  await call("stop");
  console.log(
    "PASS: website proxy, verified backend TLS, browser WebSocket ticket, encrypted send and view-once re-entry",
  );
} finally {
  child.kill();
}
