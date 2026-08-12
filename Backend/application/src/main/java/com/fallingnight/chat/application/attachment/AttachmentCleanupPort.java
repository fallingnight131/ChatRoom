package com.fallingnight.chat.application.attachment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable revoke, retry enumeration, and deletion-confirmation boundary. */
public interface AttachmentCleanupPort {
    int revokeExpiredPending(Instant createdAtOrBefore, Instant revokedAt, int limit);

    List<AttachmentCleanupCandidate> findObjectCleanupRequired(int limit);

    boolean confirmObjectDeleted(UUID attachmentId, Instant deletedAt);
}
