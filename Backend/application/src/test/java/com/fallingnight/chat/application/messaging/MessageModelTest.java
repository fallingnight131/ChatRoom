package com.fallingnight.chat.application.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageModelTest {
    @Test
    void submissionAndStoredProjectionOwnPayloadCopiesAndEnforceBounds() {
        byte[] source = {1, 2};
        MessageSubmission submission = new MessageSubmission(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "client-1", 100, source);
        source[0] = 9;
        byte[] returned = submission.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[] {1, 2}, submission.payload());

        StoredMessage stored = new StoredMessage(
                UUID.randomUUID(),
                submission.conversationId(),
                1,
                submission.senderAccountId(),
                submission.senderDeviceId(),
                submission.clientMessageId(),
                submission.messageType(),
                submission.payload(),
                Instant.EPOCH);
        byte[] storedPayload = stored.payload();
        storedPayload[0] = 8;
        assertArrayEquals(new byte[] {1, 2}, stored.payload());

        UUID target = UUID.randomUUID();
        MessageSubmission reply = new MessageSubmission(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "client-reply", 1, new byte[] {3}, Optional.of(target));
        MessageReplyReference reference = new MessageReplyReference(
                target, 7, UUID.randomUUID());
        StoredMessage storedReply = new StoredMessage(
                UUID.randomUUID(), reply.conversationId(), 8, reply.senderAccountId(),
                reply.senderDeviceId(), reply.clientMessageId(), reply.messageType(),
                reply.payload(), Instant.EPOCH, Optional.of(reference));
        org.junit.jupiter.api.Assertions.assertEquals(
                Optional.of(target), reply.replyToMessageId());
        org.junit.jupiter.api.Assertions.assertEquals(
                Optional.of(reference), storedReply.reply());
        assertThrows(IllegalArgumentException.class, () -> new StoredMessage(
                UUID.randomUUID(), reply.conversationId(), 7, reply.senderAccountId(),
                reply.senderDeviceId(), reply.clientMessageId(), reply.messageType(),
                reply.payload(), Instant.EPOCH, Optional.of(reference)));

        assertThrows(IllegalArgumentException.class, () -> new MessageSubmission(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "client-2",
                100,
                new byte[MessageSubmission.MAX_PAYLOAD_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> new MessageHistoryQuery(
                UUID.randomUUID(), UUID.randomUUID(), 0, 101));
        assertThrows(IllegalArgumentException.class, () -> new MessageSubmission(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "   ",
                100,
                new byte[0]));
    }

    @Test
    void reactionModelsEnforceOperationAndChangedSequenceInvariants() {
        MessageReactionCommand command = new MessageReactionCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                MessageReactionKind.LOVE, true, "reaction-1");
        new MessageReactionResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                command.reaction(), command.active(), command.clientOperationId(),
                true, 2, Instant.EPOCH, false);
        new MessageReactionResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                command.reaction(), command.active(), command.clientOperationId(),
                false, 0, Instant.EPOCH, false);

        assertThrows(IllegalArgumentException.class, () -> new MessageReactionCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                MessageReactionKind.LIKE, true, " "));
        assertThrows(IllegalArgumentException.class, () -> new MessageReactionResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                command.reaction(), command.active(), command.clientOperationId(),
                false, 1, Instant.EPOCH, false));
    }

    @Test
    void pinModelsEnforceOperationAndChangedSequenceInvariants() {
        MessagePinCommand command = new MessagePinCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                true, "pin-1");
        new MessagePinResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                command.pinned(), command.clientOperationId(), true, 2,
                Instant.EPOCH, false);
        new MessagePinResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                command.pinned(), command.clientOperationId(), false, 0,
                Instant.EPOCH, false);

        assertThrows(IllegalArgumentException.class, () -> new MessagePinCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                true, " "));
        assertThrows(IllegalArgumentException.class, () -> new MessagePinResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                command.pinned(), command.clientOperationId(), false, 1,
                Instant.EPOCH, false));
    }

    @Test
    void editModelsOwnContentAndEnforceUtf8RevisionAndSequenceBounds() {
        byte[] source = "updated".getBytes(StandardCharsets.UTF_8);
        MessageEditCommand command = new MessageEditCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                0, MessageEditCommand.TEXT_UTF8_CONTENT_TYPE, source, "edit-1");
        source[0] = 'X';
        assertArrayEquals("updated".getBytes(StandardCharsets.UTF_8), command.content());

        MessageEditResult.Applied applied = new MessageEditResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                1, command.contentType(), command.content(), command.clientOperationId(),
                true, 2, Instant.EPOCH, false);
        byte[] returned = applied.content();
        returned[0] = 'X';
        assertArrayEquals("updated".getBytes(StandardCharsets.UTF_8), applied.content());

        assertThrows(IllegalArgumentException.class, () -> new MessageEditCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                0, 2, new byte[] {1}, "edit-2"));
        assertThrows(IllegalArgumentException.class, () -> new MessageEditCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                0, MessageEditCommand.TEXT_UTF8_CONTENT_TYPE,
                new byte[] {(byte) 0xc3, (byte) 0x28}, "edit-3"));
        assertThrows(IllegalArgumentException.class, () -> new MessageEditResult.Applied(
                command.conversationId(), command.messageId(), command.actorAccountId(),
                1, command.contentType(), command.content(), command.clientOperationId(),
                false, 2, Instant.EPOCH, false));
        new ConversationHistoryEntry.Edit(
                command.conversationId(), 3, command.messageId(), 1,
                command.contentType(), command.content(), false, command.actorAccountId(),
                command.clientOperationId(), Instant.EPOCH);
        new ConversationHistoryEntry.Edit(
                command.conversationId(), 3, command.messageId(), 1,
                command.contentType(), new byte[0], true, command.actorAccountId(),
                command.clientOperationId(), Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> new ConversationHistoryEntry.Edit(
                command.conversationId(), 3, command.messageId(), 1,
                command.contentType(), command.content(), true, command.actorAccountId(),
                command.clientOperationId(), Instant.EPOCH));
    }
}
