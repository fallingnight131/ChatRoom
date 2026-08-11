ALTER TABLE conversation ADD COLUMN title VARCHAR(100);

UPDATE conversation
SET title = 'Group ' || left(id::text, 8)
WHERE kind = 'GROUP' AND title IS NULL;

ALTER TABLE conversation ADD CONSTRAINT conversation_title_by_kind CHECK (
    (kind = 'DIRECT' AND title IS NULL)
    OR (kind = 'GROUP' AND char_length(title) BETWEEN 1 AND 100)
);

CREATE INDEX conversation_directory_order_idx
    ON conversation_member (account_id, left_at, conversation_id);
