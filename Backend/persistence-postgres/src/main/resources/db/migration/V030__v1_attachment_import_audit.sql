ALTER TABLE legacy_v1_message_map
    DROP CONSTRAINT legacy_v1_message_map_content_type_supported,
    ADD CONSTRAINT legacy_v1_message_map_content_type_supported
    CHECK (legacy_content_type IS NULL OR legacy_content_type IN (
        'text', 'emoji', 'file', 'image', 'video'));

CREATE TABLE attachment_import_run (
    id UUID PRIMARY KEY REFERENCES message_import_run(id) ON DELETE CASCADE,
    source_fingerprint_sha256 CHAR(64) NOT NULL,
    evidence_fingerprint_sha256 CHAR(64) NOT NULL,
    backup_file_sha256 CHAR(64) NOT NULL,
    source_attachments INTEGER NOT NULL,
    supplied_object_evidence INTEGER NOT NULL,
    inserted_attachments INTEGER NOT NULL,
    already_imported_attachments INTEGER NOT NULL,
    backup_bytes BIGINT NOT NULL,
    backup_created_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT attachment_import_hashes_shape CHECK (
        source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
        AND evidence_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
        AND backup_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT attachment_import_counts_nonnegative CHECK (
        source_attachments >= 0 AND supplied_object_evidence >= 0
        AND inserted_attachments >= 0 AND already_imported_attachments >= 0),
    CONSTRAINT attachment_import_reconciliation CHECK (
        inserted_attachments + already_imported_attachments = source_attachments),
    CONSTRAINT attachment_import_evidence_bound CHECK (
        supplied_object_evidence <= source_attachments),
    CONSTRAINT attachment_import_backup_bytes_positive CHECK (backup_bytes > 0)
);
