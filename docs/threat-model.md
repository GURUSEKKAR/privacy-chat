# Threat model and release boundary

The backend and database hold public device keys, opaque MLS traffic and routing metadata. JavaFX clients hold private device identities, group secrets and volatile plaintext. TLS protects credentials and transport; MLS protects message content. A compromised endpoint can copy content or keys. Screenshots and recipient copies cannot be remotely erased.

| Attack | Control and remaining limit |
| --- | --- |
| Guessed room ID or stolen room password | Authenticated invited account and approved membership device required |
| Stolen account password on a new device | Device remains pending; existing approved device must approve exact fingerprint |
| Device key substitution | Signed KeyPackages, credential binding and pinned fingerprints; first use still requires out-of-band comparison |
| Late member or removed member | Recipient snapshots plus MLS Add/Remove epochs; tested with real client cryptography |
| Replay, stale epoch or reset generation | MLS authentication/replay state and server epoch/generation/session checks |
| Worker delay | Read/send checks enforce TTL and room-cycle deadline synchronously |
| Database or backup disclosure | Ciphertext and metadata remain sensitive; primary-row deletion does not erase WAL/snapshots |
| Shared cryptographic state under concurrency | Serialize use of mutable suite primitives; retain regression coverage |

Production release requires independent MLS/security review, official test vectors and interoperability validation, dependency/SAST scanning, backup/WAL retention enforcement, endpoint/vault assessment and adversarial concurrency/transport coverage. No external reviewer has signed off this prototype.

## Browser client extension

The browser now runs ts-mls 1.6.4, using cipher suite 1 and the same on-wire messages as the Java client. Browser/desktop interoperability is regression-tested in both owner directions. This extends the audit scope; the earlier implementation-author review is not independent browser approval.

Device private signing keys are AES-GCM encrypted under a WebCrypto PBKDF2-SHA256 key with 600,000 iterations and a per-vault random salt. Encrypted key envelopes and public fingerprint pins live in origin-local browser storage. Tokens, group state, pending private KeyPackages and plaintext remain in memory. Wrong passwords preserve the existing encrypted envelope. Browser refresh/close loses room keys and requires owner reset/re-invitation. Clearing site storage loses the browser identity; a new device then needs approval.

XSS, a malicious dependency, a compromised origin or browser extension can read plaintext or steal unlocked keys. A restrictive same-origin CSP and pinned dependency lockfile reduce exposure but cannot remove this endpoint risk. Public deployment needs a trusted certificate and a hosted backend. Browser access is a distinct approved device and does not inherit another device's room keys. Device fingerprints use first-use pinning and changes fail closed; compare fingerprints out of band.

Browser WebSocket handshakes use a separate single-use, 30-second ticket, issued only through authenticated approved-device HTTP requests. Ticket consumption revalidates the session, and same-origin handshake checks remain active. Tickets are bounded and must be redacted from proxy logs. Desktop handshake authentication is unchanged. The development proxy binds to loopback and validates the local backend certificate.
