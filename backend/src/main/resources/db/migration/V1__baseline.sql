-- Secret Chat Room baseline schema.
-- Invariants: UUID keys, UTC timestamptz, no plaintext message content, no private key material.

CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    username_normalized VARCHAR(32)  NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_username UNIQUE (username_normalized),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE devices (
    id                    UUID PRIMARY KEY,
    user_id               UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                  VARCHAR(64)  NOT NULL,
    identity_public_key   BYTEA        NOT NULL,
    fingerprint           VARCHAR(64)  NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    approved_at           TIMESTAMPTZ,
    approved_by_device_id UUID,
    revoked_at            TIMESTAMPTZ,
    CONSTRAINT uq_devices_identity_key UNIQUE (identity_public_key),
    CONSTRAINT ck_devices_status CHECK (status IN ('PENDING', 'APPROVED', 'REVOKED'))
);
CREATE INDEX ix_devices_user ON devices (user_id);

CREATE TABLE device_key_packages (
    id                 UUID PRIMARY KEY,
    device_id          UUID        NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    public_key_package BYTEA       NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    consumed_at        TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_key_packages_device ON device_key_packages (device_id, consumed_at);

CREATE TABLE auth_sessions (
    id                 UUID PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id          UUID        NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    token_hash         VARCHAR(64) NOT NULL,
    refresh_hash       VARCHAR(64) NOT NULL,
    previous_refresh_hash VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    refresh_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at         TIMESTAMPTZ,
    CONSTRAINT uq_auth_token UNIQUE (token_hash),
    CONSTRAINT uq_auth_refresh UNIQUE (refresh_hash)
);
CREATE INDEX ix_auth_sessions_device ON auth_sessions (device_id);
CREATE INDEX ix_auth_sessions_prev_refresh ON auth_sessions (previous_refresh_hash);

CREATE TABLE rooms (
    id               UUID PRIMARY KEY,
    display_code     VARCHAR(32)  NOT NULL,
    owner_user_id    UUID         NOT NULL REFERENCES users (id),
    owner_device_id  UUID         NOT NULL REFERENCES devices (id),
    password_hash    VARCHAR(255),
    message_ttl_s    INTEGER      NOT NULL,
    reset_interval_s INTEGER      NOT NULL,
    next_reset_at    TIMESTAMPTZ  NOT NULL,
    generation       INTEGER      NOT NULL,
    state            VARCHAR(32)  NOT NULL,
    mls_epoch        BIGINT       NOT NULL,
    privacy_mode     VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL,
    CONSTRAINT uq_rooms_display_code UNIQUE (display_code),
    CONSTRAINT ck_rooms_state CHECK (state IN ('ACTIVE', 'RESET_PENDING', 'LOCKED_PENDING_REKEY')),
    CONSTRAINT ck_rooms_ttl CHECK (message_ttl_s BETWEEN 10 AND 86400),
    CONSTRAINT ck_rooms_reset CHECK (reset_interval_s BETWEEN 60 AND 604800)
);
CREATE INDEX ix_rooms_reset_due ON rooms (state, next_reset_at);

CREATE TABLE memberships (
    id            UUID PRIMARY KEY,
    room_id       UUID        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id     UUID        REFERENCES devices (id),
    role          VARCHAR(8)  NOT NULL,
    state         VARCHAR(16) NOT NULL,
    joined_at     TIMESTAMPTZ,
    joined_epoch  BIGINT      NOT NULL,
    revoked_at    TIMESTAMPTZ,
    revoke_reason VARCHAR(32),
    generation    INTEGER     NOT NULL,
    CONSTRAINT uq_memberships_room_user UNIQUE (room_id, user_id),
    CONSTRAINT ck_memberships_role CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT ck_memberships_state CHECK (state IN ('PENDING_KEYS', 'ACTIVE', 'REVOKED'))
);
CREATE INDEX ix_memberships_room_state ON memberships (room_id, state);
CREATE INDEX ix_memberships_user ON memberships (user_id, state);

CREATE TABLE invitations (
    id                 UUID PRIMARY KEY,
    room_id            UUID        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    invitee_user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    invited_by_user_id UUID        NOT NULL REFERENCES users (id),
    status             VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    generation         INTEGER     NOT NULL,
    accepted_device_id UUID REFERENCES devices (id),
    CONSTRAINT ck_invitations_status CHECK (status IN
        ('PENDING', 'ACCEPTED', 'COMPLETED', 'DECLINED', 'EXPIRED', 'INVALIDATED'))
);
CREATE INDEX ix_invitations_invitee ON invitations (invitee_user_id, status);
CREATE INDEX ix_invitations_room ON invitations (room_id, status);
-- At most one open invitation per room/user.
CREATE UNIQUE INDEX uq_invitations_open ON invitations (room_id, invitee_user_id)
    WHERE status IN ('PENDING', 'ACCEPTED');

CREATE TABLE mls_events (
    id                    UUID PRIMARY KEY,
    room_id               UUID        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    generation            INTEGER     NOT NULL,
    sequence_no           BIGINT      NOT NULL,
    epoch                 BIGINT      NOT NULL,
    event_type            VARCHAR(16) NOT NULL,
    opaque_commit         BYTEA,
    welcome               BYTEA,
    created_by_device_id  UUID        NOT NULL REFERENCES devices (id),
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_mls_events_seq UNIQUE (room_id, sequence_no),
    CONSTRAINT uq_mls_events_epoch UNIQUE (room_id, generation, epoch)
);

CREATE TABLE room_sessions (
    id              UUID PRIMARY KEY,
    room_id         UUID        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id       UUID        NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    auth_session_id UUID        NOT NULL,
    connected_at    TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    room_generation INTEGER     NOT NULL
);
CREATE INDEX ix_room_sessions_open ON room_sessions (room_id, ended_at);
CREATE INDEX ix_room_sessions_auth ON room_sessions (auth_session_id, ended_at);

CREATE TABLE messages (
    id               UUID PRIMARY KEY,
    room_id          UUID        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    generation       INTEGER     NOT NULL,
    sender_user_id   UUID        NOT NULL REFERENCES users (id),
    sender_device_id UUID        NOT NULL REFERENCES devices (id),
    epoch            BIGINT      NOT NULL,
    ciphertext       BYTEA,
    idempotency_key  VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    deleted_at       TIMESTAMPTZ,
    delete_reason    VARCHAR(16),
    CONSTRAINT uq_messages_idempotency UNIQUE (sender_device_id, idempotency_key),
    -- A live message always has ciphertext; a deleted one never does.
    CONSTRAINT ck_messages_deleted CHECK ((deleted_at IS NULL) = (ciphertext IS NOT NULL))
);
CREATE INDEX ix_messages_room_expiry ON messages (room_id, expires_at);
CREATE INDEX ix_messages_due ON messages (expires_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_messages_deleted ON messages (deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE message_recipients (
    id                    UUID PRIMARY KEY,
    message_id            UUID        NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    recipient_user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    eligibility_state     VARCHAR(16) NOT NULL,
    ineligible_reason     VARCHAR(32),
    viewed_at             TIMESTAMPTZ,
    first_view_session_id UUID,
    consumed_at           TIMESTAMPTZ,
    CONSTRAINT uq_message_recipient UNIQUE (message_id, recipient_user_id),
    CONSTRAINT ck_recipient_state CHECK (eligibility_state IN ('ELIGIBLE', 'INELIGIBLE'))
);
CREATE INDEX ix_message_recipients_user ON message_recipients (recipient_user_id, message_id);
CREATE INDEX ix_message_recipients_session ON message_recipients (first_view_session_id);
