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
