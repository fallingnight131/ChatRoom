ALTER TABLE conversation_member
    ADD CONSTRAINT conversation_member_identity_role_unique
    UNIQUE (conversation_id, account_id, role);

CREATE TABLE group_join_credential (
    conversation_id UUID PRIMARY KEY,
    conversation_kind VARCHAR(16) GENERATED ALWAYS AS ('GROUP') STORED,
    encoded_password VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT group_join_credential_target
        FOREIGN KEY (conversation_id, conversation_kind)
        REFERENCES conversation(id, kind) ON DELETE CASCADE,
    CONSTRAINT group_join_credential_argon2id
        CHECK (encoded_password LIKE '$argon2id$%')
);

CREATE TABLE legacy_v1_room_creation (
    actor_account_id UUID NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    room_name VARCHAR(100) NOT NULL,
    password_idempotency_tag VARCHAR(255),
    conversation_id UUID NOT NULL UNIQUE,
    creator_role VARCHAR(16) GENERATED ALWAYS AS ('OWNER') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (actor_account_id, client_request_id),
    CONSTRAINT legacy_v1_room_creation_request_id
        CHECK (octet_length(client_request_id) BETWEEN 1 AND 128),
    CONSTRAINT legacy_v1_room_creation_name
        CHECK (char_length(room_name) BETWEEN 1 AND 100),
    CONSTRAINT legacy_v1_room_creation_password_tag
        CHECK (password_idempotency_tag IS NULL OR
               password_idempotency_tag ~ '^hmac-sha256:v[1-9][0-9]*:[A-Za-z0-9_-]{43}$'),
    CONSTRAINT legacy_v1_room_creation_owner
        FOREIGN KEY (conversation_id, actor_account_id, creator_role)
        REFERENCES conversation_member(conversation_id, account_id, role)
        ON DELETE CASCADE
);

CREATE SEQUENCE legacy_v1_room_id_seq
    AS BIGINT
    START WITH 2147483647
    INCREMENT BY -1
    MINVALUE 1
    MAXVALUE 2147483647
    NO CYCLE;
