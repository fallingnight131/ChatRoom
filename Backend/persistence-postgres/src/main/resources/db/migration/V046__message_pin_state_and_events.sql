ALTER TABLE chat.conversation_entry
    DROP CONSTRAINT conversation_entry_kind_supported;
ALTER TABLE chat.conversation_entry
    ADD CONSTRAINT conversation_entry_kind_supported CHECK (
        entry_kind IN (
            'MESSAGE', 'MESSAGE_RECALLED', 'MESSAGES_DELETED',
            'MESSAGE_REACTION_CHANGED', 'MESSAGE_PIN_CHANGED'));

CREATE TABLE chat.message_pin_operation (
    actor_account_id UUID NOT NULL REFERENCES chat.account(id),
    client_operation_id VARCHAR(128) NOT NULL,
    conversation_id UUID NOT NULL REFERENCES chat.conversation(id),
    actor_device_id UUID NOT NULL,
    message_id UUID NOT NULL,
    desired_pinned BOOLEAN NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    changed BOOLEAN NOT NULL,
    conversation_sequence BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (actor_account_id, client_operation_id),
    CONSTRAINT message_pin_operation_actor_device
        FOREIGN KEY (actor_device_id, actor_account_id)
        REFERENCES chat.device(id, account_id),
    CONSTRAINT message_pin_operation_id_length CHECK (
        char_length(client_operation_id) BETWEEN 1 AND 128),
    CONSTRAINT message_pin_operation_outcome_supported CHECK (
        outcome IN ('APPLIED', 'LIMIT_REACHED')),
    CONSTRAINT message_pin_operation_result_shape CHECK (
        (outcome = 'APPLIED' AND changed AND conversation_sequence > 0)
        OR (outcome = 'APPLIED' AND NOT changed AND conversation_sequence IS NULL)
        OR (outcome = 'LIMIT_REACHED' AND NOT changed AND conversation_sequence IS NULL))
);

CREATE INDEX message_pin_operation_conversation_idx
    ON chat.message_pin_operation (conversation_id, occurred_at DESC);

CREATE TABLE chat.message_pin (
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL,
    pinned_by_account_id UUID NOT NULL REFERENCES chat.account(id),
    last_conversation_sequence BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id, message_id),
    CONSTRAINT message_pin_target FOREIGN KEY (conversation_id, message_id)
        REFERENCES chat.message(conversation_id, id) ON DELETE CASCADE,
    CONSTRAINT message_pin_sequence_positive CHECK (last_conversation_sequence > 0)
);

CREATE INDEX message_pin_conversation_order_idx
    ON chat.message_pin (conversation_id, last_conversation_sequence DESC);

CREATE TABLE chat.message_pin_event (
    conversation_id UUID NOT NULL,
    conversation_sequence BIGINT NOT NULL,
    entry_kind VARCHAR(32) GENERATED ALWAYS AS ('MESSAGE_PIN_CHANGED') STORED,
    message_id UUID NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES chat.account(id),
    actor_device_id UUID,
    pinned BOOLEAN NOT NULL,
    client_operation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id, conversation_sequence),
    CONSTRAINT message_pin_event_entry
        FOREIGN KEY (conversation_id, conversation_sequence, entry_kind)
        REFERENCES chat.conversation_entry(
            conversation_id, conversation_sequence, entry_kind),
    CONSTRAINT message_pin_event_target
        FOREIGN KEY (conversation_id, message_id)
        REFERENCES chat.message(conversation_id, id) ON DELETE CASCADE,
    CONSTRAINT message_pin_event_actor_device
        FOREIGN KEY (actor_device_id, actor_account_id)
        REFERENCES chat.device(id, account_id),
    CONSTRAINT message_pin_event_operation_id_length CHECK (
        char_length(client_operation_id) BETWEEN 1 AND 128)
);

CREATE FUNCTION chat.remove_deleted_message_pin_entry()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM chat.conversation_entry
     WHERE conversation_id = OLD.conversation_id
       AND conversation_sequence = OLD.conversation_sequence
       AND entry_kind = 'MESSAGE_PIN_CHANGED';
    RETURN OLD;
END;
$$;

CREATE TRIGGER message_pin_event_remove_entry
AFTER DELETE ON chat.message_pin_event
FOR EACH ROW EXECUTE FUNCTION chat.remove_deleted_message_pin_entry();

CREATE FUNCTION chat.unpin_recalled_message()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allocated BIGINT;
BEGIN
    DELETE FROM chat.message_pin
     WHERE conversation_id = NEW.conversation_id AND message_id = NEW.message_id;
    IF FOUND THEN
        UPDATE chat.conversation SET next_sequence = next_sequence + 1,
               updated_at = transaction_timestamp()
         WHERE id = NEW.conversation_id RETURNING next_sequence - 1 INTO allocated;
        INSERT INTO chat.conversation_entry VALUES
            (NEW.conversation_id, allocated, 'MESSAGE_PIN_CHANGED', transaction_timestamp());
        INSERT INTO chat.message_pin_event(
            conversation_id, conversation_sequence, message_id, actor_account_id,
            actor_device_id, pinned, client_operation_id, occurred_at)
        VALUES (NEW.conversation_id, allocated, NEW.message_id, NEW.actor_account_id,
                NULL, FALSE, 'AUTO_RECALL:' || NEW.conversation_sequence,
                transaction_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER message_recall_unpin
AFTER INSERT ON chat.message_recall_event
FOR EACH ROW EXECUTE FUNCTION chat.unpin_recalled_message();

CREATE FUNCTION chat.unpin_deleted_messages()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID; allocated BIGINT; ordinal INTEGER := 0;
BEGIN
    IF NEW.source <> 'V2' THEN RETURN NEW; END IF;
    FOR target IN
        SELECT pin.message_id FROM chat.message_pin pin
         WHERE pin.conversation_id = NEW.conversation_id
           AND NEW.message_ids ? pin.message_id::text
         ORDER BY pin.message_id
    LOOP
        ordinal := ordinal + 1;
        DELETE FROM chat.message_pin
         WHERE conversation_id = NEW.conversation_id AND message_id = target;
        UPDATE chat.conversation SET next_sequence = next_sequence + 1,
               updated_at = transaction_timestamp()
         WHERE id = NEW.conversation_id RETURNING next_sequence - 1 INTO allocated;
        INSERT INTO chat.conversation_entry VALUES
            (NEW.conversation_id, allocated, 'MESSAGE_PIN_CHANGED', transaction_timestamp());
        INSERT INTO chat.message_pin_event(
            conversation_id, conversation_sequence, message_id, actor_account_id,
            actor_device_id, pinned, client_operation_id, occurred_at)
        VALUES (NEW.conversation_id, allocated, target, NEW.actor_account_id, NULL, FALSE,
                'AUTO_DELETE:' || NEW.conversation_sequence || ':' || ordinal,
                transaction_timestamp());
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER messages_deleted_unpin
AFTER INSERT ON chat.messages_deleted_event
FOR EACH ROW EXECUTE FUNCTION chat.unpin_deleted_messages();
