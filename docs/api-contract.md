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

An approved browser device obtains `POST /api/v1/ws-ticket` using its normal Authorization header. The response contains a random single-use `ticket` and `expiresInSeconds: 30`. Connect to `/ws/browser?ticket=...` from the same origin, then use the existing STOMP destinations. Only one ticket can be outstanding per login; a newer request invalidates the previous ticket. The server revalidates session approval when consuming it. The existing `/ws` native-client handshake retains HTTP bearer authentication.

Do not log WebSocket query strings at a reverse proxy. Account access or refresh tokens never go in WebSocket URLs. Tickets remain bounded to 5,000 outstanding logins, expire after 30 seconds and cannot be reused. The STOMP authorization checks and session revocation rules apply to both transports.
