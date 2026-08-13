CREATE TABLE profile_image_import_run (
    id UUID PRIMARY KEY,
    manifest_sha256 CHAR(64) NOT NULL UNIQUE,
    backup_file_sha256 CHAR(64) NOT NULL,
    identity_fingerprint_sha256 CHAR(64) NOT NULL,
    source_entries INTEGER NOT NULL,
    present_entries INTEGER NOT NULL,
    absent_entries INTEGER NOT NULL,
    unique_objects INTEGER NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT profile_image_import_manifest_hex
        CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT profile_image_import_backup_hex
        CHECK (backup_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT profile_image_import_identity_hex
        CHECK (identity_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT profile_image_import_counts_nonnegative CHECK (
        source_entries >= 0 AND present_entries >= 0
        AND absent_entries >= 0 AND unique_objects >= 0),
    CONSTRAINT profile_image_import_counts_reconcile CHECK (
        present_entries + absent_entries = source_entries
        AND unique_objects <= present_entries)
);

CREATE TABLE profile_image_import_entry (
    import_run_id UUID NOT NULL REFERENCES profile_image_import_run(id) ON DELETE CASCADE,
    target_kind VARCHAR(16) NOT NULL,
    legacy_target_id BIGINT NOT NULL,
    target_account_id UUID REFERENCES account(id),
    target_conversation_id UUID,
    target_conversation_kind VARCHAR(16) GENERATED ALWAYS AS (
        CASE WHEN target_kind = 'ROOM' THEN 'GROUP' END
    ) STORED,
    object_key VARCHAR(1024) REFERENCES profile_image_object(object_key),
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    source_updated_at TIMESTAMPTZ,
    PRIMARY KEY (import_run_id, target_kind, legacy_target_id),
    CONSTRAINT profile_image_import_entry_legacy_id CHECK (legacy_target_id > 0),
    CONSTRAINT profile_image_import_entry_target CHECK (
        (target_kind = 'ACCOUNT' AND target_account_id IS NOT NULL
            AND target_conversation_id IS NULL)
        OR (target_kind = 'ROOM' AND target_account_id IS NULL
            AND target_conversation_id IS NOT NULL)),
    CONSTRAINT profile_image_import_entry_state CHECK (
        (object_key IS NULL AND width = 0 AND height = 0
            AND source_updated_at IS NULL)
        OR (object_key IS NOT NULL AND width BETWEEN 1 AND 1024
            AND height BETWEEN 1 AND 1024 AND source_updated_at IS NOT NULL)),
    CONSTRAINT profile_image_import_entry_conversation FOREIGN KEY (
        target_conversation_id, target_conversation_kind)
        REFERENCES conversation(id, kind)
);

CREATE UNIQUE INDEX profile_image_import_entry_account_target_idx
    ON profile_image_import_entry (import_run_id, target_account_id)
    WHERE target_kind = 'ACCOUNT';
CREATE UNIQUE INDEX profile_image_import_entry_room_target_idx
    ON profile_image_import_entry (import_run_id, target_conversation_id)
    WHERE target_kind = 'ROOM';
