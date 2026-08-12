CREATE TABLE attachment (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id),
    owner_account_id UUID NOT NULL,
    owner_device_id UUID NOT NULL,
    client_attachment_id VARCHAR(128) NOT NULL,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(127) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_sha256 BYTEA NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'UPLOAD_PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    ready_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT attachment_owner_membership
        FOREIGN KEY (conversation_id, owner_account_id)
        REFERENCES conversation_member(conversation_id, account_id),
    CONSTRAINT attachment_owner_device
        FOREIGN KEY (owner_device_id, owner_account_id)
        REFERENCES device(id, account_id),
    CONSTRAINT attachment_conversation_id_unique UNIQUE (conversation_id, id),
    CONSTRAINT attachment_owner_client_unique
        UNIQUE (owner_account_id, client_attachment_id),
    CONSTRAINT attachment_client_id_length CHECK (
        char_length(client_attachment_id) BETWEEN 1 AND 128),
    CONSTRAINT attachment_object_key_length CHECK (
        char_length(object_key) BETWEEN 1 AND 512),
    CONSTRAINT attachment_file_name_length CHECK (
        octet_length(file_name) BETWEEN 1 AND 255),
    CONSTRAINT attachment_media_type_length CHECK (
        octet_length(media_type) BETWEEN 1 AND 127),
    CONSTRAINT attachment_byte_size_supported CHECK (
        byte_size BETWEEN 1 AND 10737418240),
    CONSTRAINT attachment_hash_length CHECK (octet_length(content_sha256) = 32),
    CONSTRAINT attachment_state_supported CHECK (
        state IN ('UPLOAD_PENDING', 'READY', 'REVOKED')),
    CONSTRAINT attachment_state_timestamps CHECK (
        (state = 'UPLOAD_PENDING' AND ready_at IS NULL AND revoked_at IS NULL)
        OR (state = 'READY' AND ready_at IS NOT NULL AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL)),
    CONSTRAINT attachment_ready_order CHECK (
        ready_at IS NULL OR ready_at >= created_at),
    CONSTRAINT attachment_revoked_order CHECK (
        revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX attachment_conversation_created_idx
    ON attachment (conversation_id, created_at DESC, id DESC);

CREATE INDEX attachment_pending_created_idx
    ON attachment (created_at, id) WHERE state = 'UPLOAD_PENDING';
