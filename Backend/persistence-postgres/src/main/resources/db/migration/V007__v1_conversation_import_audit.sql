CREATE TABLE conversation_import_run (
    id UUID PRIMARY KEY,
    source_fingerprint_sha256 CHAR(64) NOT NULL,
    backup_file_sha256 CHAR(64) NOT NULL,
    source_rooms INTEGER NOT NULL,
    source_friendships INTEGER NOT NULL,
    source_memberships INTEGER NOT NULL,
    inserted_conversations INTEGER NOT NULL,
    already_imported_conversations INTEGER NOT NULL,
    inserted_memberships INTEGER NOT NULL,
    already_imported_memberships INTEGER NOT NULL,
    backup_bytes BIGINT NOT NULL,
    backup_created_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT conversation_import_source_hash_shape CHECK (
        source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT conversation_import_backup_hash_shape CHECK (
        backup_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT conversation_import_source_counts_nonnegative CHECK (
        source_rooms >= 0 AND source_friendships >= 0 AND source_memberships >= 0),
    CONSTRAINT conversation_import_result_counts_nonnegative CHECK (
        inserted_conversations >= 0
        AND already_imported_conversations >= 0
        AND inserted_memberships >= 0
        AND already_imported_memberships >= 0),
    CONSTRAINT conversation_import_conversation_reconciliation CHECK (
        inserted_conversations + already_imported_conversations
        = source_rooms + source_friendships),
    CONSTRAINT conversation_import_membership_reconciliation CHECK (
        inserted_memberships + already_imported_memberships = source_memberships),
    CONSTRAINT conversation_import_backup_bytes_positive CHECK (backup_bytes > 0)
);
