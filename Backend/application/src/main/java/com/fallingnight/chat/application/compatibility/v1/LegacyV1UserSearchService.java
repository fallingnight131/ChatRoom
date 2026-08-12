package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded V1 user search with durable identity and ephemeral presence separated. */
public final class LegacyV1UserSearchService implements LegacyV1UserSearchUseCase {
    public static final int MAX_RESULTS = 20;
    public static final int MAX_KEYWORD_UTF8_BYTES = 256;
    private final LegacyV1UserSearchPort users;
    private final LegacyV1PresencePort presence;

    public LegacyV1UserSearchService(
            LegacyV1UserSearchPort users, LegacyV1PresencePort presence) {
        this.users = Objects.requireNonNull(users, "users");
        this.presence = Objects.requireNonNull(presence, "presence");
    }

    @Override
    public LegacyV1UserSearchResult search(UUID accountId, String keyword) {
        Objects.requireNonNull(accountId, "accountId");
        if (keyword == null) return LegacyV1UserSearchResult.Rejected.INSTANCE;
        String normalized = keyword.strip();
        if (normalized.isEmpty()
                || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_KEYWORD_UTF8_BYTES
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return LegacyV1UserSearchResult.Rejected.INSTANCE;
        }
        List<LegacyV1UserSearchEntry> entries = List.copyOf(
                Objects.requireNonNull(users.search(accountId, normalized, MAX_RESULTS),
                        "search result"));
        if (entries.size() > MAX_RESULTS) {
            throw new IllegalStateException("V1 user search exceeded its result bound");
        }
        Set<UUID> accountIds = new HashSet<>();
        Set<Long> legacyIds = new HashSet<>();
        Set<String> usernames = new HashSet<>();
        for (LegacyV1UserSearchEntry entry : entries) {
            if (entry.accountId().equals(accountId)
                    || !accountIds.add(entry.accountId())
                    || !legacyIds.add(entry.legacyUserId())
                    || !usernames.add(entry.username())) {
                throw new IllegalStateException("V1 user search projection is inconsistent");
            }
        }
        Set<UUID> online = Set.copyOf(presence.onlineAccounts(Set.copyOf(accountIds)));
        if (!accountIds.containsAll(online)) {
            throw new IllegalStateException("V1 user search presence is inconsistent");
        }
        List<LegacyV1UserSearchUser> result = new ArrayList<>(entries.size());
        for (LegacyV1UserSearchEntry entry : entries) {
            result.add(new LegacyV1UserSearchUser(
                    entry.legacyUserId(), entry.username(), entry.displayName(),
                    online.contains(entry.accountId())));
        }
        return new LegacyV1UserSearchResult.Found(result);
    }
}
