ALTER TABLE profile_image_object
    ADD COLUMN delete_claim_id UUID,
    ADD COLUMN delete_claimed_at TIMESTAMPTZ,
    ADD CONSTRAINT profile_image_object_claim_pair CHECK (
        (delete_claim_id IS NULL) = (delete_claimed_at IS NULL)),
    ADD CONSTRAINT profile_image_object_claim_requires_cleanup CHECK (
        delete_claim_id IS NULL OR cleanup_requested_at IS NOT NULL);

DROP INDEX profile_image_object_cleanup_idx;

CREATE INDEX profile_image_object_cleanup_idx
    ON profile_image_object (cleanup_requested_at, delete_claimed_at, object_key)
    WHERE cleanup_requested_at IS NOT NULL AND delete_confirmed_at IS NULL;
