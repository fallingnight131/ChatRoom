ALTER TABLE attachment
    DROP CONSTRAINT attachment_state_supported,
    DROP CONSTRAINT attachment_state_timestamps,
    DROP CONSTRAINT attachment_hash_length,
    ALTER COLUMN object_key DROP NOT NULL,
    ALTER COLUMN media_type DROP NOT NULL,
    ALTER COLUMN content_sha256 DROP NOT NULL,
    ADD COLUMN unavailable_at TIMESTAMPTZ,
    ADD COLUMN unavailable_reason VARCHAR(255);

ALTER TABLE attachment
    ADD CONSTRAINT attachment_state_supported CHECK (
        state IN ('UPLOAD_PENDING', 'READY', 'REVOKED', 'UNAVAILABLE')),
    ADD CONSTRAINT attachment_object_evidence_shape CHECK (
        (
            state IN ('UPLOAD_PENDING', 'READY', 'REVOKED')
            AND object_key IS NOT NULL
            AND media_type IS NOT NULL
            AND content_sha256 IS NOT NULL
            AND octet_length(content_sha256) = 32
        )
        OR (
            state = 'UNAVAILABLE'
            AND object_key IS NULL
            AND media_type IS NULL
            AND content_sha256 IS NULL
        )
    ),
    ADD CONSTRAINT attachment_state_timestamps CHECK (
        (
            state = 'UPLOAD_PENDING'
            AND ready_at IS NULL
            AND revoked_at IS NULL
            AND unavailable_at IS NULL
        )
        OR (
            state = 'READY'
            AND ready_at IS NOT NULL
            AND revoked_at IS NULL
            AND unavailable_at IS NULL
        )
        OR (
            state = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND unavailable_at IS NULL
        )
        OR (
            state = 'UNAVAILABLE'
            AND ready_at IS NULL
            AND revoked_at IS NULL
            AND object_deleted_at IS NULL
            AND unavailable_at IS NOT NULL
        )
    ),
    ADD CONSTRAINT attachment_unavailable_reason_shape CHECK (
        (
            state = 'UNAVAILABLE'
            AND unavailable_reason IS NOT NULL
            AND octet_length(unavailable_reason) BETWEEN 1 AND 255
        )
        OR (state <> 'UNAVAILABLE' AND unavailable_reason IS NULL)
    ),
    ADD CONSTRAINT attachment_unavailable_order CHECK (
        unavailable_at IS NULL OR unavailable_at >= created_at);
