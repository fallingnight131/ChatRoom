package com.fallingnight.chat.application.compatibility.v1;

import java.util.Optional;
import java.util.UUID;

/** Read-only retained-message identity translation at the V1 compatibility boundary. */
public interface LegacyV1MessageProjectionPort {
    Optional<LegacyV1MessageIdentity> findByLegacyId(
            LegacyV1ConversationKind kind, long legacyMessageId);

    Optional<LegacyV1MessageIdentity> findByMessageId(UUID messageId);
}
