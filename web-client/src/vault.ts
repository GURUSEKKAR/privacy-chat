import { b64, unb64, utf8, text } from "./bytes";
export interface Identity {
  publicKey: Uint8Array;
  signKey: Uint8Array;
  deviceId?: string;
  userId?: string;
}
interface Saved {
  version: 1;
  salt: string;
  iv: string;
  data: string;
  iterations: number;
}
async function wrapping(
  password: string,
  salt: Uint8Array,
): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey(
    "raw",
    utf8.encode(password),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: new Uint8Array(salt),
      iterations: 600000,
    },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}
export async function loadIdentity(
  user: string,
  password: string,
): Promise<Identity | null> {
  const raw = localStorage.getItem("privacy-chat.identity." + user);
  if (!raw) return null;
  try {
    const saved = JSON.parse(raw) as Saved;
    if (saved.version !== 1 || saved.iterations !== 600000)
      throw new Error("Unsupported vault");
    const key = await wrapping(password, unb64(saved.salt));
    const clear = new Uint8Array(
      await crypto.subtle.decrypt(
        {
          name: "AES-GCM",
          iv: new Uint8Array(unb64(saved.iv)),
          additionalData: utf8.encode(user),
        },
        key,
        new Uint8Array(unb64(saved.data)),
      ),
    );
    try {
      const d = JSON.parse(text.decode(clear));
      return { ...d, publicKey: unb64(d.publicKey), signKey: unb64(d.signKey) };
    } finally {
      clear.fill(0);
    }
  } catch {
    throw new Error(
      "Unable to unlock this browser’s device key. Check your password. Your saved key has been kept.",
    );
  }
}
export async function saveIdentity(
  user: string,
  password: string,
  id: Identity,
): Promise<void> {
  const salt = crypto.getRandomValues(new Uint8Array(16)),
    iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await wrapping(password, salt);
  const clear = utf8.encode(
    JSON.stringify({
      ...id,
      publicKey: b64(id.publicKey),
      signKey: b64(id.signKey),
    }),
  );
  try {
    const data = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData: utf8.encode(user) },
      key,
      clear,
    );
    localStorage.setItem(
      "privacy-chat.identity." + user,
      JSON.stringify({
        version: 1,
        salt: b64(salt),
        iv: b64(iv),
        data: b64(new Uint8Array(data)),
        iterations: 600000,
      }),
    );
  } finally {
    clear.fill(0);
  }
}
