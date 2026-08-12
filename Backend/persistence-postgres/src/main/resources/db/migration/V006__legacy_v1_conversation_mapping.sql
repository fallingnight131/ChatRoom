ALTER TABLE conversation
    ADD CONSTRAINT conversation_id_kind_unique UNIQUE (id, kind);

CREATE TABLE legacy_v1_conversation_map (
    legacy_kind VARCHAR(16) NOT NULL,
    legacy_conversation_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL UNIQUE,
    conversation_kind VARCHAR(16) GENERATED ALWAYS AS (
        CASE legacy_kind
            WHEN 'ROOM' THEN 'GROUP'
            WHEN 'FRIENDSHIP' THEN 'DIRECT'
        END
    ) STORED,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (legacy_kind, legacy_conversation_id),
    CONSTRAINT legacy_v1_conversation_map_kind_supported
        CHECK (legacy_kind IN ('ROOM', 'FRIENDSHIP')),
    CONSTRAINT legacy_v1_conversation_map_id_positive
        CHECK (legacy_conversation_id > 0),
    CONSTRAINT legacy_v1_conversation_map_target
        FOREIGN KEY (conversation_id, conversation_kind)
        REFERENCES conversation(id, kind) ON DELETE CASCADE
);
