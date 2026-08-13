ALTER TABLE chat.message
    ADD COLUMN forwarded BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE chat.message_forward_request (
    destination_message_id UUID PRIMARY KEY
        REFERENCES chat.message(id) ON DELETE CASCADE,
    request_sha256 BYTEA NOT NULL,
    CONSTRAINT message_forward_request_hash_length
        CHECK (octet_length(request_sha256) = 32)
);
