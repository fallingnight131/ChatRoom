CREATE TABLE legacy_v1_room_dissolution (
    conversation_id UUID PRIMARY KEY REFERENCES conversation(id),
    actor_account_id UUID NOT NULL REFERENCES account(id),
    legacy_room_id BIGINT NOT NULL UNIQUE,
    room_name VARCHAR(100) NOT NULL,
    dissolved_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT legacy_v1_room_dissolution_room_id CHECK (
        legacy_room_id BETWEEN 1 AND 2147483647),
    CONSTRAINT legacy_v1_room_dissolution_name CHECK (
        char_length(room_name) BETWEEN 1 AND 100)
);

CREATE INDEX legacy_v1_room_dissolution_actor_idx
    ON legacy_v1_room_dissolution (actor_account_id, dissolved_at DESC);
