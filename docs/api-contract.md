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
