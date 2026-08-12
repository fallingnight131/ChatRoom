package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V1ContactRequestImportPlannerTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void plansOnlyPendingRequestsDeterministicallyAndCountsTerminalHistory() {
        V1ContactRequestSourceSnapshot source = new V1ContactRequestSourceSnapshot(
                Set.of(1L, 2L, 3L, 4L),
                List.of(new V1ExistingFriendPair(3, 4)),
                List.of(
                        new V1ContactRequestRow(12, 2, 1, "ACCEPTED", CREATED.plusSeconds(2)),
                        new V1ContactRequestRow(11, 1, 2, "pending", CREATED),
                        new V1ContactRequestRow(13, 3, 4, "REJECTED", CREATED.plusSeconds(3))));

        V1ContactRequestImportPlan first = new V1ContactRequestImportPlanner().plan(source);
        V1ContactRequestImportPlan reordered = new V1ContactRequestImportPlanner().plan(
                new V1ContactRequestSourceSnapshot(
                        source.legacyUserIds(),
                        source.friendships().reversed(),
                        source.requests().reversed()));

        assertTrue(first.readyToCompareWithTarget());
        assertEquals(first, reordered);
        assertEquals(3, first.sourceRows());
        assertEquals(1, first.sourcePendingRows());
        assertEquals(2, first.sourceTerminalRows());
        assertEquals(64, first.sourceFingerprint().length());
        PlannedV1ContactRequest pending = first.pendingRequests().getFirst();
        assertEquals(11, pending.legacyRequestId());
        assertEquals(V1ContactRequestImportPlanner.deterministicRequestId(11), pending.requestId());
        assertEquals(V1IdentityImportPlanner.deterministicUserId(1), pending.requesterAccountId());
        assertEquals(V1IdentityImportPlanner.deterministicUserId(2), pending.recipientAccountId());
        assertEquals(CREATED, pending.createdAt());
        assertNotEquals(
                V1ContactRequestImportPlanner.deterministicRequestId(12), pending.requestId());
    }

    @Test
    void blocksInvalidRowsContradictoryFriendshipAndReversePendingPairSafely() {
        V1ContactRequestSourceSnapshot source = new V1ContactRequestSourceSnapshot(
                Set.of(1L, 2L, 3L),
                List.of(new V1ExistingFriendPair(1, 2)),
                List.of(
                        new V1ContactRequestRow(0, 1, 9, "PENDING", null),
                        new V1ContactRequestRow(2, 1, 1, "WAITING", CREATED),
                        new V1ContactRequestRow(3, 1, 2, "PENDING", CREATED),
                        new V1ContactRequestRow(4, 3, 2, "PENDING", CREATED),
                        new V1ContactRequestRow(5, 2, 3, "PENDING", CREATED),
                        new V1ContactRequestRow(5, 1, 3, "REJECTED", CREATED)));

        V1ContactRequestImportPlan plan = new V1ContactRequestImportPlanner().plan(source);

        assertFalse(plan.readyToCompareWithTarget());
        Set<String> codes = plan.issues().stream()
                .map(V1ContactRequestImportIssue::code)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("INVALID_REQUEST_ID"));
        assertTrue(codes.contains("UNKNOWN_REQUEST_ACCOUNT"));
        assertTrue(codes.contains("INVALID_REQUEST_CREATED_AT"));
        assertTrue(codes.contains("SELF_REQUEST"));
        assertTrue(codes.contains("UNSUPPORTED_REQUEST_STATUS"));
        assertTrue(codes.contains("PENDING_FOR_EXISTING_FRIEND"));
        assertTrue(codes.contains("DUPLICATE_PENDING_PAIR"));
        assertTrue(codes.contains("DUPLICATE_REQUEST_ID"));
        assertFalse(plan.issues().toString().contains("username"));
        assertFalse(plan.issues().toString().contains("/"));
    }
}
