CREATE TABLE group_resource_policy (
    conversation_id UUID PRIMARY KEY,
    conversation_kind VARCHAR(16) GENERATED ALWAYS AS ('GROUP') STORED,
    max_file_size BIGINT NOT NULL DEFAULT 10737418240,
    total_file_space BIGINT NOT NULL DEFAULT 10737418240,
    max_file_count INTEGER NOT NULL DEFAULT 1500,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT group_resource_policy_target
        FOREIGN KEY (conversation_id, conversation_kind)
        REFERENCES conversation(id, kind) ON DELETE CASCADE,
    CONSTRAINT group_resource_policy_max_file_size
        CHECK (max_file_size BETWEEN 1 AND 9007199254740991),
    CONSTRAINT group_resource_policy_total_file_space
        CHECK (total_file_space BETWEEN max_file_size AND 9007199254740991),
    CONSTRAINT group_resource_policy_max_file_count
        CHECK (max_file_count >= 1)
);

INSERT INTO group_resource_policy(conversation_id)
SELECT id FROM conversation WHERE kind = 'GROUP';

CREATE FUNCTION ensure_group_resource_policy() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind = 'GROUP' THEN
        INSERT INTO chat.group_resource_policy(conversation_id) VALUES (NEW.id);
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER conversation_group_resource_policy_insert
AFTER INSERT ON conversation
FOR EACH ROW EXECUTE FUNCTION ensure_group_resource_policy();
