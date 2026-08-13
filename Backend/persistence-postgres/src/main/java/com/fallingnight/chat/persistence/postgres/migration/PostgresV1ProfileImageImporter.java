package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import java.security.MessageDigest;
import java.sql.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;

/** Atomic metadata apply after all immutable Provider objects are exact. */
public final class PostgresV1ProfileImageImporter {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresV1ProfileImageImporter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public V1ProfileImageImportApplyReport apply(
            ProviderVerifiedV1ProfileImageImportInput input) {
        Objects.requireNonNull(input, "input"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(input.plan()); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new V1ProfileImageImportException(
                "V1 profile image target apply failed", last);
    }

    private V1ProfileImageImportApplyReport attempt(V1ProfileImageImportPlan plan)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                lockImportState(connection);
                UUID existing = existingRun(connection, plan);
                if (existing != null) {
                    reconcile(connection, existing, plan);
                    connection.commit();
                    return report(plan, 0, true, existing);
                }
                Resolved resolved = resolveFreshTargets(connection, plan);
                registerObjects(connection, plan);
                int pointers = insertPointers(connection, plan, resolved);
                UUID runId = UUID.randomUUID();
                insertRun(connection, runId, plan);
                insertEntries(connection, runId, plan, resolved);
                reconcile(connection, runId, plan);
                connection.commit();
                return report(plan, pointers, false, runId);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static Resolved resolveFreshTargets(Connection connection,
            V1ProfileImageImportPlan plan) throws SQLException {
        Map<Long, PostgresV1ProfileImageImportPlanner.Target> accounts =
                PostgresV1ProfileImageImportPlanner.accountTargets(connection);
        Map<Long, PostgresV1ProfileImageImportPlanner.Target> rooms =
                PostgresV1ProfileImageImportPlanner.roomTargets(connection);
        Map<TargetKey, UUID> targets = new LinkedHashMap<>();
        for (V1ProfileImageImportEntry entry : plan.entries()) {
            var target = (entry.kind() == V1ProfileImageImportEntry.Kind.ACCOUNT
                    ? accounts : rooms).get(entry.legacyId());
            if (target == null || !target.available() || target.objectKey() != null)
                throw new V1ProfileImageImportException(
                        "V1 profile image target changed after preview");
            targets.put(new TargetKey(entry.kind(), entry.legacyId()), target.id());
        }
        return new Resolved(Map.copyOf(targets));
    }

    private static void registerObjects(Connection connection,
            V1ProfileImageImportPlan plan) throws SQLException {
        Map<String, ProfileImageObjectEvidence> expected =
                PostgresV1ProfileImageImportPlanner.uniqueObjects(plan);
        Map<String, PostgresV1ProfileImageImportPlanner.StoredObject> stored =
                PostgresV1ProfileImageImportPlanner.storedObjects(
                        connection, expected.keySet());
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO chat.profile_image_object
                    (object_key, byte_size, content_sha256, media_type)
                VALUES (?, ?, ?, ?)
                """); PreparedStatement revive = connection.prepareStatement("""
                UPDATE chat.profile_image_object SET cleanup_requested_at = NULL,
                    delete_claim_id = NULL, delete_claimed_at = NULL,
                    delete_confirmed_at = NULL WHERE object_key = ?
                """)) {
            for (ProfileImageObjectEvidence object : expected.values()) {
                var current = stored.get(object.objectKey());
                if (current == null) {
                    insert.setString(1, object.objectKey());
                    insert.setLong(2, object.byteSize());
                    insert.setBytes(3, object.contentSha256());
                    insert.setString(4, object.mediaType());
                    requireOne(insert, "profile image import object registration");
                } else {
                    if (!current.matches(object) || current.activelyDeleting())
                        throw new V1ProfileImageImportException(
                                "profile image import object state conflicts");
                    revive.setString(1, object.objectKey());
                    requireOne(revive, "profile image import object revival");
                }
            }
        }
    }

    private static int insertPointers(Connection connection,
            V1ProfileImageImportPlan plan, Resolved resolved) throws SQLException {
        int inserted = 0;
        try (PreparedStatement account = connection.prepareStatement("""
                INSERT INTO chat.account_profile_image
                    (account_id, object_key, width, height, version, updated_at)
                VALUES (?, ?, ?, ?, 1, ?)
                """); PreparedStatement room = connection.prepareStatement("""
                INSERT INTO chat.group_profile_image
                    (conversation_id, object_key, width, height, version, updated_at)
                VALUES (?, ?, ?, ?, 1, ?)
                """)) {
            for (V1ProfileImageImportEntry entry : plan.entries()) {
                if (!entry.present()) continue;
                PreparedStatement statement = entry.kind()
                        == V1ProfileImageImportEntry.Kind.ACCOUNT ? account : room;
                statement.setObject(1, resolved.id(entry));
                statement.setString(2, entry.object().objectKey());
                statement.setInt(3, entry.width()); statement.setInt(4, entry.height());
                statement.setObject(5, OffsetDateTime.ofInstant(
                        entry.updatedAt(), ZoneOffset.UTC));
                requireOne(statement, "profile image import pointer"); inserted++;
            }
        }
        return inserted;
    }

    private static void insertRun(Connection connection, UUID runId,
            V1ProfileImageImportPlan plan) throws SQLException {
        int present = plan.presentEntries();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.profile_image_import_run
                    (id, manifest_sha256, backup_file_sha256,
                     identity_fingerprint_sha256, source_entries, present_entries,
                     absent_entries, unique_objects)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, runId); statement.setString(2, plan.manifestSha256());
            statement.setString(3, plan.backupFileSha256());
            statement.setString(4, plan.identityFingerprintSha256());
            statement.setInt(5, plan.entries().size()); statement.setInt(6, present);
            statement.setInt(7, plan.entries().size() - present);
            statement.setInt(8, plan.uniqueObjects());
            requireOne(statement, "profile image import run");
        }
    }

    private static void insertEntries(Connection connection, UUID runId,
            V1ProfileImageImportPlan plan, Resolved resolved) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.profile_image_import_entry
                    (import_run_id, target_kind, legacy_target_id,
                     target_account_id, target_conversation_id, object_key,
                     width, height, source_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (V1ProfileImageImportEntry entry : plan.entries()) {
                UUID target = resolved.id(entry);
                statement.setObject(1, runId); statement.setString(2, entry.kind().name());
                statement.setLong(3, entry.legacyId());
                statement.setObject(4, entry.kind() == V1ProfileImageImportEntry.Kind.ACCOUNT
                        ? target : null);
                statement.setObject(5, entry.kind() == V1ProfileImageImportEntry.Kind.ROOM
                        ? target : null);
                statement.setString(6, entry.present() ? entry.object().objectKey() : null);
                statement.setInt(7, entry.width()); statement.setInt(8, entry.height());
                statement.setObject(9, entry.updatedAt() == null ? null
                        : OffsetDateTime.ofInstant(entry.updatedAt(), ZoneOffset.UTC));
                requireOne(statement, "profile image import entry");
            }
        }
    }

    private static UUID existingRun(Connection connection, V1ProfileImageImportPlan plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, backup_file_sha256, identity_fingerprint_sha256,
                       source_entries, present_entries, absent_entries, unique_objects
                FROM chat.profile_image_import_run WHERE manifest_sha256 = ? FOR UPDATE
                """)) {
            statement.setString(1, plan.manifestSha256());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                UUID id = row.getObject(1, UUID.class); int present = plan.presentEntries();
                boolean exact = plan.backupFileSha256().equals(row.getString(2))
                        && plan.identityFingerprintSha256().equals(row.getString(3))
                        && plan.entries().size() == row.getInt(4)
                        && present == row.getInt(5)
                        && plan.entries().size() - present == row.getInt(6)
                        && plan.uniqueObjects() == row.getInt(7);
                if (row.next() || !exact)
                    throw new V1ProfileImageImportException(
                            "existing profile image import proof conflicts");
                return id;
            }
        }
    }

    private static void reconcile(Connection connection, UUID runId,
            V1ProfileImageImportPlan plan) throws SQLException {
        Map<Long, PostgresV1ProfileImageImportPlanner.Target> accounts =
                PostgresV1ProfileImageImportPlanner.accountTargets(connection);
        Map<Long, PostgresV1ProfileImageImportPlanner.Target> rooms =
                PostgresV1ProfileImageImportPlanner.roomTargets(connection);
        Map<TargetKey, AuditEntry> audit = loadAudit(connection, runId);
        if (audit.size() != plan.entries().size())
            throw new V1ProfileImageImportException(
                    "profile image import audit count does not reconcile");
        for (V1ProfileImageImportEntry entry : plan.entries()) {
            var target = (entry.kind() == V1ProfileImageImportEntry.Kind.ACCOUNT
                    ? accounts : rooms).get(entry.legacyId());
            AuditEntry retained = audit.get(new TargetKey(entry.kind(), entry.legacyId()));
            if (target == null || !target.available() || retained == null
                    || !target.id().equals(retained.targetId())
                    || !retained.matches(entry)
                    || entry.present() != (target.objectKey() != null)
                    || entry.present() && !entry.object().objectKey().equals(target.objectKey()))
                throw new V1ProfileImageImportException(
                        "profile image import target does not reconcile");
        }
        Map<String, ProfileImageObjectEvidence> expected =
                PostgresV1ProfileImageImportPlanner.uniqueObjects(plan);
        Map<String, PostgresV1ProfileImageImportPlanner.StoredObject> stored =
                PostgresV1ProfileImageImportPlanner.storedObjects(
                        connection, expected.keySet());
        for (ProfileImageObjectEvidence object : expected.values()) {
            var current = stored.get(object.objectKey());
            if (current == null || !current.matches(object)
                    || current.claimed() || current.deleted())
                throw new V1ProfileImageImportException(
                        "profile image import object does not reconcile");
        }
    }

    private static Map<TargetKey, AuditEntry> loadAudit(Connection connection, UUID runId)
            throws SQLException {
        Map<TargetKey, AuditEntry> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT target_kind, legacy_target_id, target_account_id,
                       target_conversation_id, object_key, width, height, source_updated_at
                FROM chat.profile_image_import_entry WHERE import_run_id = ?
                """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    var kind = V1ProfileImageImportEntry.Kind.valueOf(row.getString(1));
                    TargetKey key = new TargetKey(kind, row.getLong(2));
                    UUID target = row.getObject(kind == V1ProfileImageImportEntry.Kind.ACCOUNT
                            ? 3 : 4, UUID.class);
                    AuditEntry value = new AuditEntry(target, row.getString(5), row.getInt(6),
                            row.getInt(7), row.getObject(8, OffsetDateTime.class));
                    if (result.put(key, value) != null)
                        throw new SQLException("profile image import audit duplicated");
                }
            }
        }
        return result;
    }

    private static void lockImportState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("LOCK TABLE chat.profile_image_import_run, "
                    + "chat.profile_image_import_entry, chat.profile_image_object, "
                    + "chat.account_profile_image, chat.group_profile_image, "
                    + "chat.legacy_v1_account_map, chat.legacy_v1_conversation_map, "
                    + "chat.account, chat.conversation, chat.group_lifecycle "
                    + "IN SHARE ROW EXCLUSIVE MODE");
        }
    }

    private static V1ProfileImageImportApplyReport report(V1ProfileImageImportPlan plan,
            int pointers, boolean retry, UUID runId) {
        int present = plan.presentEntries();
        return new V1ProfileImageImportApplyReport(plan.manifestSha256(),
                plan.entries().size(), present, plan.entries().size() - present,
                plan.uniqueObjects(), pointers, retry, runId);
    }
    private static void requireOne(PreparedStatement statement, String operation)
            throws SQLException {
        if (statement.executeUpdate() != 1) throw new SQLException(operation + " failed");
    }
    private static boolean retryable(SQLException exception) {
        return "40001".equals(exception.getSQLState())
                || "40P01".equals(exception.getSQLState());
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException rollback) { original.addSuppressed(rollback); }
    }
    private record TargetKey(V1ProfileImageImportEntry.Kind kind, long legacyId) { }
    private record Resolved(Map<TargetKey, UUID> targets) {
        UUID id(V1ProfileImageImportEntry entry) {
            return Objects.requireNonNull(targets.get(
                    new TargetKey(entry.kind(), entry.legacyId())), "resolved target");
        }
    }
    private record AuditEntry(UUID targetId, String objectKey, int width, int height,
            OffsetDateTime updatedAt) {
        boolean matches(V1ProfileImageImportEntry entry) {
            if (entry.present()) return entry.object().objectKey().equals(objectKey)
                    && entry.width() == width && entry.height() == height
                    && updatedAt != null && updatedAt.toInstant().equals(entry.updatedAt());
            return objectKey == null && width == 0 && height == 0 && updatedAt == null;
        }
    }
}
