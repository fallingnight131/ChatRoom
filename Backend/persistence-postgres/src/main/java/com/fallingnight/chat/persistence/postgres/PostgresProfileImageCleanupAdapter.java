package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.profile.*;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL leased claim/release/confirm lifecycle for unreferenced objects. */
public final class PostgresProfileImageCleanupAdapter implements ProfileImageCleanupPort {
    private static final int MAX_BATCH_SIZE = 1_000;
    private final DataSource dataSource;

    public PostgresProfileImageCleanupAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public List<ProfileImageCleanupClaim> claim(Instant requestedBefore,
            Instant staleBefore, Instant claimedAt, int limit) {
        Objects.requireNonNull(requestedBefore, "requestedBefore");
        Objects.requireNonNull(staleBefore, "staleBefore");
        Objects.requireNonNull(claimedAt, "claimedAt");
        if (limit < 1 || limit > MAX_BATCH_SIZE || staleBefore.isAfter(claimedAt)
                || requestedBefore.isAfter(claimedAt))
            throw new IllegalArgumentException("invalid profile image cleanup claim query");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<String> keys = lockCandidates(connection, requestedBefore, staleBefore, limit);
                List<ProfileImageCleanupClaim> claims = new ArrayList<>(keys.size());
                for (String key : keys) {
                    UUID claimId = UUID.randomUUID();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE chat.profile_image_object
                            SET delete_claim_id = ?, delete_claimed_at = ?
                            WHERE object_key = ? AND delete_confirmed_at IS NULL
                            """)) {
                        statement.setObject(1, claimId);
                        statement.setObject(2, at(claimedAt)); statement.setString(3, key);
                        if (statement.executeUpdate() != 1)
                            throw new SQLException("profile image cleanup claim disappeared");
                    }
                    claims.add(new ProfileImageCleanupClaim(claimId, key, claimedAt));
                }
                connection.commit(); return List.copyOf(claims);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    "profile image cleanup claim failed", exception);
        }
    }

    @Override public boolean release(ProfileImageCleanupClaim claim) {
        Objects.requireNonNull(claim, "claim");
        return mutateClaim(claim, null, false);
    }

    @Override public boolean confirmDeleted(ProfileImageCleanupClaim claim, Instant confirmedAt) {
        Objects.requireNonNull(claim, "claim"); Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (confirmedAt.isBefore(claim.claimedAt()))
            throw new IllegalArgumentException("delete confirmation precedes claim");
        return mutateClaim(claim, confirmedAt, true);
    }

    private boolean mutateClaim(ProfileImageCleanupClaim claim, Instant confirmedAt,
            boolean confirm) {
        String sql = confirm ? """
                UPDATE chat.profile_image_object object SET delete_confirmed_at = ?
                WHERE object.object_key = ? AND object.delete_claim_id = ?
                  AND object.delete_confirmed_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM chat.account_profile_image current
                                  WHERE current.object_key = object.object_key)
                  AND NOT EXISTS (SELECT 1 FROM chat.group_profile_image current
                                  WHERE current.object_key = object.object_key)
                """ : """
                UPDATE chat.profile_image_object SET delete_claim_id = NULL,
                    delete_claimed_at = NULL
                WHERE object_key = ? AND delete_claim_id = ? AND delete_confirmed_at IS NULL
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (confirm) statement.setObject(index++, at(confirmedAt));
            statement.setString(index++, claim.objectKey());
            statement.setObject(index, claim.claimId());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new ConversationPersistenceException(
                    confirm ? "profile image cleanup confirmation failed"
                            : "profile image cleanup release failed", exception);
        }
    }

    private static List<String> lockCandidates(Connection connection, Instant requestedBefore,
            Instant staleBefore, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT object.object_key
                FROM chat.profile_image_object object
                WHERE object.cleanup_requested_at <= ? AND object.delete_confirmed_at IS NULL
                  AND (object.delete_claim_id IS NULL OR object.delete_claimed_at <= ?)
                  AND NOT EXISTS (SELECT 1 FROM chat.account_profile_image current
                                  WHERE current.object_key = object.object_key)
                  AND NOT EXISTS (SELECT 1 FROM chat.group_profile_image current
                                  WHERE current.object_key = object.object_key)
                ORDER BY object.cleanup_requested_at, object.object_key
                FOR UPDATE OF object SKIP LOCKED LIMIT ?
                """)) {
            statement.setObject(1, at(requestedBefore));
            statement.setObject(2, at(staleBefore)); statement.setInt(3, limit);
            List<String> keys = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) keys.add(row.getString(1));
            }
            return keys;
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    private static void rollback(Connection connection, Exception primary) {
        try { connection.rollback(); }
        catch (SQLException exception) { primary.addSuppressed(exception); }
    }
}
