package com.fallingnight.chat.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationParticipantModelTest {
    private static final UUID CONVERSATION =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID FIRST =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID SECOND =
            UUID.fromString("20000000-0000-4000-8000-000000000002");

    @Test
    void requiresBoundedQueryAndStableAscendingPage() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationParticipantQuery(
                        CONVERSATION, FIRST, Optional.empty(), 0));
        ConversationParticipant first = participant(FIRST, "Alice");
        ConversationParticipant second = participant(SECOND, "李");
        ConversationParticipantPage page = new ConversationParticipantPage(
                CONVERSATION, List.of(first, second), Optional.of(SECOND), true);
        assertEquals(SECOND, page.nextAccountId().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationParticipantPage(CONVERSATION,
                        List.of(second, first), Optional.of(FIRST), false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationParticipantPage(CONVERSATION,
                        List.of(first), Optional.of(SECOND), false));
    }

    @Test
    void boundsUnicodeDisplayNamesByCodePointAndUtf8Size() {
        assertEquals("李", participant(FIRST, "李").displayName());
        assertThrows(IllegalArgumentException.class,
                () -> participant(FIRST, " "));
        assertThrows(IllegalArgumentException.class,
                () -> participant(FIRST, "李".repeat(101)));
    }

    private static ConversationParticipant participant(UUID accountId, String displayName) {
        return new ConversationParticipant(
                accountId, displayName, ConversationRole.MEMBER);
    }
}
