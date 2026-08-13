ALTER TABLE account_username_change_audit
    DROP CONSTRAINT account_username_audit_old_shape;

ALTER TABLE account_username_change_audit
    ADD CONSTRAINT account_username_audit_old_shape CHECK (
        char_length(btrim(old_username)) BETWEEN 1 AND 128
        AND old_username = btrim(old_username));
