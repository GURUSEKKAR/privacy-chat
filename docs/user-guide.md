# Desktop user guide

Create an account with a unique username and a password of at least 12 characters. Complete Google Authenticator setup before entering chat: scan the website QR code, or enter the desktop setup key manually as a time-based account. Confirm its six-digit code and save your one-time recovery codes. Every new sign-in needs a password and authenticator or recovery code. If another session exists, review its device name/time and explicitly revoke it before activating the new session. Only one session can access the account at once. Existing accounts enroll from their original approved device first. See the [authenticator setup and deployment guide](../README.md#google-authenticator-and-one-active-session).

Reuse the same website domain/browser profile or desktop data folder to retain the device identity. A new browser can authenticate using its authenticator code, but it does not inherit another device's MLS room keys or membership. Account/session and device controls are available in the website header. **Join group** is next to **Create group**; enter an invitation's code/password, or accept it from Invitations. Joining still requires an owner invitation and MLS Add.

Create a room with optional code/password, message lifetime and member reset interval. Select it, type an account username and choose Invite. The recipient refreshes invitations and accepts. The owner selects Add accepted members to issue MLS Welcome messages. A room password adds an entry check; it does not decrypt messages or grant membership.

Enter the room to begin a viewing session. Members and the cycle countdown appear alongside chat. Hover over a member for the enrolled fingerprint and compare it through another trusted channel. Unexpected fingerprint changes are refused. First-use trust alone cannot prove the server supplied the correct identity.

After a message appears, the client acknowledges display. Leaving prevents retrieval of that viewed message on return. An eligible offline member may catch up before expiry if its running client still has the necessary MLS state. Late members receive no earlier content. Once all eligible recipients acknowledge, the server erases its copy; existing volatile copies remain until session end. Participants can still copy or screenshot content.

Leave clears the session buffer. Network disconnection also clears it; Reconnect opens a new session and consumed messages remain unavailable. A failed display withholds its receipt; refresh can retry the retained unseen message without decrypting its ciphertext twice.

At the cycle deadline, non-owners lose access. The approved owner must rekey before traffic resumes. If the owner is offline, the room stays locked. Reset room now performs the same transition immediately. Reset selected room in the room list also recovers an owner after restart. Re-invite members for the next cycle.

Quitting drops plaintext and group state. The encrypted device vault uses macOS Keychain-backed wrapping in the default data folder when available. Custom demo folders and other platforms use password encryption. No decrypted history is written. Memory wiping is best effort because Java and library object copies can outlive application references.
