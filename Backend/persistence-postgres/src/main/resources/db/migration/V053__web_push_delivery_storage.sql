CREATE TABLE chat.web_push_subscription (
    account_id UUID NOT NULL REFERENCES chat.account(id) ON DELETE CASCADE,
    installation_id UUID NOT NULL,
    endpoint_ciphertext BYTEA NOT NULL,
    p256dh_ciphertext BYTEA NOT NULL,
    auth_secret_ciphertext BYTEA NOT NULL,
    endpoint_lookup_tag BYTEA NOT NULL,
    encryption_key_id VARCHAR(128) NOT NULL,
    browser_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (account_id, installation_id),
    CONSTRAINT web_push_subscription_endpoint_ciphertext_bounded CHECK (
        octet_length(endpoint_ciphertext) BETWEEN 17 AND 4096),
    CONSTRAINT web_push_subscription_p256dh_ciphertext_bounded CHECK (
        octet_length(p256dh_ciphertext) BETWEEN 17 AND 256),
    CONSTRAINT web_push_subscription_auth_ciphertext_bounded CHECK (
        octet_length(auth_secret_ciphertext) BETWEEN 17 AND 128),
    CONSTRAINT web_push_subscription_lookup_tag_length CHECK (
        octet_length(endpoint_lookup_tag) = 32),
    CONSTRAINT web_push_subscription_key_id_shape CHECK (
        encryption_key_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT web_push_subscription_timestamp_order CHECK (
        updated_at >= created_at),
    CONSTRAINT web_push_subscription_browser_expiry CHECK (
        browser_expires_at IS NULL OR browser_expires_at > TIMESTAMPTZ '1970-01-01 00:00:00+00'),
    CONSTRAINT web_push_subscription_endpoint_unique UNIQUE (endpoint_lookup_tag)
);

CREATE INDEX web_push_subscription_account_expiry_idx
    ON chat.web_push_subscription (account_id, browser_expires_at, installation_id);

CREATE TABLE chat.web_push_notification_outbox (
    message_id UUID PRIMARY KEY REFERENCES chat.message(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    sender_account_id UUID NOT NULL REFERENCES chat.account(id),
    mentioned_account_ids UUID[] NOT NULL DEFAULT ARRAY[]::UUID[],
    committed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    claim_owner UUID,
    claim_id UUID,
    claim_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    terminal_outcome VARCHAR(32),
    last_failure_code VARCHAR(64),
    CONSTRAINT web_push_notification_message
        FOREIGN KEY (conversation_id, message_id)
        REFERENCES chat.message(conversation_id, id) ON DELETE CASCADE,
    CONSTRAINT web_push_notification_lifetime CHECK (
        expires_at > committed_at
        AND expires_at <= committed_at + INTERVAL '24 hours'),
    CONSTRAINT web_push_notification_available_order CHECK (
        available_at >= committed_at AND available_at <= expires_at),
    CONSTRAINT web_push_notification_mentions_bounded CHECK (
        array_ndims(mentioned_account_ids) = 1
        AND cardinality(mentioned_account_ids) <= 20
        AND array_position(mentioned_account_ids, NULL) IS NULL
        AND array_position(mentioned_account_ids, sender_account_id) IS NULL),
    CONSTRAINT web_push_notification_claim_lifecycle CHECK (
        (claim_owner IS NULL) = (claim_id IS NULL)
        AND (claim_owner IS NULL) = (claim_expires_at IS NULL)),
    CONSTRAINT web_push_notification_claim_order CHECK (
        claim_expires_at IS NULL
        OR (claim_expires_at > committed_at AND claim_expires_at <= expires_at)),
    CONSTRAINT web_push_notification_attempt_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT web_push_notification_completion_lifecycle CHECK (
        (completed_at IS NULL) = (terminal_outcome IS NULL)
        AND (completed_at IS NULL OR claim_owner IS NULL)),
    CONSTRAINT web_push_notification_completion_order CHECK (
        completed_at IS NULL OR completed_at >= committed_at),
    CONSTRAINT web_push_notification_outcome CHECK (
        terminal_outcome IS NULL OR terminal_outcome IN (
            'DELIVERED', 'EXPIRED', 'INELIGIBLE', 'INVALID_SUBSCRIPTION')),
    CONSTRAINT web_push_notification_failure_code_shape CHECK (
        last_failure_code IS NULL OR last_failure_code ~ '^[A-Z0-9_]{1,64}$')
);

CREATE INDEX web_push_notification_outbox_available_idx
    ON chat.web_push_notification_outbox (available_at, message_id)
    WHERE completed_at IS NULL;

CREATE INDEX web_push_notification_outbox_retention_idx
    ON chat.web_push_notification_outbox (expires_at, message_id);
