CREATE TABLE account_block (
    blocker_account_id UUID NOT NULL REFERENCES account(id),
    blocked_account_id UUID NOT NULL REFERENCES account(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (blocker_account_id, blocked_account_id),
    CONSTRAINT account_block_distinct_accounts CHECK (
        blocker_account_id <> blocked_account_id)
);

CREATE INDEX account_block_blocked_by_idx
    ON account_block (blocked_account_id, blocker_account_id);

CREATE TABLE account_block_operation (
    actor_account_id UUID NOT NULL REFERENCES account(id),
    client_operation_id UUID NOT NULL,
    target_account_id UUID NOT NULL REFERENCES account(id),
    desired_blocked BOOLEAN NOT NULL,
    changed BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (actor_account_id, client_operation_id),
    CONSTRAINT account_block_operation_distinct_accounts CHECK (
        actor_account_id <> target_account_id)
);

CREATE INDEX account_block_operation_target_idx
    ON account_block_operation (target_account_id, occurred_at DESC);
