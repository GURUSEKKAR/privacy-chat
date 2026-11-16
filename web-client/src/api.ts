import type { Tokens } from "./types";
export class Api {
  tokens: Tokens | null = null;
  private refreshing: Promise<Tokens> | null = null;
  constructor(readonly origin: string = location.origin) {}
  async call<T>(
    path: string,
    method = "GET",
    body?: unknown,
    auth = true,
  ): Promise<T> {
    if (auth && !this.tokens) throw new Error("Sign in first.");
    if (auth && Date.parse(this.tokens!.accessExpiresAt) < Date.now() + 20_000)
      await this.refresh();
    const response = await fetch(this.origin + path, {
      method,
      headers: {
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(auth
          ? { Authorization: "Bearer " + this.tokens!.accessToken }
          : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
      credentials: "omit",
      signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) {
      const error = await response
        .json()
        .catch(() => ({ message: "Server request failed." }));
      throw new Error(error.message || `Request failed (${response.status}).`);
    }
    return response.status === 204
      ? (undefined as T)
      : ((await response.json()) as T);
  }
  refresh(): Promise<Tokens> {
    if (!this.tokens)
      return Promise.reject(new Error("Sign in before checking approval."));
    if (!this.refreshing)
      this.refreshing = this.call<Tokens>(
        "/api/v1/auth/refresh",
        "POST",
        { refreshToken: this.tokens.refreshToken },
        false,
      )
        .then((t) => ((this.tokens = t), t))
        .finally(() => {
          this.refreshing = null;
        });
    return this.refreshing;
  }
}
