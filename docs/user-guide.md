# Desktop user guide

Create an account with a unique username and a password of at least 12 characters. The first device becomes approved. Reuse the same local data folder when signing in later to retain its identity. An additional computer remains pending until an approved device confirms its displayed fingerprint. The device list supports approval and revocation of another device.

Create a room with optional code/password, message lifetime and member reset interval. Select it, type an account username and choose Invite. The recipient refreshes invitations and accepts. The owner selects Add accepted members to issue MLS Welcome messages. A room password adds an entry check; it does not decrypt messages or grant membership.

Enter the room to begin a viewing session. Members and the cycle countdown appear alongside chat. Hover over a member for the enrolled fingerprint and compare it through another trusted channel. Unexpected fingerprint changes are refused. First-use trust alone cannot prove the server supplied the correct identity.

After a message appears, the client acknowledges display. Leaving prevents retrieval of that viewed message on return. An eligible offline member may catch up before expiry if its running client still has the necessary MLS state. Late members receive no earlier content. Once all eligible recipients acknowledge, the server erases its copy; existing volatile copies remain until session end. Participants can still copy or screenshot content.

Leave clears the session buffer. Network disconnection also clears it; Reconnect opens a new session and consumed messages remain unavailable. A failed display withholds its receipt; refresh can retry the retained unseen message without decrypting its ciphertext twice.

At the cycle deadline, non-owners lose access. The approved owner must rekey before traffic resumes. If the owner is offline, the room stays locked. Reset room now performs the same transition immediately. Reset selected room in the room list also recovers an owner after restart. Re-invite members for the next cycle.

Quitting drops plaintext and group state. The encrypted device vault uses macOS Keychain-backed wrapping in the default data folder when available. Custom demo folders and other platforms use password encryption. No decrypted history is written. Memory wiping is best effort because Java and library object copies can outlive application references.
