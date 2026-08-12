CREATE TABLE contact_request (
    id UUID PRIMARY KEY,
    requester_account_id UUID NOT NULL REFERENCES account(id),
    recipient_account_id UUID NOT NULL REFERENCES account(id),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT contact_request_distinct_accounts CHECK (
        requester_account_id <> recipient_account_id),
    CONSTRAINT contact_request_state_supported CHECK (
        state IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT contact_request_resolution_consistent CHECK (
        (state = 'PENDING' AND resolved_at IS NULL)
        OR (state <> 'PENDING' AND resolved_at IS NOT NULL AND resolved_at >= created_at))
);

CREATE UNIQUE INDEX contact_request_one_pending_pair_idx
    ON contact_request (
        LEAST(requester_account_id, recipient_account_id),
        GREATEST(requester_account_id, recipient_account_id))
    WHERE state = 'PENDING';

CREATE INDEX contact_request_recipient_pending_idx
    ON contact_request (recipient_account_id, created_at DESC, id DESC)
    WHERE state = 'PENDING';

CREATE TABLE legacy_v1_contact_request_map (
    legacy_request_id BIGINT PRIMARY KEY,
    contact_request_id UUID NOT NULL UNIQUE REFERENCES contact_request(id) ON DELETE CASCADE,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT legacy_v1_contact_request_id_positive CHECK (legacy_request_id > 0)
);
