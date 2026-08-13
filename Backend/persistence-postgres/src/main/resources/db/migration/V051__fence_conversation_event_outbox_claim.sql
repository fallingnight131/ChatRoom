ALTER TABLE chat.conversation_event_outbox
    DROP CONSTRAINT conversation_event_outbox_claim_pair,
    ADD COLUMN claim_id UUID,
    ADD CONSTRAINT conversation_event_outbox_claim_lifecycle CHECK (
        (claim_owner IS NULL) = (claim_id IS NULL)
        AND (claim_owner IS NULL) = (claim_expires_at IS NULL)
    );
