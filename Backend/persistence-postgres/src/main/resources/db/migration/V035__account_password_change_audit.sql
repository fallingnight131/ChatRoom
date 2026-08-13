ALTER TABLE account
    ADD COLUMN password_changed_at TIMESTAMPTZ;

UPDATE account SET password_changed_at = created_at;

ALTER TABLE account
    ALTER COLUMN password_changed_at SET NOT NULL,
    ALTER COLUMN password_changed_at SET DEFAULT transaction_timestamp(),
    ADD CONSTRAINT account_password_change_order CHECK (
        password_changed_at >= created_at);

CREATE TABLE account_password_change_audit (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    initiating_session_id UUID NOT NULL,
    other_sessions_revoked INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT account_password_change_revoked_nonnegative CHECK (
        other_sessions_revoked >= 0)
);

CREATE INDEX account_password_change_audit_account_idx
    ON account_password_change_audit (account_id, occurred_at DESC, id DESC);
