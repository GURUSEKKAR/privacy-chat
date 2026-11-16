# Production release review request

Repository: https://github.com/GURUSEKKAR/privacy-chat

Review the exact source digest in target/release/source-manifest.json. The source-review archive includes implementation, tests, official vector provenance and verification evidence. A source change after review invalidates its digest binding.

## Independent security scope

Review MLS credential binding, public KeyPackage validation, first-use fingerprint trust and explicit key-change confirmation. Examine PrivateMessage authentication, replay protection, epoch retention and the reflected Bouncy Castle authentication APIs. Verify that intermediate catch-up epochs are not compared with a later membership roster and that a stable-epoch mismatch fails closed before sending.

Review per-user view consumption, approved-device and authentication-session ownership, recipient snapshots, simultaneous views, revoked recipients, TTL enforcement, send/reset ordering, owner-offline locking and fresh-group recovery. Evaluate stale notifications and reconnect races across multiple sockets for the same account.

Review password hashing, challenge capacity, rate-limit capacity, token refresh/reuse, device approval and revocation. Review all REST/STOMP entry points for authorization and resource exhaustion. Assess vault file permissions, Keychain fallback, Java memory-erasure limits, logs, metrics, transport configuration and deployment/backup retention.

Record each finding with severity, affected source digest, reproduction, remediation and retest evidence. Production approval requires no unresolved critical/high findings and an explicit reviewer decision. Automated scans and the implementation author's own review are supporting evidence, not independent approval.

## Owner sign-off

Confirm the threat model and limitations in docs/threat-model.md: screenshots and compromised endpoints, first-use trust, one device per room membership, single-node routing, volatile MLS state and retention in WAL/backups. Confirm intended deployment, incident ownership, certificate rotation and retention policy.

## Approval record

Copy approvals.example.json to ignored .local/release/approvals.json. The responsible humans fill in their decisions, identities, timestamps and source digest. Place the review report in the project documentation and record its SHA-256. Add the successful GitHub Actions run ID after publishing the reviewed commit. Run scripts/release-readiness.py to validate source binding and recorded evidence. This script cannot establish a reviewer's real-world identity or independence.

No independent approval has been supplied or invented. Do not label the software production-safe E2EE until the review and finding closure are complete.

## Browser extension review

Include the ts-mls 1.6.4 implementation and WebCrypto key vault, origin-local fingerprint pins, same-origin CSP, DOM rendering and display receipts, memory-only tokens and chat state, page lifecycle clearing, browser/desktop interoperability, and one-use WebSocket ticket issuance/consumption. Review the loopback development proxy separately from the packaged same-origin production assets. Browser visual acceptance remains pending while browser-control access is unavailable.
