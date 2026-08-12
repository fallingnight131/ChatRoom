package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyV1ConversationIdentityTest {
    @Test
    void requiresTypedPositiveLegacyIdentity() {
        UUID conversation = UUID.randomUUID();
        LegacyV1ConversationIdentity identity = new LegacyV1ConversationIdentity(
                LegacyV1ConversationKind.ROOM, 7, conversation);
        assertEquals(LegacyV1ConversationKind.ROOM, identity.legacyKind());
        assertEquals(7, identity.legacyConversationId());
        assertEquals(conversation, identity.conversationId());

        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1ConversationIdentity(
                        LegacyV1ConversationKind.ROOM, 0, conversation));
        assertThrows(NullPointerException.class,
                () -> new LegacyV1ConversationIdentity(null, 7, conversation));
        assertThrows(NullPointerException.class,
                () -> new LegacyV1ConversationIdentity(
                        LegacyV1ConversationKind.FRIENDSHIP, 7, null));
    }
}
