ALTER TABLE chat.message
    ADD COLUMN content_revision INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN edited_at TIMESTAMPTZ,
    ADD CONSTRAINT message_content_revision_bounded CHECK (
        content_revision BETWEEN 0 AND 100),
    ADD CONSTRAINT message_edit_metadata_shape CHECK (
        (content_revision = 0 AND edited_at IS NULL)
        OR (content_revision > 0 AND edited_at IS NOT NULL));

ALTER TABLE chat.conversation_entry
    DROP CONSTRAINT conversation_entry_kind_supported;
ALTER TABLE chat.conversation_entry
    ADD CONSTRAINT conversation_entry_kind_supported CHECK (
        entry_kind IN (
            'MESSAGE', 'MESSAGE_RECALLED', 'MESSAGES_DELETED',
            'MESSAGE_REACTION_CHANGED', 'MESSAGE_PIN_CHANGED', 'MESSAGE_EDITED'));

CREATE TABLE chat.message_edit_operation (
    actor_account_id UUID NOT NULL REFERENCES chat.account(id),
    client_operation_id VARCHAR(128) NOT NULL,
    conversation_id UUID NOT NULL REFERENCES chat.conversation(id),
    actor_device_id UUID NOT NULL,
    message_id UUID NOT NULL,
    expected_revision INTEGER NOT NULL,
    content_type INTEGER NOT NULL,
    requested_content_sha256 BYTEA NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    result_revision INTEGER NOT NULL,
    changed BOOLEAN NOT NULL,
    conversation_sequence BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (actor_account_id, client_operation_id),
    CONSTRAINT message_edit_operation_actor_device
        FOREIGN KEY (actor_device_id, actor_account_id)
        REFERENCES chat.device(id, account_id),
    CONSTRAINT message_edit_operation_id_length CHECK (
        char_length(client_operation_id) BETWEEN 1 AND 128),
    CONSTRAINT message_edit_operation_revision_bounded CHECK (
        expected_revision BETWEEN 0 AND 100 AND result_revision BETWEEN 0 AND 100),
    CONSTRAINT message_edit_operation_text_only CHECK (content_type = 1),
    CONSTRAINT message_edit_operation_hash_length CHECK (
        octet_length(requested_content_sha256) = 32),
    CONSTRAINT message_edit_operation_outcome_supported CHECK (
        outcome IN ('APPLIED', 'STALE_REVISION', 'WINDOW_EXPIRED', 'REVISION_LIMIT')),
    CONSTRAINT message_edit_operation_result_shape CHECK (
        (outcome = 'APPLIED' AND changed AND conversation_sequence > 0)
        OR (outcome = 'APPLIED' AND NOT changed AND conversation_sequence IS NULL)
        OR (outcome <> 'APPLIED' AND NOT changed AND conversation_sequence IS NULL))
);

CREATE INDEX message_edit_operation_conversation_idx
    ON chat.message_edit_operation (conversation_id, occurred_at DESC);

CREATE TABLE chat.message_edit_event (
    conversation_id UUID NOT NULL,
    conversation_sequence BIGINT NOT NULL,
    entry_kind VARCHAR(32) GENERATED ALWAYS AS ('MESSAGE_EDITED') STORED,
    message_id UUID NOT NULL,
    content_revision INTEGER NOT NULL,
    content_type INTEGER NOT NULL,
    content BYTEA,
    content_sha256 BYTEA NOT NULL,
    content_erased_at TIMESTAMPTZ,
    actor_account_id UUID NOT NULL REFERENCES chat.account(id),
    actor_device_id UUID NOT NULL,
    client_operation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id, conversation_sequence),
    CONSTRAINT message_edit_event_entry
        FOREIGN KEY (conversation_id, conversation_sequence, entry_kind)
        REFERENCES chat.conversation_entry(
            conversation_id, conversation_sequence, entry_kind),
    CONSTRAINT message_edit_event_target
        FOREIGN KEY (conversation_id, message_id)
        REFERENCES chat.message(conversation_id, id) ON DELETE CASCADE,
    CONSTRAINT message_edit_event_actor_device
        FOREIGN KEY (actor_device_id, actor_account_id)
        REFERENCES chat.device(id, account_id),
    CONSTRAINT message_edit_event_revision_bounded CHECK (
        content_revision BETWEEN 1 AND 100),
    CONSTRAINT message_edit_event_text_only CHECK (content_type = 1),
    CONSTRAINT message_edit_event_content_bounded CHECK (
        content IS NULL OR octet_length(content) BETWEEN 1 AND 65536),
    CONSTRAINT message_edit_event_hash_length CHECK (
        octet_length(content_sha256) = 32),
    CONSTRAINT message_edit_event_content_shape CHECK (
        (content IS NOT NULL AND content_erased_at IS NULL)
        OR (content IS NULL AND content_erased_at IS NOT NULL)),
    CONSTRAINT message_edit_event_operation_id_length CHECK (
        char_length(client_operation_id) BETWEEN 1 AND 128),
    CONSTRAINT message_edit_event_revision_unique
        UNIQUE (conversation_id, message_id, content_revision)
);

CREATE INDEX message_edit_event_target_idx
    ON chat.message_edit_event (conversation_id, message_id, content_revision DESC);

CREATE FUNCTION chat.erase_recalled_message_edit_bodies()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE chat.message_edit_event
       SET content = NULL, content_erased_at = transaction_timestamp()
     WHERE conversation_id = NEW.conversation_id
       AND message_id = NEW.message_id
       AND content IS NOT NULL;
    RETURN NEW;
END;
$$;

CREATE TRIGGER message_recall_erase_edit_bodies
AFTER INSERT ON chat.message_recall_event
FOR EACH ROW EXECUTE FUNCTION chat.erase_recalled_message_edit_bodies();

CREATE FUNCTION chat.erase_deleted_message_edit_bodies()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source = 'V2' THEN
        UPDATE chat.message_edit_event
           SET content = NULL, content_erased_at = transaction_timestamp()
         WHERE conversation_id = NEW.conversation_id
           AND NEW.message_ids ? message_id::text
           AND content IS NOT NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER messages_deleted_erase_edit_bodies
AFTER INSERT ON chat.messages_deleted_event
FOR EACH ROW EXECUTE FUNCTION chat.erase_deleted_message_edit_bodies();
