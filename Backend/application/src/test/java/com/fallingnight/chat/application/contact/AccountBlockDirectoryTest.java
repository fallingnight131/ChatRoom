package com.fallingnight.chat.application.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AccountBlockDirectoryTest {
    private static final UUID ACTOR = UUID.fromString(
            "00000000-0000-4000-8000-000000000001");
    private static final UUID FIRST = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString(
            "20000000-0000-4000-8000-000000000001");

    @Test
    void acceptsBoundedActorCorrelatedOrderedPages() {
        var first = new AccountBlockSummary(FIRST, "甲", Instant.ofEpochMilli(1));
        var second = new AccountBlockSummary(SECOND, "乙", Instant.ofEpochMilli(2));
        var page = new AccountBlockDirectoryPage(
                ACTOR, List.of(first, second), Optional.of(SECOND), true);
        assertEquals(ACTOR, page.accountId());
        assertEquals(SECOND, page.nextAfterTargetAccountId().orElseThrow());
        assertEquals(25, new AccountBlockDirectoryQuery(
                ACTOR, Optional.of(FIRST), 25).limit());
        assertEquals(List.of(), new AccountBlockDirectoryPage(
                ACTOR, List.of(), Optional.empty(), false).blocks());
        var service = new AccountBlockDirectoryService(query -> {
            assertEquals(ACTOR, query.accountId());
            assertEquals(Optional.of(FIRST), query.afterTargetAccountId());
            return new AccountBlockDirectoryResult.Found(new AccountBlockDirectoryPage(
                    ACTOR, List.of(second), Optional.empty(), false));
        });
        assertEquals(second, assertFound(service.list(ACTOR,
                new AccountBlockDirectoryRequest(Optional.of(FIRST), 25)))
                .blocks().getFirst());
    }

    @Test
    void rejectsAmbiguousOrUnboundedProjections() {
        var first = new AccountBlockSummary(FIRST, "甲", Instant.ofEpochMilli(1));
        var second = new AccountBlockSummary(SECOND, "乙", Instant.ofEpochMilli(2));
        assertThrows(IllegalArgumentException.class, () ->
                new AccountBlockDirectoryQuery(ACTOR, Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class, () ->
                new AccountBlockDirectoryPage(
                        ACTOR, List.of(second, first), Optional.empty(), false));
        assertThrows(IllegalArgumentException.class, () ->
                new AccountBlockDirectoryPage(
                        ACTOR, List.of(first), Optional.of(FIRST), false));
        assertThrows(IllegalArgumentException.class, () ->
                new AccountBlockDirectoryPage(
                        ACTOR, List.of(), Optional.empty(), true));
        assertThrows(IllegalArgumentException.class, () ->
                new AccountBlockSummary(FIRST, "界".repeat(134), Instant.ofEpochMilli(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new AccountBlockSummary(FIRST, "\ud800", Instant.ofEpochMilli(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new AccountBlockSummary(FIRST, "   ", Instant.ofEpochMilli(1)));
        var wrongActor = new AccountBlockDirectoryService(query ->
                new AccountBlockDirectoryResult.Found(new AccountBlockDirectoryPage(
                        SECOND, List.of(), Optional.empty(), false)));
        assertThrows(IllegalStateException.class, () -> wrongActor.list(
                ACTOR, new AccountBlockDirectoryRequest(Optional.empty(), 1)));
        var tooMany = new AccountBlockDirectoryService(query ->
                new AccountBlockDirectoryResult.Found(new AccountBlockDirectoryPage(
                        ACTOR, List.of(first, second), Optional.empty(), false)));
        assertThrows(IllegalStateException.class, () -> tooMany.list(
                ACTOR, new AccountBlockDirectoryRequest(Optional.empty(), 1)));
        var nullResult = new AccountBlockDirectoryService(query -> null);
        assertThrows(NullPointerException.class, () -> nullResult.list(
                ACTOR, new AccountBlockDirectoryRequest(Optional.empty(), 1)));
    }

    private static AccountBlockDirectoryPage assertFound(AccountBlockDirectoryResult result) {
        return ((AccountBlockDirectoryResult.Found) result).page();
    }
}
