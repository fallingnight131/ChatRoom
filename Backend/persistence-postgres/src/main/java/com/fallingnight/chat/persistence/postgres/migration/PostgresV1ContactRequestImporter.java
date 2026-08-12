package com.fallingnight.chat.persistence.postgres.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Strict one-way V1 pending contact-request importer with atomic reconciliation. */
public final class PostgresV1ContactRequestImporter {
    private final DataSource dataSource;

    public PostgresV1ContactRequestImporter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public V1ContactRequestImportReport preview(V1ContactRequestImportPlan plan) {
        requireReady(plan);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                Comparison comparison = compare(connection, plan);
                connection.commit();
                return comparison.report(plan, false, null);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new V1ContactRequestImportException(
                    "V1 contact request target preview failed", exception);
        }
    }

    public V1ContactRequestImportReport apply(VerifiedV1ContactRequestImportInput input) {
        Objects.requireNonNull(input, "input");
        V1ContactRequestImportPlan plan = input.plan();
        requireReady(plan);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                lockTarget(connection);
                Comparison before = compare(connection, plan);
                if (!before.ready()) {
                    throw new V1ContactRequestImportException(
                            "V1 contact request target contains blocking conflicts");
                }
                insertRequests(connection, before.requestsToInsert());
                insertMappings(connection, before.mappingsToInsert());
                Comparison after = compare(connection, plan);
                if (!after.fullyReconciled(plan)) {
                    throw new V1ContactRequestImportException(
                            "V1 contact request post-write reconciliation failed");
                }
                input.reverify();
                UUID runId = UUID.randomUUID();
                persistProof(connection, runId, plan, input.backupProof(), before);
                connection.commit();
                return new V1ContactRequestImportReport(
                        plan.sourceFingerprint(),
                        plan.sourceRows(),
                        plan.sourcePendingRows(),
                        plan.sourceTerminalRows(),
                        before.requestsToInsert().size(),
                        before.alreadyRequests(),
                        List.of(),
                        true,
                        true,
                        runId);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new V1ContactRequestImportException(
                    "V1 contact request target apply failed", exception);
        }
    }

    private static Comparison compare(Connection connection, V1ContactRequestImportPlan plan)
            throws SQLException {
        Map<UUID, List<TargetRequest>> requests = readRequests(connection, plan.pendingRequests());
        Map<UUID, List<TargetMapping>> mappings = readMappings(connection, plan.pendingRequests());
        Set<UUID> accounts = readAccounts(connection, plan.pendingRequests());
        List<PlannedV1ContactRequest> requestsToInsert = new ArrayList<>();
        List<PlannedV1ContactRequest> mappingsToInsert = new ArrayList<>();
        List<V1ContactRequestImportIssue> issues = new ArrayList<>();
        int already = 0;

        for (PlannedV1ContactRequest planned : plan.pendingRequests()) {
            if (!accounts.contains(planned.requesterAccountId())
                    || !accounts.contains(planned.recipientAccountId())) {
                issues.add(issue(planned, "TARGET_ACCOUNT_MISSING",
                        "planned contact request account is absent or disabled"));
                continue;
            }
            List<TargetRequest> targetRequests = requests.getOrDefault(
                    planned.requestId(), List.of());
            List<TargetMapping> targetMappings = mappings.getOrDefault(
                    planned.requestId(), List.of());
            boolean requestMatches = targetRequests.size() == 1
                    && targetRequests.getFirst().matches(planned);
            boolean mappingMatches = targetMappings.size() == 1
                    && targetMappings.getFirst().matches(planned);
            if (targetRequests.isEmpty() && targetMappings.isEmpty()) {
                requestsToInsert.add(planned);
                mappingsToInsert.add(planned);
            } else if (requestMatches && targetMappings.isEmpty()) {
                mappingsToInsert.add(planned);
                already++;
            } else if (requestMatches && mappingMatches) {
                already++;
            } else {
                if (!requestMatches) {
                    issues.add(issue(planned, "TARGET_CONTACT_REQUEST_CONFLICT",
                            "target contact request differs from plan"));
                }
                if (!targetMappings.isEmpty() && !mappingMatches) {
                    issues.add(issue(planned, "TARGET_CONTACT_REQUEST_MAPPING_CONFLICT",
                            "target V1 contact request mapping differs from plan"));
                }
            }
        }
        return new Comparison(
                List.copyOf(requestsToInsert),
                List.copyOf(mappingsToInsert),
                already,
                List.copyOf(issues));
    }

    private static Map<UUID, List<TargetRequest>> readRequests(
            Connection connection, List<PlannedV1ContactRequest> planned) throws SQLException {
        Map<UUID, List<TargetRequest>> result = new HashMap<>();
        if (planned.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, requester_account_id, recipient_account_id, state,
                       created_at, resolved_at
                FROM chat.contact_request
                WHERE id = ANY (?)
                   OR (state = 'PENDING' AND (
                       (requester_account_id, recipient_account_id) IN (
                           SELECT * FROM unnest(?::uuid[], ?::uuid[]))
                       OR (requester_account_id, recipient_account_id) IN (
                           SELECT * FROM unnest(?::uuid[], ?::uuid[]))))
                """)) {
            statement.setArray(1, connection.createArrayOf("uuid", planned.stream()
                    .map(PlannedV1ContactRequest::requestId).toArray()));
            statement.setArray(2, connection.createArrayOf("uuid", planned.stream()
                    .map(PlannedV1ContactRequest::requesterAccountId).toArray()));
            statement.setArray(3, connection.createArrayOf("uuid", planned.stream()
                    .map(PlannedV1ContactRequest::recipientAccountId).toArray()));
            statement.setArray(4, connection.createArrayOf("uuid", planned.stream()
                    .map(PlannedV1ContactRequest::recipientAccountId).toArray()));
            statement.setArray(5, connection.createArrayOf("uuid", planned.stream()
                    .map(PlannedV1ContactRequest::requesterAccountId).toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    OffsetDateTime resolved = rows.getObject("resolved_at", OffsetDateTime.class);
                    TargetRequest target = new TargetRequest(
                            rows.getObject("id", UUID.class),
                            rows.getObject("requester_account_id", UUID.class),
                            rows.getObject("recipient_account_id", UUID.class),
                            rows.getString("state"),
                            rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                            resolved == null ? null : resolved.toInstant());
                    for (PlannedV1ContactRequest value : planned) {
                        if (target.id().equals(value.requestId()) || target.samePair(value)) {
                            result.computeIfAbsent(value.requestId(), ignored -> new ArrayList<>())
                                    .add(target);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Map<UUID, List<TargetMapping>> readMappings(
            Connection connection, List<PlannedV1ContactRequest> planned) throws SQLException {
        Map<UUID, List<TargetMapping>> result = new HashMap<>();
        if (planned.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_request_id, contact_request_id
                FROM chat.legacy_v1_contact_request_map
                WHERE legacy_request_id = ANY (?) OR contact_request_id = ANY (?)
                """)) {
            statement.setArray(1, connection.createArrayOf("bigint", planned.stream()
                    .map(PlannedV1ContactRequest::legacyRequestId).toArray()));
            statement.setArray(2, connection.createArrayOf("uuid", planned.stream()
                    .map(PlannedV1ContactRequest::requestId).toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    TargetMapping target = new TargetMapping(
                            rows.getLong("legacy_request_id"),
                            rows.getObject("contact_request_id", UUID.class));
                    for (PlannedV1ContactRequest value : planned) {
                        if (target.legacyRequestId() == value.legacyRequestId()
                                || target.requestId().equals(value.requestId())) {
                            result.computeIfAbsent(value.requestId(), ignored -> new ArrayList<>())
                                    .add(target);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Set<UUID> readAccounts(
            Connection connection, List<PlannedV1ContactRequest> planned) throws SQLException {
        Set<UUID> requested = new HashSet<>();
        for (PlannedV1ContactRequest value : planned) {
            requested.add(value.requesterAccountId());
            requested.add(value.recipientAccountId());
        }
        Set<UUID> result = new HashSet<>();
        if (requested.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM chat.account WHERE id = ANY (?) AND disabled_at IS NULL")) {
            statement.setArray(1, connection.createArrayOf("uuid", requested.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getObject(1, UUID.class));
            }
        }
        return result;
    }

    private static void insertRequests(
            Connection connection, List<PlannedV1ContactRequest> planned) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.contact_request(
                    id, requester_account_id, recipient_account_id, state, created_at)
                VALUES (?, ?, ?, 'PENDING', ?)
                """)) {
            for (PlannedV1ContactRequest value : planned) {
                statement.setObject(1, value.requestId());
                statement.setObject(2, value.requesterAccountId());
                statement.setObject(3, value.recipientAccountId());
                statement.setObject(4, OffsetDateTime.ofInstant(value.createdAt(), ZoneOffset.UTC));
                statement.addBatch();
            }
            requireBatch(statement.executeBatch(), "contact request");
        }
    }

    private static void insertMappings(
            Connection connection, List<PlannedV1ContactRequest> planned) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_contact_request_map(
                    legacy_request_id, contact_request_id) VALUES (?, ?)
                """)) {
            for (PlannedV1ContactRequest value : planned) {
                statement.setLong(1, value.legacyRequestId());
                statement.setObject(2, value.requestId());
                statement.addBatch();
            }
            requireBatch(statement.executeBatch(), "contact request mapping");
        }
    }

    private static void persistProof(
            Connection connection,
            UUID runId,
            V1ContactRequestImportPlan plan,
            VerifiedV1IdentityBackup proof,
            Comparison before) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.contact_request_import_run(
                    id, source_fingerprint_sha256, backup_file_sha256,
                    source_requests, source_pending_requests, source_terminal_requests,
                    inserted_pending_requests, already_imported_pending_requests,
                    backup_bytes, backup_created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, runId);
            statement.setString(2, plan.sourceFingerprint());
            statement.setString(3, proof.backupFileSha256());
            statement.setInt(4, plan.sourceRows());
            statement.setInt(5, plan.sourcePendingRows());
            statement.setInt(6, plan.sourceTerminalRows());
            statement.setInt(7, before.requestsToInsert().size());
            statement.setInt(8, before.alreadyRequests());
            statement.setLong(9, proof.backupBytes());
            statement.setObject(10, OffsetDateTime.ofInstant(proof.createdAt(), ZoneOffset.UTC));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("contact request import proof was not persisted");
            }
        }
    }

    private static void lockTarget(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "LOCK TABLE chat.account, chat.contact_request, "
                        + "chat.legacy_v1_contact_request_map, "
                        + "chat.contact_request_import_run IN SHARE ROW EXCLUSIVE MODE")) {
            statement.execute();
        }
    }

    private static void requireBatch(int[] counts, String name) throws SQLException {
        for (int count : counts) {
            if (count != 1) {
                throw new SQLException(name + " insert did not affect exactly one row");
            }
        }
    }

    private static V1ContactRequestImportIssue issue(
            PlannedV1ContactRequest planned, String code, String message) {
        return new V1ContactRequestImportIssue(planned.legacyRequestId(), code, message);
    }

    private static void requireReady(V1ContactRequestImportPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.readyToCompareWithTarget()) {
            throw new V1ContactRequestImportException(
                    "V1 contact request plan is not ready for target comparison");
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }

    private record Comparison(
            List<PlannedV1ContactRequest> requestsToInsert,
            List<PlannedV1ContactRequest> mappingsToInsert,
            int alreadyRequests,
            List<V1ContactRequestImportIssue> issues) {
        boolean ready() {
            return issues.isEmpty();
        }

        boolean fullyReconciled(V1ContactRequestImportPlan plan) {
            return ready()
                    && requestsToInsert.isEmpty()
                    && mappingsToInsert.isEmpty()
                    && alreadyRequests == plan.pendingRequests().size();
        }

        V1ContactRequestImportReport report(
                V1ContactRequestImportPlan plan, boolean applied, UUID runId) {
            return new V1ContactRequestImportReport(
                    plan.sourceFingerprint(),
                    plan.sourceRows(),
                    plan.sourcePendingRows(),
                    plan.sourceTerminalRows(),
                    requestsToInsert.size(),
                    alreadyRequests,
                    issues,
                    applied,
                    applied,
                    runId);
        }
    }

    private record TargetRequest(
            UUID id,
            UUID requester,
            UUID recipient,
            String state,
            java.time.Instant createdAt,
            java.time.Instant resolvedAt) {
        boolean samePair(PlannedV1ContactRequest value) {
            return (requester.equals(value.requesterAccountId())
                            && recipient.equals(value.recipientAccountId()))
                    || (requester.equals(value.recipientAccountId())
                            && recipient.equals(value.requesterAccountId()));
        }

        boolean matches(PlannedV1ContactRequest value) {
            return id.equals(value.requestId())
                    && requester.equals(value.requesterAccountId())
                    && recipient.equals(value.recipientAccountId())
                    && state.equals("PENDING")
                    && createdAt.equals(value.createdAt())
                    && resolvedAt == null;
        }
    }

    private record TargetMapping(long legacyRequestId, UUID requestId) {
        boolean matches(PlannedV1ContactRequest value) {
            return legacyRequestId == value.legacyRequestId()
                    && requestId.equals(value.requestId());
        }
    }
}
