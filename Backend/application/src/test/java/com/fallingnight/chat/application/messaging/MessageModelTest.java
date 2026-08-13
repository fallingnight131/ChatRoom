package com.fallingnight.chat.application.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
