# Local acceptance evidence

Verified 16 November 2026. The MVP implementation passes local acceptance; independent production security approval is still outstanding.

The final verification run executed **52 Java tests, with zero failures, errors or skips**. The flags enable actual JavaFX screens, native macOS Keychain and the separately packaged TLS Docker application.

```sh
mvn -o verify spotless:check -Dsecretchat.gui=true -Dsecretchat.keychain.integration=true -Dsecretchat.deployment=true -Dsecretchat.web=true
```

| Suite | Tests |
| --- | ---: |
| AuthIntegrationTest | 8 |
| BrowserAccessTest | 3 |
| BrowserInteropTest | 2 |
| BoundedAuthenticationStateTest | 2 |
| ClientConfigurationTest | 1 |
| OperationsTest | 2 |
| AdversarialLifecycleTest | 9 |
| MessageLifecycleIntegrationTest | 4 |
| MlsLifecycleTest | 5 |
| OfficialMlsVectorsTest | 2 |
| LocalVaultTest | 4 |
| GuiAcceptanceTest | 1 |
| DeploymentSmokeTest | 1 |
| EncryptedChatTest | 5 |
| WebSocketAuthorizationTest | 3 |

## Coverage and artifacts

- PostgreSQL migrations, authentication/device boundaries, message snapshots, strict-live policy, exact TTL boundary, consumption and revocation.
- Concurrent receipts, conflicting commits, reset/send races and room-capacity enforcement across pending invitations.
- Real HTTP/STOMP delivery, offline catch-up, display failure and receipt retry, additional-device isolation, owner restart/reset recovery and stale reset notifications.
- Actual JavaFX registration/login, invitation acceptance, chat display, countdown, leave/rejoin, reconnect and owner reset. Screenshots are generated under e2e-tests/target/gui-evidence.
- Native Keychain create/read/delete plus password-encrypted fallback, persistent fingerprint mismatch detection and best-effort key disposal.
- Official MLS crypto-basics known answers and 21 passive-client Welcome/commit vectors for suite 1; original source revisions and hashes are in desktop-client/src/test/resources/mls/README.md.
- A built Docker image with TLS 1.3, certificate validation and an actual encrypted WSS conversation.
- Real dump/restore of an isolated database, restored expired-fixture exclusion and purge, encrypted-backup age selection, and aggregate monitoring checks.

Static analysis uses SpotBugs 4.10.5.0 with High priority enforcement. The resolved runtime Maven dependencies are queried against OSV; see dependency-audit.json for the timestamp and complete finding list. These checks are scoped evidence, not a guarantee that all vulnerabilities have been discovered.

The final image logs and a database export were checked for the controlled test message/password/private-key markers. None were present. This verifies those fixtures; it does not claim an exhaustive scan of every possible plaintext value. No default generated Spring password account remains.

## Remaining human and hosting gates

- R-05: organizational acceptance/sign-off of the threat model.
- Q-06: independent external security review and remediation of its findings. No reviewer has been contacted or certification claimed.

M1 through M4 have implementation and local acceptance evidence. M5 remains release-gated by the independent review. Local certificates are for development; remote infrastructure approval, trust distribution and backup policies remain operator responsibilities.

## Release candidate hardening

The additional cases verify bounded authentication storage, room-session ownership across separate logins, legacy password-hash upgrades, HTTPS endpoint restrictions, stable MLS roster/fingerprint agreement and safe retry after explicit fingerprint verification. See the implementation audit for the fixes.

The native macOS bundle self-check passes with three FXML screens, Bouncy Castle signatures and JSON support under Java 21. Desktop visual interaction in the packaged app was not observed because computer-control permission was unavailable; the JavaFX acceptance test exercised the application screens. The app is unsigned and unnotarized.

Trivy 0.76.2 scanned 140 Ubuntu 26.04 OS packages with networking disabled while the private image was mounted; no known high/critical findings were reported. Java libraries are covered separately by the OSV dependency audit. The final backend rebuild changes the application layer; its OS base layers are compared with the scanned image rather than representing a second full scan. Hosted CI scans its fresh image. Docker Scout was not used because automatic approval review rejected its potential upload of private image metadata/content.

Hosted Linux CI passed on commit `dda6f5143a302d49594a31fcdada61a5adf12f96`: [verification run](https://github.com/GURUSEKKAR/privacy-chat/actions/runs/43824064106). It includes the full build, JavaFX GUI, static/dependency checks, certificate-validated HTTPS/WSS, database restore and offline OS scan. The same workflow triggers on pull requests; this evidence is a push run.

## Website acceptance

The website supports encrypted browser/desktop conversations in either owner direction. BrowserAccessTest checks public assets versus protected API routes, one-use 30-second tickets, superseded and expired tickets, logout invalidation, pending-device rejection and cross-origin socket rejection. BrowserInteropTest runs the actual TypeScript SDK against Spring Boot/PostgreSQL and the Java desktop client, including message exchange, viewed-message re-entry and reset exclusion.

Four Node tests check password lengths at 11 and 12 characters, password-encrypted vault preservation on failed unlock, MLS sender authentication, tampering, replay and reset isolation, plus static import resolution in the production JavaScript bundle. A separate running deployment check through the loopback preview verifies API proxying, server-certificate validation, browser ticket transport, encrypted send and view-once re-entry. Browser UI interaction could not be visually verified because the browser access host is unavailable; SDK and HTTP checks do not establish visual acceptance.

The development website is served at http://localhost:5173. The packaged backend serves its built assets at https://localhost:18443/. These are local endpoints, not a public Internet deployment. The browser MLS implementation expands the pending independent-review scope.
