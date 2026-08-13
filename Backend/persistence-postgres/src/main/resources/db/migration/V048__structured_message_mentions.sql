CREATE TABLE chat.message_mention (
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL,
    mention_ordinal SMALLINT NOT NULL,
    target_account_id UUID NOT NULL REFERENCES chat.account(id),
    start_utf8_byte INTEGER NOT NULL,
    length_utf8_bytes INTEGER NOT NULL,
    PRIMARY KEY (conversation_id, message_id, mention_ordinal),
    CONSTRAINT message_mention_message
        FOREIGN KEY (conversation_id, message_id)
        REFERENCES chat.message(conversation_id, id) ON DELETE CASCADE,
    CONSTRAINT message_mention_ordinal_bounded CHECK (
        mention_ordinal BETWEEN 0 AND 19),
    CONSTRAINT message_mention_span_bounded CHECK (
        start_utf8_byte >= 0 AND length_utf8_bytes > 0
        AND start_utf8_byte + length_utf8_bytes <= 65536)
);

CREATE INDEX message_mention_target_idx
    ON chat.message_mention (target_account_id, conversation_id, message_id);

ALTER TABLE chat.message_edit_operation
    ADD COLUMN requested_mentions_sha256 BYTEA NOT NULL
        DEFAULT decode(
            'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
            'hex'),
    ADD CONSTRAINT message_edit_operation_mentions_hash_length CHECK (
        octet_length(requested_mentions_sha256) = 32);

CREATE TABLE chat.message_edit_event_mention (
    conversation_id UUID NOT NULL,
    conversation_sequence BIGINT NOT NULL,
    mention_ordinal SMALLINT NOT NULL,
    target_account_id UUID NOT NULL REFERENCES chat.account(id),
    start_utf8_byte INTEGER NOT NULL,
    length_utf8_bytes INTEGER NOT NULL,
    PRIMARY KEY (conversation_id, conversation_sequence, mention_ordinal),
    CONSTRAINT message_edit_event_mention_event
        FOREIGN KEY (conversation_id, conversation_sequence)
        REFERENCES chat.message_edit_event(conversation_id, conversation_sequence)
        ON DELETE CASCADE,
    CONSTRAINT message_edit_event_mention_ordinal_bounded CHECK (
        mention_ordinal BETWEEN 0 AND 19),
    CONSTRAINT message_edit_event_mention_span_bounded CHECK (
        start_utf8_byte >= 0 AND length_utf8_bytes > 0
        AND start_utf8_byte + length_utf8_bytes <= 65536)
);

CREATE INDEX message_edit_event_mention_target_idx
    ON chat.message_edit_event_mention (
        target_account_id, conversation_id, conversation_sequence);

CREATE OR REPLACE FUNCTION chat.erase_recalled_message_edit_bodies()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM chat.message_mention
     WHERE conversation_id = NEW.conversation_id
       AND message_id = NEW.message_id;
    DELETE FROM chat.message_edit_event_mention mention
     USING chat.message_edit_event event
     WHERE event.conversation_id = NEW.conversation_id
       AND event.message_id = NEW.message_id
       AND mention.conversation_id = event.conversation_id
       AND mention.conversation_sequence = event.conversation_sequence;
    UPDATE chat.message_edit_event
       SET content = NULL, content_erased_at = transaction_timestamp()
     WHERE conversation_id = NEW.conversation_id
       AND message_id = NEW.message_id
       AND content IS NOT NULL;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION chat.erase_deleted_message_edit_bodies()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source = 'V2' THEN
        DELETE FROM chat.message_mention
         WHERE conversation_id = NEW.conversation_id
           AND NEW.message_ids ? message_id::text;
        DELETE FROM chat.message_edit_event_mention mention
         USING chat.message_edit_event event
         WHERE event.conversation_id = NEW.conversation_id
           AND NEW.message_ids ? event.message_id::text
           AND mention.conversation_id = event.conversation_id
           AND mention.conversation_sequence = event.conversation_sequence;
        UPDATE chat.message_edit_event
           SET content = NULL, content_erased_at = transaction_timestamp()
         WHERE conversation_id = NEW.conversation_id
           AND NEW.message_ids ? message_id::text
           AND content IS NOT NULL;
    END IF;
    RETURN NEW;
END;
$$;
