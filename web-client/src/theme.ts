export type Theme = "terminal" | "normal";
export const THEME_STORAGE_KEY = "null.theme";
export function readTheme(storage?: Pick<Storage, "getItem">): Theme {
  try {
    return storage?.getItem(THEME_STORAGE_KEY) === "normal"
      ? "normal"
      : "terminal";
  } catch {
    return "terminal";
  }
}
export function storeTheme(theme: Theme, storage?: Pick<Storage, "setItem">) {
  try {
    storage?.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    /* Theme still works for this visit. */
  }
}
