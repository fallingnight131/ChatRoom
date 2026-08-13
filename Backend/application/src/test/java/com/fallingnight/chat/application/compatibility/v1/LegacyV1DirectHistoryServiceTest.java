package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1DirectHistoryServiceTest {
    @Test
    void validatesQueryBeforePortAndPreservesServerBoundAccount() {
        UUID account = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1DirectHistoryService(query -> {
            calls.incrementAndGet();
            assertEquals(account, query.accountId());
            assertEquals("peer", query.targetUsername());
            assertEquals(50, query.limit());
            assertEquals(7L, query.afterSequence());
            return new LegacyV1DirectHistoryResult.Page(
                    9, "peer", true, List.of(message(8, null)), 8, 8, false);
        });

        assertEquals(8, ((LegacyV1DirectHistoryResult.Page) service.read(
                new LegacyV1DirectHistoryQuery(account, "peer", 50, 0, 7L)))
                .nextSequence());
        assertEquals(LegacyV1DirectHistoryResult.Rejected.INVALID_REQUEST,
                service.read(new LegacyV1DirectHistoryQuery(account, " peer ", 50, 0, null)));
        assertEquals(LegacyV1DirectHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR,
                service.read(new LegacyV1DirectHistoryQuery(account, "peer", 50, 0, -1L)));
        assertEquals(LegacyV1DirectHistoryResult.Rejected.INVALID_REQUEST,
                service.read(new LegacyV1DirectHistoryQuery(account, "peer", 101, 0, null)));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInconsistentPageAndMessageOrdering() {
        UUID account = UUID.randomUUID();
        var service = new LegacyV1DirectHistoryService(query ->
                new LegacyV1DirectHistoryResult.Page(
                        9, "other", true, List.of(), 0, 0, false));
        assertThrows(IllegalStateException.class, () -> service.read(
                new LegacyV1DirectHistoryQuery(account, "peer", 50, 0, 0L)));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1DirectHistoryResult.Page(9, "peer", true,
                        List.of(message(2, null), message(1, null)), 2, 2, false));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1DirectHistoryMessage(101, 1, null, 1, "client", "sender",
                        "Sender", "file.zip", "file", 9, "file.zip", 10,
                        true, "   ", false, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1DirectHistoryMessage(101, 1, null, 1, "client", "sender",
                        "Sender", "hello", "text", -1, "", 0,
                        false, "", false, Instant.EPOCH));
    }

    private static LegacyV1DirectHistoryMessage message(long sequence, Long mutation) {
        return new LegacyV1DirectHistoryMessage(100 + sequence, sequence, mutation,
                mutation == null ? sequence : mutation, "client-" + sequence,
                "sender", "Sender", "hello", "text", mutation != null,
                Instant.parse("2026-08-13T12:00:00Z"));
    }
}
