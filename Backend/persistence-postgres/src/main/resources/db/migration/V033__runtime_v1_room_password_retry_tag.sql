ALTER TABLE group_join_credential
    ADD COLUMN password_idempotency_tag VARCHAR(255),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp();

UPDATE group_join_credential SET updated_at = created_at;

ALTER TABLE group_join_credential
    ADD CONSTRAINT group_join_credential_password_tag CHECK (
        password_idempotency_tag IS NULL OR
        password_idempotency_tag ~ '^hmac-sha256:v[1-9][0-9]*:[A-Za-z0-9_-]{43}$'),
    ADD CONSTRAINT group_join_credential_update_order CHECK (
        updated_at >= created_at);
