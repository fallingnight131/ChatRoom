package com.fallingnight.chat.application.compatibility.v1;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only compatibility lookup used only by V1 boundary adapters. */
public interface LegacyV1AccountProjectionPort {
    Optional<LegacyV1AccountIdentity> findByPresentedUsername(String username);

    Optional<LegacyV1AccountIdentity> findByAccountId(UUID accountId);

    default Map<UUID, LegacyV1AccountIdentity> findByAccountIds(Set<UUID> accountIds) {
        Map<UUID, LegacyV1AccountIdentity> result = new LinkedHashMap<>();
        for (UUID accountId : Set.copyOf(accountIds)) {
            findByAccountId(accountId).ifPresent(value -> result.put(accountId, value));
        }
        return Map.copyOf(result);
    }
}
