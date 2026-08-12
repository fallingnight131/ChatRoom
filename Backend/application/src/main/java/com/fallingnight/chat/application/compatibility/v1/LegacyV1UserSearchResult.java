package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.Objects;

public sealed interface LegacyV1UserSearchResult {
    record Found(List<LegacyV1UserSearchUser> users) implements LegacyV1UserSearchResult {
        public Found {
            users = List.copyOf(Objects.requireNonNull(users, "users"));
        }
    }
    enum Rejected implements LegacyV1UserSearchResult { INSTANCE }
}
