CREATE SEQUENCE legacy_v1_deletion_event_id_seq
    AS BIGINT
    START WITH 2147483647
    INCREMENT BY -1
    MINVALUE 1
    MAXVALUE 2147483647
    NO CYCLE;

CREATE UNIQUE INDEX messages_deleted_event_runtime_operation_unique
    ON messages_deleted_event(actor_account_id, client_operation_id)
    WHERE source = 'V2';
