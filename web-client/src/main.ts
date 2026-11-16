import "./style.css";
import { ChatClient, type Displayed } from "./client";
import { validateAccount } from "./bytes";
import type { Room, Invite, Member, Device } from "./types";
const app = document.querySelector<HTMLDivElement>("#app")!;
const apiOrigin = (
  import.meta.env.VITE_API_ORIGIN?.trim() || location.origin
).replace(/\/+$/, "");
const client = new ChatClient(apiOrigin);
let mode: "register" | "login" = "register";
let selected: Room | null = null;
let allRooms: Room[] = [];
let polling = false;
const h = (value: unknown) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ]!,
  );
const brand =
  '<div class="brand"><img src="/favicon.svg" alt=""><span>Secret Chat Room</span></div>';
function status(message: string) {
  const node = document.querySelector<HTMLElement>("#auth-status,#app-status");
  if (node) node.textContent = message;
}
async function action(button: HTMLButtonElement, work: () => Promise<void>) {
  button.disabled = true;
  try {
    status("");
    await client.serialized(work);
  } catch (error) {
    status(
      error instanceof Error
        ? error.message
        : "Unable to complete this request.",
    );
  } finally {
    if (button.isConnected) button.disabled = false;
  }
}
function bind(id: string, work: () => Promise<void>) {
  const b = document.querySelector<HTMLButtonElement>("#" + id);
  if (b) b.onclick = () => void action(b, work);
}
function login() {
  app.innerHTML = `<main class="landing"><header class="top">${brand}<span class="secure-pill">Invitation only. Encrypted on your device.</span></header><div class="auth-layout"><section class="intro"><div class="tag">A little less permanent</div><h1>Good conversations.<br>Without the history.</h1><p>A private place for your people. Invite who belongs, choose how long messages stay, and let the rest disappear.</p><div class="features"><span>Messages encrypted before they leave your browser</span><span>Device approval keeps unfamiliar devices out</span><span>Temporary history, with a countdown you control</span></div><small>Release candidate · Independent security review pending</small></section><section class="auth-card"><div class="tabs"><button id="register-tab" type="button">Create account</button><button id="login-tab" type="button">Sign in</button></div><h2 id="form-title">Make yourself at home.</h2><p class="muted" id="form-description">Your browser becomes your first trusted device.</p><form id="auth-form" novalidate><label>Username<input id="username" name="username" autocomplete="username" placeholder="e.g. guru.r.c" maxlength="32" required></label><label>Password<div class="password-wrap"><input id="password" type="password" name="password" autocomplete="new-password" maxlength="128" required><button id="show-password" type="button" aria-label="Show password">Show</button></div></label><p id="password-count" class="hint" aria-live="polite">0 characters · Use 12–128 for a new account</p><label>Device name<input id="device" name="device" value="My browser" maxlength="64" required></label><p id="auth-status" class="status" role="alert"></p><button id="authenticate" class="primary form-action" type="submit">Create account</button></form><div class="notice">This browser stores a password-encrypted device key. Your password and chat history are never saved by the website.</div></section></div><footer class="footer">Messages stay in memory and disappear at expiry or when you leave. People can still copy or screenshot what they see. Restarting the browser loses room keys; the owner must reset and invite members again.</footer></main>`;
  const password = document.querySelector<HTMLInputElement>("#password")!,
    user = document.querySelector<HTMLInputElement>("#username")!,
    device = document.querySelector<HTMLInputElement>("#device")!;
  const hint = document.querySelector<HTMLElement>("#password-count")!;
  const update = () => {
    hint.textContent = `${password.value.length} characters · ${mode === "register" ? "Use 12–128 for a new account" : "Enter your existing password"}`;
  };
  password.oninput = () => {
    update();
    status("");
  };
  const change = (next: typeof mode) => {
    mode = next;
    document
      .querySelector("#register-tab")!
      .classList.toggle("active", mode === "register");
    document
      .querySelector("#login-tab")!
      .classList.toggle("active", mode === "login");
    document.querySelector("#form-title")!.textContent =
      mode === "register" ? "Make yourself at home." : "Welcome back.";
    document.querySelector("#form-description")!.textContent =
      mode === "register"
        ? "Your browser becomes your first trusted device."
        : "A new browser needs approval from a trusted device.";
    document.querySelector("#authenticate")!.textContent =
      mode === "register" ? "Create account" : "Sign in";
    password.autocomplete =
      mode === "register" ? "new-password" : "current-password";
    update();
    status("");
  };
  document.querySelector<HTMLButtonElement>("#register-tab")!.onclick = () =>
    change("register");
  document.querySelector<HTMLButtonElement>("#login-tab")!.onclick = () =>
    change("login");
  document.querySelector<HTMLButtonElement>("#show-password")!.onclick = (
    event,
  ) => {
    const visible = password.type === "password";
    password.type = visible ? "text" : "password";
    (event.currentTarget as HTMLButtonElement).textContent = visible
      ? "Hide"
      : "Show";
  };
  change(mode);
  document.querySelector<HTMLFormElement>("#auth-form")!.onsubmit = (event) => {
    event.preventDefault();
    const error = validateAccount(
      user.value,
      password.value,
      device.value,
      mode === "register",
    );
    if (error) {
      status(error);
      return;
    }
    const b = document.querySelector<HTMLButtonElement>("#authenticate")!;
    void action(b, async () => {
      const p = password.value;
      try {
        await client.authenticate(
          user.value,
          p,
          device.value,
          mode === "register",
        );
        password.value = "";
        await signedIn();
      } finally {
        password.value = "";
        update();
      }
    });
  };
}
async function signedIn() {
  const t = client.api.tokens!;
  if (t.deviceStatus !== "APPROVED") {
    app.innerHTML = `<main class="landing"><header>${brand}</header><section class="pending-wrap"><div class="tag">One more step</div><h2>Approve this browser.</h2><p class="muted">You signed in as <strong>${h(t.username)}</strong>. On an already approved device, open Devices and approve the fingerprint below. Compare it through a separate trusted channel.</p><p class="fingerprint">${h(t.deviceFingerprint)}</p><p id="auth-status" class="status" role="alert"></p><div class="inline-actions"><button id="approval" class="primary">Check approval</button><button id="logout">Sign out</button></div></section></main>`;
    bind("approval", async () => {
      await client.checkApproval();
      if (client.api.tokens?.deviceStatus === "APPROVED") await signedIn();
      else status("Still awaiting approval from an existing trusted device.");
    });
    bind("logout", async () => {
      await client.logout();
      login();
    });
    return;
  }
  app.innerHTML = `<div class="shell"><header class="app-header">${brand}<div class="header-actions"><span><i class="online"></i>${h(t.username)}</span><button id="reconnect">Reconnect</button><button id="devices">Devices</button><button id="logout">Sign out</button></div></header><div><div id="app-status" class="global-status" role="alert"></div><div class="workspace"><aside class="sidebar"><div class="section-head"><h3>Your rooms</h3><button id="refresh" aria-label="Refresh rooms">↻</button></div><div id="rooms" class="room-list"></div><button id="new-room" class="primary">+ Create room</button><h3>Invitations</h3><div id="invitations"><small>No pending invitations.</small></div></aside><section id="chat" class="chat"></section><aside id="details" class="details"></aside></div></div></div>`;
  bind("refresh", refreshLists);
  bind("new-room", async () => newRoom());
  bind("devices", devices);
  bind("reconnect", async () => {
    await client.api.refresh();
    await client.connect();
    status("Connected.");
  });
  bind("logout", async () => {
    await client.logout();
    selected = null;
    login();
  });
  renderChat();
  await refreshLists();
}
async function refreshLists() {
  if (client.api.tokens?.deviceStatus !== "APPROVED") return;
  allRooms = await client.rooms();
  const container = document.querySelector("#rooms");
  if (container) {
    container.innerHTML = allRooms.length
      ? ""
      : "<small>Create a room or accept an invitation to get started.</small>";
    for (const r of allRooms) {
      const button = document.createElement("button");
      button.className = "room-card" + (selected?.id === r.id ? " active" : "");
      button.innerHTML = `<strong>${h(r.displayCode || r.id.slice(0, 8))}</strong><small>${h(r.myRole === "OWNER" ? "Owner" : "Member")} · ${h(r.state === "ACTIVE" ? "Ready" : r.state.replaceAll("_", " ").toLowerCase())}</small>`;
      button.onclick = () => void action(button, async () => choose(r));
      container.append(button);
    }
  }
  const invites = await client.api.call<Invite[]>("/api/v1/invites");
  const box = document.querySelector("#invitations");
  if (box) {
    box.innerHTML = "";
    for (const i of invites.filter((i) => i.status === "PENDING")) {
      const card = document.createElement("div");
      card.className = "card";
      card.innerHTML = `<p><strong>${h(i.roomDisplayCode || i.roomId.slice(0, 8))}</strong><br>You’ve been invited.</p><button class="accept">Accept</button><button class="decline">Decline</button>`;
      (card.querySelector(".accept") as HTMLButtonElement).onclick = () => {
        formDialog(
          "Accept invitation",
          '<label>Room password, if required<input name="password" type="password" maxlength="128"></label>',
          async (data) => {
            await client.accept(i, String(data.get("password") || "") || null);
            await refreshLists();
            status(
              "Invitation accepted. Ask the owner to add accepted members.",
            );
          },
        );
      };
      (card.querySelector(".decline") as HTMLButtonElement).onclick = (event) =>
        void action(event.currentTarget as HTMLButtonElement, async () => {
          await client.api.call("/api/v1/invites/" + i.id + "/decline", "POST");
          await refreshLists();
        });
      box.append(card);
    }
    if (!box.childElementCount)
      box.innerHTML = "<small>No pending invitations.</small>";
  }
  if (selected) {
    selected = allRooms.find((r) => r.id === selected!.id) || selected;
    await renderDetails();
  }
}
async function choose(room: Room) {
  if (selected?.id === room.id && client.sessionId) return;
  const enter = async (password: string | null) => {
    selected = room;
    renderChat();
    await client.enter(room, password);
    renderChat();
    renderMessages();
    await refreshLists();
  };
  if (room.passwordProtected)
    formDialog(
      "Enter room",
      '<label>Room password<input name="password" type="password" required></label>',
      async (data) => enter(String(data.get("password"))),
    );
  else await enter(null);
}
function renderChat() {
  const box = document.querySelector("#chat");
  if (!box) return;
  if (!selected) {
    box.innerHTML =
      '<div class="empty"><div><span class="symbol">◌</span><h2>A space for your people.</h2><p>Choose a room to start a conversation. Your messages are encrypted here, before they reach the server.</p></div></div>';
    void renderDetails();
    return;
  }
  box.innerHTML = `<header class="chat-head"><div><h2>${h(selected.displayCode || selected.id.slice(0, 8))}</h2><small id="room-timer">Temporary conversation</small></div><button id="leave">Leave room</button></header><div id="messages" class="messages" role="log" aria-label="Messages"></div><form id="compose" class="composer"><div class="compose-row"><textarea id="message-input" aria-label="Message" placeholder="Write something temporary…" rows="1" maxlength="32000" ${client.sessionId ? "" : "disabled"}></textarea><button class="primary" type="submit" ${client.sessionId ? "" : "disabled"}>Send</button></div><small>Encrypted on this browser. Messages disappear at expiry or when you leave.</small></form>`;
  bind("leave", async () => {
    await client.leave();
    selected = null;
    renderChat();
    await refreshLists();
  });
  document.querySelector<HTMLFormElement>("#compose")!.onsubmit = (event) => {
    event.preventDefault();
    const input =
        document.querySelector<HTMLTextAreaElement>("#message-input")!,
      button = document.querySelector<HTMLButtonElement>("#compose button")!;
    const value = input.value;
    void action(button, async () => {
      await client.send(value);
      input.value = "";
      renderMessages();
    });
  };
  void renderDetails();
}
function renderMessages() {
  const box = document.querySelector("#messages");
  if (!box) return;
  box.innerHTML = "";
  if (document.visibilityState !== "visible") return;
  const now = Date.now();
  for (const [id, m] of client.messages) {
    if (Date.parse(m.expiresAt) <= now) {
      client.messages.delete(id);
      continue;
    }
    const item = document.createElement("article");
    item.className = "message" + (m.own ? " own" : "");
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.textContent = m.cleartext;
    const info = document.createElement("small");
    info.textContent = `${m.own ? "You" : m.senderUsername} · ${Math.max(0, Math.ceil((Date.parse(m.expiresAt) - now) / 1000))}s left${m.acknowledged ? " · viewed" : ""}`;
    item.append(bubble, info);
    box.append(item);
  }
  box.scrollTop = box.scrollHeight;
  const timer = document.querySelector("#room-timer");
  if (timer && selected)
    timer.textContent = `Resets in ${Math.max(0, Math.ceil((Date.parse(selected.nextResetAt) - now) / 60000))} min · ${selected.messageTtlSeconds}s message lifetime`;
}
client.onDisplay = async (m: Displayed) => {
  if (
    document.visibilityState !== "visible" ||
    selected?.id !== m.roomId ||
    !client.sessionId
  )
    return false;
  renderMessages();
  await new Promise<void>((resolve) =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
  );
  return (
    document.visibilityState === "visible" &&
    selected?.id === m.roomId &&
    Date.parse(m.expiresAt) > Date.now()
  );
};
client.onWarning = (message) => {
  status(message);
  renderMessages();
};
client.onChange = () => {
  renderMessages();
};
async function renderDetails() {
  const box = document.querySelector("#details");
  if (!box) return;
  if (!selected) {
    box.innerHTML =
      '<section><div class="tag">Your privacy</div><h3>Only the people you invite.</h3><p>New devices need approval. Compare fingerprints through a separate channel before trusting a device.</p><p>Chat state is held in memory. Closing or refreshing the page loses room keys.</p></section>';
    return;
  }
  const room = selected;
  const members = await client.members(room.id).catch(() => [] as Member[]);
  if (selected?.id !== room.id) return;
  box.innerHTML = `<section><div class="tag">Room details</div><h3>Members</h3><div id="members"></div><p>Owner fingerprint</p><div class="fingerprint">${h(room.ownerDeviceFingerprint)}</div></section><section id="owner-tools"></section><section><h3>Before you leave</h3><p>Leaving clears the messages shown in this session. Viewed messages cannot be retrieved in a new session.</p></section>`;
  const list = box.querySelector("#members")!;
  for (const m of members.filter((m) => m.state === "ACTIVE")) {
    const row = document.createElement("div");
    row.className = "member";
    row.innerHTML = `<strong>${h(m.username)} ${m.role === "OWNER" ? "· owner" : ""}</strong><span class="fingerprint">${h(m.deviceFingerprint)}</span>`;
    list.append(row);
  }
  if (room.myRole === "OWNER") {
    box.querySelector("#owner-tools")!.innerHTML =
      '<h3>Owner controls</h3><button id="invite">Invite someone</button><button id="add-members">Add accepted members</button><button id="reset" class="danger">Reset room</button>';
    bind("invite", async () =>
      formDialog(
        "Invite someone",
        '<label>Username<input name="username" required maxlength="32" autocomplete="off"></label>',
        async (data) => {
          await client.api.call(
            "/api/v1/rooms/" + room.id + "/invites",
            "POST",
            { username: String(data.get("username")) },
          );
          status("Invitation sent.");
        },
      ),
    );
    bind("add-members", async () => {
      await client.addAccepted(room);
      await refreshLists();
      status("Accepted members added with fresh MLS keys.");
    });
    bind("reset", async () =>
      formDialog(
        "Reset this room?",
        '<p class="muted">This removes everyone except you and starts fresh encryption keys. All current messages are deleted. Members need new invitations.</p>',
        async () => {
          await client.reset(room);
          selected = null;
          renderChat();
          await refreshLists();
          status("Room reset. Invite members again for the new cycle.");
        },
        "Reset room",
      ),
    );
  }
}
function formDialog(
  title: string,
  fields: string,
  submit: (data: FormData) => Promise<void>,
  label = "Continue",
) {
  const dialog = document.createElement("dialog");
  dialog.innerHTML = `<form novalidate><h2>${h(title)}</h2><div class="dialog-fields">${fields}</div><p class="status" role="alert"></p><div class="dialog-buttons"><button type="button" id="cancel-dialog">Cancel</button><button class="primary" type="submit">${h(label)}</button></div></form>`;
  document.body.append(dialog);
  const close = () => {
    dialog.close();
    dialog.remove();
  };
  dialog.querySelector<HTMLButtonElement>("#cancel-dialog")!.onclick = close;
  dialog.addEventListener("cancel", () => dialog.remove());
  dialog.querySelector<HTMLFormElement>("form")!.onsubmit = (event) => {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    if (!form.reportValidity()) return;
    const button = form.querySelector<HTMLButtonElement>(
      "button[type=submit]",
    )!;
    button.disabled = true;
    void client
      .serialized(() => submit(new FormData(form)))
      .then(close)
      .catch((error) => {
        dialog.querySelector(".status")!.textContent =
          error instanceof Error
            ? error.message
            : "Unable to complete request.";
        button.disabled = false;
      });
  };
  dialog.showModal();
}
async function newRoom() {
  formDialog(
    "Create a private room",
    '<label>Message lifetime (minutes)<input name="ttl" type="number" min="1" max="1440" value="10" required></label><label>Room reset interval (minutes)<input name="reset" type="number" min="1" max="10080" value="60" required></label><label>Optional room password<input name="password" type="password" minlength="8" maxlength="128"></label>',
    async (data) => {
      const roomPassword = String(data.get("password") || "") || null;
      const room = await client.create(
        Number(data.get("ttl")) * 60,
        Number(data.get("reset")) * 60,
        roomPassword,
      );
      await refreshLists();
      selected = room;
      await client.enter(room, roomPassword);
      renderChat();
      renderMessages();
    },
    "Create room",
  );
}
async function devices() {
  const entries = await client.api.call<Device[]>("/api/v1/devices");
  const own = client.api.tokens!;
  const fields = `<p class="muted">This browser’s fingerprint</p><p class="fingerprint">${h(own.deviceFingerprint)}</p><div id="device-list"></div>`;
  formDialog("Your devices", fields, async () => {}, "Done");
  const list = document.querySelector("#device-list")!;
  for (const d of entries) {
    const card = document.createElement("div");
    card.className = "card";
    card.innerHTML = `<p><strong>${h(d.name)}</strong> · ${h(d.status.toLowerCase())}</p><div class="fingerprint">${h(d.fingerprint)}</div>`;
    if (d.status === "PENDING") {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = "Verify and approve";
      b.onclick = () => {
        formDialog(
          "Verify this fingerprint",
          '<p class="muted">Compare the fingerprint shown on the new device through a separate trusted channel, then type it here.</p><label>Verified fingerprint<input name="fingerprint" autocomplete="off" required></label>',
          async (data) => {
            const value = String(data.get("fingerprint"))
              .replace(/\s/g, "")
              .toLowerCase();
            if (value !== d.fingerprint.replace(/\s/g, "").toLowerCase())
              throw new Error("Fingerprint does not match the pending device.");
            await client.api.call(
              "/api/v1/devices/" + d.id + "/approve",
              "POST",
              { fingerprint: value },
            );
            status(
              "Device approved. On the other device, choose Check approval.",
            );
          },
        );
      };
      card.append(b);
    }
    if (d.status !== "REVOKED") {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "danger";
      b.textContent = "Revoke device";
      b.onclick = () =>
        formDialog(
          "Revoke this device?",
          `<p class="muted">${h(d.name)} will lose access. Revoking this browser also signs you out.</p>`,
          async () => {
            await client.api.call(
              "/api/v1/devices/" + d.id + "/revoke",
              "POST",
            );
            if (d.id === own.deviceId) {
              await client.logout().catch(() => {});
              selected = null;
              login();
            } else status("Device revoked.");
          },
          "Revoke",
        );
      card.append(b);
    }
    list.append(card);
  }
}
async function poll() {
  if (polling || client.api.tokens?.deviceStatus !== "APPROVED") return;
  polling = true;
  try {
    await client.serialized(async () => {
      if (client.sessionId) {
        await client.catchUp();
        if (document.visibilityState === "visible") await client.readPending();
        renderMessages();
      }
    });
  } catch (error) {
    status(
      error instanceof Error
        ? error.message
        : "Unable to refresh conversation.",
    );
  } finally {
    polling = false;
  }
}
setInterval(() => void poll(), 2000);
setInterval(() => {
  if (client.api.tokens?.deviceStatus === "APPROVED" && !polling)
    void client
      .serialized(refreshLists)
      .catch((error) => status(error.message));
}, 15000);
setInterval(renderMessages, 1000);
document.addEventListener("visibilitychange", () => {
  renderMessages();
  if (document.visibilityState === "visible") void poll();
});
window.addEventListener("pageshow", (event) => {
  if (event.persisted) location.reload();
});
window.addEventListener("pagehide", () => {
  client.messages.clear();
  client.mls?.clear();
  void client.socket?.deactivate();
});
if (!window.isSecureContext || !crypto.subtle) {
  app.innerHTML =
    '<main class="pending-wrap"><h1>Use a secure connection.</h1><p>Open this site through HTTPS or localhost. Browser encryption is unavailable on an insecure remote connection.</p></main>';
} else login();
