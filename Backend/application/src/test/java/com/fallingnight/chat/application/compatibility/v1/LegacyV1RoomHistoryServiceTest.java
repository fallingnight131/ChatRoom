package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomHistoryServiceTest {
    @Test
    void validatesQueryAndPreservesAuthenticatedAccount() {
        UUID account = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1RoomHistoryService(query -> {
            calls.incrementAndGet();
            assertEquals(account, query.accountId());
            assertEquals(7, query.legacyRoomId());
            return new LegacyV1RoomHistoryResult.Page(7, true,
                    List.of(message(8, null)), List.of(deletion(9)), 9, 12, true);
        });

        var page = (LegacyV1RoomHistoryResult.Page) service.read(
                new LegacyV1RoomHistoryQuery(account, 7, 50, 0, 7L));
        assertEquals(9, page.nextSequence());
        assertEquals(LegacyV1RoomHistoryResult.Rejected.INVALID_REQUEST,
                service.read(new LegacyV1RoomHistoryQuery(account, 0, 50, 0, null)));
        assertEquals(LegacyV1RoomHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR,
                service.read(new LegacyV1RoomHistoryQuery(account, 7, 50, 0, -1L)));
        assertEquals(LegacyV1RoomHistoryResult.Rejected.INVALID_REQUEST,
                service.read(new LegacyV1RoomHistoryQuery(account, 7, 101, 0, null)));
        assertEquals(LegacyV1RoomHistoryResult.Rejected.INVALID_REQUEST,
                service.read(new LegacyV1RoomHistoryQuery(account, 7, 50, 1, 0L)));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInconsistentRoomModeSizeAndCursor() {
        UUID account = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> new LegacyV1RoomHistoryService(query ->
                new LegacyV1RoomHistoryResult.Page(8, true, List.of(), List.of(), 10, 10, false))
                .read(new LegacyV1RoomHistoryQuery(account, 7, 50, 0, 9L)));
        assertThrows(IllegalStateException.class, () -> new LegacyV1RoomHistoryService(query ->
                new LegacyV1RoomHistoryResult.Page(7, true,
                        List.of(message(9, null)), List.of(), 10, 10, false))
                .read(new LegacyV1RoomHistoryQuery(account, 7, 50, 0, 9L)));
        assertThrows(IllegalStateException.class, () -> new LegacyV1RoomHistoryService(query ->
                new LegacyV1RoomHistoryResult.Page(7, true,
                        List.of(message(8, null)), List.of(deletion(9)), 9, 10, true))
                .read(new LegacyV1RoomHistoryQuery(account, 7, 1, 0, 7L)));
    }

    @Test
    void pageEnforcesMixedOrderingIdentityAndContinuation() {
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomHistoryResult.Page(7, true,
                        List.of(message(10, null), message(8, null)), List.of(), 10, 12, true));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomHistoryResult.Page(7, true,
                        List.of(message(8, 10L)), List.of(deletion(10)), 10, 12, true));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomHistoryResult.Page(7, false,
                        List.of(message(8, null)), List.of(deletion(9)), 12, 12, false));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomHistoryDeletion(1, 2, "Admin", "operation", "unknown",
                        List.of(), List.of(), 0, 0, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomHistoryMessage(101, 1, null, 1, "client", "sender",
                        "Sender", "file.zip", "file", 9, "file.zip", -1,
                        false, "", false, Instant.EPOCH));
    }

    private static LegacyV1RoomHistoryMessage message(long sequence, Long mutation) {
        return new LegacyV1RoomHistoryMessage(100 + sequence, sequence, mutation,
                mutation == null ? sequence : mutation, "client-" + sequence,
                "sender", "Sender", "hello", "text", mutation != null,
                Instant.parse("2026-08-13T12:00:00Z"));
    }

    private static LegacyV1RoomHistoryDeletion deletion(long sequence) {
        return new LegacyV1RoomHistoryDeletion(200 + sequence, sequence, "Admin",
                "operation-" + sequence, "selected", List.of(101L), List.of(201L),
                0, 1, Instant.parse("2026-08-13T12:00:00Z"));
    }
}
