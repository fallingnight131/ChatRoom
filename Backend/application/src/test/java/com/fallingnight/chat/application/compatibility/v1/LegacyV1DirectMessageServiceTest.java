package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1DirectMessageServiceTest {
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void validatesTextLikeMessageBeforePersistenceAndPreservesIdentity() {
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1DirectMessageService(command -> {
            calls.incrementAndGet();
            assertEquals(SENDER, command.senderAccountId());
            assertEquals(DEVICE, command.senderDeviceId());
            assertEquals("peer", command.targetUsername());
            assertEquals("client-1", command.clientMessageId());
            assertEquals("hello", command.content());
            assertEquals("text", command.contentType());
            return accepted("peer");
        });

        assertEquals(accepted("peer"), service.submit(command("peer", "client-1", "hello", "text")));
        assertEquals(LegacyV1DirectMessageResult.Rejected.FRIENDSHIP_ACCESS_DENIED,
                service.submit(command(" peer ", "client-2", "hello", "text")));
        assertEquals(LegacyV1DirectMessageResult.Rejected.INVALID_CLIENT_MESSAGE_ID,
                service.submit(command("peer", "", "hello", "text")));
        assertEquals(LegacyV1DirectMessageResult.Rejected.INVALID_MESSAGE,
                service.submit(command("peer", "client-2", "", "text")));
        assertEquals(LegacyV1DirectMessageResult.Rejected.INVALID_MESSAGE,
                service.submit(command("peer", "client-2", "hello", "file")));
        assertEquals(1, calls.get());
    }

    @Test
    void failsClosedIfPersistenceChangesResolvedTarget() {
        var service = new LegacyV1DirectMessageService(command -> accepted("other"));
        assertThrows(IllegalStateException.class,
                () -> service.submit(command("peer", "client-1", "hello", "emoji")));
    }

    private static LegacyV1DirectMessageCommand command(
            String target, String clientId, String content, String type) {
        return new LegacyV1DirectMessageCommand(
                SENDER, DEVICE, target, clientId, content, type);
    }

    private static LegacyV1DirectMessageResult.Accepted accepted(String targetUsername) {
        return new LegacyV1DirectMessageResult.Accepted(false, 9, 101, 3,
                Instant.parse("2026-08-13T12:00:00Z"), TARGET, targetUsername);
    }
}
