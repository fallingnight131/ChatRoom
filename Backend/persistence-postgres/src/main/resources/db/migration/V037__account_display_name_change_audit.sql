ALTER TABLE account
    ADD COLUMN profile_updated_at TIMESTAMPTZ;

UPDATE account SET profile_updated_at = created_at;

ALTER TABLE account
    ALTER COLUMN profile_updated_at SET NOT NULL,
    ALTER COLUMN profile_updated_at SET DEFAULT transaction_timestamp(),
    ADD CONSTRAINT account_profile_update_order CHECK (
        profile_updated_at >= created_at);

CREATE TABLE account_display_name_change_audit (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    old_display_name VARCHAR(100) NOT NULL,
    new_display_name VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT account_display_name_audit_changed CHECK (
        old_display_name <> new_display_name),
    CONSTRAINT account_display_name_audit_old_nonempty CHECK (
        char_length(btrim(old_display_name)) BETWEEN 1 AND 100),
    CONSTRAINT account_display_name_audit_new_nonempty CHECK (
        char_length(btrim(new_display_name)) BETWEEN 1 AND 100)
);

CREATE INDEX account_display_name_change_audit_account_idx
    ON account_display_name_change_audit (account_id, occurred_at DESC, id DESC);
