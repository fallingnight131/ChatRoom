package com.fallingnight.chat.persistence.postgres.migration;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Pure deterministic validation and mapping for V1 contact requests. */
public final class V1ContactRequestImportPlanner {
    private static final UUID REQUEST_NAMESPACE =
            UUID.fromString("c6fc0651-ce31-5f09-a346-296a2827d001");

    public V1ContactRequestImportPlan plan(V1ContactRequestSourceSnapshot source) {
        List<V1ContactRequestRow> rows = new ArrayList<>(source.requests());
        rows.sort(Comparator.comparingLong(V1ContactRequestRow::legacyRequestId));
        Set<String> friendshipPairs = new HashSet<>();
        for (V1ExistingFriendPair friendship : source.friendships()) {
            friendshipPairs.add(pair(friendship.firstUserId(), friendship.secondUserId()));
        }
        Set<Long> ids = new HashSet<>();
        Set<String> pendingPairs = new HashSet<>();
        List<PlannedV1ContactRequest> planned = new ArrayList<>();
        List<V1ContactRequestImportIssue> issues = new ArrayList<>();
        int pending = 0;
        int terminal = 0;
        for (V1ContactRequestRow row : rows) {
            String status = row.status() == null
                    ? "" : row.status().toUpperCase(Locale.ROOT);
            List<V1ContactRequestImportIssue> rowIssues = new ArrayList<>();
            if (row.legacyRequestId() <= 0) {
                rowIssues.add(issue(row, "INVALID_REQUEST_ID",
                        "legacy request id must be positive"));
            } else if (!ids.add(row.legacyRequestId())) {
                rowIssues.add(issue(row, "DUPLICATE_REQUEST_ID",
                        "legacy request id is duplicated"));
            }
            if (!source.legacyUserIds().contains(row.requesterUserId())
                    || !source.legacyUserIds().contains(row.recipientUserId())) {
                rowIssues.add(issue(row, "UNKNOWN_REQUEST_ACCOUNT",
                        "request participants must reference imported users"));
            }
            if (row.requesterUserId() == row.recipientUserId()) {
                rowIssues.add(issue(row, "SELF_REQUEST",
                        "request participants must be distinct"));
            }
            if (row.createdAt() == null) {
                rowIssues.add(issue(row, "INVALID_REQUEST_CREATED_AT",
                        "request creation timestamp is required"));
            }
            if (!Set.of("PENDING", "ACCEPTED", "REJECTED").contains(status)) {
                rowIssues.add(issue(row, "UNSUPPORTED_REQUEST_STATUS",
                        "request status must be pending, accepted, or rejected"));
            }
            if ("PENDING".equals(status)) {
                pending++;
                String pair = pair(row.requesterUserId(), row.recipientUserId());
                if (friendshipPairs.contains(pair)) {
                    rowIssues.add(issue(row, "PENDING_FOR_EXISTING_FRIEND",
                            "pending request conflicts with an accepted friendship"));
                }
                if (!pendingPairs.add(pair)) {
                    rowIssues.add(issue(row, "DUPLICATE_PENDING_PAIR",
                            "only one pending request is allowed for an unordered pair"));
                }
                if (rowIssues.isEmpty()) {
                    planned.add(new PlannedV1ContactRequest(
                            row.legacyRequestId(),
                            deterministicRequestId(row.legacyRequestId()),
                            V1IdentityImportPlanner.deterministicUserId(row.requesterUserId()),
                            V1IdentityImportPlanner.deterministicUserId(row.recipientUserId()),
                            row.createdAt()));
                }
            } else {
                terminal++;
            }
            issues.addAll(rowIssues);
        }
        return new V1ContactRequestImportPlan(
                fingerprint(source, rows), rows.size(), pending, terminal, planned, issues);
    }

    public static UUID deterministicRequestId(long legacyRequestId) {
        if (legacyRequestId <= 0) throw new IllegalArgumentException("legacyRequestId");
        MessageDigest digest = digest("SHA-1");
        digest.update(uuidBytes(REQUEST_NAMESPACE));
        byte[] hash = digest.digest(("v1-contact-request:" + legacyRequestId)
                .getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(),
                ByteBuffer.wrap(hash, 8, 8).getLong());
    }

    private static V1ContactRequestImportIssue issue(
            V1ContactRequestRow row, String code, String message) {
        return new V1ContactRequestImportIssue(row.legacyRequestId(), code, message);
    }

    private static String pair(long first, long second) {
        return Math.min(first, second) + ":" + Math.max(first, second);
    }

    private static String fingerprint(
            V1ContactRequestSourceSnapshot source, List<V1ContactRequestRow> rows) {
        MessageDigest digest = digest("SHA-256");
        try (DataOutputStream data = new DataOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            data.writeInt(source.legacyUserIds().size());
            for (long user : source.legacyUserIds().stream().sorted().toList()) data.writeLong(user);
            List<V1ExistingFriendPair> friendships = new ArrayList<>(source.friendships());
            friendships.sort(Comparator.comparingLong(V1ExistingFriendPair::firstUserId)
                    .thenComparingLong(V1ExistingFriendPair::secondUserId));
            data.writeInt(friendships.size());
            for (V1ExistingFriendPair friendship : friendships) {
                data.writeLong(friendship.firstUserId());
                data.writeLong(friendship.secondUserId());
            }
            data.writeInt(rows.size());
            for (V1ContactRequestRow row : rows) {
                data.writeLong(row.legacyRequestId());
                data.writeLong(row.requesterUserId());
                data.writeLong(row.recipientUserId());
                writeNullable(data, row.status());
                writeNullable(data, row.createdAt() == null ? null : row.createdAt().toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory contact request fingerprint failed", exception);
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

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
