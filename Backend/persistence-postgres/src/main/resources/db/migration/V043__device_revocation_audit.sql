CREATE TABLE device_revocation_audit (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    target_device_id UUID NOT NULL UNIQUE,
    actor_device_id UUID NOT NULL,
    actor_session_id UUID NOT NULL REFERENCES device_session(id),
    revoked_sessions INTEGER NOT NULL,
    reason VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT device_revocation_target_owner
        FOREIGN KEY (target_device_id, account_id) REFERENCES device(id, account_id),
    CONSTRAINT device_revocation_actor_owner
        FOREIGN KEY (actor_device_id, account_id) REFERENCES device(id, account_id),
    CONSTRAINT device_revocation_not_self CHECK (target_device_id <> actor_device_id),
    CONSTRAINT device_revocation_session_count CHECK (revoked_sessions >= 0),
    CONSTRAINT device_revocation_reason_supported CHECK (reason = 'USER_REQUEST')
);

CREATE INDEX device_revocation_audit_account_idx
    ON device_revocation_audit (account_id, occurred_at DESC, id DESC);
