CREATE TABLE chat.web_push_http_credential (
    session_id UUID PRIMARY KEY REFERENCES chat.device_session(id) ON DELETE CASCADE,
    bearer_sha256 BYTEA NOT NULL UNIQUE,
    csrf_sha256 BYTEA NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT web_push_http_credential_bearer_hash_length CHECK (
        octet_length(bearer_sha256) = 32),
    CONSTRAINT web_push_http_credential_csrf_hash_length CHECK (
        octet_length(csrf_sha256) = 32),
    CONSTRAINT web_push_http_credential_lifetime CHECK (
        expires_at > issued_at
        AND expires_at <= issued_at + INTERVAL '1 hour')
);

CREATE INDEX web_push_http_credential_expiry_idx
    ON chat.web_push_http_credential (expires_at, session_id);
