ALTER TABLE direct_conversation
    DROP CONSTRAINT direct_conversation_canonical_order;

ALTER TABLE direct_conversation
    ADD CONSTRAINT direct_conversation_canonical_order CHECK (
        first_account_id <= second_account_id);
