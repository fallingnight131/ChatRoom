package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

public interface LegacyV1PasswordChangePort {
    LegacyV1PasswordChangeAccess inspect(UUID actorAccountId, UUID currentSessionId);
    LegacyV1PasswordChangePersistenceResult replace(LegacyV1PasswordChangeIntent intent);
}
