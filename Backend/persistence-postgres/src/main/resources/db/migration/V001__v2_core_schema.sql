CREATE TABLE account (
    id UUID PRIMARY KEY,
    username_key VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    disabled_at TIMESTAMPTZ,
    CONSTRAINT account_username_key_length CHECK (char_length(username_key) BETWEEN 1 AND 128),
    CONSTRAINT account_display_name_length CHECK (char_length(display_name) BETWEEN 1 AND 100)
);

CREATE TABLE device (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    client_device_id VARCHAR(128) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT device_platform_supported CHECK (platform IN ('WEB', 'WINDOWS')),
    CONSTRAINT device_client_id_length CHECK (char_length(client_device_id) BETWEEN 1 AND 128),
    CONSTRAINT device_account_client_unique UNIQUE (account_id, client_device_id),
    CONSTRAINT device_id_account_unique UNIQUE (id, account_id)
);

CREATE TABLE device_session (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    device_id UUID NOT NULL,
    token_sha256 BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT device_session_device_owner
        FOREIGN KEY (device_id, account_id) REFERENCES device(id, account_id),
    CONSTRAINT device_session_token_hash_length CHECK (octet_length(token_sha256) = 32),
    CONSTRAINT device_session_expiry_order CHECK (expires_at > created_at)
);

CREATE INDEX device_session_active_account_idx
    ON device_session (account_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE conversation (
    id UUID PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    next_sequence BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT conversation_kind_supported CHECK (kind IN ('DIRECT', 'GROUP')),
    CONSTRAINT conversation_next_sequence_positive CHECK (next_sequence > 0)
);

CREATE TABLE conversation_member (
    conversation_id UUID NOT NULL REFERENCES conversation(id),
    account_id UUID NOT NULL REFERENCES account(id),
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    left_at TIMESTAMPTZ,
    last_read_sequence BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, account_id),
    CONSTRAINT conversation_member_role_supported CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT conversation_member_read_sequence_nonnegative CHECK (last_read_sequence >= 0),
    CONSTRAINT conversation_member_leave_order CHECK (left_at IS NULL OR left_at >= joined_at)
);

CREATE INDEX conversation_member_account_idx
    ON conversation_member (account_id, conversation_id);

CREATE TABLE direct_conversation (
    conversation_id UUID PRIMARY KEY REFERENCES conversation(id),
    first_account_id UUID NOT NULL REFERENCES account(id),
    second_account_id UUID NOT NULL REFERENCES account(id),
    CONSTRAINT direct_conversation_canonical_order CHECK (first_account_id < second_account_id),
    CONSTRAINT direct_conversation_pair_unique UNIQUE (first_account_id, second_account_id)
);

CREATE TABLE message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id),
    conversation_sequence BIGINT NOT NULL,
    sender_account_id UUID NOT NULL,
    sender_device_id UUID NOT NULL,
    client_message_id VARCHAR(128) NOT NULL,
    message_type INTEGER NOT NULL,
    payload BYTEA NOT NULL,
    payload_sha256 BYTEA NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT message_sender_membership
        FOREIGN KEY (conversation_id, sender_account_id)
        REFERENCES conversation_member(conversation_id, account_id),
    CONSTRAINT message_sender_device
        FOREIGN KEY (sender_device_id, sender_account_id)
        REFERENCES device(id, account_id),
    CONSTRAINT message_sequence_positive CHECK (conversation_sequence > 0),
    CONSTRAINT message_client_id_length CHECK (char_length(client_message_id) BETWEEN 1 AND 128),
    CONSTRAINT message_type_positive CHECK (message_type > 0),
    CONSTRAINT message_payload_bounded CHECK (octet_length(payload) <= 1048576),
    CONSTRAINT message_payload_hash_length CHECK (octet_length(payload_sha256) = 32),
    CONSTRAINT message_conversation_sequence_unique
        UNIQUE (conversation_id, conversation_sequence),
    CONSTRAINT message_sender_client_id_unique
        UNIQUE (sender_account_id, client_message_id)
);

CREATE INDEX message_conversation_history_idx
    ON message (conversation_id, conversation_sequence DESC);
