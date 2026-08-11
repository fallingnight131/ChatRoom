package com.fallingnight.chat.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationDirectoryModelTest {
    @Test
    void enforcesBoundedPagesAndCompositeCursor() {
        UUID id = UUID.randomUUID();
        Instant updated = Instant.parse("2026-08-12T12:00:00Z");
        ConversationSummary summary = new ConversationSummary(
                id, ConversationKind.GROUP, "Team", ConversationRole.MEMBER,
                3, 2, updated);
        ConversationDirectoryCursor cursor = new ConversationDirectoryCursor(updated, id);
        ConversationDirectoryPage page = new ConversationDirectoryPage(
                List.of(summary), Optional.of(cursor), false);
        assertEquals(cursor, page.next().orElseThrow());

        assertThrows(IllegalArgumentException.class, () -> new ConversationDirectoryQuery(
                UUID.randomUUID(), Optional.empty(), 101));
        assertThrows(IllegalArgumentException.class, () -> new ConversationSummary(
                id, ConversationKind.GROUP, " ", ConversationRole.MEMBER,
                0, 0, updated));
        assertThrows(IllegalArgumentException.class, () -> new ConversationSummary(
                id, ConversationKind.GROUP, "Team", ConversationRole.MEMBER,
                2, 3, updated));
        assertThrows(IllegalArgumentException.class, () -> new ConversationDirectoryPage(
                List.of(summary), Optional.empty(), false));
    }
}
