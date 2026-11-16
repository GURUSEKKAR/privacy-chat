import { defineConfig } from "vite";
import { readFileSync, existsSync } from "node:fs";
import { Agent } from "node:https";
import { fileURLToPath } from "node:url";
const certificate = fileURLToPath(
  new URL("../.local/tls/localhost.crt", import.meta.url),
);
const localBackend = !process.env.SECRETCHAT_DEV_BACKEND;
const backend = process.env.SECRETCHAT_DEV_BACKEND || "https://localhost:18443";
const agent =
  localBackend && existsSync(certificate)
    ? new Agent({ ca: readFileSync(certificate) })
    : undefined;
export default defineConfig({
  build: {
    target: "es2022",
    rollupOptions: {
      external: [
        "@hpke/dhkem-x448",
        "@hpke/chacha20poly1305",
        "@hpke/ml-kem",
        "@hpke/hybridkem-x-wing",
      ],
    },
  },
  server: {
    host: "127.0.0.1",
    strictPort: true,
    port: 5173,
    proxy: {
      "/api": {
        target: backend,
        agent,
        changeOrigin: true,
      },
      "/ws": {
        target: backend.replace(/^http/, "ws"),
        agent,
        changeOrigin: true,
        rewriteWsOrigin: localBackend,
        ws: true,
      },
      "/actuator/health": {
        target: backend,
        agent,
        changeOrigin: true,
      },
    },
  },
});
