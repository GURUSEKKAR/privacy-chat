# Optional scope decisions

V1 binds each room membership to one approved device. Other account devices may be approved, but transferring room access requires a fresh owner-approved membership and epoch. Historical key transfer and multi-device history synchronization need a separate entitlement review.

Strict Live is implemented and tested at the API level. Temporary Catch-Up remains the desktop default; an optional UI selector can expose Strict Live later.

Attachments are deferred. They require encrypted blob lifetime, download/view semantics, recipient snapshots, size limits and independent backup-retention controls.

Horizontal scaling is deferred. One process owns WebSocket routing, the broker, challenges and rate-limit maps. Multiple replicas require shared authenticated routing, distributed limits and cleanup coordination. Do not run independent backend replicas with the current in-memory broker.
