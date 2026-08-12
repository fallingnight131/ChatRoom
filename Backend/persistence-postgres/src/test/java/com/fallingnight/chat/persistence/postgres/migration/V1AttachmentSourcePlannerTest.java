package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

final class V1AttachmentSourcePlannerTest {
    private static final Instant FILE_TIME = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant MESSAGE_TIME = FILE_TIME.plusSeconds(1);

    @Test void reconcilesRoomAndFriendFileGraphsDeterministicallyWithoutLocators() {
        var roomFile = file(LegacyV1ConversationKind.ROOM, 9, 70, 3,
                "report.pdf", 4096, false, "/private/source/report.pdf", "https://old/secret");
        var friendFile = file(LegacyV1ConversationKind.FRIENDSHIP, 4, 70, 5,
                "photo.png", 8192, false, "/private/source/photo.png", "");
        var roomLink = link(roomFile, 101, "file");
        var friendLink = link(friendFile, 102, "image");

        var planner = new V1AttachmentSourcePlanner();
        V1AttachmentSourcePlan first = planner.plan(
                List.of(roomFile, friendFile), List.of(roomLink, friendLink));
        V1AttachmentSourcePlan reordered = planner.plan(
                List.of(friendFile, roomFile), List.of(friendLink, roomLink));

        assertTrue(first.readyForObjectEvidence()); assertEquals(first, reordered);
        assertEquals(2, first.attachments().size());
        assertNotEquals(first.attachments().getFirst().attachmentId(),
                first.attachments().getLast().attachmentId());
        assertTrue(first.attachments().stream().allMatch(value ->
                value.ownerDeviceId().equals(V1MessageTargetImportPlanner
                        .deterministicLegacyDeviceId(value.ownerAccountId()))));
        String safeProjection = first.attachments().toString() + first.issues();
        assertFalse(safeProjection.contains("/private/source"));
        assertFalse(safeProjection.contains("https://old"));

        var changedLocator = file(LegacyV1ConversationKind.ROOM, 9, 70, 3,
                "report.pdf", 4096, false, "/other/report.pdf", "https://old/secret");
        V1AttachmentSourcePlan changed = planner.plan(
                List.of(changedLocator, friendFile), List.of(roomLink, friendLink));
        assertNotEquals(first.sourceFingerprintSha256(), changed.sourceFingerprintSha256());
    }

    @Test void blocksOrphansDuplicatesAuthorityMetadataLifecycleAndUnsafeNames() {
        String secret = "/private/secret/path";
        var good = file(LegacyV1ConversationKind.ROOM, 9, 70, 3,
                "report.pdf", 4096, false, secret, "https://secret");
        var orphan = file(LegacyV1ConversationKind.ROOM, 9, 71, 3,
                "orphan.pdf", 4096, false, secret, "");
        var unsafe = new V1AttachmentSourceFile(LegacyV1ConversationKind.ROOM,
                9, 72, 3, "../escape.pdf", 0, true, "cleared", null,
                FILE_TIME, secret, "");
        var wrongAuthority = new V1AttachmentMessageLink(LegacyV1ConversationKind.ROOM,
                10, 101, 99, 70, "file", "different.pdf", 4097,
                true, "different", MESSAGE_TIME);
        var duplicateFile = file(LegacyV1ConversationKind.ROOM, 9, 70, 3,
                "report.pdf", 4096, false, secret, "");
        var drifted = file(LegacyV1ConversationKind.ROOM, 9, 73, 3,
                "source.pdf", 4096, false, secret, "");
        var driftedLink = new V1AttachmentMessageLink(LegacyV1ConversationKind.ROOM,
                10, 103, 99, 73, "archive", "different.pdf", 4097,
                true, "different", MESSAGE_TIME);
        var missing = new V1AttachmentMessageLink(LegacyV1ConversationKind.FRIENDSHIP,
                4, 200, 5, 999, "video", "movie.mp4", 10,
                false, "", MESSAGE_TIME);

        V1AttachmentSourcePlan plan = new V1AttachmentSourcePlanner().plan(
                List.of(good, orphan, unsafe, duplicateFile, drifted),
                List.of(wrongAuthority, driftedLink, missing));

        assertFalse(plan.readyForObjectEvidence());
        Set<String> codes = plan.issues().stream().map(V1AttachmentSourceIssue::code)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("DUPLICATE_FILE_ID"));
        assertTrue(codes.contains("ORPHAN_FILE_ROW"));
        assertTrue(codes.contains("INVALID_FILE_NAME"));
        assertTrue(codes.contains("INVALID_FILE_SIZE"));
        assertTrue(codes.contains("INVALID_FILE_LIFECYCLE"));
        assertTrue(codes.contains("MISSING_FILE_ROW"));
        assertTrue(codes.contains("FILE_MESSAGE_AUTHORITY_MISMATCH"));
        assertTrue(codes.contains("FILE_MESSAGE_METADATA_MISMATCH"));
        assertTrue(codes.contains("UNSUPPORTED_ATTACHMENT_TYPE"));
        assertFalse(plan.issues().toString().contains(secret));
        assertFalse(plan.issues().toString().contains("https://secret"));
    }

    private static V1AttachmentSourceFile file(LegacyV1ConversationKind kind,
            long conversation, long file, long uploader, String name, long size,
            boolean cleared, String path, String url) {
        return new V1AttachmentSourceFile(kind, conversation, file, uploader, name, size,
                cleared, cleared ? "cleared" : "", cleared ? FILE_TIME.plusSeconds(2) : null,
                FILE_TIME, path, url);
    }
    private static V1AttachmentMessageLink link(
            V1AttachmentSourceFile file, long message, String type) {
        return new V1AttachmentMessageLink(file.legacyKind(), file.legacyConversationId(),
                message, file.legacyUploaderUserId(), file.legacyFileId(), type,
                file.fileName(), file.byteSize(), file.cleared(), file.clearReason(), MESSAGE_TIME);
    }
}
