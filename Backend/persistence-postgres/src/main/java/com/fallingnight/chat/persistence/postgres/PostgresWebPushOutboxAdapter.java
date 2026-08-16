package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.notification.WebPushNotificationIntent;
import com.fallingnight.chat.application.notification.WebPushOutboxClaim;
import com.fallingnight.chat.application.notification.WebPushOutboxPort;
import com.fallingnight.chat.application.notification.WebPushOutboxStatus;
import com.fallingnight.chat.application.notification.WebPushOutboxStatusPort;
import com.fallingnight.chat.application.notification.WebPushTerminalOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** PostgreSQL SKIP LOCKED claims and fenced terminal/retry/retention transitions. */
public final class PostgresWebPushOutboxAdapter
        implements WebPushOutboxPort, WebPushOutboxStatusPort {
    static final int MAX_BATCH_SIZE = 100;
    static final int MAX_RETENTION_BATCH_SIZE = 1_000;
    static final Duration MIN_LEASE = Duration.ofSeconds(1);
    static final Duration MAX_LEASE = Duration.ofMinutes(5);

    private final DataSource dataSource;
    private final Supplier<UUID> uuidSupplier;

    public PostgresWebPushOutboxAdapter(DataSource dataSource) {
        this(dataSource, UUID::randomUUID);
    }

    PostgresWebPushOutboxAdapter(DataSource dataSource, Supplier<UUID> uuidSupplier) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
    }

    @Override
    public List<WebPushOutboxClaim> claim(
            UUID owner, Instant claimedAt, Duration lease, int limit) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1 || limit > MAX_BATCH_SIZE || lease.compareTo(MIN_LEASE) < 0
                || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("invalid Web Push outbox claim query");
        }
        Instant requestedExpiry;
        try {
            requestedExpiry = claimedAt.plus(lease);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Web Push outbox lease overflows", exception);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<WebPushNotificationIntent> candidates = lockCandidates(
                        connection, claimedAt, limit);
                List<WebPushOutboxClaim> claims = new ArrayList<>(candidates.size());
                for (WebPushNotificationIntent intent : candidates) {
                    UUID claimId = Objects.requireNonNull(uuidSupplier.get(), "claimId");
                    Instant expiresAt = requestedExpiry.isAfter(intent.expiresAt())
                            ? intent.expiresAt() : requestedExpiry;
                    int attempt = claimCandidate(
                            connection, intent.messageId(), owner, claimId, expiresAt);
                    claims.add(new WebPushOutboxClaim(
                            intent, claimId, owner, claimedAt, expiresAt, attempt));
                }
                connection.commit();
                return List.copyOf(claims);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new NotificationPersistenceException("Web Push outbox claim failed", exception);
        }
    }

    @Override
    public boolean complete(
            WebPushOutboxClaim claim, Instant completedAt, WebPushTerminalOutcome outcome) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(outcome, "outcome");
        requireWithinLease(claim, completedAt, "completion");
        if (outcome == WebPushTerminalOutcome.EXPIRED
                && completedAt.isBefore(claim.intent().expiresAt())) {
            throw new IllegalArgumentException("Web Push event has not expired");
        }
        String sql = """
                UPDATE chat.web_push_notification_outbox
                SET completed_at = ?, terminal_outcome = ?, claim_owner = NULL,
                    claim_id = NULL, claim_expires_at = NULL, last_failure_code = NULL
                WHERE message_id = ? AND claim_owner = ? AND claim_id = ?
                  AND claim_expires_at = ? AND completed_at IS NULL
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, at(completedAt));
            statement.setString(2, outcome.name());
            bindFence(statement, 3, claim);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new NotificationPersistenceException(
                    "Web Push outbox completion failed", exception);
        }
    }

    @Override
    public boolean defer(
            WebPushOutboxClaim claim, Instant failedAt, Instant retryAt, String failureCode) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(retryAt, "retryAt");
        Objects.requireNonNull(failureCode, "failureCode");
        requireWithinLease(claim, failedAt, "failure");
        if (retryAt.isBefore(failedAt) || !retryAt.isBefore(claim.intent().expiresAt())
                || !failureCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("invalid Web Push outbox retry");
        }
        String sql = """
                UPDATE chat.web_push_notification_outbox
                SET available_at = ?, claim_owner = NULL, claim_id = NULL,
                    claim_expires_at = NULL, last_failure_code = ?
                WHERE message_id = ? AND claim_owner = ? AND claim_id = ?
                  AND claim_expires_at = ? AND completed_at IS NULL
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, at(retryAt));
            statement.setString(2, failureCode);
            bindFence(statement, 3, claim);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new NotificationPersistenceException("Web Push outbox defer failed", exception);
        }
    }

    @Override
    public int expire(Instant observedAt, int limit) {
        Objects.requireNonNull(observedAt, "observedAt");
        requireBatch(limit, MAX_BATCH_SIZE);
        String sql = """
                WITH candidates AS (
                    SELECT message_id FROM chat.web_push_notification_outbox
                    WHERE completed_at IS NULL AND expires_at <= ?
                    ORDER BY expires_at, message_id
                    FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE chat.web_push_notification_outbox event
                SET completed_at = ?, terminal_outcome = 'EXPIRED',
                    claim_owner = NULL, claim_id = NULL, claim_expires_at = NULL,
                    last_failure_code = NULL
                FROM candidates WHERE event.message_id = candidates.message_id
                """;
        return boundedMutation(sql, observedAt, observedAt, limit, "expiry");
    }

    @Override
    public int purgeCompletedBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        requireBatch(limit, MAX_RETENTION_BATCH_SIZE);
        String sql = """
                WITH candidates AS (
                    SELECT message_id FROM chat.web_push_notification_outbox
                    WHERE completed_at IS NOT NULL AND completed_at < ?
                    ORDER BY completed_at, message_id
                    FOR UPDATE SKIP LOCKED LIMIT ?
                )
                DELETE FROM chat.web_push_notification_outbox event
                USING candidates WHERE event.message_id = candidates.message_id
                """;
        return boundedMutation(sql, cutoff, null, limit, "retention");
    }

    @Override
    public WebPushOutboxStatus readStatus(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        String sql = """
                WITH pending AS MATERIALIZED (
                    SELECT committed_at, expires_at, available_at,
                           claim_owner, claim_expires_at, attempt_count
                    FROM chat.web_push_notification_outbox
                    WHERE completed_at IS NULL
                )
                SELECT count(*),
                       count(*) FILTER (WHERE expires_at > ? AND available_at <= ?
                           AND (claim_owner IS NULL OR claim_expires_at <= ?)),
                       count(*) FILTER (WHERE expires_at > ? AND claim_owner IS NOT NULL
                           AND claim_expires_at > ?),
                       count(*) FILTER (WHERE expires_at > ? AND available_at > ?
                           AND (claim_owner IS NULL OR claim_expires_at <= ?)),
                       count(*) FILTER (WHERE expires_at <= ?),
                       count(*) FILTER (WHERE attempt_count > 1),
                       COALESCE(max(attempt_count), 0), min(committed_at)
                FROM pending
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            OffsetDateTime at = at(observedAt);
            for (int index = 1; index <= 9; index++) statement.setObject(index, at);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Web Push outbox status missing");
                OffsetDateTime oldest = result.getObject(8, OffsetDateTime.class);
                return new WebPushOutboxStatus(
                        result.getLong(1), result.getLong(2), result.getLong(3),
                        result.getLong(4), result.getLong(5), result.getLong(6),
                        result.getInt(7),
                        Optional.ofNullable(oldest).map(OffsetDateTime::toInstant));
            }
        } catch (SQLException exception) {
            throw new NotificationPersistenceException(
                    "Web Push outbox status failed", exception);
        }
    }

    private int boundedMutation(
            String sql, Instant selector, Instant mutationAt, int limit, String action) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, at(selector));
            statement.setInt(2, limit);
            if (mutationAt != null) statement.setObject(3, at(mutationAt));
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new NotificationPersistenceException(
                    "Web Push outbox " + action + " failed", exception);
        }
    }

    private static List<WebPushNotificationIntent> lockCandidates(
            Connection connection, Instant claimedAt, int limit) throws SQLException {
        String sql = """
                SELECT message_id, conversation_id, sender_account_id,
                       committed_at, expires_at, mentioned_account_ids
                FROM chat.web_push_notification_outbox
                WHERE completed_at IS NULL AND available_at <= ? AND expires_at > ?
                  AND (claim_owner IS NULL OR claim_expires_at <= ?)
                ORDER BY available_at, message_id
                FOR UPDATE SKIP LOCKED LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, at(claimedAt));
            statement.setObject(2, at(claimedAt));
            statement.setObject(3, at(claimedAt));
            statement.setInt(4, limit);
            List<WebPushNotificationIntent> candidates = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    candidates.add(new WebPushNotificationIntent(
                            result.getObject(1, UUID.class),
                            result.getObject(2, UUID.class),
                            result.getObject(3, UUID.class),
                            result.getObject(4, OffsetDateTime.class).toInstant(),
                            result.getObject(5, OffsetDateTime.class).toInstant(),
                            readUuidSet(result.getArray(6))));
                }
            }
            return candidates;
        }
    }

    private static Set<UUID> readUuidSet(java.sql.Array array) throws SQLException {
        try {
            return Set.copyOf(new LinkedHashSet<>(Arrays.asList((UUID[]) array.getArray())));
        } finally {
            array.free();
        }
    }

    private static int claimCandidate(
            Connection connection,
            UUID messageId,
            UUID owner,
            UUID claimId,
            Instant expiresAt) throws SQLException {
        String sql = """
                UPDATE chat.web_push_notification_outbox
                SET claim_owner = ?, claim_id = ?, claim_expires_at = ?,
                    attempt_count = attempt_count + 1
                WHERE message_id = ? AND completed_at IS NULL
                RETURNING attempt_count
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, owner);
            statement.setObject(2, claimId);
            statement.setObject(3, at(expiresAt));
            statement.setObject(4, messageId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Web Push candidate disappeared");
                return result.getInt(1);
            }
        }
    }

    private static void bindFence(
            PreparedStatement statement, int first, WebPushOutboxClaim claim)
            throws SQLException {
        statement.setObject(first, claim.intent().messageId());
        statement.setObject(first + 1, claim.claimOwner());
        statement.setObject(first + 2, claim.claimId());
        statement.setObject(first + 3, at(claim.claimExpiresAt()));
    }

    private static void requireWithinLease(
            WebPushOutboxClaim claim, Instant at, String action) {
        if (at.isBefore(claim.claimedAt()) || at.isAfter(claim.claimExpiresAt())) {
            throw new IllegalArgumentException(action + " is outside Web Push claim lease");
        }
    }

    private static void requireBatch(int limit, int maximum) {
        if (limit < 1 || limit > maximum) {
            throw new IllegalArgumentException("invalid Web Push outbox batch size");
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
