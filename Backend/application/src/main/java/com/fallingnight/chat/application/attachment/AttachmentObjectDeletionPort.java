package com.fallingnight.chat.application.attachment;

/** Idempotent deletion of one server-generated attachment object key. */
public interface AttachmentObjectDeletionPort {
    void deleteIfPresent(String objectKey);
}
