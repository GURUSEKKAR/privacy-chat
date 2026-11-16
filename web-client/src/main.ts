import "./style.css";
import { readTheme, storeTheme, type Theme } from "./theme";
import QRCode from "qrcode";
import { ChatClient, type Displayed } from "./client";
import { FingerprintChangedError } from "./mls";
import { validateAccount } from "./bytes";
import type { Room, Invite, Member, Device } from "./types";
let theme: Theme = readTheme(localStorage);
function applyTheme() {
  document.documentElement.dataset.theme = theme;
  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (icon)
    icon.href = theme === "terminal" ? "/favicon.svg" : "/favicon-normal.svg";
  document
    .querySelectorAll<HTMLButtonElement>("[data-theme-toggle]")
    .forEach((button) => {
      button.textContent = `Theme: ${theme === "terminal" ? "Terminal" : "Normal"}`;
      button.setAttribute(
        "aria-label",
        `Switch to ${theme === "terminal" ? "Normal" : "Terminal"} theme`,
      );
      button.setAttribute("aria-pressed", String(theme === "terminal"));
    });
}
applyTheme();
document.addEventListener("click", (event) => {
  if (
    !(event.target instanceof Element) ||
    !event.target.closest("[data-theme-toggle]")
  )
    return;
  theme = theme === "terminal" ? "normal" : "terminal";
  storeTheme(theme, localStorage);
  applyTheme();
});
const app = document.querySelector<HTMLDivElement>("#app")!;
new MutationObserver(applyTheme).observe(app, { childList: true });
const apiOrigin = (
  import.meta.env.VITE_API_ORIGIN?.trim() || location.origin
).replace(/\/+$/, "");
const client = new ChatClient(apiOrigin);
client.api.onUnauthorized = () => {
  client.messages.clear();
  client.mls?.clear();
  client.mls = null;
  client.api.tokens = null;
  void client.socket?.deactivate();
  client.sessionId = null;
  selected = null;
  login();
  status(
    "This session ended or was replaced by another sign-in. Sign in again to continue.",
  );
};
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
  '<div class="brand"><span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 64 64" fill="none" stroke="currentColor" stroke-width="5"><circle cx="32" cy="32" r="23"/><path d="M8 56 56 8"/></svg></span><span>Null<small>Nothing stays forever.</small></span></div><button type="button" data-theme-toggle>Theme</button>';
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
  app.innerHTML = `<main class="landing auth-only"><header>${brand}</header><section class="auth-card"><div class="tabs"><button id="register-tab" type="button">Create account</button><button id="login-tab" type="button">Sign in</button></div><h2 id="form-title">Create account</h2><p class="muted" id="form-description">Set up your authenticator after creating your account.</p><form id="auth-form" novalidate><label>Username<input id="username" name="username" autocomplete="username" maxlength="32" required></label><label>Password<div class="password-wrap"><input id="password" type="password" name="password" autocomplete="new-password" maxlength="128" required><button id="show-password" type="button" aria-label="Show password">Show</button></div></label><p id="password-count" class="hint" aria-live="polite"></p><p id="auth-status" class="status" role="alert"></p><button id="authenticate" class="primary form-action" type="submit">Create account</button></form></section></main>`;
  const password = document.querySelector<HTMLInputElement>("#password")!,
    user = document.querySelector<HTMLInputElement>("#username")!;
  const deviceName = /Android/i.test(navigator.userAgent)
    ? "Android browser"
    : /iPhone|iPad/i.test(navigator.userAgent)
      ? "iPhone / iPad browser"
      : /Mac/i.test(navigator.userAgent)
        ? "Mac browser"
        : "Web browser";
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
      mode === "register" ? "Create account" : "Sign in";
    document.querySelector("#form-description")!.textContent =
      mode === "register"
        ? "Create your account, then scan a QR code with Google Authenticator."
        : "Verify your authenticator code after entering your password.";
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
      deviceName,
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
          deviceName,
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
  if (
    t.deviceStatus.startsWith("MFA_") ||
    t.deviceStatus === "SESSION_CONFLICT"
  ) {
    await authenticator();
    return;
  }
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
  app.innerHTML = `<div class="shell"><header class="app-header">${brand}<div class="header-actions"><span><i class="online"></i>${h(t.username)}</span><button id="reconnect">Reconnect</button><button id="account">Account &amp; session</button><button id="devices">Devices</button><button id="logout">Sign out</button></div></header><div><div id="app-status" class="global-status" role="alert"></div><div class="workspace"><aside class="sidebar"><div class="section-head"><h3>Your rooms</h3><button id="refresh" aria-label="Refresh rooms">↻</button></div><div id="rooms" class="room-list"></div><button id="new-room" class="primary">+ Create group</button><button id="join-room">Join group</button><h3>Invitations</h3><div id="invitations"><small>No pending invitations.</small></div></aside><section id="chat" class="chat"></section><aside id="details" class="details"></aside></div></div></div>`;
  bind("refresh", refreshLists);
  bind("new-room", async () => newRoom());
  bind("devices", devices);
  bind("account", account);
  bind("join-room", joinGroup);
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
  const [rooms, invites] = await Promise.all([
    client.rooms(),
    client.api.call<Invite[]>("/api/v1/invites"),
  ]);
  allRooms = rooms;
  const container = document.querySelector("#rooms");
  if (container) {
    container.innerHTML = allRooms.length
      ? ""
      : "<small>Create a room or accept an invitation to get started.</small>";
    for (const r of allRooms) {
      const button = document.createElement("button");
      button.className = "room-card" + (selected?.id === r.id ? " active" : "");
      button.innerHTML = `<strong>${h(r.name || r.displayCode || r.id.slice(0, 8))}</strong><small>${h(r.myRole === "OWNER" ? "Owner" : "Member")} · ${h(r.myMembershipState === "PENDING_KEYS" ? "Waiting for owner" : r.state === "ACTIVE" ? "Ready" : r.state.replaceAll("_", " ").toLowerCase())}</small>`;
      button.onclick = () => void action(button, async () => choose(r));
      container.append(button);
    }
  }
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
              "Join request accepted. The owner must add you to the encrypted group before you can enter.",
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
  room = await client.api.call<Room>(`/api/v1/rooms/${room.id}`);
  if (room.myMembershipState === "PENDING_KEYS") {
    status(
      "Your invitation is accepted. Ask the owner to select Add accepted members and complete any fingerprint review. You can enter once your membership is ready.",
    );
    return;
  }
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
  box.innerHTML = `<header class="chat-head"><div><h2>${h(selected.name || selected.displayCode || selected.id.slice(0, 8))}</h2><small id="room-timer">Temporary conversation</small></div><button id="leave">Leave room</button></header><div id="messages" class="messages" role="log" aria-label="Messages"></div><form id="compose" class="composer"><div class="compose-row"><textarea id="message-input" aria-label="Message" placeholder="Write something temporary…" rows="1" maxlength="32000" ${client.sessionId ? "" : "disabled"}></textarea><button class="primary" type="submit" ${client.sessionId ? "" : "disabled"}>Send</button></div><small>Encrypted on this browser. Messages disappear at expiry or when you leave.</small></form>`;
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
      '<section><div class="tag">Your privacy</div><h3>Only the people you invite.</h3><p>New sign-ins require your authenticator code. Replacing a session signs out the previous browser.</p><p>Chat state is held in memory. Closing or refreshing the page loses room keys.</p></section>';
    return;
  }
  const room = selected;
  const members = await client.members(room.id).catch(() => [] as Member[]);
  if (selected?.id !== room.id) return;
  box.innerHTML = `<section><div class="tag">Room details</div><h3>Members</h3><div id="members"></div>${room.myRole === "OWNER" ? `<p>Group code: ${h(room.displayCode)}</p><p>Your public device fingerprint</p><div class="fingerprint">${h(room.ownerDeviceFingerprint)}</div>` : ""}</section><section id="owner-tools"></section><section><h3>Before you leave</h3><p>Leaving clears the messages shown in this session. Viewed messages cannot be retrieved in a new session.</p></section>`;
  const list = box.querySelector("#members")!;
  for (const m of members.filter((m) => m.state === "ACTIVE")) {
    const row = document.createElement("div");
    row.className = "member";
    row.innerHTML = `<strong>${h(m.username)} ${room.myRole === "OWNER" && m.role === "OWNER" ? "· owner" : ""}</strong>${room.myRole === "OWNER" ? `<span class="fingerprint">${h(m.deviceFingerprint)}</span>` : ""}`;
    if (room.myRole === "OWNER" && m.role !== "OWNER") {
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "danger";
      remove.textContent = "Remove";
      remove.onclick = () =>
        formDialog(
          "Remove member?",
          `<p>Remove ${h(m.username)} and rotate the group encryption keys? Remaining members stay in the group.</p>`,
          async () => {
            await client.removeMember(room, m.userId);
            await refreshLists();
            status("Member removed. Encryption keys rotated.");
          },
          "Remove member",
        );
      row.append(remove);
    }
    list.append(row);
  }
  if (room.myRole === "OWNER") {
    box.querySelector("#owner-tools")!.innerHTML =
      '<h3>Owner controls</h3><button id="group-settings">Group settings</button><button id="invite">Invite someone</button><button id="add-members">Add accepted members</button><button id="reset" class="danger">Reset room</button>';
    bind("group-settings", async () => groupSettings(room));
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
      await addAcceptedMembers(room);
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
async function addAcceptedMembers(room: Room) {
  try {
    await client.addAccepted(room);
    await refreshLists();
    status("Accepted members added. They can now select the group to enter.");
  } catch (error) {
    if (!(error instanceof FingerprintChangedError)) throw error;
    const invites = await client.api.call<Invite[]>(
      `/api/v1/rooms/${room.id}/invites`,
    );
    const invite = invites.find(
      (i) => i.inviteeUserId === error.userId && i.status === "ACCEPTED",
    );
    if (!invite || invite.acceptedDeviceFingerprint !== error.current)
      throw new Error("Invitation changed. Refresh and review again.");
    formDialog(
      "Review member’s new device",
      `<p>${h(invite.inviteeUsername)} is using a different device key. Ask them to open Devices and compare their current fingerprint through a trusted channel.</p><p>Previously saved fingerprint</p><p class="fingerprint">${h(error.previous)}</p><p>Current fingerprint</p><p class="fingerprint">${h(error.current)}</p><label><input type="checkbox" name="verified" value="yes" required> I compared the current fingerprint with ${h(invite.inviteeUsername)} and it matches.</label>`,
      async (data) => {
        if (data.get("verified") !== "yes")
          throw new Error(
            "Verify the fingerprint before approving the changed key.",
          );
        const latest = await client.api.call<Invite[]>(
          `/api/v1/rooms/${room.id}/invites`,
        );
        if (
          !latest.some(
            (i) =>
              i.id === invite.id &&
              i.status === "ACCEPTED" &&
              i.acceptedDeviceFingerprint === error.current,
          )
        )
          throw new Error(
            "Invitation changed during review. Refresh and try again.",
          );
        await client.mls!.trustChangedFingerprint(
          error.userId,
          error.previous,
          error.current,
        );
        await addAcceptedMembers(room);
      },
      "Trust verified device & add member",
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
    '<label>Group name<input name="name" required maxlength="80" placeholder="e.g. Weekend plans"></label><label>Message lifetime (minutes)<input name="ttl" type="number" min="1" max="1440" value="10" required></label><label>Room reset interval (minutes)<input name="reset" type="number" min="1" max="10080" value="60" required></label><label>Optional room password<input name="password" type="password" minlength="8" maxlength="128"></label>',
    async (data) => {
      const roomPassword = String(data.get("password") || "") || null;
      const room = await client.create(
        Number(data.get("ttl")) * 60,
        Number(data.get("reset")) * 60,
        roomPassword,
        String(data.get("name") || "").trim(),
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
    card.innerHTML = `<p><strong>${h(d.name)}</strong> · ${h(d.status.toLowerCase())}${d.id === own.deviceId ? " · This browser" : ""}</p><div class="fingerprint">${h(d.fingerprint)}</div>`;
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
setInterval(() => void poll(), 15000);
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

interface MfaState {
  status: string;
  previousSessions: {
    id: string;
    deviceName: string;
    signedInAt: string;
    expiresAt: string;
  }[];
  recoveryCodes: string[];
}
async function finishAuthentication() {
  await client.api.refresh();
  await client.online();
  await signedIn();
}
async function authenticator(state?: MfaState) {
  const t = client.api.tokens!;
  const setup = t.deviceStatus === "MFA_SETUP";
  if (t.deviceStatus === "MFA_SETUP_APPROVAL") {
    app.innerHTML = `<main class="landing"><header>${brand}</header><section class="pending-wrap"><h2>Set up your authenticator on your original device.</h2><p>This account was created before authenticator setup. Sign in on the original approved browser or app first to secure it. Then sign in here with your authenticator code.</p><p id="auth-status" class="status"></p><button id="logout">Sign out</button></section></main>`;
    bind("logout", async () => {
      await client.logout();
      login();
    });
    return;
  }
  if (!state && t.deviceStatus === "SESSION_CONFLICT")
    state = await client.api.call<MfaState>("/api/v1/auth/mfa");
  const conflict = state?.status === "SESSION_CONFLICT";
  if (state?.recoveryCodes.length) {
    // This one-time screen is the only place recovery codes are exposed. No local persistence.
    app.innerHTML = `<main class="landing"><header>${brand}</header><section class="pending-wrap"><h2>Save your recovery codes.</h2><p>Each code can replace an authenticator code once. Store these somewhere private outside this browser. They will not be shown again.</p><pre class="fingerprint">${state.recoveryCodes.map(h).join("\n")}</pre><label><input id="codes-saved" type="checkbox"> I saved my recovery codes.</label><p id="auth-status" class="status"></p><button id="saved" class="primary">Continue</button></section></main>`;
    bind("saved", async () => {
      if (!document.querySelector<HTMLInputElement>("#codes-saved")!.checked)
        throw new Error("Save your recovery codes before continuing.");
      const next = { ...state!, recoveryCodes: [] };
      if (conflict) await authenticator(next);
      else await finishAuthentication();
    });
    return;
  }
  if (conflict) {
    app.innerHTML = `<main class="landing"><header>${brand}</header><section class="pending-wrap"><h2>Another session is active.</h2><p>You verified your authenticator. Only one session can access this account at a time. Continuing will sign out the previous session and close its chat connection.</p>${state!.previousSessions.map((s) => `<div class="card"><strong>${h(s.deviceName)}</strong><p>Signed in ${h(new Date(s.signedInAt).toLocaleString())}</p></div>`).join("")}<p id="auth-status" class="status"></p><div class="inline-actions"><button id="replace-session" class="primary">Revoke previous session &amp; sign in here</button><button id="logout">Cancel and sign out</button></div></section></main>`;
    bind("replace-session", async () => {
      await client.api.call("/api/v1/auth/mfa/replace-session", "POST");
      await finishAuthentication();
    });
    bind("logout", async () => {
      await client.logout();
      login();
    });
    return;
  }
  app.innerHTML = `<main class="landing"><header>${brand}</header><section class="pending-wrap"><div class="tag">${setup ? "Secure your account" : "Second verification"}</div><h2>${setup ? "Set up Google Authenticator." : "Enter your authenticator code."}</h2><p>${setup ? "Open Google Authenticator, tap +, and scan this QR code. Then enter the 6-digit code shown for Null." : "Open your authenticator app and enter the current 6-digit code. You can also use an unused recovery code."}</p>${setup ? '<canvas id="auth-qr" aria-label="Authenticator setup QR code"></canvas><details><summary>Cannot scan? Enter this setup key manually</summary><p id="setup-secret" class="fingerprint"></p></details>' : ""}<form id="mfa-form"><label>${setup ? "6-digit code" : "Authenticator or recovery code"}<input id="mfa-code" autocomplete="one-time-code" ${setup ? 'inputmode="numeric" pattern="[0-9]{6}" maxlength="6"' : 'maxlength="64"'} required></label><p id="auth-status" class="status" role="alert"></p><div class="inline-actions"><button id="verify-mfa" class="primary" type="submit">Verify &amp; continue</button><button id="logout" type="button">Sign out</button></div></form></section></main>`;
  bind("logout", async () => {
    await client.logout();
    login();
  });
  const submit = document.querySelector<HTMLButtonElement>("#verify-mfa")!;
  if (setup) {
    submit.disabled = true;
    try {
      const qr = await client.api.call<{ secret: string; uri: string }>(
        "/api/v1/auth/mfa/setup",
        "POST",
      );
      await QRCode.toCanvas(
        document.querySelector<HTMLCanvasElement>("#auth-qr")!,
        qr.uri,
        { width: 256, margin: 4 },
      );
      document.querySelector("#setup-secret")!.textContent = qr.secret;
      submit.disabled = false;
    } catch (error) {
      status(
        error instanceof Error
          ? error.message
          : "Unable to load authenticator setup.",
      );
    }
  }
  document.querySelector<HTMLFormElement>("#mfa-form")!.onsubmit = (event) => {
    event.preventDefault();
    const code = document.querySelector<HTMLInputElement>("#mfa-code")!;
    void action(submit, async () => {
      const result = await client.api.call<MfaState>(
        "/api/v1/auth/mfa/verify",
        "POST",
        { code: code.value.trim() },
      );
      code.value = "";
      if (result.recoveryCodes.length || result.status === "SESSION_CONFLICT")
        await authenticator(result);
      else await finishAuthentication();
    });
  };
}
async function account() {
  const t = client.api.tokens!;
  formDialog(
    "Account & session",
    `<p><strong>Username:</strong> ${h(t.username)}</p><p><strong>User ID:</strong> ${h(t.userId)}</p><p><strong>Current device ID:</strong> ${h(t.deviceId)}</p><p>Authenticator verification is required at every new sign-in. One session is active at a time. Devices can be reviewed from the Devices button in the header.</p>`,
    async () => {},
    "Done",
  );
}
async function joinGroup() {
  const invites = await client.api.call<Invite[]>("/api/v1/invites");
  formDialog(
    "Join a group",
    `<p>Enter a group code from an invitation. Ask the owner to invite <strong>${h(client.api.tokens!.username)}</strong> first. After accepting, the owner adds your device to the encrypted group.</p><label>Group code<input name="code" maxlength="32" required></label><label>Group password, if required<input name="password" type="password" maxlength="128"></label><p>Invitations waiting for you: ${invites.length ? invites.map((i) => h(i.roomDisplayCode)).join(", ") : "None yet"}</p>`,
    async (data) => {
      const code = String(data.get("code") || "").trim();
      const password = String(data.get("password") || "") || null;
      const room = allRooms.find(
        (r) => r.displayCode.toLowerCase() === code.toLowerCase(),
      );
      if (room) {
        await choose(room);
        return;
      }
      const invite = invites.find(
        (i) => i.roomDisplayCode.toLowerCase() === code.toLowerCase(),
      );
      if (!invite)
        throw new Error(
          "No invitation for that code. Ask the owner to invite your username, then try again.",
        );
      await client.accept(invite, password);
      await refreshLists();
      status(
        "Invitation accepted. Wait for the owner to add you to the encrypted group, then select it to enter.",
      );
    },
    "Join group",
  );
}

async function groupSettings(room: Room) {
  formDialog(
    "Group settings",
    `<label>Group name<input name="name" maxlength="80" required value="${h(room.name || room.displayCode)}"></label><label>Message lifetime (minutes)<input name="ttl" type="number" min="1" max="1440" required step="any" value="${room.messageTtlSeconds / 60}"></label><label>Room reset interval (minutes)<input name="reset" type="number" min="1" max="10080" required step="any" value="${room.resetIntervalSeconds / 60}"></label><label>Password<select name="passwordAction"><option value="keep">Keep current password setting</option><option value="set">Set a new password</option><option value="remove">Remove password</option></select></label><label>New password (only when setting a new password)<input name="password" type="password" minlength="8" maxlength="128" autocomplete="new-password"></label><p>Message lifetime applies to new messages. Changing the reset interval starts a new countdown for an active, unexpired room. If a reset is pending, these settings apply when the owner reopens the room to finish it. Password changes affect future group entry.</p>`,
    async (data) => {
      const passwordAction = String(data.get("passwordAction"));
      const password = String(data.get("password") || "");
      if (passwordAction === "set" && password.length < 8)
        throw new Error(
          "Enter a new group password with at least 8 characters.",
        );
      const updated = await client.api.call<Room>(
        "/api/v1/rooms/" + room.id,
        "PUT",
        {
          name: String(data.get("name") || "").trim(),
          messageTtlSeconds: Number(data.get("ttl")) * 60,
          resetIntervalSeconds: Number(data.get("reset")) * 60,
          changePassword: passwordAction !== "keep",
          roomPassword: passwordAction === "set" ? password : null,
        },
      );
      selected = updated;
      if (client.room?.id === updated.id) client.room = updated;
      renderChat();
      renderMessages();
      await refreshLists();
      status("Group settings saved.");
    },
    "Save settings",
  );
}
