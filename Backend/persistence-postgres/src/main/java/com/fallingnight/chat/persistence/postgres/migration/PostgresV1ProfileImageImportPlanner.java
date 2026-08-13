package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Read-only target gate that must pass before historical object uploads. */
public final class PostgresV1ProfileImageImportPlanner {
    private static final int OBJECT_BATCH = 10_000;
    private final DataSource dataSource;

    public PostgresV1ProfileImageImportPlanner(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public V1ProfileImageImportPreview preview(V1ProfileImageImportPlan plan) {
        Objects.requireNonNull(plan, "plan");
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try {
                Map<Long, Target> accounts = accountTargets(connection);
                Map<Long, Target> rooms = roomTargets(connection);
                List<V1ProfileImageImportIssue> issues = new ArrayList<>();
                for (V1ProfileImageImportEntry entry : plan.entries()) {
                    Target target = (entry.kind() == V1ProfileImageImportEntry.Kind.ACCOUNT
                            ? accounts : rooms).get(entry.legacyId());
                    if (target == null) {
                        issues.add(issue(entry, "TARGET_MAPPING_MISSING"));
                    } else if (!target.available()) {
                        issues.add(issue(entry, "TARGET_UNAVAILABLE"));
                    } else if (target.objectKey() != null) {
                        issues.add(issue(entry, "TARGET_POINTER_EXISTS"));
                    }
                }
                Map<String, ProfileImageObjectEvidence> expected = uniqueObjects(plan);
                Map<String, StoredObject> stored = storedObjects(connection, expected.keySet());
                int registered = 0;
                for (ProfileImageObjectEvidence object : expected.values()) {
                    StoredObject current = stored.get(object.objectKey());
                    if (current == null) continue;
                    if (!current.matches(object)) {
                        issues.add(new V1ProfileImageImportIssue(
                                firstKind(plan, object.objectKey()),
                                firstLegacyId(plan, object.objectKey()),
                                "OBJECT_EVIDENCE_CONFLICT"));
                    } else if (current.activelyDeleting()) {
                        issues.add(new V1ProfileImageImportIssue(
                                firstKind(plan, object.objectKey()),
                                firstLegacyId(plan, object.objectKey()),
                                "OBJECT_DELETE_CLAIM_ACTIVE"));
                    } else registered++;
                }
                if (existingRun(connection, plan.manifestSha256()))
                    issues.add(new V1ProfileImageImportIssue(
                            plan.entries().getFirst().kind(),
                            plan.entries().getFirst().legacyId(),
                            "MANIFEST_ALREADY_IMPORTED"));
                connection.commit();
                int present = plan.presentEntries();
                return new V1ProfileImageImportPreview(plan.manifestSha256(),
                        plan.entries().size(), present, plan.entries().size() - present,
                        plan.uniqueObjects(), registered, plan.uniqueObjects(),
                        List.copyOf(issues));
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        } catch (SQLException exception) {
            throw new V1ProfileImageImportException(
                    "V1 profile image target preview failed", exception);
        }
    }

    private static Map<Long, Target> accountTargets(Connection connection) throws SQLException {
        Map<Long, Target> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT mapping.legacy_user_id, account.disabled_at,
                       current.object_key
                FROM chat.legacy_v1_account_map mapping
                JOIN chat.account account ON account.id = mapping.account_id
                LEFT JOIN chat.account_profile_image current
                  ON current.account_id = account.id
                """); ResultSet row = statement.executeQuery()) {
            while (row.next()) putUnique(result, row.getLong(1),
                    new Target(row.getObject(2) == null, row.getString(3)));
        }
        return result;
    }

    private static Map<Long, Target> roomTargets(Connection connection) throws SQLException {
        Map<Long, Target> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT mapping.legacy_conversation_id,
                       lifecycle.conversation_id, lifecycle.closed_at, current.object_key
                FROM chat.legacy_v1_conversation_map mapping
                JOIN chat.conversation conversation
                  ON conversation.id = mapping.conversation_id
                 AND conversation.kind = 'GROUP'
                LEFT JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                LEFT JOIN chat.group_profile_image current
                  ON current.conversation_id = conversation.id
                WHERE mapping.legacy_kind = 'ROOM'
                """); ResultSet row = statement.executeQuery()) {
            while (row.next()) putUnique(result, row.getLong(1),
                    new Target(row.getObject(2) != null && row.getObject(3) == null,
                            row.getString(4)));
        }
        return result;
    }

    private static <T> void putUnique(Map<Long, T> targets, long id, T target)
            throws SQLException {
        if (targets.put(id, target) != null)
            throw new SQLException("V1 profile image target mapping duplicated");
    }

    private static Map<String, ProfileImageObjectEvidence> uniqueObjects(
            V1ProfileImageImportPlan plan) {
        Map<String, ProfileImageObjectEvidence> result = new LinkedHashMap<>();
        for (V1ProfileImageImportEntry entry : plan.entries()) if (entry.present())
            result.putIfAbsent(entry.object().objectKey(), entry.object());
        return result;
    }

    private static Map<String, StoredObject> storedObjects(Connection connection,
            Set<String> keys) throws SQLException {
        Map<String, StoredObject> result = new HashMap<>();
        List<String> ordered = new ArrayList<>(keys);
        for (int start = 0; start < ordered.size(); start += OBJECT_BATCH) {
            List<String> batch = ordered.subList(start,
                    Math.min(ordered.size(), start + OBJECT_BATCH));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT object_key, byte_size, content_sha256, media_type,
                           delete_claim_id, delete_confirmed_at
                    FROM chat.profile_image_object WHERE object_key IN (
                    """ + placeholders + ")")) {
                for (int index = 0; index < batch.size(); index++)
                    statement.setString(index + 1, batch.get(index));
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) {
                        StoredObject value = new StoredObject(row.getLong(2), row.getBytes(3),
                                row.getString(4), row.getObject(5) != null,
                                row.getObject(6) != null);
                        if (result.put(row.getString(1), value) != null)
                            throw new SQLException("profile image object duplicated");
                    }
                }
            }
        }
        return result;
    }

    private static boolean existingRun(Connection connection, String manifest)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM chat.profile_image_import_run WHERE manifest_sha256 = ?")) {
            statement.setString(1, manifest);
            try (ResultSet row = statement.executeQuery()) {
                boolean found = row.next();
                if (found && row.next()) throw new SQLException("avatar import run duplicated");
                return found;
            }
        }
    }

    private static V1ProfileImageImportIssue issue(
            V1ProfileImageImportEntry entry, String code) {
        return new V1ProfileImageImportIssue(entry.kind(), entry.legacyId(), code);
    }
    private static V1ProfileImageImportEntry.Kind firstKind(
            V1ProfileImageImportPlan plan, String key) {
        return plan.entries().stream().filter(entry -> entry.present()
                && entry.object().objectKey().equals(key)).findFirst().orElseThrow().kind();
    }
    private static long firstLegacyId(V1ProfileImageImportPlan plan, String key) {
        return plan.entries().stream().filter(entry -> entry.present()
                && entry.object().objectKey().equals(key)).findFirst().orElseThrow().legacyId();
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException rollback) { original.addSuppressed(rollback); }
    }
    private record Target(boolean available, String objectKey) { }
    private record StoredObject(long bytes, byte[] digest, String mediaType,
            boolean claimed, boolean deleted) {
        boolean matches(ProfileImageObjectEvidence expected) {
            return bytes == expected.byteSize()
                    && Arrays.equals(digest, expected.contentSha256())
                    && mediaType.equals(expected.mediaType());
        }
        boolean activelyDeleting() { return claimed && !deleted; }
    }
}
