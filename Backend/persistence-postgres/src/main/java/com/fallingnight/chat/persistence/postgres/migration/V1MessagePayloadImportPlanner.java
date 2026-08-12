package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure, fail-closed mapping for retained V1 content before attachment support exists. */
public final class V1MessagePayloadImportPlanner {
    private static final int TEXT_UTF8 = 1;
    private static final int MAX_TEXT_BYTES = 65_536;
    private static final UUID MESSAGE_NAMESPACE =
            UUID.fromString("81277d90-43aa-5fe4-a599-62280c07d003");

    public V1MessagePayloadImportPlan plan(List<V1MessagePayloadRow> source) {
        List<V1MessagePayloadRow> rows = new ArrayList<>(source);
        rows.sort(Comparator.comparing(V1MessagePayloadRow::legacyKind)
                .thenComparingLong(V1MessagePayloadRow::legacyMessageId)
                .thenComparingLong(V1MessagePayloadRow::legacyConversationId));
        List<PlannedV1MessagePayload> planned = new ArrayList<>();
        List<DeferredV1AttachmentPayload> deferredAttachments = new ArrayList<>();
        List<V1MessagePayloadImportIssue> issues = new ArrayList<>();
        Set<LegacyMessageKey> identities = new HashSet<>();
        for (V1MessagePayloadRow row : rows) {
            LegacyMessageKey key = new LegacyMessageKey(row.legacyKind(), row.legacyMessageId());
            if (row.legacyConversationId() <= 0 || row.legacyMessageId() <= 0
                    || !identities.add(key)) {
                issues.add(issue(row, "INVALID_OR_DUPLICATE_MESSAGE_ID",
                        "message identifiers must be positive and unique in their source table"));
                continue;
            }
            if (isAttachment(row.contentType())) {
                deferredAttachments.add(new DeferredV1AttachmentPayload(
                        row.legacyKind(), row.legacyConversationId(), row.legacyMessageId(),
                        row.fileId(), row.contentType(), row.recalled()));
                continue;
            }
            if (!"text".equals(row.contentType()) && !"emoji".equals(row.contentType())) {
                issues.add(issue(row, "UNSUPPORTED_CONTENT_TYPE",
                        "content type has no reviewed V2 mapping"));
                continue;
            }
            String content = row.content();
            if (content == null || content.isEmpty()
                    || content.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                issues.add(issue(row, "INVALID_TEXT_CONTENT",
                        "text-like content must contain 1..65536 UTF-8 bytes"));
                continue;
            }
            planned.add(new PlannedV1MessagePayload(
                    row.legacyKind(),
                    row.legacyConversationId(),
                    row.legacyMessageId(),
                    deterministicMessageId(row.legacyKind(), row.legacyMessageId()),
                    "v1-import-" + row.legacyKind().name().toLowerCase(java.util.Locale.ROOT)
                            + "-" + row.legacyMessageId(),
                    TEXT_UTF8,
                    row.contentType(),
                    content,
                    !row.recalled()));
        }
        return new V1MessagePayloadImportPlan(
                fingerprint(rows), rows.size(), planned, deferredAttachments, issues);
    }

    private static String fingerprint(List<V1MessagePayloadRow> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream data = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                data.writeInt(rows.size());
                for (V1MessagePayloadRow row : rows) {
                    writeNullable(data, row.legacyKind() == null ? null : row.legacyKind().name());
                    data.writeLong(row.legacyConversationId());
                    data.writeLong(row.legacyMessageId());
                    writeNullable(data, row.contentType());
                    writeNullable(data, row.content());
                    writeNullable(data, row.fileName());
                    data.writeLong(row.fileSize());
                    data.writeLong(row.fileId());
                    data.writeBoolean(row.fileCleared());
                    writeNullable(data, row.clearReason());
                    writeNullable(data, row.thumbnail());
                    data.writeBoolean(row.recalled());
                }
                return HexFormat.of().formatHex(digest.digest());
            }
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("message payload fingerprint failed", exception);
        }
    }

    private static void writeNullable(DataOutputStream data, String value) throws IOException {
        if (value == null) {
            data.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(encoded.length);
        data.write(encoded);
    }

    public static UUID deterministicMessageId(
            LegacyV1ConversationKind kind, long legacyMessageId) {
        if (legacyMessageId <= 0) {
            throw new IllegalArgumentException("legacyMessageId must be positive");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(uuidBytes(MESSAGE_NAMESPACE));
            byte[] hash = digest.digest((kind.name() + ":" + legacyMessageId)
                    .getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(),
                    ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static boolean isAttachment(String type) {
        return "file".equals(type) || "image".equals(type) || "video".equals(type);
    }

    private static V1MessagePayloadImportIssue issue(
            V1MessagePayloadRow row, String code, String message) {
        return new V1MessagePayloadImportIssue(
                row.legacyKind(), row.legacyConversationId(),
                row.legacyMessageId(), code, message);
    }

    private record LegacyMessageKey(LegacyV1ConversationKind kind, long id) {}
}
