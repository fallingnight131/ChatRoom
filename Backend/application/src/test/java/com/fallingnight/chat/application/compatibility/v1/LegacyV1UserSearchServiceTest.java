package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1UserSearchServiceTest {
    @Test
    void bindsExclusionNormalizesKeywordAndJoinsPresence() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        LegacyV1UserSearchService service = new LegacyV1UserSearchService(
                (excluded, keyword, limit) -> {
                    calls.incrementAndGet();
                    assertEquals(owner, excluded);
                    assertEquals("Peer", keyword);
                    assertEquals(20, limit);
                    return List.of(new LegacyV1UserSearchEntry(
                            peer, 44, "peer", "Peer User"));
                }, requested -> {
                    assertEquals(Set.of(peer), requested);
                    return Set.of(peer);
                });

        assertEquals(new LegacyV1UserSearchResult.Found(List.of(
                        new LegacyV1UserSearchUser(44, "peer", "Peer User", true))),
                service.search(owner, "  Peer  "));
        assertEquals(LegacyV1UserSearchResult.Rejected.INSTANCE,
                service.search(owner, "   "));
        assertEquals(LegacyV1UserSearchResult.Rejected.INSTANCE,
                service.search(owner, "x".repeat(257)));
        assertEquals(1, calls.get());
    }

    @Test
    void failsClosedOnSelfDuplicateOrExcessProjection() {
        UUID owner = UUID.randomUUID();
        var self = new LegacyV1UserSearchService(
                (ignored, keyword, limit) -> List.of(
                        new LegacyV1UserSearchEntry(owner, 1, "owner", "Owner")),
                ignored -> Set.of());
        assertThrows(IllegalStateException.class, () -> self.search(owner, "owner"));

        UUID peer = UUID.randomUUID();
        var duplicate = new LegacyV1UserSearchService(
                (ignored, keyword, limit) -> List.of(
                        new LegacyV1UserSearchEntry(peer, 2, "peer", "Peer"),
                        new LegacyV1UserSearchEntry(peer, 2, "peer", "Peer")),
                ignored -> Set.of());
        assertThrows(IllegalStateException.class, () -> duplicate.search(owner, "peer"));
    }
}
