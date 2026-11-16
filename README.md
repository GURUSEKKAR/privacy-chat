# Secret Chat Room

Java 21, Spring Boot, PostgreSQL, JavaFX and TypeScript browser implementation of the supplied workflow. The implementation includes device approval, invitations, encrypted live/offline delivery, viewing sessions, expiry, owner-only reset/rekey, desktop recovery and operational checks. Independent security review remains required before advertising production-safe E2EE.

## Build and test

Install JDK 21, Maven 3.9+, Node.js 24 and Docker. Start Docker before running:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; select JDK 21 on other systems
npm ci --prefix web-client --ignore-scripts
npm run build --prefix web-client
npm test --prefix web-client
mvn clean verify -Dsecretchat.web=true
mvn spotless:check
# Actual desktop acceptance, with an available display:
mvn verify -Dsecretchat.gui=true
# Native macOS Keychain acceptance:
mvn verify -Dsecretchat.keychain.integration=true
```

Tests create disposable PostgreSQL 17 containers and migrate an empty database. They cover authentication, device trust, message lifecycle, room reset, MLS membership changes and real clients using HTTP/STOMP. No pre-existing database is needed. Artifacts appear in each module's `target` directory; the runnable backend is `backend/target/backend-0.1.0-SNAPSHOT-exec.jar`.

## Run locally

```sh
cp .env.example .env
# Set a unique SECRETCHAT_DB_PASSWORD in .env.
docker compose up -d postgres
set -a
. ./.env
set +a
java -jar backend/target/backend-0.1.0-SNAPSHOT-exec.jar
```

Health: `http://localhost:8080/actuator/health`. In a second terminal:

```sh
mvn install -DskipTests
mvn -pl desktop-client javafx:run
```

Use distinct data folders for demo devices: `mvn -pl desktop-client javafx:run -Dsecretchat.dataDir=/private/tmp/secretchat-a`. Override the default server with `-Dsecretchat.server=https://your-server`. The default macOS data folder uses Keychain-backed encryption when available; other platforms and custom demo folders use a password-encrypted vault. MLS room state is memory-only. A restarted owner uses **Reset selected room** and re-invites members; members need a fresh invitation if their group state was lost. Use **Reconnect** after a network interruption.

Register A, B and C. A creates a room, invites B/C by username, and approves accepted invitations. Enter the room on A/B, send a message, leave/rejoin on B, then enter on C. B's consumed message stays unavailable; an eligible offline C can catch up. Compare device fingerprints out of band before trusting them. Reset removes non-owners and requires owner rekey before the next cycle.

## Structure

- `protocol-contracts`: validated request and event records.
- `backend`: authentication, device approval, invitations, recipient snapshots, encrypted relay, receipt and reset workers; Flyway migrations.
- `desktop-client`: JavaFX screens, HTTP/STOMP client, MLS and encrypted local identity vault.
- `e2e-tests`: real headless clients against Spring Boot and PostgreSQL.
- `docs`: decisions, threat model, API overview and operations.

The original `workflow.docx` and updated `todo.docx` are in the parent folder. `TODO.md` records verification evidence and open release work.

## Secure local deployment

```sh
sh scripts/prepare-local-tls.sh
docker compose -p secretchat-acceptance --env-file .local/tls/stack.env -f compose.secure.yaml up --build -d
curl --cacert .local/tls/localhost.crt https://localhost:18443/actuator/health
mvn install -DskipTests
mvn -pl desktop-client -Plocal-tls javafx:run
```

The stack uses a 30-day localhost certificate, TLS 1.3, an unprivileged application container, a private database network and generated credentials in ignored `.local/tls`. The desktop profile trusts the generated certificate. For another client append `-Dsecretchat.dataDir=/private/tmp/secretchat-b`. Remote service requires a CA-issued certificate and managed secrets.

```sh
# Test the packaged deployment with actual HTTPS/WSS clients:
mvn verify -Dtest=DeploymentSmokeTest -Dsurefire.failIfNoSpecifiedTests=false -Dsecretchat.deployment=true
sh scripts/verify-restore.sh
```

Stop it with `docker compose -p secretchat-acceptance --env-file .local/tls/stack.env -f compose.secure.yaml down`. Its named database volume persists until explicitly removed.

## Security and operational verification

```sh
mvn com.github.spotbugs:spotbugs-maven-plugin:4.10.5.0:check -Dspotbugs.threshold=High
python3 scripts/audit-dependencies.py
set -a
. ./.local/tls/stack.env
set +a
python3.13 scripts/check-health.py --ca .local/tls/localhost.crt
```

Monitoring needs Python with TLS 1.3 support; the older LibreSSL in macOS system Python cannot connect. Its operations token is separate from account tokens. Linux GUI checks use `xvfb-run -a` and GTK, as configured in CI. Read the [user guide](docs/user-guide.md), [operations policy](docs/operations.md), and [verification evidence](docs/verification/acceptance.md).

## Desktop release candidate and review

```sh
python3 scripts/package-desktop.py --local-tls --replace
python3 scripts/build-review-bundle.py
python3 scripts/release-readiness.py
```

The macOS app and ZIP appear in `target/desktop`; the source review ZIP and readiness report appear in `target/release`. The app uses the local TLS certificate when packaged with `--local-tls`. `SECRETCHAT_SERVER_URL` and `SECRETCHAT_DATA_DIR` override its defaults. The bundle self-test loads all three screens and verifies signing and JSON support. Native bundle version 1.0.0 satisfies macOS packaging requirements; it does not signify production approval. Public distribution still needs signing and notarization.

The release checker intentionally fails until source-bound threat-model approval, an independent security report and successful hosted CI are recorded. See [review instructions](docs/release/REVIEW_REQUEST.md) and [implementation audit](docs/release/IMPLEMENTATION_AUDIT.md). Repository: [GURUSEKKAR/privacy-chat](https://github.com/GURUSEKKAR/privacy-chat).

HTTP development binds to loopback. The TLS container explicitly binds its container interface. Forwarded headers are ignored by default; enable them only behind a proxy that strips untrusted forwarding headers. Passwords use Argon2id with 19 MiB memory and two iterations; successful login upgrades older hashes. Authentication challenge and rate-limit storage have bounded capacity and fail closed when full.

## Website

The browser client supports registration, sign-in, device approval, invitations, rooms and MLS-encrypted temporary chat. It uses the same backend and interoperates with desktop clients. Tokens and chat state stay in memory; browser device keys are password-encrypted in local storage. Refreshing the page loses room keys, so the owner must reset the room and invite members again. A browser is a distinct device and needs approval if the account was created elsewhere.

After starting the secure Docker stack, run:

```sh
npm ci --prefix web-client --ignore-scripts
npm run build --prefix web-client
npm run dev --prefix web-client
```

Open **http://localhost:5173**. The local preview proxies only to the localhost backend, verifies its generated certificate, and binds to loopback. Run `sh scripts/prepare-local-tls.sh` before starting the preview. A rebuilt backend also serves the compiled website at **https://localhost:18443/**; this generated development certificate is not publicly trusted by browsers. Public deployment needs a trusted certificate, domain and hosted backend.

`npm test --prefix web-client` checks password boundaries, encrypted key storage, authenticated messages, tampering, replay and reset isolation. `mvn verify -Dsecretchat.web=true` additionally runs the TypeScript web SDK against the real Java backend and desktop MLS client. Browser UI interaction still needs visual acceptance on target browsers; programmatic SDK tests do not establish visual behavior.

The browser MLS library is ts-mls 1.6.4 (MIT), cipher suite 1. Unsupported optional cipher suites are not exposed. See its upstream security notice; independent review remains a release gate for the expanded browser implementation.

## Free Render + Vercel deployment

The production web frontend is a static Vite site on Vercel; the Java API and WebSocket endpoint run together as one Docker web service on Render. Vercel assigns the static site its own hosting and HTTPS, so it does not need a fixed port. The Render container binds to `0.0.0.0:$PORT` (the Blueprint defaults `PORT` to `10000`) and Render terminates public HTTPS/WSS. Do not set a Vercel `PORT`.

1. In Vercel, import this repository as a Vite project and set the project **Root Directory** to `web-client`. Deploy once to get the site's `https://<project>.vercel.app` origin; the API is not connected yet.
2. In Render, create a Blueprint from this repository and select the free plan for both resources. The root `render.yaml` creates the Docker API service and PostgreSQL database. When prompted for `SECRETCHAT_WEB_ORIGINS`, enter the exact Vercel origin from step 1. Render's first image build compiles the browser bundle and Java backend inside the Docker build.
3. Copy the actual Render API URL from its dashboard, then add it to Vercel as `VITE_API_ORIGIN` (for example `https://privacy-chat-api.onrender.com`, no trailing slash). Redeploy Vercel. If you add a custom domain, add its exact HTTPS origin, comma-separated, to Render's `SECRETCHAT_WEB_ORIGINS` and redeploy the API.
4. Open the Vercel URL and create an account with a password of at least 12 characters. The API can take about a minute to wake after inactivity on Render's free service.

The HTTP port is selected by Render's `PORT` environment variable, not a port number embedded in frontend code. `render.yaml` sets the default to `10000`; Render routes both HTTPS and WSS to that same port. For local Vite development, `SECRETCHAT_DEV_BACKEND` optionally changes the proxy target.

**Free-tier data limitation:** Render's free PostgreSQL is temporary: it expires 30 days after creation, has no backups, and the free web service can sleep after 15 minutes without inbound traffic. Treat this as a demo, export any data you need before database expiry, and do not use these free resources for accounts or data you need to preserve. Render describes these limitations in its [free service documentation](https://render.com/docs/free). Vercel Functions cannot host this app's long-lived WebSocket server; the browser connects directly to Render instead ([Vercel WebSocket limits](https://vercel.com/docs/limits#websockets)).
