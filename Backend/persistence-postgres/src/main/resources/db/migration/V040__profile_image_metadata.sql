CREATE TABLE profile_image_object (
    object_key VARCHAR(1024) PRIMARY KEY,
    byte_size INTEGER NOT NULL,
    content_sha256 BYTEA NOT NULL UNIQUE,
    media_type VARCHAR(127) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    cleanup_requested_at TIMESTAMPTZ,
    delete_confirmed_at TIMESTAMPTZ,
    CONSTRAINT profile_image_object_size CHECK (byte_size BETWEEN 1 AND 262144),
    CONSTRAINT profile_image_object_sha CHECK (octet_length(content_sha256) = 32),
    CONSTRAINT profile_image_object_type CHECK (media_type = 'image/png'),
    CONSTRAINT profile_image_object_key_matches_sha CHECK (
        object_key = 'avatars/sha256/' || encode(content_sha256, 'hex') || '.png'),
    CONSTRAINT profile_image_object_cleanup_order CHECK (
        delete_confirmed_at IS NULL OR cleanup_requested_at IS NOT NULL)
);

CREATE TABLE account_profile_image (
    account_id UUID PRIMARY KEY REFERENCES account(id),
    object_key VARCHAR(1024) NOT NULL REFERENCES profile_image_object(object_key),
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT account_profile_image_dimensions CHECK (
        width BETWEEN 1 AND 1024 AND height BETWEEN 1 AND 1024),
    CONSTRAINT account_profile_image_version CHECK (version > 0)
);

CREATE TABLE group_profile_image (
    conversation_id UUID PRIMARY KEY,
    conversation_kind VARCHAR(16) GENERATED ALWAYS AS ('GROUP') STORED,
    object_key VARCHAR(1024) NOT NULL REFERENCES profile_image_object(object_key),
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT group_profile_image_target FOREIGN KEY (conversation_id, conversation_kind)
        REFERENCES conversation(id, kind) ON DELETE CASCADE,
    CONSTRAINT group_profile_image_dimensions CHECK (
        width BETWEEN 1 AND 1024 AND height BETWEEN 1 AND 1024),
    CONSTRAINT group_profile_image_version CHECK (version > 0)
);

CREATE TABLE profile_image_change_audit (
    id UUID PRIMARY KEY,
    target_kind VARCHAR(16) NOT NULL,
    target_account_id UUID REFERENCES account(id),
    target_conversation_id UUID REFERENCES conversation(id),
    actor_account_id UUID NOT NULL REFERENCES account(id),
    old_object_key VARCHAR(1024) REFERENCES profile_image_object(object_key),
    new_object_key VARCHAR(1024) NOT NULL REFERENCES profile_image_object(object_key),
    version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT profile_image_audit_target CHECK (
        (target_kind = 'ACCOUNT' AND target_account_id IS NOT NULL
            AND target_conversation_id IS NULL AND target_account_id = actor_account_id)
        OR (target_kind = 'GROUP' AND target_account_id IS NULL
            AND target_conversation_id IS NOT NULL)),
    CONSTRAINT profile_image_audit_changed CHECK (
        old_object_key IS NULL OR old_object_key <> new_object_key),
    CONSTRAINT profile_image_audit_version CHECK (version > 0)
);

CREATE INDEX profile_image_change_audit_account_idx
    ON profile_image_change_audit (target_account_id, occurred_at DESC, id DESC)
    WHERE target_kind = 'ACCOUNT';
CREATE INDEX profile_image_change_audit_group_idx
    ON profile_image_change_audit (target_conversation_id, occurred_at DESC, id DESC)
    WHERE target_kind = 'GROUP';
CREATE INDEX profile_image_object_cleanup_idx
    ON profile_image_object (cleanup_requested_at, object_key)
    WHERE cleanup_requested_at IS NOT NULL AND delete_confirmed_at IS NULL;
