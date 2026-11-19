# Workflow decisions

Temporary Catch-Up is the default. Each send snapshots active members and their approved devices. Adding D later never grants D a recipient row for an earlier message. Strict Live additionally limits the snapshot to participating users.

A successful sender display counts as viewed. B's first acknowledgement fixes a viewing session; repeat acknowledgements preserve that first receipt. Ending that session consumes B's entitlement across later sessions. Existing volatile copies can remain until session end after server deletion. A receipt establishes client display, not human attention.

A sends while B is present and C is offline. B acknowledges then exits: B cannot retrieve on return, while eligible C may fetch before TTL. After C acknowledges, all eligible recipients are complete and ciphertext is erased. If C never acknowledges, TTL denies access synchronously and the retryable worker erases it. Revoking C makes C ineligible without inventing a receipt.

At the reset deadline, protected room operations fail even before the worker runs. The reset transaction increments generation, invalidates invitations/sessions, removes all non-owners and clears messages. An offline owner leaves the room locked. An approved owner client removes other MLS leaves or creates a fresh owner group; only then does the next cycle begin at completion time plus the reset interval.

V1 binds a room membership to one device. New MLS Add events record the public device fingerprints for that epoch. Browsers use those fingerprints to verify a delayed Welcome or apply an Add after an intermediate member was removed. Existing fingerprint pins still apply, and the final roster must match current active membership. Older events fall back to the current roster. The client retains current and one prior MLS epoch for in-flight traffic. Older messages may be undecryptable after multiple epoch changes. Restart loses MLS group state, and broader offline persistence requires a separate reviewed design.

Bounds: room capacity defaults to 30 (including the owner); decoded ciphertext is at most 65,536 bytes; MLS control payloads at most 256 KiB. DTOs define TTL/reset bounds. Passwords use Argon2id. Avoid logging bodies, tokens, private keys and plaintext. Suite operations synchronize on the Bouncy Castle suite singleton because its signer/digest state is mutable across threads.
