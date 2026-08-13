ALTER TABLE chat.conversation_entry
    DROP CONSTRAINT conversation_entry_kind_supported;
ALTER TABLE chat.conversation_entry
    ADD CONSTRAINT conversation_entry_kind_supported CHECK (
        entry_kind IN (
            'MESSAGE',
            'MESSAGE_RECALLED',
            'MESSAGES_DELETED',
            'MESSAGE_REACTION_CHANGED'));

CREATE TABLE chat.message_reaction_operation (
    actor_account_id UUID NOT NULL REFERENCES chat.account(id),
    client_operation_id VARCHAR(128) NOT NULL,
    conversation_id UUID NOT NULL REFERENCES chat.conversation(id),
    actor_device_id UUID NOT NULL,
    message_id UUID NOT NULL,
    reaction VARCHAR(16) NOT NULL,
    desired_active BOOLEAN NOT NULL,
    changed BOOLEAN NOT NULL,
    conversation_sequence BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (actor_account_id, client_operation_id),
    CONSTRAINT message_reaction_operation_actor_device
        FOREIGN KEY (actor_device_id, actor_account_id)
        REFERENCES chat.device(id, account_id),
    CONSTRAINT message_reaction_operation_id_length CHECK (
        char_length(client_operation_id) BETWEEN 1 AND 128),
    CONSTRAINT message_reaction_operation_kind_supported CHECK (
        reaction IN ('LIKE', 'LOVE', 'LAUGH', 'SURPRISED', 'SAD', 'ANGRY')),
    CONSTRAINT message_reaction_operation_sequence_shape CHECK (
        (changed AND conversation_sequence > 0)
        OR (NOT changed AND conversation_sequence IS NULL))
);

CREATE INDEX message_reaction_operation_conversation_idx
    ON chat.message_reaction_operation (conversation_id, occurred_at DESC);

CREATE TABLE chat.message_reaction (
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES chat.account(id),
    reaction VARCHAR(16) NOT NULL,
    last_conversation_sequence BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (message_id, actor_account_id, reaction),
    CONSTRAINT message_reaction_target
        FOREIGN KEY (conversation_id, message_id)
        REFERENCES chat.message(conversation_id, id) ON DELETE CASCADE,
    CONSTRAINT message_reaction_kind_supported CHECK (
        reaction IN ('LIKE', 'LOVE', 'LAUGH', 'SURPRISED', 'SAD', 'ANGRY')),
    CONSTRAINT message_reaction_sequence_positive CHECK (
        last_conversation_sequence > 0)
);

CREATE INDEX message_reaction_conversation_target_idx
    ON chat.message_reaction (conversation_id, message_id, reaction);

CREATE TABLE chat.message_reaction_event (
    conversation_id UUID NOT NULL,
    conversation_sequence BIGINT NOT NULL,
    entry_kind VARCHAR(32)
        GENERATED ALWAYS AS ('MESSAGE_REACTION_CHANGED') STORED,
    message_id UUID NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES chat.account(id),
    actor_device_id UUID NOT NULL,
    reaction VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL,
    client_operation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id, conversation_sequence),
    CONSTRAINT message_reaction_event_entry
        FOREIGN KEY (conversation_id, conversation_sequence, entry_kind)
        REFERENCES chat.conversation_entry(
            conversation_id, conversation_sequence, entry_kind),
    CONSTRAINT message_reaction_event_target
        FOREIGN KEY (conversation_id, message_id)
        REFERENCES chat.message(conversation_id, id) ON DELETE CASCADE,
    CONSTRAINT message_reaction_event_actor_device
        FOREIGN KEY (actor_device_id, actor_account_id)
        REFERENCES chat.device(id, account_id),
    CONSTRAINT message_reaction_event_operation_unique
        UNIQUE (actor_account_id, client_operation_id),
    CONSTRAINT message_reaction_event_operation_id_length CHECK (
        char_length(client_operation_id) BETWEEN 1 AND 128),
    CONSTRAINT message_reaction_event_kind_supported CHECK (
        reaction IN ('LIKE', 'LOVE', 'LAUGH', 'SURPRISED', 'SAD', 'ANGRY'))
);

CREATE FUNCTION chat.remove_deleted_message_reaction_entry()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM chat.conversation_entry
     WHERE conversation_id = OLD.conversation_id
       AND conversation_sequence = OLD.conversation_sequence
       AND entry_kind = 'MESSAGE_REACTION_CHANGED';
    RETURN OLD;
END;
$$;

CREATE TRIGGER message_reaction_event_remove_entry
AFTER DELETE ON chat.message_reaction_event
FOR EACH ROW EXECUTE FUNCTION chat.remove_deleted_message_reaction_entry();
