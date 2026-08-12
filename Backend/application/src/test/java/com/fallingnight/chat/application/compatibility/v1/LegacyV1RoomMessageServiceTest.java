package com.fallingnight.chat.application.compatibility.v1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
final class LegacyV1RoomMessageServiceTest {
    @Test void bindsIdentityAndValidatesBeforePersistence() {
        UUID account = UUID.randomUUID(), device = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1RoomMessageService(command -> {
            calls.incrementAndGet(); assertEquals(account, command.senderAccountId());
            assertEquals(device, command.senderDeviceId());
            return new LegacyV1RoomMessageResult.Accepted(false, 7, 101, 3,
                    Instant.EPOCH, conversation);
        });
        assertEquals(101, ((LegacyV1RoomMessageResult.Accepted) service.submit(
                new LegacyV1RoomMessageCommand(account, device, 7, "client-1",
                        "hello", "text"))).legacyMessageId());
        assertEquals(LegacyV1RoomMessageResult.Rejected.ROOM_ACCESS_DENIED, service.submit(
                new LegacyV1RoomMessageCommand(account, device, 0, "client", "hello", "text")));
        assertEquals(LegacyV1RoomMessageResult.Rejected.INVALID_CLIENT_MESSAGE_ID, service.submit(
                new LegacyV1RoomMessageCommand(account, device, 7, "", "hello", "text")));
        assertEquals(LegacyV1RoomMessageResult.Rejected.INVALID_MESSAGE, service.submit(
                new LegacyV1RoomMessageCommand(account, device, 7, "client", "hello", "file")));
        assertEquals(1, calls.get());
    }
    @Test void rejectsChangedTarget() {
        UUID account = UUID.randomUUID(), device = UUID.randomUUID();
        var service = new LegacyV1RoomMessageService(command ->
                new LegacyV1RoomMessageResult.Accepted(false, 8, 101, 3,
                        Instant.EPOCH, UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> service.submit(
                new LegacyV1RoomMessageCommand(account, device, 7, "client", "hello", "emoji")));
    }
}
