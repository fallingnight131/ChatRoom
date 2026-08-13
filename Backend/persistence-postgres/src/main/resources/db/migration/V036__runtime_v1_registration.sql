CREATE SEQUENCE legacy_v1_user_id_seq
    AS BIGINT
    START WITH 2147483647
    INCREMENT BY -1
    MINVALUE 1
    MAXVALUE 2147483647
    NO CYCLE;

CREATE TABLE legacy_v1_registration_audit (
    account_id UUID PRIMARY KEY REFERENCES account(id),
    legacy_user_id BIGINT NOT NULL UNIQUE,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT legacy_v1_registration_user_id CHECK (
        legacy_user_id BETWEEN 1 AND 2147483647),
    CONSTRAINT legacy_v1_registration_user_mapping
        FOREIGN KEY (legacy_user_id)
        REFERENCES legacy_v1_account_map(legacy_user_id),
    CONSTRAINT legacy_v1_registration_account_mapping
        FOREIGN KEY (account_id)
        REFERENCES legacy_v1_account_map(account_id)
);
