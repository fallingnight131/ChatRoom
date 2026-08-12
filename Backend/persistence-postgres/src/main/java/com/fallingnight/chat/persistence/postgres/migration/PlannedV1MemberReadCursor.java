package com.fallingnight.chat.persistence.postgres.migration;

import java.util.UUID;

/** Conservative translation of a V1 message-table ID pointer to a V2 sequence. */
public record PlannedV1MemberReadCursor(
        UUID conversationId,
        UUID accountId,
        long legacyLastReadMessageId,
        long targetLastReadSequence) {}
