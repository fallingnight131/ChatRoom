CREATE TABLE group_lifecycle (
    conversation_id UUID PRIMARY KEY,
    conversation_kind VARCHAR(16) GENERATED ALWAYS AS ('GROUP') STORED,
    closed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT group_lifecycle_target
        FOREIGN KEY (conversation_id, conversation_kind)
        REFERENCES conversation(id, kind) ON DELETE CASCADE
);

INSERT INTO group_lifecycle(conversation_id)
SELECT id FROM conversation WHERE kind = 'GROUP';

CREATE FUNCTION ensure_group_lifecycle() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind = 'GROUP' THEN
        INSERT INTO chat.group_lifecycle(conversation_id) VALUES (NEW.id);
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER conversation_group_lifecycle_insert
AFTER INSERT ON conversation
FOR EACH ROW EXECUTE FUNCTION ensure_group_lifecycle();

CREATE INDEX group_lifecycle_active_idx
    ON group_lifecycle (conversation_id) WHERE closed_at IS NULL;
