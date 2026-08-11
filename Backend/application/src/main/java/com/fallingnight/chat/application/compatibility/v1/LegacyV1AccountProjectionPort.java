package com.fallingnight.chat.application.compatibility.v1;

import java.util.Optional;
import java.util.UUID;

/** Read-only compatibility lookup used only by V1 boundary adapters. */
public interface LegacyV1AccountProjectionPort {
    Optional<LegacyV1AccountIdentity> findByPresentedUsername(String username);

    Optional<LegacyV1AccountIdentity> findByAccountId(UUID accountId);
}
