CREATE TABLE legacy_v1_room_kick_event (
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    actor_account_id UUID NOT NULL REFERENCES account(id),
    target_account_id UUID NOT NULL REFERENCES account(id),
    kicked_at TIMESTAMPTZ NOT NULL,
    legacy_room_id BIGINT NOT NULL,
    room_name_snapshot VARCHAR(100) NOT NULL,
    target_username_snapshot VARCHAR(128) NOT NULL,
    target_display_name_snapshot VARCHAR(100) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (conversation_id, target_account_id, kicked_at),
    CONSTRAINT legacy_v1_room_kick_target_membership
        FOREIGN KEY (conversation_id, target_account_id)
        REFERENCES conversation_member(conversation_id, account_id),
    CONSTRAINT legacy_v1_room_kick_room_id_positive
        CHECK (legacy_room_id BETWEEN 1 AND 2147483647),
    CONSTRAINT legacy_v1_room_kick_distinct_accounts
        CHECK (actor_account_id <> target_account_id),
    CONSTRAINT legacy_v1_room_kick_room_name
        CHECK (char_length(room_name_snapshot) BETWEEN 1 AND 100),
    CONSTRAINT legacy_v1_room_kick_username
        CHECK (char_length(target_username_snapshot) BETWEEN 1 AND 128),
    CONSTRAINT legacy_v1_room_kick_display_name
        CHECK (char_length(target_display_name_snapshot) BETWEEN 1 AND 100)
);

CREATE INDEX legacy_v1_room_kick_actor_retry_idx
    ON legacy_v1_room_kick_event(
        conversation_id, actor_account_id, target_account_id, kicked_at);
