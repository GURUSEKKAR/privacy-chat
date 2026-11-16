# Implementation TODO

Updated 16 November 2026. MVP implementation and local acceptance are complete. Forty-seven tests pass with no failures or skips, including real GUI, native Keychain and TLS deployment tests. The original task IDs below are updated against the evidence in [acceptance.md](docs/verification/acceptance.md).

## Completed implementation

- [x] Accounts, device enrollment/approval/revocation, fingerprint checks and encrypted local storage.
- [x] Invitations, room capacity, MLS membership changes, encrypted delivery and offline catch-up.
- [x] View-once session consumption, display acknowledgement, exact TTL enforcement and cleanup.
- [x] Owner-only reset/rekey, stale-event handling, restart recovery and reconnect UI.
- [x] Concurrency and malicious WebSocket checks, official MLS vectors and static/dependency scans.
- [x] Docker HTTPS/WSS packaging, metrics/alert checks, backup retention and restore rehearsal.
- [x] CI configuration, installation/user/API/security/operations documentation and Git initialization.

- [x] Bounded authentication state, login-bound room sessions, strict MLS roster/key checks and legacy password-hash upgrades.
- [x] Native macOS app/ZIP, runtime self-check, source review archive and source-bound release gate checker.
- [x] Offline container OS scan without application upload; zero known high/critical findings.

## Remaining release gates

- [ ] R-05 — organizational sign-off of the documented threat model.
- [ ] F-06 — execute the hosted PR workflow on the supplied GitHub repository; configuration and equivalent local checks already pass.
- [ ] Q-06 — independent external security review and closure of its findings.

No external reviewer has been contacted. No production-safe E2EE certification is claimed. M1–M4 have local acceptance evidence; M5 remains release-gated. The MVP deliberately keeps MLS group state in memory, uses one device per room membership and remains a single-node server. See [optional scope decisions](docs/backlog.md).

## Original workflow checklist

Checked items have implementation and local verification evidence. Human approvals and hosted execution remain explicitly unchecked.

### Phase 0  |  Requirements and threats

- [x] R-01  [P0]  Freeze message-view semantics: viewed in current session vs unavailable after exit.
  Proof: A/B/C timeline written and reviewed.
- [x] R-02  [P0]  Define immutable recipient snapshot per message and sender acknowledgement.
  Proof: Adding C later never alters old recipient set.
- [x] R-03  [P0]  Choose default Temporary Catch-Up and document Strict Live as optional.
  Proof: Offline initial member vs late joiner behaves differently.
- [x] R-04  [P0]  Fix room-cycle reset policy: all non-owner removed; owner remains; room locked for rekey.
  Proof: Owner-offline case and next reset time documented.
- [ ] R-05  [P0]  Document attacker model: stolen room password, stolen login, unknown device, compromised endpoint.
  Proof: Attack/defense matrix signed off.
- [x] R-06  [P1]  Define data retention, logging limits, message sizes and room capacity.
  Proof: Written TTL bounds and no-plaintext policy.

### Phase 1  |  Foundation and project setup

- [x] F-01  [P0]  Install Java 21, Maven, Docker, IDE and PostgreSQL tooling.
  Proof: java -version and mvn -version are reproducible.
- [x] F-02  [P0]  Create Git repository and Maven parent with backend, desktop-client and protocol-contracts modules.
  Proof: mvn clean verify succeeds.
- [x] F-03  [P0]  Generate Spring Boot 4.1.x API with Web, Security, Validation, WebSocket and JPA starters.
  Proof: GET /actuator/health works in local environment.
- [x] F-04  [P0]  Create PostgreSQL Docker Compose configuration and Flyway baseline migration.
  Proof: Fresh DB can be created from scratch.
- [x] F-05  [P1]  Create JavaFX skeleton with registration, login, room list and chat navigation.
  Proof: Screens launch without backend errors.
- [ ] F-06  [P1]  Add CI for build, unit tests, formatting and dependency alerts.
  Proof: Pull request runs checks automatically.

### Phase 2  |  User identity and device trust

- [x] A-01  [P0]  Create users table with unique normalized username and UUID identity.
  Proof: Concurrent duplicate registration produces one success.
- [x] A-02  [P0]  Implement Argon2id password hashing and basic registration/login validation.
  Proof: No plaintext or weak hash in DB/logs.
- [x] A-03  [P0]  Implement short-lived authenticated sessions, refresh rotation, logout and revocation.
  Proof: Revoked/expired token gets 401 on REST and WS.
- [x] A-04  [P0]  Implement login rate limiting and safe generic error responses.
  Proof: Credential guessing is throttled.
- [x] A-05  [P0]  Generate client-side MLS identity and KeyPackage; publish public info only.
  Proof: Private keys never appear in DB or captured API traffic.
- [x] A-06  [P0]  Implement first-device enrollment plus approval for additional devices.
  Proof: Stolen login from unknown device cannot read chat.
- [x] A-07  [P0]  Bind each device to verified identity/fingerprint and support device revocation.
  Proof: Wrong device public key rejected or visibly flagged.
- [x] A-08  [P1]  Protect client cryptographic secrets using OS-backed storage where available.
  Proof: Keys are encrypted at rest; plaintext buffer not persisted.

### Phase 3  |  Room lifecycle and invitations

- [x] RM-01  [P0]  Create rooms table with owner, random UUID, unique display code, TTL, reset interval and generation.
  Proof: Room owner always active after reset.
- [x] RM-02  [P0]  Hash optional room password independently of chat encryption keys.
  Proof: Room password alone cannot decrypt old messages.
- [x] RM-03  [P0]  Implement invite-by-user-ID, accept, owner approval and invitation expiration.
  Proof: Uninvited C is denied on every entry path.
- [x] RM-04  [P0]  Implement ACTIVE/RESET_PENDING/LOCKED_PENDING_REKEY room state machine.
  Proof: State transitions are valid and persisted.
- [x] RM-05  [P0]  Apply room membership/device checks to every REST endpoint.
  Proof: B cannot enumerate A’s other rooms.
- [x] RM-06  [P1]  Create owner room settings and invitation management UI.
  Proof: Owner can add B and view correct members.

### Phase 4  |  MLS cryptography proof of concept

- [x] C-01  [P0]  Add patched Bouncy Castle bcmls-jdk18on 1.86+ and verify official test vectors.
  Proof: Dependency scan clean; MLS test vectors pass.
- [x] C-02  [P0]  Implement MLS group creation with A and a Welcome-based join for B.
  Proof: A/B arrive at same authenticated MLS epoch.
- [x] C-03  [P0]  Implement client encryption/decryption with MLS PrivateMessages only.
  Proof: Backend payload is ciphertext; A/B decrypt.
- [x] C-04  [P0]  Implement ordered MLS Commit delivery and epoch conflict handling.
  Proof: Concurrent conflicting commits cannot fork a room silently.
- [x] C-05  [P0]  Test new C join after “Hi”: no pre-join content or old keys exposed.
  Proof: C cannot decrypt A/B previous traffic.
- [x] C-06  [P0]  Test MLS Remove/Commit after B removal; new sends remain private to remaining group.
  Proof: Removed B cannot read post-removal traffic.
- [x] C-07  [P0]  Define offline approved-client catch-up and bounds on retained epoch keys.
  Proof: Offline eligible B can process allowed backlog within TTL.
- [x] C-08  [P0]  Verify owner device identity and detect server-substituted device keys.
  Proof: Unexpected fingerprint requires explicit user intervention.

### Phase 5  |  Real-time encrypted messaging

- [x] M-01  [P0]  Configure WSS/secure handshake with Spring WebSocket + STOMP.
  Proof: Only authenticated sessions establish socket.
- [x] M-02  [P0]  Authorize inbound SEND and SUBSCRIBE, deny client writes to broker prefixes.
  Proof: Unauthorized topic subscriptions and forged sender denied.
- [x] M-03  [P0]  Create message tables with immutable recipient snapshot, ciphertext, epoch and expires_at.
  Proof: Server never receives/persists plaintext.
- [x] M-04  [P0]  Bind message sender identity to server-side principal/device, not request fields.
  Proof: Spoofed senderUserId ignored or rejected.
- [x] M-05  [P1]  Route ciphertext to recipient-specific approved sessions.
  Proof: A/B receive; C without membership receives nothing.
- [x] M-06  [P1]  Implement JavaFX encrypted chat UI, delivery errors and reconnect states.
  Proof: Conversation uses local decrypt and renders correctly.
- [x] M-07  [P0]  Reject stale room generations, epochs, replayed ids and oversized payloads.
  Proof: Adversarial send tests pass.

### Phase 6  |  View-once, dual deletion and expiration

- [x] V-01  [P0]  Record first viewed_at and first_view_session_id once per user/message.
  Proof: Duplicate acknowledgements do not change first view.
- [x] V-02  [P0]  Block delivery of previously viewed content after that session ends.
  Proof: B views, exits and rejoins: “Hi” unavailable.
- [x] V-03  [P0]  Clear rendered plaintext on session exit and prevent history persistence in client.
  Proof: No local history file after quit/restart.
- [x] V-04  [P0]  Calculate all-viewed against snapshot-eligible recipients; sender auto-viewed.
  Proof: A/B/C all view → ciphertext removed early.
- [x] V-05  [P0]  Enforce TTL synchronously in REST and WS message retrieval.
  Proof: At exactly expires_at, server denies access.
- [x] V-06  [P0]  Implement scheduled and retryable ciphertext cleanup with row locks.
  Proof: Worker restart does not resurrect messages.
- [x] V-07  [P0]  Handle revoked-before-view recipient without pretending they viewed.
  Proof: Remaining eligible recipients control completion.
- [x] V-08  [P1]  Add status indicators (unseen/viewed/expired) without exposing unnecessary identities.
  Proof: UI matches backend state; no stale messages.

### Phase 7  |  Owner-exempt automatic membership reset

- [x] X-01  [P0]  Implement next_reset_at and atomic timed room lock/generation increment.
  Proof: Old sessions and invitations become invalid immediately.
- [x] X-02  [P0]  Remove every member where role != OWNER; keep owner active.
  Proof: After 60 minutes A stays, B/C are revoked.
- [x] X-03  [P0]  Reject stale membership tokens and WebSocket frames after reset.
  Proof: Disconnected B/C cannot send or subscribe.
- [x] X-04  [P0]  Delete remaining temporary message ciphertext and clear current room history.
  Proof: Old room generation has zero active message rows.
- [x] X-05  [P0]  Require owner’s approved client to perform MLS Remove/Commit rekey.
  Proof: New epoch excludes B/C.
- [x] X-06  [P0]  Lock room when owner is offline; unlock only after verified owner rekey.
  Proof: No message sent with old shared keys.
- [x] X-07  [P1]  Implement manual Reset Room Now for owner with same invariant checks.
  Proof: Manual reset produces same safe transitions.
- [x] X-08  [P1]  Show reset countdown and new-cycle re-invite UI.
  Proof: User sees membership expiry time and reset result.

### Phase 8  |  QA, deployment and release

- [x] Q-01  [P0]  Write JUnit tests for user, room, invitation, receipt, expiry and reset services.
  Proof: Business rules have meaningful branch coverage.
- [x] Q-02  [P0]  Use Testcontainers PostgreSQL for transaction/race/integration tests.
  Proof: Concurrent views and resets are deterministic.
- [x] Q-03  [P0]  Run A/B/C adversarial scenarios including stolen passwords and unapproved devices.
  Proof: No unauthorized ciphertext delivery or decryption.
- [x] Q-04  [P0]  Run MLS interoperability, membership changes and key-erasure tests.
  Proof: Group rekey/new member behavior proven.
- [x] Q-05  [P0]  Run dependency/SAST checks; ensure patched crypto dependencies.
  Proof: No unresolved critical/high issues.
- [ ] Q-06  [P0]  Conduct external security review before advertising production-safe E2EE.
  Proof: High-risk findings closed and retested.
- [x] Q-07  [P0]  Document plaintext/ciphertext metadata, backup/WAL retention and screenshot limits.
  Proof: Privacy statements are accurate and scoped.
- [x] Q-08  [P1]  Package Docker backend + PostgreSQL, configure HTTPS/WSS and secrets.
  Proof: Fresh deployment from README succeeds.
- [x] Q-09  [P1]  Add metrics/alerts for reset backlog, TTL backlog and authentication failures.
  Proof: No passwords, keys or plaintext in telemetry.
- [x] Q-10  [P1]  Write installation, user guide, API docs, threat model and incident response.
  Proof: Another developer can reproduce demo and tests.
- [x] Q-11  [P2]  Evaluate multi-device support, strict-live mode, attachments and horizontal scaling.
  Proof: Backlog only; does not delay secure MVP.

### Final acceptance scenarios

- [x]  A creates room with 10-minute TTL and 60-minute member reset. A adds B and C by username; both approve a device.
- [x]  A sends “Hi”; B views and exits; B rejoins before expiry and cannot retrieve the same message.
- [x]  C is an existing invited recipient, was offline, and joins before 10-minute TTL; C can view only if its approved device has epoch rights.
- [x]  After A/B/C acknowledge, ciphertext is deleted before 10-minute TTL (A is sender-viewed).
- [x]  On a second message, C never views; at the 10-minute deadline the API cannot return it and cleanup purges it.
- [x]  D joins later with an invitation; D cannot receive any message from before joining.
- [x]  Attacker who has only the room ID/password cannot join or decrypt content; an unapproved device with a stolen account password also fails.
- [x]  At 60-minute reset B/C are revoked, A remains; room is locked until A commits new MLS state. B/C cannot receive newer messages.
- [x]  Owner A re-invites B to next cycle; B gets new keys and no prior-cycle history.
- [x]  Inspect a database export and logs: no plaintext messages, no private device keys, and documented metadata retention.
