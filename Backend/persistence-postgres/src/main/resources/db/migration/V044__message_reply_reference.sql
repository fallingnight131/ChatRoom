CREATE TABLE chat.message_reply_reference (
    message_id UUID PRIMARY KEY
        REFERENCES chat.message(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    target_message_id UUID NOT NULL,
    target_conversation_sequence BIGINT NOT NULL,
    target_sender_account_id UUID NOT NULL,
    CONSTRAINT message_reply_target_sequence_positive
        CHECK (target_conversation_sequence > 0),
    CONSTRAINT message_reply_not_self
        CHECK (message_id <> target_message_id)
);

CREATE INDEX message_reply_reference_target_idx
    ON chat.message_reply_reference (conversation_id, target_message_id);

CREATE FUNCTION chat.validate_message_reply_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    reply_conversation UUID;
    reply_sequence BIGINT;
    target_conversation UUID;
    target_sequence BIGINT;
    target_sender UUID;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'message reply references are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT conversation_id, conversation_sequence
      INTO reply_conversation, reply_sequence
      FROM chat.message
     WHERE id = NEW.message_id;
    SELECT conversation_id, conversation_sequence, sender_account_id
      INTO target_conversation, target_sequence, target_sender
      FROM chat.message
     WHERE id = NEW.target_message_id;

    IF reply_conversation IS NULL OR target_conversation IS NULL
       OR NEW.conversation_id <> reply_conversation
       OR target_conversation <> reply_conversation
       OR NEW.target_conversation_sequence <> target_sequence
       OR NEW.target_sender_account_id <> target_sender
       OR target_sequence >= reply_sequence THEN
        RAISE EXCEPTION 'invalid message reply reference'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER message_reply_reference_validate
BEFORE INSERT OR UPDATE ON chat.message_reply_reference
FOR EACH ROW EXECUTE FUNCTION chat.validate_message_reply_reference();
