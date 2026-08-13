ALTER TABLE account
    ADD COLUMN username_changed_at TIMESTAMPTZ;

ALTER TABLE account
    ADD CONSTRAINT account_username_change_order CHECK (
        username_changed_at IS NULL OR username_changed_at >= created_at);

CREATE TABLE account_username_change_audit (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    old_username VARCHAR(128) NOT NULL,
    new_username VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT account_username_audit_changed CHECK (old_username <> new_username),
    CONSTRAINT account_username_audit_old_shape CHECK (
        old_username ~ '^[A-Za-z0-9_]{6,20}$'),
    CONSTRAINT account_username_audit_new_shape CHECK (
        new_username ~ '^[A-Za-z0-9_]{6,20}$')
);

CREATE INDEX account_username_change_audit_account_idx
    ON account_username_change_audit (account_id, occurred_at DESC, id DESC);
