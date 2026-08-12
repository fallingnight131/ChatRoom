ALTER TABLE legacy_v1_conversation_map
    ADD CONSTRAINT legacy_v1_conversation_map_source_target_unique
    UNIQUE (legacy_kind, legacy_conversation_id, conversation_id);

CREATE TABLE legacy_v1_message_map (
    legacy_kind VARCHAR(16) NOT NULL,
    legacy_message_id BIGINT NOT NULL,
    legacy_conversation_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL UNIQUE,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (legacy_kind, legacy_message_id),
    CONSTRAINT legacy_v1_message_map_kind_supported
        CHECK (legacy_kind IN ('ROOM', 'FRIENDSHIP')),
    CONSTRAINT legacy_v1_message_map_ids_positive
        CHECK (legacy_message_id > 0 AND legacy_conversation_id > 0),
    CONSTRAINT legacy_v1_message_map_conversation
        FOREIGN KEY (legacy_kind, legacy_conversation_id, conversation_id)
        REFERENCES legacy_v1_conversation_map(
            legacy_kind, legacy_conversation_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT legacy_v1_message_map_target
        FOREIGN KEY (conversation_id, message_id)
        REFERENCES message(conversation_id, id) ON DELETE CASCADE
);

ALTER TABLE conversation_entry
    ADD CONSTRAINT conversation_entry_identity_kind_unique
    UNIQUE (conversation_id, conversation_sequence, entry_kind);

CREATE TABLE legacy_v1_deletion_event_map (
    legacy_event_id BIGINT PRIMARY KEY,
    legacy_room_id BIGINT NOT NULL,
    legacy_kind VARCHAR(16) GENERATED ALWAYS AS ('ROOM') STORED,
    conversation_id UUID NOT NULL,
    conversation_sequence BIGINT NOT NULL,
    entry_kind VARCHAR(32) GENERATED ALWAYS AS ('MESSAGES_DELETED') STORED,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT legacy_v1_deletion_event_map_ids_positive
        CHECK (legacy_event_id > 0 AND legacy_room_id > 0
            AND conversation_sequence > 0),
    CONSTRAINT legacy_v1_deletion_event_map_conversation
        FOREIGN KEY (legacy_kind, legacy_room_id, conversation_id)
        REFERENCES legacy_v1_conversation_map(
            legacy_kind, legacy_conversation_id, conversation_id),
    CONSTRAINT legacy_v1_deletion_event_map_target
        FOREIGN KEY (conversation_id, conversation_sequence, entry_kind)
        REFERENCES conversation_entry(
            conversation_id, conversation_sequence, entry_kind) ON DELETE CASCADE
);
