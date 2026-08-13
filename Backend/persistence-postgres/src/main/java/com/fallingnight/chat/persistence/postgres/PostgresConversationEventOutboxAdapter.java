package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.messaging.ConversationEventOutboxClaim;
import com.fallingnight.chat.application.messaging.ConversationEventOutboxPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL fenced leases over payload-free conversation outbox rows. */
public final class PostgresConversationEventOutboxAdapter
        implements ConversationEventOutboxPort {
    static final int MAX_BATCH_SIZE = 100;
    static final Duration MIN_LEASE = Duration.ofSeconds(1);
    static final Duration MAX_LEASE = Duration.ofMinutes(5);

    private final DataSource dataSource;

    public PostgresConversationEventOutboxAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<ConversationEventOutboxClaim> claim(
            UUID owner, Instant claimedAt, Duration lease, int limit) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1 || limit > MAX_BATCH_SIZE || lease.compareTo(MIN_LEASE) < 0
                || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("invalid conversation event outbox claim query");
        }
        Instant expiresAt;
        try {
            expiresAt = claimedAt.plus(lease);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("conversation event outbox lease overflows", exception);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Candidate> candidates = lockCandidates(connection, claimedAt, limit);
                List<ConversationEventOutboxClaim> claims = new ArrayList<>(candidates.size());
                for (Candidate candidate : candidates) {
                    UUID claimId = UUID.randomUUID();
                    int attemptCount = claimCandidate(
                            connection, candidate.eventId(), owner, claimId, expiresAt);
                    claims.add(new ConversationEventOutboxClaim(
                            candidate.eventId(), candidate.conversationId(),
                            candidate.conversationSequence(), claimId, owner,
                            claimedAt, expiresAt, attemptCount));
                }
                connection.commit();
                return List.copyOf(claims);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "conversation event outbox claim failed", exception);
        }
    }

    @Override
    public boolean markPublished(ConversationEventOutboxClaim claim, Instant publishedAt) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(publishedAt, "publishedAt");
        requireWithinLease(claim, publishedAt, "publication");
        String sql = """
                UPDATE chat.conversation_event_outbox
                SET published_at = ?, claim_owner = NULL, claim_id = NULL,
                    claim_expires_at = NULL, last_failure_code = NULL
                WHERE event_id = ? AND claim_owner = ? AND claim_id = ?
                  AND claim_expires_at = ? AND published_at IS NULL
                """;
        return mutateClaim(sql, claim, publishedAt, null);
    }

    @Override
    public boolean defer(ConversationEventOutboxClaim claim, Instant failedAt,
            Instant retryAt, String failureCode) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(retryAt, "retryAt");
        Objects.requireNonNull(failureCode, "failureCode");
        requireWithinLease(claim, failedAt, "failure");
        if (retryAt.isBefore(failedAt) || !failureCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("invalid conversation event outbox retry");
        }
        String sql = """
                UPDATE chat.conversation_event_outbox
                SET available_at = ?, claim_owner = NULL, claim_id = NULL,
                    claim_expires_at = NULL, last_failure_code = ?
                WHERE event_id = ? AND claim_owner = ? AND claim_id = ?
                  AND claim_expires_at = ? AND published_at IS NULL
                """;
        return mutateClaim(sql, claim, retryAt, failureCode);
    }

    private boolean mutateClaim(String sql, ConversationEventOutboxClaim claim,
            Instant timestamp, String failureCode) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setObject(index++, at(timestamp));
            if (failureCode != null) {
                statement.setString(index++, failureCode);
            }
            statement.setObject(index++, claim.eventId());
            statement.setObject(index++, claim.claimOwner());
            statement.setObject(index++, claim.claimId());
            statement.setObject(index, at(claim.claimExpiresAt()));
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "conversation event outbox mutation failed", exception);
        }
    }

    private static List<Candidate> lockCandidates(
            Connection connection, Instant claimedAt, int limit) throws SQLException {
        String sql = """
                SELECT event.event_id, event.conversation_id, event.conversation_sequence
                FROM chat.conversation_event_outbox event
                WHERE event.published_at IS NULL AND event.available_at <= ?
                  AND (event.claim_owner IS NULL OR event.claim_expires_at <= ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM chat.conversation_event_outbox earlier
                      WHERE earlier.conversation_id = event.conversation_id
                        AND earlier.conversation_sequence < event.conversation_sequence
                        AND earlier.published_at IS NULL)
                ORDER BY event.available_at, event.conversation_id,
                         event.conversation_sequence
                FOR UPDATE OF event SKIP LOCKED LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, at(claimedAt));
            statement.setObject(2, at(claimedAt));
            statement.setInt(3, limit);
            List<Candidate> candidates = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    candidates.add(new Candidate(
                            result.getObject(1, UUID.class),
                            result.getObject(2, UUID.class), result.getLong(3)));
                }
            }
            return candidates;
        }
    }

    private static int claimCandidate(Connection connection, UUID eventId,
            UUID owner, UUID claimId, Instant expiresAt) throws SQLException {
        String sql = """
                UPDATE chat.conversation_event_outbox
                SET claim_owner = ?, claim_id = ?, claim_expires_at = ?,
                    attempt_count = attempt_count + 1
                WHERE event_id = ? AND published_at IS NULL
                RETURNING attempt_count
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, owner);
            statement.setObject(2, claimId);
            statement.setObject(3, at(expiresAt));
            statement.setObject(4, eventId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("conversation event outbox candidate disappeared");
                }
                return result.getInt(1);
            }
        }
    }

    private static void requireWithinLease(
            ConversationEventOutboxClaim claim, Instant at, String action) {
        if (at.isBefore(claim.claimedAt()) || at.isAfter(claim.claimExpiresAt())) {
            throw new IllegalArgumentException(action + " is outside outbox claim lease");
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void rollback(Connection connection, Exception primary) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            primary.addSuppressed(exception);
        }
    }

    private record Candidate(UUID eventId, UUID conversationId, long conversationSequence) { }
}
