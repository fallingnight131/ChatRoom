ALTER TABLE message_recall_event
    ADD COLUMN entry_kind VARCHAR(32)
    GENERATED ALWAYS AS ('MESSAGE_RECALLED') STORED;

ALTER TABLE message_recall_event DROP CONSTRAINT message_recall_entry;
ALTER TABLE message_recall_event
    ADD CONSTRAINT message_recall_entry
    FOREIGN KEY (conversation_id, conversation_sequence, entry_kind)
    REFERENCES conversation_entry(
        conversation_id, conversation_sequence, entry_kind);

ALTER TABLE message_recall_event
    ADD CONSTRAINT message_recall_target_unique
    UNIQUE (conversation_id, message_id);
