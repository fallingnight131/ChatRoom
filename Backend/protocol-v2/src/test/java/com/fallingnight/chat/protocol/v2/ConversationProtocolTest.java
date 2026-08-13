package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ConversationProtocolTest {
    private static final String FIRST = "00000000-0000-0000-0000-000000000002";
    private static final String SECOND = "00000000-0000-0000-0000-000000000001";
    private static final String LIST_GOLDEN =
            "0880d095ffbc31122430303030303030302d303030302d303030302d303030302d"
                    + "3030303030303030303030321819";
    private static final String LIST_PARTICIPANTS_GOLDEN =
            "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
                    + "122430303030303030302d303030302d303030302d303030302d"
                    + "3030303030303030303030321819";

    @Test
    void listCommandHasStableWireBytesAndPermanentKinds() throws Exception {
        ListConversations command = ListConversations.newBuilder()
                .setAfterUpdatedAtEpochMs(1_700_000_000_000L)
                .setAfterConversationId(FIRST)
                .setLimit(25)
                .build();
        ConversationPayloadPolicy.requireValid(command);
        assertEquals(LIST_GOLDEN, HexFormat.of().formatHex(command.toByteArray()));
        assertEquals(command, ListConversations.parseFrom(
                HexFormat.of().parseHex(LIST_GOLDEN)));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_LIST_CONVERSATIONS));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(
                        MessageType.MESSAGE_TYPE_CONVERSATION_DIRECTORY_PAGE));
    }

    @Test
    void validatesCompositeCursorBoundsAndDescendingTieOrder() {
        ConversationPayloadPolicy.requireValid(ListConversations.newBuilder()
                .setLimit(100)
                .build());
        assertThrows(IllegalArgumentException.class,
                () -> ConversationPayloadPolicy.requireValid(ListConversations.newBuilder()
                        .setAfterUpdatedAtEpochMs(1)
                        .setLimit(1)
                        .build()));

        ConversationDirectoryRecord first = record(FIRST, 1_700_000_000_000L);
        ConversationDirectoryRecord second = record(SECOND, 1_700_000_000_000L);
        ConversationDirectoryPage page = ConversationDirectoryPage.newBuilder()
                .addConversations(first)
                .addConversations(second)
                .setNextUpdatedAtEpochMs(second.getUpdatedAtEpochMs())
                .setNextConversationId(second.getConversationId())
                .setHasMore(true)
                .build();
        ConversationPayloadPolicy.requireValid(page);

        assertThrows(IllegalArgumentException.class,
                () -> ConversationPayloadPolicy.requireValid(page.toBuilder()
                        .clearConversations()
                        .addConversations(second)
                        .addConversations(first)
                        .setNextConversationId(first.getConversationId())
                        .build()));
    }

    @Test
    void participantDirectoryHasStableBytesAndAscendingAccountCursor() throws Exception {
        ListConversationParticipants command = ListConversationParticipants.newBuilder()
                .setConversationId(SECOND)
                .setAfterAccountId(FIRST)
                .setLimit(25)
                .build();
        ConversationPayloadPolicy.requireValid(command);
        assertEquals(LIST_PARTICIPANTS_GOLDEN,
                HexFormat.of().formatHex(command.toByteArray()));
        assertEquals(command, ListConversationParticipants.parseFrom(
                HexFormat.of().parseHex(LIST_PARTICIPANTS_GOLDEN)));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND, MessageTypeRegistry.requiredKind(
                MessageType.MESSAGE_TYPE_LIST_CONVERSATION_PARTICIPANTS));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE, MessageTypeRegistry.requiredKind(
                MessageType.MESSAGE_TYPE_CONVERSATION_PARTICIPANT_PAGE));

        ConversationParticipantRecord first = participant(SECOND, "李");
        ConversationParticipantRecord second = participant(FIRST, "Alice");
        ConversationParticipantPage page = ConversationParticipantPage.newBuilder()
                .setConversationId(SECOND)
                .addParticipants(first)
                .addParticipants(second)
                .setNextAccountId(FIRST)
                .setHasMore(true)
                .build();
        ConversationPayloadPolicy.requireValid(page);

        assertThrows(IllegalArgumentException.class,
                () -> ConversationPayloadPolicy.requireValid(page.toBuilder()
                        .clearParticipants()
                        .addParticipants(second)
                        .addParticipants(first)
                        .setNextAccountId(SECOND)
                        .build()));
        assertThrows(IllegalArgumentException.class,
                () -> ConversationPayloadPolicy.requireValid(command.toBuilder()
                        .setAfterAccountId("not-a-uuid")
                        .build()));
    }

    private static ConversationDirectoryRecord record(String id, long updatedAt) {
        return ConversationDirectoryRecord.newBuilder()
                .setConversationId(id)
                .setKind(ConversationKind.CONVERSATION_KIND_GROUP)
                .setDisplayName("Project")
                .setRole(ConversationRole.CONVERSATION_ROLE_MEMBER)
                .setLatestSequence(3)
                .setLastReadSequence(2)
                .setUpdatedAtEpochMs(updatedAt)
                .build();
    }

    private static ConversationParticipantRecord participant(String id, String displayName) {
        return ConversationParticipantRecord.newBuilder()
                .setAccountId(id)
                .setDisplayName(displayName)
                .setRole(ConversationRole.CONVERSATION_ROLE_MEMBER)
                .build();
    }
}
