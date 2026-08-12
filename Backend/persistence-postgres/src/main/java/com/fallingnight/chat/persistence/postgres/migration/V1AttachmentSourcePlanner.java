package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.attachment.AttachmentRegistration;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Pure fail-closed reconciliation of V1 file rows and attachment messages. */
public final class V1AttachmentSourcePlanner {
    private static final UUID ATTACHMENT_NAMESPACE =
            UUID.fromString("7b1d8a50-ea41-5c39-930f-6b9fd835d005");

    public V1AttachmentSourcePlan plan(
            List<V1AttachmentSourceFile> inputFiles,
            List<V1AttachmentMessageLink> inputLinks) {
        Objects.requireNonNull(inputFiles, "inputFiles");
        Objects.requireNonNull(inputLinks, "inputLinks");
        List<V1AttachmentSourceFile> files = new ArrayList<>(inputFiles);
        List<V1AttachmentMessageLink> links = new ArrayList<>(inputLinks);
        files.sort(fileOrder()); links.sort(linkOrder());
        List<V1AttachmentSourceIssue> issues = new ArrayList<>();
        Map<FileKey, V1AttachmentSourceFile> filesByKey = new HashMap<>();
        Set<FileKey> invalidFileKeys = new HashSet<>();
        for (var file : files) {
            FileKey key = new FileKey(file.legacyKind(), file.legacyFileId());
            if (filesByKey.putIfAbsent(key, file) != null) {
                invalidFileKeys.add(key);
                issues.add(issue(file, 0, "DUPLICATE_FILE_ID",
                        "V1 file identity is duplicated in its typed namespace"));
            }
        }
        Map<FileKey, List<V1AttachmentMessageLink>> linksByFile = new HashMap<>();
        Map<MessageKey, FileKey> messages = new HashMap<>();
        for (var link : links) {
            FileKey fileKey = new FileKey(link.legacyKind(), link.legacyFileId());
            FileKey firstFile = messages.putIfAbsent(
                    new MessageKey(link.legacyKind(), link.legacyMessageId()), fileKey);
            if (firstFile != null) {
                invalidFileKeys.add(firstFile); invalidFileKeys.add(fileKey);
                issues.add(issue(link, "DUPLICATE_MESSAGE_ID",
                        "V1 attachment message identity is duplicated"));
            }
            linksByFile.computeIfAbsent(fileKey, ignored -> new ArrayList<>()).add(link);
        }

        List<PlannedV1AttachmentSource> planned = new ArrayList<>();
        for (var file : files) {
            FileKey key = new FileKey(file.legacyKind(), file.legacyFileId());
            if (invalidFileKeys.contains(key)) continue;
            List<V1AttachmentMessageLink> matches = linksByFile.getOrDefault(key, List.of());
            int before = issues.size();
            validateFile(file, matches, issues);
            if (issues.size() != before) continue;
            V1AttachmentMessageLink link = matches.getFirst();
            planned.add(project(file, link));
        }
        for (var entry : linksByFile.entrySet()) {
            if (!filesByKey.containsKey(entry.getKey())) {
                for (var link : entry.getValue()) issues.add(issue(link,
                        "MISSING_FILE_ROW", "V1 attachment message has no file registry row"));
            }
        }
        planned.sort(Comparator.comparing(PlannedV1AttachmentSource::legacyKind)
                .thenComparingLong(PlannedV1AttachmentSource::legacyFileId));
        issues.sort(Comparator.comparing(V1AttachmentSourceIssue::legacyKind,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(V1AttachmentSourceIssue::legacyConversationId)
                .thenComparingLong(V1AttachmentSourceIssue::legacyFileId)
                .thenComparingLong(V1AttachmentSourceIssue::legacyMessageId)
                .thenComparing(V1AttachmentSourceIssue::code));
        return new V1AttachmentSourcePlan(fingerprint(files, links), files.size(), links.size(),
                planned, issues);
    }

    private static void validateFile(V1AttachmentSourceFile file,
            List<V1AttachmentMessageLink> links, List<V1AttachmentSourceIssue> issues) {
        if (file.legacyKind() == null || file.legacyConversationId() <= 0
                || file.legacyFileId() <= 0 || file.legacyUploaderUserId() <= 0) {
            issues.add(issue(file, 0, "INVALID_FILE_IDENTITY",
                    "V1 file identity and ownership must be positive and typed"));
        }
        if (!validFileName(file.fileName())) {
            issues.add(issue(file, 0, "INVALID_FILE_NAME",
                    "V1 filename must be a bounded basename"));
        }
        if (file.byteSize() < 1 || file.byteSize() > AttachmentRegistration.MAX_BYTE_SIZE) {
            issues.add(issue(file, 0, "INVALID_FILE_SIZE",
                    "V1 file size is outside the canonical attachment bound"));
        }
        if (file.createdAt() == null || (file.cleared() && file.clearedAt() == null)
                || (!file.cleared() && file.clearedAt() != null)) {
            issues.add(issue(file, 0, "INVALID_FILE_LIFECYCLE",
                    "V1 file lifecycle timestamps are inconsistent"));
        }
        if (links.size() != 1) {
            issues.add(issue(file, 0, links.isEmpty() ? "ORPHAN_FILE_ROW" :
                    "FILE_LINKED_TO_MULTIPLE_MESSAGES",
                    "each V1 file must map to exactly one retained attachment message"));
            return;
        }
        V1AttachmentMessageLink link = links.getFirst();
        if (link.legacyConversationId() != file.legacyConversationId()
                || link.legacySenderUserId() != file.legacyUploaderUserId()
                || link.legacyMessageId() <= 0 || link.acceptedAt() == null) {
            issues.add(issue(file, link.legacyMessageId(), "FILE_MESSAGE_AUTHORITY_MISMATCH",
                    "V1 file and message conversation, uploader, or identity differ"));
        }
        if (!Set.of("file", "image", "video").contains(link.contentType())) {
            issues.add(issue(file, link.legacyMessageId(), "UNSUPPORTED_ATTACHMENT_TYPE",
                    "V1 attachment message type is unsupported"));
        }
        if (!Objects.equals(file.fileName(), link.fileName())
                || file.byteSize() != link.byteSize()
                || file.cleared() != link.fileCleared()
                || !Objects.equals(normalize(file.clearReason()), normalize(link.clearReason()))) {
            issues.add(issue(file, link.legacyMessageId(), "FILE_MESSAGE_METADATA_MISMATCH",
                    "V1 file and message attachment metadata differ"));
        }
    }

    private static PlannedV1AttachmentSource project(
            V1AttachmentSourceFile file, V1AttachmentMessageLink link) {
        UUID account = V1IdentityImportPlanner.deterministicUserId(file.legacyUploaderUserId());
        return new PlannedV1AttachmentSource(file.legacyKind(), file.legacyConversationId(),
                file.legacyFileId(), link.legacyMessageId(), file.legacyUploaderUserId(),
                conversationId(file.legacyKind(), file.legacyConversationId()),
                deterministicAttachmentId(file.legacyKind(), file.legacyFileId()),
                V1MessagePayloadImportPlanner.deterministicMessageId(
                        file.legacyKind(), link.legacyMessageId()), account,
                V1MessageTargetImportPlanner.deterministicLegacyDeviceId(account),
                "v1-import-" + file.legacyKind().name().toLowerCase(Locale.ROOT)
                        + "-file-" + file.legacyFileId(), file.fileName(), file.byteSize(),
                link.contentType(), file.cleared(), normalize(file.clearReason()),
                file.createdAt(), link.acceptedAt());
    }

    public static UUID deterministicAttachmentId(
            LegacyV1ConversationKind kind, long legacyFileId) {
        Objects.requireNonNull(kind, "kind");
        if (legacyFileId <= 0) throw new IllegalArgumentException("legacyFileId must be positive");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(uuidBytes(ATTACHMENT_NAMESPACE));
            byte[] hash = digest.digest((kind.name() + ":" + legacyFileId)
                    .getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(),
                    ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static UUID conversationId(LegacyV1ConversationKind kind, long id) {
        return kind == LegacyV1ConversationKind.ROOM
                ? V1ConversationImportPlanner.deterministicRoomId(id)
                : V1ConversationImportPlanner.deterministicFriendshipId(id);
    }
    private static boolean validFileName(String value) {
        return value != null && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length <= 255
                && !value.equals(".") && !value.equals("..")
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static String normalize(String value) { return value == null ? "" : value; }

    private static String fingerprint(List<V1AttachmentSourceFile> files,
            List<V1AttachmentMessageLink> links) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream data = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                data.writeInt(files.size());
                for (var row : files) {
                    write(data, row.legacyKind() == null ? null : row.legacyKind().name());
                    data.writeLong(row.legacyConversationId()); data.writeLong(row.legacyFileId());
                    data.writeLong(row.legacyUploaderUserId()); write(data, row.fileName());
                    data.writeLong(row.byteSize()); data.writeBoolean(row.cleared());
                    write(data, row.clearReason()); write(data, instant(row.clearedAt()));
                    write(data, instant(row.createdAt())); write(data, row.sourcePath());
                    write(data, row.legacyObjectUrl());
                }
                data.writeInt(links.size());
                for (var row : links) {
                    write(data, row.legacyKind() == null ? null : row.legacyKind().name());
                    data.writeLong(row.legacyConversationId());
                    data.writeLong(row.legacyMessageId()); data.writeLong(row.legacySenderUserId());
                    data.writeLong(row.legacyFileId()); write(data, row.contentType());
                    write(data, row.fileName()); data.writeLong(row.byteSize());
                    data.writeBoolean(row.fileCleared()); write(data, row.clearReason());
                    write(data, instant(row.acceptedAt()));
                }
                return HexFormat.of().formatHex(digest.digest());
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("V1 attachment source fingerprint failed", exception);
        }
    }
    private static String instant(java.time.Instant value) {
        return value == null ? null : value.toString();
    }
    private static void write(DataOutputStream data, String value) throws IOException {
        if (value == null) { data.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length); data.write(bytes);
    }
    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
    private static Comparator<V1AttachmentSourceFile> fileOrder() {
        return Comparator.comparing(V1AttachmentSourceFile::legacyKind,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(V1AttachmentSourceFile::legacyFileId)
                .thenComparingLong(V1AttachmentSourceFile::legacyConversationId);
    }
    private static Comparator<V1AttachmentMessageLink> linkOrder() {
        return Comparator.comparing(V1AttachmentMessageLink::legacyKind,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(V1AttachmentMessageLink::legacyMessageId)
                .thenComparingLong(V1AttachmentMessageLink::legacyFileId);
    }
    private static V1AttachmentSourceIssue issue(
            V1AttachmentSourceFile row, long messageId, String code, String message) {
        return new V1AttachmentSourceIssue(row.legacyKind(), row.legacyConversationId(),
                row.legacyFileId(), messageId, code, message);
    }
    private static V1AttachmentSourceIssue issue(
            V1AttachmentMessageLink row, String code, String message) {
        return new V1AttachmentSourceIssue(row.legacyKind(), row.legacyConversationId(),
                row.legacyFileId(), row.legacyMessageId(), code, message);
    }
    private record FileKey(LegacyV1ConversationKind kind, long id) { }
    private record MessageKey(LegacyV1ConversationKind kind, long id) { }
}
