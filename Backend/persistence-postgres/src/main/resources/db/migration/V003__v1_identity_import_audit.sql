CREATE TABLE identity_import_run (
    id UUID PRIMARY KEY,
    source_fingerprint_sha256 CHAR(64) NOT NULL,
    backup_file_sha256 CHAR(64) NOT NULL,
    source_rows INTEGER NOT NULL,
    inserted_rows INTEGER NOT NULL,
    already_imported_rows INTEGER NOT NULL,
    backup_bytes BIGINT NOT NULL,
    backup_created_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT identity_import_source_fingerprint_hex
        CHECK (source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_import_backup_hash_hex
        CHECK (backup_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_import_counts_nonnegative
        CHECK (source_rows > 0 AND inserted_rows >= 0 AND already_imported_rows >= 0),
    CONSTRAINT identity_import_counts_reconcile
        CHECK (inserted_rows + already_imported_rows = source_rows),
    CONSTRAINT identity_import_backup_nonempty CHECK (backup_bytes > 0)
);

CREATE INDEX identity_import_run_source_idx
    ON identity_import_run (source_fingerprint_sha256, applied_at DESC);
