CREATE TABLE group_admission_policy (
    conversation_id UUID PRIMARY KEY,
    conversation_kind VARCHAR(16) GENERATED ALWAYS AS ('GROUP') STORED,
    max_members INTEGER NOT NULL DEFAULT 50,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT group_admission_policy_target
        FOREIGN KEY (conversation_id, conversation_kind)
        REFERENCES conversation(id, kind) ON DELETE CASCADE,
    CONSTRAINT group_admission_policy_member_limit
        CHECK (max_members BETWEEN 1 AND 100000)
);

INSERT INTO group_admission_policy(conversation_id)
SELECT id FROM conversation WHERE kind = 'GROUP';
