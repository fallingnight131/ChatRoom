package com.fallingnight.chat.application.compatibility.v1;

import java.util.Optional;
import java.util.UUID;

/** Read-only V1/V2 conversation identity translation at the compatibility boundary. */
public interface LegacyV1ConversationProjectionPort {
    Optional<LegacyV1ConversationIdentity> findByLegacyId(
            LegacyV1ConversationKind kind, long legacyConversationId);

    Optional<LegacyV1ConversationIdentity> findByConversationId(UUID conversationId);
}
