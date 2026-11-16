# API overview

All authenticated REST requests use `Authorization: Bearer <access token>`. Identity comes from the principal. Public registration/login use a single-use challenge and device proof-of-possession signature; see `protocol-contracts` for exact JSON records and validation constraints.

| Area | Routes |
| --- | --- |
| Authentication | `/api/v1/auth`: challenge, register, login, refresh, logout |
| Devices | `/api/v1/devices`: list, approve, revoke, KeyPackages |
| Rooms | `/api/v1/rooms`: create/list/get, members, viewing sessions, reset |
| Invitations | Room invites plus `/api/v1/invites` acceptance |
| MLS | Room-scoped commit, events and Welcome delivery |
| Messages | Room-scoped pending ciphertext and view receipts |

Controller annotations are the route authority. `MessageDtos`, `RoomDtos`, `AuthDtos` and `DeviceDtos` define payloads. STOMP connects to `/ws` with bearer authorization, subscribes to `/user/queue/chat`, and sends `SendMessageFrame` to `/app/rooms/{roomId}/send`. Broker-prefix writes and arbitrary subscriptions are denied.

Errors use a stable code with HTTP status: 401 unauthenticated, 403 forbidden device/member, 404 inaccessible resource, 409 stale epoch/reset conflict, 410 ended or consumed session/message, 429 throttling. STOMP returns equivalent ERROR events. Clients process ordered MLS commits before decrypting later epochs.

## Browser website and WebSocket tickets

The compiled website is served at `/` with public same-origin static assets under `/assets/`. API routes retain bearer authentication; static-asset access does not authenticate a visitor.

An approved browser device obtains `POST /api/v1/ws-ticket` using its normal Authorization header. The response contains a random single-use `ticket` and `expiresInSeconds: 30`. Connect to `/ws/browser?ticket=...` from the same origin or an explicitly configured `SECRETCHAT_WEB_ORIGINS` origin, then use the existing STOMP destinations. Only one ticket can be outstanding per login; a newer request invalidates the previous ticket. The server revalidates session approval when consuming it. The existing `/ws` native-client handshake retains HTTP bearer authentication.

Do not log WebSocket query strings at a reverse proxy. Account access or refresh tokens never go in WebSocket URLs. Tickets remain bounded to 5,000 outstanding logins, expire after 30 seconds and cannot be reused. The STOMP authorization checks and session revocation rules apply to both transports.

## Authenticator verification and session activation

Register/login retain the device-key challenge protocol and return restricted bearer tokens with `deviceStatus` of `MFA_SETUP` (trusted account enrollment), `MFA_SETUP_APPROVAL` (use an original device first), or `MFA_REQUIRED` (verify code). These tokens cannot access rooms, invitations, KeyPackages or WebSocket tickets. Every enrolled account requires a second factor even when the device was previously approved.

- `POST /api/v1/auth/mfa/setup`: returns `{secret, uri}` only for a recent login on an approved device before enrollment. Returns the same pending secret on retry; refuses already-enrolled accounts.
- `POST /api/v1/auth/mfa/verify`: body `{code}` accepts a six-digit TOTP or unused recovery code (recovery is only valid after enrollment). Returns `{status, previousSessions, recoveryCodes}`. Codes are consumed atomically. First confirmation returns ten recovery codes once.
- `GET /api/v1/auth/mfa`: status and previous active session's device name and timestamps after verification; never a secret or recovery code.
- `POST /api/v1/auth/mfa/replace-session`: only after recent successful second-factor verification, explicitly revokes other sessions and activates this one.

After confirmation/activation call refresh to receive the current `deviceStatus`. `SESSION_CONFLICT` permits no chat access until replacement; `APPROVED` identifies the one fully authorized login. A waiting login may sign out to preserve the previous active session. Revocation invalidates access and refresh tokens and WebSocket authorization. Clients must erase displayed chat on socket loss and return to sign-in on revoked-token HTTP 401.

## Mutable owner settings

`CreateRoomRequest` accepts optional `name` (up to 80 characters); legacy clients default to the stable display code. `RoomView` includes `name`. `PUT /api/v1/rooms/{roomId}` is restricted to the owner's approved room device and takes `{name,messageTtlSeconds,resetIntervalSeconds,changePassword,roomPassword}`. The name is nonblank and at most 80 characters; existing TTL/reset bounds apply. `changePassword:false` preserves the hash, `true` with null/empty removes it, and `true` with a new 8–128-character password replaces it. Settings serialize under the room lock. Expired/non-active cycles cannot be extended through settings. Member removal continues through owner-authorized MLS `REMOVE`, not an unencrypted membership-only deletion.
