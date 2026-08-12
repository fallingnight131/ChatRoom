package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface LegacyV1UserSearchPort {
    List<LegacyV1UserSearchEntry> search(
            UUID excludedAccountId, String literalKeyword, int limit);
}
