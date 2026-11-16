# Operations and retention

The secure local stack in README has been tested through certificate-validated HTTPS/WSS. It binds only to loopback and does not publish PostgreSQL. The backend runs as UID 10001, with a read-only filesystem, writable temporary filesystem, dropped capabilities and no privilege escalation. The TLS profile accepts TLS 1.3. Local certificates expire in 30 days; use managed CA-issued certificates for remote service. Generated environment files, vaults, PEM files and keystores stay outside Git.

## Monitoring

SECRETCHAT_METRICS_TOKEN must contain at least 32 characters. It is the only credential accepted on /actuator/metrics; user tokens cannot read operational metrics. Public health exposes no internals. The TLS profile exposes health and JSON metrics only.

Run scripts/check-health.py every minute under a supervisor, with a TLS 1.3 capable Python, the operations token in its environment and the correct CA file. It exits nonzero for TTL/reset backlog, stale collection or an authentication burst of at least 20 failures between checks within two minutes. It also reports rooms awaiting owner rekey. Route its JSON output and exit status to your alert transport. No external messaging integration was contacted. The local state file stores only a timestamp and previous aggregate failure count.

Metrics have no room/user/device labels. They cover expiry/reset backlogs, locked rekeys, collection freshness and authentication failures. Never log bodies, passwords, bearer tokens, private keys or plaintext. The application defines no default password account and emits no generated Spring password.

## Retention and recovery

The database stores account/device/room identifiers, membership times, encrypted bytes, receipts and opaque MLS events. Message and room-cycle expiry are synchronous access boundaries. Workers erase ciphertext and purge deleted metadata; its default extra retention is zero. Ended sessions and expired authentication metadata have a one-day grace period. Prior-cycle MLS control events are removed on completed reset/rekey.

Secure local PostgreSQL disables WAL archiving and replication slots, retains no extra WAL for standbys and uses a 256 MiB checkpoint target. This is a soft target, not a deletion deadline or guaranteed disk cap. Necessary WAL, host snapshots and copied dumps can retain ciphertext after application-row deletion. Those systems require deployment-specific retention and access controls; immediate physical erasure is not promised.

No automatic backups are enabled. If required, encrypt exports using an operator-managed key and store them in a dedicated directory with names secretchat-<timestamp>.dump.age. Run scripts/prune-backups.py DIRECTORY --max-age-hours N to preview expired files; add --apply to remove them. Symlinks and unrelated names are skipped. Schedule this with the selected backup job and include remote copies in the policy.

The real dump/restore rehearsal in scripts/verify-restore.sh uses an isolated temporary database. It round-trips an expired ciphertext fixture, verifies its exclusion under the expiry predicate and purges it. It never replaces live data. Exact-boundary service tests separately verify delivery denial and cleanup. Temporary dumps are mode-restricted and removed on exit. Keep a restored production database isolated until expiry/reset workers run and overdue rooms lock for owner rekey; never extend original expiry timestamps or generations during recovery.

## Incident response

For device compromise, revoke its sessions from a trusted device, reset affected rooms, verify fingerprints out of band and re-invite trusted devices. Reset selected room recovers an owner whose client lost in-memory group state. If no approved device remains, do not bypass approval to recover historical keys; that requires a separately reviewed recovery design.

For suspected server compromise, stop external ingress, rotate infrastructure credentials and TLS material, preserve minimal access metadata, and reset groups from trusted clients after remediation. Review metadata exposure and retained backups. Independent security review and closure of its findings remain a release gate. Recipient screenshots, copies and compromised endpoints cannot be remotely erased.
