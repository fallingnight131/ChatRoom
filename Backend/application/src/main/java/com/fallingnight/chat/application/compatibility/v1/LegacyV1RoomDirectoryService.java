package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.conversation.ConversationDirectoryCursor;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPage;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPort;
import com.fallingnight.chat.application.conversation.ConversationDirectoryQuery;
import com.fallingnight.chat.application.conversation.ConversationKind;
import com.fallingnight.chat.application.conversation.ConversationRole;
import com.fallingnight.chat.application.conversation.ConversationSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Closed V1 room-list projection over the canonical authenticated directory. */
public final class LegacyV1RoomDirectoryService implements LegacyV1RoomDirectoryUseCase {
    public static final int MAX_DIRECTORY_ROWS = 1_000;

    private final ConversationDirectoryPort directory;
    private final LegacyV1ConversationProjectionPort legacyConversations;

    public LegacyV1RoomDirectoryService(
            ConversationDirectoryPort directory,
            LegacyV1ConversationProjectionPort legacyConversations) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.legacyConversations = Objects.requireNonNull(
                legacyConversations, "legacyConversations");
    }

    @Override
    public List<LegacyV1RoomSummary> listRooms(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        Optional<ConversationDirectoryCursor> cursor = Optional.empty();
        List<LegacyV1RoomSummary> rooms = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        int scanned = 0;
        while (true) {
            ConversationDirectoryPage page = directory.list(new ConversationDirectoryQuery(
                    accountId, cursor, ConversationDirectoryQuery.MAX_LIMIT));
            scanned += page.conversations().size();
            if (scanned > MAX_DIRECTORY_ROWS) {
                throw new IllegalStateException("V1 room directory exceeds its fixed bound");
            }
            List<ConversationSummary> groups = page.conversations().stream()
                    .filter(summary -> summary.kind() == ConversationKind.GROUP)
                    .toList();
            Set<UUID> ids = new LinkedHashSet<>();
            for (ConversationSummary group : groups) {
                if (!seen.add(group.conversationId())) {
                    throw new IllegalStateException("conversation directory repeated a row");
                }
                ids.add(group.conversationId());
            }
            Map<UUID, LegacyV1ConversationIdentity> mappings =
                    legacyConversations.findByConversationIds(ids);
            if (!mappings.keySet().equals(ids)) {
                throw new IllegalStateException("V1 room directory mapping is incomplete");
            }
            for (ConversationSummary group : groups) {
                LegacyV1ConversationIdentity mapping = mappings.get(group.conversationId());
                if (mapping.legacyKind() != LegacyV1ConversationKind.ROOM
                        || !mapping.conversationId().equals(group.conversationId())) {
                    throw new IllegalStateException("V1 room directory mapping kind is invalid");
                }
                rooms.add(new LegacyV1RoomSummary(
                        mapping.legacyConversationId(),
                        group.displayName(),
                        group.latestSequence() - group.lastReadSequence(),
                        group.role() != ConversationRole.MEMBER));
            }
            if (!page.hasMore()) break;
            cursor = page.next();
            if (cursor.isEmpty()) {
                throw new IllegalStateException("conversation directory omitted its next cursor");
            }
        }
        rooms.sort(Comparator.comparingLong(LegacyV1RoomSummary::roomId));
        return List.copyOf(rooms);
    }
}
