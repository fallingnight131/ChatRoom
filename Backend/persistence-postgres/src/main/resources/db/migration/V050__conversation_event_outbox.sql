CREATE TABLE chat.conversation_event_outbox (
    event_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL,
    conversation_sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    claim_owner UUID,
    claim_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    last_failure_code VARCHAR(64),
    CONSTRAINT conversation_event_outbox_entry
        FOREIGN KEY (conversation_id, conversation_sequence)
        REFERENCES chat.conversation_entry(conversation_id, conversation_sequence),
    CONSTRAINT conversation_event_outbox_sequence_unique
        UNIQUE (conversation_id, conversation_sequence),
    CONSTRAINT conversation_event_outbox_sequence_positive
        CHECK (conversation_sequence > 0),
    CONSTRAINT conversation_event_outbox_available_order
        CHECK (available_at >= created_at),
    CONSTRAINT conversation_event_outbox_claim_pair
        CHECK ((claim_owner IS NULL) = (claim_expires_at IS NULL)),
    CONSTRAINT conversation_event_outbox_claim_order
        CHECK (claim_expires_at IS NULL OR claim_expires_at > created_at),
    CONSTRAINT conversation_event_outbox_attempt_nonnegative
        CHECK (attempt_count >= 0),
    CONSTRAINT conversation_event_outbox_published_order
        CHECK (published_at IS NULL OR published_at >= created_at),
    CONSTRAINT conversation_event_outbox_published_unclaimed
        CHECK (published_at IS NULL OR claim_owner IS NULL),
    CONSTRAINT conversation_event_outbox_failure_code_length
        CHECK (last_failure_code IS NULL
            OR char_length(last_failure_code) BETWEEN 1 AND 64)
);

CREATE INDEX conversation_event_outbox_available_idx
    ON chat.conversation_event_outbox (
        available_at, conversation_id, conversation_sequence)
    WHERE published_at IS NULL;
