ALTER TABLE legacy_v1_message_map
    ADD COLUMN legacy_content_type VARCHAR(16);

ALTER TABLE legacy_v1_message_map
    ADD CONSTRAINT legacy_v1_message_map_content_type_supported
    CHECK (legacy_content_type IS NULL OR legacy_content_type IN ('text', 'emoji'));
