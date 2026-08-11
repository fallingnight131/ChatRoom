package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class MessagingProtocolTest {
    private static final String CONVERSATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SUBMIT_GOLDEN =
            "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
                    + "10011a026869";

    @Test
    void submitMessageHasStableWireBytesAndPermanentRegistryKinds() throws Exception {
        SubmitMessage command = SubmitMessage.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8("hi"))
                .build();
        MessagingPayloadPolicy.requireValid(command, "client-1");
        assertEquals(SUBMIT_GOLDEN, HexFormat.of().formatHex(command.toByteArray()));
        assertEquals(command, SubmitMessage.parseFrom(HexFormat.of().parseHex(SUBMIT_GOLDEN)));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_READ_MESSAGE_HISTORY));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE));
        assertEquals(MessageKind.MESSAGE_KIND_EVENT,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED));
    }

    @Test
    void rejectsMalformedCommandsAndUnboundedOrOutOfOrderPages() {
        SubmitMessage invalidSubmit = SubmitMessage.newBuilder()
                .setConversationId("not-a-uuid")
                .setContent(ByteString.copyFrom(
                        new byte[MessagingPayloadPolicy.MAX_CONTENT_BYTES + 1]))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> MessagingPayloadPolicy.requireValid(invalidSubmit, " "));

        ReadMessageHistory invalidHistory = ReadMessageHistory.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setAfterSequence(-1)
                .setLimit(101)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> MessagingPayloadPolicy.requireValid(invalidHistory));

        MessageRecord second = record(2, "00000000-0000-0000-0000-000000000002");
        MessageRecord first = record(1, "00000000-0000-0000-0000-000000000003");
        MessageHistoryPage outOfOrder = MessageHistoryPage.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .addMessages(second)
                .addMessages(first)
                .setNextSequence(1)
                .setLatestSequence(2)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> MessagingPayloadPolicy.requireValid(outOfOrder));
    }

    @Test
    void validatesBoundedServerResponses() {
        MessageAccepted accepted = MessageAccepted.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setMessageId("00000000-0000-0000-0000-000000000002")
                .setConversationSequence(1)
                .setAcceptedAtEpochMs(1_700_000_000_000L)
                .build();
        MessagingPayloadPolicy.requireValid(accepted);

        MessageHistoryPage page = MessageHistoryPage.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .addMessages(record(1, "00000000-0000-0000-0000-000000000002"))
                .setNextSequence(1)
                .setLatestSequence(1)
                .build();
        MessagingPayloadPolicy.requireValid(page);
        MessagingPayloadPolicy.requireValid(page.getMessages(0));
    }

    private static MessageRecord record(long sequence, String messageId) {
        return MessageRecord.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setMessageId(messageId)
                .setConversationSequence(sequence)
                .setSenderAccountId("00000000-0000-0000-0000-000000000004")
                .setSenderDeviceId("00000000-0000-0000-0000-000000000005")
                .setClientMessageId("client-" + sequence)
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8("hi"))
                .setAcceptedAtEpochMs(1_700_000_000_000L + sequence)
                .build();
    }
}
