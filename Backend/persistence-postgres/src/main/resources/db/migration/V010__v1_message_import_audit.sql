CREATE TABLE message_import_run (
    id UUID PRIMARY KEY,
    state_fingerprint_sha256 CHAR(64) NOT NULL,
    payload_fingerprint_sha256 CHAR(64) NOT NULL,
    backup_file_sha256 CHAR(64) NOT NULL,
    source_messages INTEGER NOT NULL,
    source_recalled_messages INTEGER NOT NULL,
    source_deletion_events INTEGER NOT NULL,
    source_legacy_devices INTEGER NOT NULL,
    source_member_read_cursors INTEGER NOT NULL,
    inserted_messages INTEGER NOT NULL,
    already_imported_messages INTEGER NOT NULL,
    inserted_entries INTEGER NOT NULL,
    already_imported_entries INTEGER NOT NULL,
    inserted_legacy_devices INTEGER NOT NULL,
    already_imported_legacy_devices INTEGER NOT NULL,
    updated_read_cursors INTEGER NOT NULL,
    already_translated_read_cursors INTEGER NOT NULL,
    backup_bytes BIGINT NOT NULL,
    backup_created_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT message_import_state_hash_shape CHECK (
        state_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT message_import_payload_hash_shape CHECK (
        payload_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT message_import_backup_hash_shape CHECK (
        backup_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT message_import_source_counts_nonnegative CHECK (
        source_messages >= 0 AND source_recalled_messages >= 0
        AND source_deletion_events >= 0 AND source_legacy_devices >= 0
        AND source_member_read_cursors >= 0),
    CONSTRAINT message_import_result_counts_nonnegative CHECK (
        inserted_messages >= 0 AND already_imported_messages >= 0
        AND inserted_entries >= 0 AND already_imported_entries >= 0
        AND inserted_legacy_devices >= 0 AND already_imported_legacy_devices >= 0
        AND updated_read_cursors >= 0 AND already_translated_read_cursors >= 0),
    CONSTRAINT message_import_message_reconciliation CHECK (
        inserted_messages + already_imported_messages = source_messages),
    CONSTRAINT message_import_entry_reconciliation CHECK (
        inserted_entries + already_imported_entries
        = source_messages + source_recalled_messages + source_deletion_events),
    CONSTRAINT message_import_device_reconciliation CHECK (
        inserted_legacy_devices + already_imported_legacy_devices
        = source_legacy_devices),
    CONSTRAINT message_import_read_reconciliation CHECK (
        updated_read_cursors + already_translated_read_cursors
        = source_member_read_cursors),
    CONSTRAINT message_import_recalled_bound CHECK (
        source_recalled_messages <= source_messages),
    CONSTRAINT message_import_backup_bytes_positive CHECK (backup_bytes > 0)
);
