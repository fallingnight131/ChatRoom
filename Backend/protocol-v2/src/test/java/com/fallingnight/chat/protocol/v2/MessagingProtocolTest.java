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
    private static final String SUBMIT_REPLY_GOLDEN =
            "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
                    + "122430303030303030302d303030302d303030302d303030302d303030303030303030303032"
                    + "180122026869";
    private static final String SET_REACTION_GOLDEN =
            "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
                    + "122430303030303030302d303030302d303030302d303030302d303030303030303030303032"
                    + "180120012a0a7265616374696f6e2d31";
    private static final String SET_PIN_GOLDEN =
            "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
                    + "122430303030303030302d303030302d303030302d303030302d303030303030303030303032"
                    + "1801220570696e2d31";

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
    void replySubmitHasStableWireBytesAndPermanentCommandKind() throws Exception {
        SubmitReplyMessage command = SubmitReplyMessage.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setTargetMessageId("00000000-0000-0000-0000-000000000002")
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8("hi"))
                .build();
        MessagingPayloadPolicy.requireValid(command, "client-reply-1");
        assertEquals(SUBMIT_REPLY_GOLDEN, HexFormat.of().formatHex(command.toByteArray()));
        assertEquals(command,
                SubmitReplyMessage.parseFrom(HexFormat.of().parseHex(SUBMIT_REPLY_GOLDEN)));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_SUBMIT_REPLY_MESSAGE));
    }

    @Test
    void reactionMutationHasStableWireBytesKindsAndChangedOnlySequence() throws Exception {
        SetMessageReaction command = SetMessageReaction.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setMessageId("00000000-0000-0000-0000-000000000002")
                .setReaction(MessageReactionKind.MESSAGE_REACTION_KIND_LIKE)
                .setActive(true)
                .setClientOperationId("reaction-1")
                .build();
        MessagingPayloadPolicy.requireValid(command);
        assertEquals(SET_REACTION_GOLDEN, HexFormat.of().formatHex(command.toByteArray()));
        assertEquals(command,
                SetMessageReaction.parseFrom(HexFormat.of().parseHex(SET_REACTION_GOLDEN)));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_SET_MESSAGE_REACTION));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(
                        MessageType.MESSAGE_TYPE_MESSAGE_REACTION_APPLIED));
        assertEquals(MessageKind.MESSAGE_KIND_EVENT,
                MessageTypeRegistry.requiredKind(
                        MessageType.MESSAGE_TYPE_MESSAGE_REACTION_CHANGED));

        MessageReactionApplied changed = MessageReactionApplied.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setMessageId("00000000-0000-0000-0000-000000000002")
                .setReaction(MessageReactionKind.MESSAGE_REACTION_KIND_LIKE)
                .setActive(true)
                .setActorAccountId("00000000-0000-0000-0000-000000000003")
                .setClientOperationId("reaction-1")
                .setChanged(true)
                .setConversationSequence(3)
                .setOccurredAtEpochMs(1)
                .build();
        MessagingPayloadPolicy.requireValid(changed);
        assertThrows(IllegalArgumentException.class, () ->
                MessagingPayloadPolicy.requireValid(changed.toBuilder()
                        .setChanged(false).build()));
        MessagingPayloadPolicy.requireValid(changed.toBuilder()
                .setChanged(false).setConversationSequence(0).build());
        assertThrows(IllegalArgumentException.class, () ->
                MessagingPayloadPolicy.requireValid(command.toBuilder()
                        .setReaction(MessageReactionKind.MESSAGE_REACTION_KIND_UNSPECIFIED)
                        .build()));
    }

    @Test
    void pinMutationHasStableWireBytesKindsAndChangedOnlySequence() throws Exception {
        SetMessagePin command = SetMessagePin.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setMessageId("00000000-0000-0000-0000-000000000002")
                .setPinned(true)
                .setClientOperationId("pin-1")
                .build();
        MessagingPayloadPolicy.requireValid(command);
        assertEquals(SET_PIN_GOLDEN, HexFormat.of().formatHex(command.toByteArray()));
        assertEquals(command, SetMessagePin.parseFrom(HexFormat.of().parseHex(SET_PIN_GOLDEN)));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_SET_MESSAGE_PIN));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_MESSAGE_PIN_APPLIED));
        assertEquals(MessageKind.MESSAGE_KIND_EVENT,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_MESSAGE_PIN_CHANGED));

        MessagePinApplied changed = MessagePinApplied.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setMessageId("00000000-0000-0000-0000-000000000002")
                .setPinned(true)
                .setActorAccountId("00000000-0000-0000-0000-000000000003")
                .setClientOperationId("pin-1")
                .setChanged(true)
                .setConversationSequence(3)
                .setOccurredAtEpochMs(1)
                .build();
        MessagingPayloadPolicy.requireValid(changed);
        assertThrows(IllegalArgumentException.class, () ->
                MessagingPayloadPolicy.requireValid(changed.toBuilder()
                        .setChanged(false).build()));
        MessagingPayloadPolicy.requireValid(changed.toBuilder()
                .setChanged(false).setConversationSequence(0).build());
        assertThrows(IllegalArgumentException.class, () ->
                MessagingPayloadPolicy.requireValid(command.toBuilder()
                        .setClientOperationId("").build()));
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

        SubmitReplyMessage invalidReply = SubmitReplyMessage.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setTargetMessageId("not-a-message")
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8("hi"))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> MessagingPayloadPolicy.requireValid(invalidReply, "client-reply"));

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
                .addEntries(ConversationEntryRecord.newBuilder()
                        .setConversationId(CONVERSATION_ID)
                        .setConversationSequence(1)
                        .setMessage(record(1, "00000000-0000-0000-0000-000000000002")))
                .addEntries(ConversationEntryRecord.newBuilder()
                        .setConversationId(CONVERSATION_ID)
                        .setConversationSequence(2)
                        .setRecall(MessageRecalledRecord.newBuilder()
                                .setConversationId(CONVERSATION_ID)
                                .setConversationSequence(2)
                                .setMessageId("00000000-0000-0000-0000-000000000002")
                                .setActorAccountId("00000000-0000-0000-0000-000000000004")
                                .setSource("V1_IMPORT")))
                .addEntries(ConversationEntryRecord.newBuilder()
                        .setConversationId(CONVERSATION_ID)
                        .setConversationSequence(3)
                        .setReaction(MessageReactionChangedRecord.newBuilder()
                                .setConversationId(CONVERSATION_ID)
                                .setConversationSequence(3)
                                .setMessageId("00000000-0000-0000-0000-000000000002")
                                .setReaction(MessageReactionKind.MESSAGE_REACTION_KIND_LOVE)
                                .setActive(true)
                                .setActorAccountId(
                                        "00000000-0000-0000-0000-000000000004")
                                .setClientOperationId("reaction-history-1")
                                .setOccurredAtEpochMs(1_700_000_000_003L)))
                .addEntries(ConversationEntryRecord.newBuilder()
                        .setConversationId(CONVERSATION_ID)
                        .setConversationSequence(4)
                        .setPin(MessagePinChangedRecord.newBuilder()
                                .setConversationId(CONVERSATION_ID)
                                .setConversationSequence(4)
                                .setMessageId("00000000-0000-0000-0000-000000000002")
                                .setPinned(true)
                                .setActorAccountId(
                                        "00000000-0000-0000-0000-000000000004")
                                .setClientOperationId("pin-history-1")
                                .setOccurredAtEpochMs(1_700_000_000_004L)))
                .setNextSequence(4)
                .setLatestSequence(4)
                .build();
        MessagingPayloadPolicy.requireValid(page);
        MessagingPayloadPolicy.requireValid(page.getMessages(0));
        MessagingPayloadPolicy.requireValid(page.getEntries(2).getReaction());
        MessagingPayloadPolicy.requireValid(page.getEntries(3).getPin());
        assertThrows(IllegalArgumentException.class, () ->
                MessagingPayloadPolicy.requireValid(page.toBuilder()
                        .setEntries(2, page.getEntries(2).toBuilder()
                                .setReaction(page.getEntries(2).getReaction().toBuilder()
                                        .setReaction(MessageReactionKind
                                                .MESSAGE_REACTION_KIND_UNSPECIFIED)))
                        .build()));
        assertThrows(IllegalArgumentException.class, () ->
                MessagingPayloadPolicy.requireValid(page.toBuilder()
                        .setEntries(3, page.getEntries(3).toBuilder()
                                .setPin(page.getEntries(3).getPin().toBuilder()
                                        .setConversationSequence(5)))
                        .build()));

        MessageRecord reply = record(2, "00000000-0000-0000-0000-000000000006")
                .toBuilder()
                .setReply(MessageReplyReference.newBuilder()
                        .setTargetMessageId("00000000-0000-0000-0000-000000000002")
                        .setTargetConversationSequence(1)
                        .setTargetSenderAccountId(
                                "00000000-0000-0000-0000-000000000004"))
                .build();
        MessagingPayloadPolicy.requireValid(reply);
        assertThrows(IllegalArgumentException.class,
                () -> MessagingPayloadPolicy.requireValid(reply.toBuilder()
                        .setReply(reply.getReply().toBuilder()
                                .setTargetConversationSequence(2))
                        .build()));

        MessageHistoryPage missingDetail = MessageHistoryPage.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .addEntries(ConversationEntryRecord.newBuilder()
                        .setConversationId(CONVERSATION_ID)
                        .setConversationSequence(1))
                .setNextSequence(1)
                .setLatestSequence(1)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> MessagingPayloadPolicy.requireValid(missingDetail));
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
