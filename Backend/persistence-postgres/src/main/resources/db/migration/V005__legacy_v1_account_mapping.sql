CREATE TABLE legacy_v1_account_map (
    legacy_user_id BIGINT PRIMARY KEY,
    account_id UUID NOT NULL UNIQUE REFERENCES account(id) ON DELETE CASCADE,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT legacy_v1_account_map_id_positive CHECK (legacy_user_id > 0)
);
