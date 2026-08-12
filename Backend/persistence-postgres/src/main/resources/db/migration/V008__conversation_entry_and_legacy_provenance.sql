ALTER TABLE device DROP CONSTRAINT device_platform_supported;
ALTER TABLE device ADD CONSTRAINT device_platform_supported
    CHECK (platform IN ('WEB', 'WINDOWS', 'LEGACY'));

CREATE TABLE conversation_entry (
    conversation_id UUID NOT NULL REFERENCES conversation(id),
    conversation_sequence BIGINT NOT NULL,
    entry_kind VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (conversation_id, conversation_sequence),
    CONSTRAINT conversation_entry_sequence_positive CHECK (conversation_sequence > 0),
    CONSTRAINT conversation_entry_kind_supported CHECK (
        entry_kind IN ('MESSAGE', 'MESSAGE_RECALLED', 'MESSAGES_DELETED'))
);

INSERT INTO conversation_entry(
    conversation_id, conversation_sequence, entry_kind, occurred_at, ingested_at)
SELECT conversation_id, conversation_sequence, 'MESSAGE', accepted_at, accepted_at
FROM message;

ALTER TABLE message ADD CONSTRAINT message_conversation_id_id_unique
    UNIQUE (conversation_id, id);
ALTER TABLE message ADD CONSTRAINT message_conversation_entry
    FOREIGN KEY (conversation_id, conversation_sequence)
    REFERENCES conversation_entry(conversation_id, conversation_sequence);

CREATE TABLE message_recall_event (
    conversation_id UUID NOT NULL,
    conversation_sequence BIGINT NOT NULL,
    message_id UUID NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES account(id),
    source VARCHAR(16) NOT NULL,
    PRIMARY KEY (conversation_id, conversation_sequence),
    CONSTRAINT message_recall_entry FOREIGN KEY (conversation_id, conversation_sequence)
        REFERENCES conversation_entry(conversation_id, conversation_sequence),
    CONSTRAINT message_recall_target FOREIGN KEY (conversation_id, message_id)
        REFERENCES message(conversation_id, id),
    CONSTRAINT message_recall_source_supported CHECK (source IN ('V2', 'V1_IMPORT'))
);

CREATE TABLE messages_deleted_event (
    conversation_id UUID NOT NULL,
    conversation_sequence BIGINT NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES account(id),
    source VARCHAR(16) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    client_operation_id VARCHAR(128) NOT NULL,
    command_fingerprint VARCHAR(128) NOT NULL,
    message_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    file_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    cutoff_epoch_ms BIGINT NOT NULL DEFAULT 0,
    deleted_count INTEGER NOT NULL,
    PRIMARY KEY (conversation_id, conversation_sequence),
    CONSTRAINT messages_deleted_entry FOREIGN KEY (conversation_id, conversation_sequence)
        REFERENCES conversation_entry(conversation_id, conversation_sequence),
    CONSTRAINT messages_deleted_source_supported CHECK (source IN ('V2', 'V1_IMPORT')),
    CONSTRAINT messages_deleted_message_ids_array CHECK (jsonb_typeof(message_ids) = 'array'),
    CONSTRAINT messages_deleted_file_ids_array CHECK (jsonb_typeof(file_ids) = 'array'),
    CONSTRAINT messages_deleted_cutoff_nonnegative CHECK (cutoff_epoch_ms >= 0),
    CONSTRAINT messages_deleted_count_nonnegative CHECK (deleted_count >= 0),
    CONSTRAINT messages_deleted_operation_id_length CHECK (
        char_length(client_operation_id) BETWEEN 1 AND 128),
    CONSTRAINT messages_deleted_fingerprint_length CHECK (
        char_length(command_fingerprint) BETWEEN 1 AND 128)
);
