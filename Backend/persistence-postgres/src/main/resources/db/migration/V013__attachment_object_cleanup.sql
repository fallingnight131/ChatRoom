ALTER TABLE attachment
    ADD COLUMN object_deleted_at TIMESTAMPTZ;

ALTER TABLE attachment
    ADD CONSTRAINT attachment_object_deletion_lifecycle CHECK (
        object_deleted_at IS NULL
        OR (
            state = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND object_deleted_at >= revoked_at
        )
    );

CREATE INDEX attachment_cleanup_required_idx
    ON attachment (revoked_at, id)
    WHERE state = 'REVOKED' AND object_deleted_at IS NULL;
