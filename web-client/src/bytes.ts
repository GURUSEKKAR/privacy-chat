export const utf8 = new TextEncoder();
export const text = new TextDecoder("utf-8", { fatal: true });
export function b64(bytes: Uint8Array): string {
  let out = "";
  for (const n of bytes) out += String.fromCharCode(n);
  return btoa(out);
}
export function unb64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
}
export async function fingerprint(key: Uint8Array): Promise<string> {
  return Array.from(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", new Uint8Array(key)),
    ).slice(0, 20),
    (n) => n.toString(16).padStart(2, "0"),
  )
    .join("")
    .toUpperCase()
    .match(/.{4}/g)!
    .join(" ");
}
export function normalizeUser(value: string): string {
  return value.normalize("NFKC").trim().toLowerCase();
}
export function validateAccount(
  user: string,
  password: string,
  device: string,
  register: boolean,
): string | null {
  if (!/^[a-z0-9][a-z0-9_.-]{2,31}$/.test(normalizeUser(user)))
    return "Use a username with 3–32 letters, numbers, dots, underscores or hyphens. Start with a letter or number.";
  if (
    !password.trim() ||
    password.length > 128 ||
    (register && password.length < 12)
  )
    return `Password: ${password.length} characters entered. New accounts require 12–128 characters.`;
  if (!device.trim() || device.length > 64)
    return "Enter a device name with 1–64 characters.";
  return null;
}
