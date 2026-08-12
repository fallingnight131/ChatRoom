package com.fallingnight.chat.application.compatibility.v1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
final class LegacyV1RoomAudienceServiceTest {
    @Test void filtersOnlyCandidatesAndSkipsEmptyPersistenceCall() {
        UUID conversation = UUID.randomUUID(), member = UUID.randomUUID(), outsider = UUID.randomUUID();
        var service = new LegacyV1RoomAudienceService((actual, candidates) -> {
            assertEquals(conversation, actual); assertEquals(Set.of(member, outsider), candidates);
            return Set.of(member);
        });
        assertEquals(Set.of(member), service.activeMappedMembers(
                conversation, Set.of(member, outsider)));
        assertEquals(Set.of(), new LegacyV1RoomAudienceService((actual, candidates) -> {
            throw new AssertionError();
        }).activeMappedMembers(conversation, Set.of()));
    }
    @Test void rejectsExpansionAndOversizedCandidates() {
        UUID conversation = UUID.randomUUID(), candidate = UUID.randomUUID();
        var expanding = new LegacyV1RoomAudienceService((actual, candidates) ->
                Set.of(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> expanding.activeMappedMembers(
                conversation, Set.of(candidate)));
        Set<UUID> tooMany = new HashSet<>();
        while (tooMany.size() <= LegacyV1RoomAudienceService.MAX_CANDIDATES)
            tooMany.add(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () ->
                expanding.activeMappedMembers(conversation, tooMany));
    }
}
