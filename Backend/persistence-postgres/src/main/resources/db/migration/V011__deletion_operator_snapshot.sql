ALTER TABLE messages_deleted_event
    ADD COLUMN operator_name_snapshot VARCHAR(100) NOT NULL DEFAULT '';

ALTER TABLE messages_deleted_event
    ADD CONSTRAINT messages_deleted_operator_name_bounded CHECK (
        char_length(operator_name_snapshot) <= 100);
