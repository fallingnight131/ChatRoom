ALTER TABLE account
    ADD COLUMN password_scheme VARCHAR(16) NOT NULL DEFAULT 'ARGON2ID',
    ADD COLUMN legacy_password_salt VARCHAR(512);

ALTER TABLE account
    ADD CONSTRAINT account_password_scheme_supported
        CHECK (password_scheme IN ('ARGON2ID', 'V1_SHA256')),
    ADD CONSTRAINT account_password_material_consistent
        CHECK (
            (
                password_scheme = 'ARGON2ID'
                AND legacy_password_salt IS NULL
                AND password_hash LIKE '$argon2id$%'
            )
            OR
            (
                password_scheme = 'V1_SHA256'
                AND password_hash ~ '^[0-9a-fA-F]{64}$'
                AND char_length(legacy_password_salt) BETWEEN 1 AND 512
            )
        );
