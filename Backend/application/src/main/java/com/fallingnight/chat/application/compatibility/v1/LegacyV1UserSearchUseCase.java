package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1UserSearchUseCase {
    LegacyV1UserSearchResult search(UUID accountId, String keyword);
}
