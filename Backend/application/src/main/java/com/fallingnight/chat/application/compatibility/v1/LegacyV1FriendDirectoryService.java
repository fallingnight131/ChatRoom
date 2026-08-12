package com.fallingnight.chat.application.compatibility.v1;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Fail-closed V1 friend-list composition over canonical durable state. */
public final class LegacyV1FriendDirectoryService implements LegacyV1FriendDirectoryUseCase {
    public static final int MAX_FRIENDS = 1_000;

    private final LegacyV1FriendDirectoryPort directory;
    private final LegacyV1ConversationProjectionPort conversations;
    private final LegacyV1AccountProjectionPort accounts;
    private final LegacyV1PresencePort presence;

    public LegacyV1FriendDirectoryService(
            LegacyV1FriendDirectoryPort directory,
            LegacyV1ConversationProjectionPort conversations,
            LegacyV1AccountProjectionPort accounts,
            LegacyV1PresencePort presence) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.presence = Objects.requireNonNull(presence, "presence");
    }

    @Override
    public LegacyV1FriendDirectorySnapshot listFriends(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        LegacyV1FriendDirectoryState state = directory.read(accountId, MAX_FRIENDS);
        if (state.friends().size() > MAX_FRIENDS) {
            throw new IllegalStateException("V1 friend directory exceeds its fixed bound");
        }
        Set<UUID> conversationIds = new LinkedHashSet<>();
        Set<UUID> peerIds = new LinkedHashSet<>();
        for (LegacyV1FriendState friend : state.friends()) {
            if (!conversationIds.add(friend.conversationId())) {
                throw new IllegalStateException("V1 friend directory repeated a conversation");
            }
            peerIds.add(friend.peerAccountId());
        }
        Map<UUID, LegacyV1ConversationIdentity> conversationMappings =
                conversations.findByConversationIds(conversationIds);
        Map<UUID, LegacyV1AccountIdentity> accountMappings =
                accounts.findByAccountIds(peerIds);
        Set<UUID> online = Set.copyOf(presence.onlineAccounts(Set.copyOf(peerIds)));
        if (!conversationMappings.keySet().equals(conversationIds)
                || !accountMappings.keySet().equals(peerIds)
                || !peerIds.containsAll(online)) {
            throw new IllegalStateException("V1 friend directory projection is incomplete");
        }
        List<LegacyV1FriendSummary> friends = new ArrayList<>();
        for (LegacyV1FriendState friend : state.friends()) {
            LegacyV1ConversationIdentity conversation =
                    conversationMappings.get(friend.conversationId());
            LegacyV1AccountIdentity peer = accountMappings.get(friend.peerAccountId());
            if (conversation.legacyKind() != LegacyV1ConversationKind.FRIENDSHIP
                    || !conversation.conversationId().equals(friend.conversationId())
                    || !peer.accountId().equals(friend.peerAccountId())) {
                throw new IllegalStateException("V1 friend directory mapping is invalid");
            }
            friends.add(new LegacyV1FriendSummary(
                    conversation.legacyConversationId(),
                    peer.legacyUserId(),
                    friend.username(),
                    friend.displayName(),
                    online.contains(friend.peerAccountId()),
                    friend.unread(),
                    friend.peerLastReadMessageId()));
        }
        friends.sort(Comparator.comparing(LegacyV1FriendSummary::displayName)
                .thenComparing(LegacyV1FriendSummary::username)
                .thenComparingLong(LegacyV1FriendSummary::friendshipId));
        return new LegacyV1FriendDirectorySnapshot(friends, state.pendingFriendRequests());
    }
}
