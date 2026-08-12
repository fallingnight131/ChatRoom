package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomSearchServiceTest {
    @Test void bindsActorNormalizesKeywordAndRemovesCanonicalIdentity() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        LegacyV1RoomSearchService service = new LegacyV1RoomSearchService(
                (boundActor, keyword, limit) -> {
                    calls.incrementAndGet(); assertEquals(actor, boundActor);
                    assertEquals("Project", keyword); assertEquals(20, limit);
                    return List.of(new LegacyV1RoomSearchEntry(
                            conversation, 7, "Project Room", 42, 3));
                });
        assertEquals(new LegacyV1RoomSearchResult.Found(List.of(
                        new LegacyV1RoomSearchRoom(7, "Project Room", 42, 3))),
                service.search(actor, "  Project  "));
        assertEquals(LegacyV1RoomSearchResult.Rejected.INSTANCE, service.search(actor, "   "));
        assertEquals(LegacyV1RoomSearchResult.Rejected.INSTANCE,
                service.search(actor, "x".repeat(257)));
        assertEquals(LegacyV1RoomSearchResult.Rejected.INSTANCE,
                service.search(actor, "bad\nkeyword"));
        assertEquals(1, calls.get());
    }

    @Test void failsClosedOnDuplicateOrExcessProjection() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        var duplicate = new LegacyV1RoomSearchService((ignored, keyword, limit) -> List.of(
                new LegacyV1RoomSearchEntry(conversation, 7, "One", 42, 1),
                new LegacyV1RoomSearchEntry(conversation, 7, "Two", 42, 1)));
        assertThrows(IllegalStateException.class, () -> duplicate.search(actor, "room"));
        var excess = new LegacyV1RoomSearchService((ignored, keyword, limit) ->
                java.util.stream.IntStream.rangeClosed(1, 21)
                        .mapToObj(id -> new LegacyV1RoomSearchEntry(
                                UUID.randomUUID(), id, "Room " + id, 42, 0))
                        .toList());
        assertThrows(IllegalStateException.class, () -> excess.search(actor, "room"));
    }
}
