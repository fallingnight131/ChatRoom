CREATE TABLE contact_request_import_run (
    id UUID PRIMARY KEY,
    source_fingerprint_sha256 CHAR(64) NOT NULL,
    backup_file_sha256 CHAR(64) NOT NULL,
    source_requests INTEGER NOT NULL,
    source_pending_requests INTEGER NOT NULL,
    source_terminal_requests INTEGER NOT NULL,
    inserted_pending_requests INTEGER NOT NULL,
    already_imported_pending_requests INTEGER NOT NULL,
    backup_bytes BIGINT NOT NULL,
    backup_created_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT contact_request_import_source_hash_shape CHECK (
        source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT contact_request_import_backup_hash_shape CHECK (
        backup_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT contact_request_import_source_counts_nonnegative CHECK (
        source_requests >= 0
        AND source_pending_requests >= 0
        AND source_terminal_requests >= 0),
    CONSTRAINT contact_request_import_source_reconciliation CHECK (
        source_pending_requests + source_terminal_requests = source_requests),
    CONSTRAINT contact_request_import_result_counts_nonnegative CHECK (
        inserted_pending_requests >= 0
        AND already_imported_pending_requests >= 0),
    CONSTRAINT contact_request_import_pending_reconciliation CHECK (
        inserted_pending_requests + already_imported_pending_requests
        = source_pending_requests),
    CONSTRAINT contact_request_import_backup_bytes_positive CHECK (backup_bytes > 0)
);
