package com.fallingnight.chat.application.compatibility.v1;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only V1/V2 conversation identity translation at the compatibility boundary. */
public interface LegacyV1ConversationProjectionPort {
    Optional<LegacyV1ConversationIdentity> findByLegacyId(
            LegacyV1ConversationKind kind, long legacyConversationId);

    Optional<LegacyV1ConversationIdentity> findByConversationId(UUID conversationId);

    /** Exact bounded batch lookup used to avoid one query per legacy directory row. */
    Map<UUID, LegacyV1ConversationIdentity> findByConversationIds(Set<UUID> conversationIds);
}
