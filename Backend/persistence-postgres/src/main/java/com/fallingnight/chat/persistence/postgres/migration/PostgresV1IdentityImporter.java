package com.fallingnight.chat.persistence.postgres.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Strict, one-way V1 account importer with preview, atomic apply, and reconciliation. */
public final class PostgresV1IdentityImporter {
    private static final String TARGET_READ = """
            SELECT id, username_key, display_name, password_hash, password_scheme,
                   legacy_password_salt, created_at, disabled_at
            FROM chat.account
            """;
    private final DataSource dataSource;

    public PostgresV1IdentityImporter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public V1IdentityImportReport preview(V1IdentityImportPlan plan) {
        requireReadyPlan(plan);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                Comparison comparison = compare(connection, plan);
                connection.commit();
                return comparison.preview(plan);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new V1IdentityImportException(
                    "V1 identity target preview failed", exception);
        }
    }

    public V1IdentityImportReport apply(VerifiedV1IdentityImportInput input) {
        Objects.requireNonNull(input, "input");
        V1IdentityImportPlan plan = input.plan();
        requireReadyPlan(plan);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                lockTarget(connection);
                Comparison before = compare(connection, plan);
                if (!before.ready()) {
                    throw new V1IdentityImportException(
                            "V1 identity target contains blocking conflicts");
                }
                int inserted = insertMissing(connection, before.insertableAccounts());
                Comparison after = compare(connection, plan);
                if (!after.fullyReconciled(plan.sourceRows())) {
                    throw new V1IdentityImportException(
                            "V1 identity post-write reconciliation failed");
                }
                input.reverify();
                UUID runId = UUID.randomUUID();
                persistProof(connection, runId, input.backupProof(), inserted,
                        before.alreadyImportedRows());
                connection.commit();
                return new V1IdentityImportReport(
                        plan.sourceFingerprintSha256(),
                        plan.sourceRows(),
                        before.insertableAccounts().size(),
                        before.alreadyImportedRows(),
                        inserted,
                        0,
                        List.of(),
                        true,
                        true,
                        runId);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new V1IdentityImportException(
                    "V1 identity target apply failed", exception);
        }
    }

    private static Comparison compare(Connection connection, V1IdentityImportPlan plan)
            throws SQLException {
        Map<UUID, TargetAccount> byId = new HashMap<>();
        Map<String, TargetAccount> byUsername = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(TARGET_READ);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                TargetAccount account = targetAccount(result);
                byId.put(account.id(), account);
                byUsername.put(account.usernameKey(), account);
            }
        }

        List<PlannedIdentityAccount> insertable = new ArrayList<>();
        List<IdentityImportIssue> issues = new ArrayList<>();
        Set<UUID> plannedIds = new HashSet<>();
        int alreadyImported = 0;
        for (PlannedIdentityAccount planned : plan.accounts()) {
            plannedIds.add(planned.accountId());
            TargetAccount idMatch = byId.get(planned.accountId());
            TargetAccount usernameMatch = byUsername.get(planned.usernameKey());
            if (idMatch == null && usernameMatch == null) {
                insertable.add(planned);
            } else if (idMatch != null
                    && idMatch == usernameMatch
                    && idMatch.matches(planned)) {
                alreadyImported++;
            } else {
                issues.add(new IdentityImportIssue(
                        planned.legacyId(),
                        "TARGET_ACCOUNT_CONFLICT",
                        "target account does not exactly match the planned V1 identity"));
            }
        }
        int unexpected = (int) byId.keySet().stream()
                .filter(id -> !plannedIds.contains(id))
                .count();
        return new Comparison(
                List.copyOf(insertable), alreadyImported, unexpected, List.copyOf(issues));
    }

    private static int insertMissing(
            Connection connection,
            List<PlannedIdentityAccount> accounts) throws SQLException {
        int inserted = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.account(
                    id, username_key, display_name, password_hash, created_at,
                    password_scheme, legacy_password_salt)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (PlannedIdentityAccount account : accounts) {
                statement.setObject(1, account.accountId());
                statement.setString(2, account.usernameKey());
                statement.setString(3, account.displayName());
                statement.setString(4, account.passwordHash());
                statement.setObject(5, OffsetDateTime.ofInstant(
                        account.createdAt(), java.time.ZoneOffset.UTC));
                statement.setString(6, account.credentialScheme().name());
                statement.setString(7, account.legacyPasswordSalt());
                statement.addBatch();
            }
            for (int count : statement.executeBatch()) {
                if (count != 1) {
                    throw new SQLException("identity insert did not affect exactly one row");
                }
                inserted++;
            }
        }
        return inserted;
    }

    private static void persistProof(
            Connection connection,
            UUID runId,
            VerifiedV1IdentityBackup proof,
            int inserted,
            int alreadyImported) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.identity_import_run(
                    id, source_fingerprint_sha256, backup_file_sha256, source_rows,
                    inserted_rows, already_imported_rows, backup_bytes, backup_created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, runId);
            statement.setString(2, proof.sourceFingerprintSha256());
            statement.setString(3, proof.backupFileSha256());
            statement.setInt(4, proof.identityRows());
            statement.setInt(5, inserted);
            statement.setInt(6, alreadyImported);
            statement.setLong(7, proof.backupBytes());
            statement.setObject(8, OffsetDateTime.ofInstant(
                    proof.createdAt(), java.time.ZoneOffset.UTC));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("identity import proof was not persisted");
            }
        }
    }

    private static TargetAccount targetAccount(ResultSet result) throws SQLException {
        OffsetDateTime disabled = result.getObject("disabled_at", OffsetDateTime.class);
        return new TargetAccount(
                result.getObject("id", UUID.class),
                result.getString("username_key"),
                result.getString("display_name"),
                result.getString("password_hash"),
                ImportedCredentialScheme.valueOf(result.getString("password_scheme")),
                result.getString("legacy_password_salt"),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                disabled != null);
    }

    private static void lockTarget(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "LOCK TABLE chat.account IN SHARE ROW EXCLUSIVE MODE")) {
            statement.execute();
        }
    }

    private static void requireReadyPlan(V1IdentityImportPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.readyToCompareWithTarget()) {
            throw new V1IdentityImportException(
                    "V1 identity plan is not ready for target comparison");
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record Comparison(
            List<PlannedIdentityAccount> insertableAccounts,
            int alreadyImportedRows,
            int unexpectedTargetRows,
            List<IdentityImportIssue> issues) {
        boolean ready() {
            return unexpectedTargetRows == 0 && issues.isEmpty();
        }

        boolean fullyReconciled(int sourceRows) {
            return ready()
                    && insertableAccounts.isEmpty()
                    && alreadyImportedRows == sourceRows;
        }

        V1IdentityImportReport preview(V1IdentityImportPlan plan) {
            return new V1IdentityImportReport(
                    plan.sourceFingerprintSha256(),
                    plan.sourceRows(),
                    insertableAccounts.size(),
                    alreadyImportedRows,
                    0,
                    unexpectedTargetRows,
                    issues,
                    false,
                    false,
                    null);
        }
    }

    private record TargetAccount(
            UUID id,
            String usernameKey,
            String displayName,
            String passwordHash,
            ImportedCredentialScheme credentialScheme,
            String legacyPasswordSalt,
            java.time.Instant createdAt,
            boolean disabled) {
        boolean matches(PlannedIdentityAccount planned) {
            return id.equals(planned.accountId())
                    && usernameKey.equals(planned.usernameKey())
                    && displayName.equals(planned.displayName())
                    && passwordHash.equals(planned.passwordHash())
                    && credentialScheme == planned.credentialScheme()
                    && Objects.equals(legacyPasswordSalt, planned.legacyPasswordSalt())
                    && createdAt.equals(planned.createdAt())
                    && !disabled;
        }
    }
}
