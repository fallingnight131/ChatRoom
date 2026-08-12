ALTER TABLE message
    ADD COLUMN attachment_id UUID;

ALTER TABLE message
    ADD CONSTRAINT message_attachment_target
        FOREIGN KEY (conversation_id, attachment_id)
        REFERENCES attachment(conversation_id, id),
    ADD CONSTRAINT message_attachment_shape CHECK (
        (
            message_type = 2
            AND attachment_id IS NOT NULL
            AND octet_length(payload) = 0
        )
        OR (
            message_type <> 2
            AND attachment_id IS NULL
        )
    );

CREATE UNIQUE INDEX message_attachment_unique_idx
    ON message (attachment_id) WHERE attachment_id IS NOT NULL;

CREATE TABLE legacy_v1_attachment_map (
    legacy_kind VARCHAR(16) NOT NULL,
    legacy_file_id BIGINT NOT NULL,
    legacy_conversation_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    attachment_id UUID NOT NULL UNIQUE,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (legacy_kind, legacy_file_id),
    CONSTRAINT legacy_v1_attachment_map_kind_supported
        CHECK (legacy_kind IN ('ROOM', 'FRIENDSHIP')),
    CONSTRAINT legacy_v1_attachment_map_ids_positive
        CHECK (legacy_file_id > 0 AND legacy_conversation_id > 0),
    CONSTRAINT legacy_v1_attachment_map_conversation
        FOREIGN KEY (legacy_kind, legacy_conversation_id, conversation_id)
        REFERENCES legacy_v1_conversation_map(
            legacy_kind, legacy_conversation_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT legacy_v1_attachment_map_target
        FOREIGN KEY (conversation_id, attachment_id)
        REFERENCES attachment(conversation_id, id) ON DELETE CASCADE
);
