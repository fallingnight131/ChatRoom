package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.attachment.AttachmentActor;
import com.fallingnight.chat.application.attachment.AttachmentCleanupCandidate;
import com.fallingnight.chat.application.attachment.AttachmentCleanupPort;
import com.fallingnight.chat.application.attachment.AttachmentLifecyclePort;
import com.fallingnight.chat.application.attachment.AttachmentRegistration;
import com.fallingnight.chat.application.attachment.AttachmentRegistrationPort;
import com.fallingnight.chat.application.attachment.AttachmentRegistrationResult;
import com.fallingnight.chat.application.attachment.AttachmentReadyTransition;
import com.fallingnight.chat.application.attachment.AttachmentState;
import com.fallingnight.chat.application.attachment.RegisteredAttachment;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** Active-member/device PostgreSQL reservation and lifecycle of attachment metadata. */
public final class PostgresAttachmentAdapter
        implements AttachmentRegistrationPort, AttachmentLifecyclePort, AttachmentCleanupPort {
    private final DataSource dataSource;
    private final Supplier<UUID> uuidSupplier;

    public PostgresAttachmentAdapter(DataSource dataSource) {
        this(dataSource, UUID::randomUUID);
    }

    PostgresAttachmentAdapter(DataSource dataSource, Supplier<UUID> uuidSupplier) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
    }

    @Override
    public AttachmentRegistrationResult register(AttachmentRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!authorized(connection, registration)) {
                    connection.rollback();
                    return AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED;
                }
                Optional<RegisteredAttachment> existing = findExisting(connection, registration);
                if (existing.isPresent()) {
                    connection.rollback();
                    return existingResult(existing.orElseThrow(), registration);
                }
                UUID attachmentId = Objects.requireNonNull(
                        uuidSupplier.get(), "attachmentId");
                Optional<RegisteredAttachment> inserted = insert(
                        connection, attachmentId, registration);
                if (inserted.isPresent()) {
                    connection.commit();
                    return new AttachmentRegistrationResult.Accepted(
                            inserted.orElseThrow(), false);
                }
                RegisteredAttachment raced = findExisting(connection, registration)
                        .orElseThrow(() -> new SQLException(
                                "attachment idempotency conflict row disappeared"));
                AttachmentRegistrationResult result = existingResult(raced, registration);
                connection.rollback();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new AttachmentPersistenceException(
                    "attachment registration failed", exception);
        }
    }

    @Override
    public Optional<RegisteredAttachment> findAuthorized(
            UUID attachmentId, AttachmentActor actor) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(actor, "actor");
        try (Connection connection = dataSource.getConnection()) {
            return findAuthorized(connection, attachmentId, actor, false);
        } catch (SQLException exception) {
            throw new AttachmentPersistenceException(
                    "authorized attachment lookup failed", exception);
        }
    }

    @Override
    public AttachmentReadyTransition markReadyIfAuthorized(
            UUID attachmentId, AttachmentActor actor, Instant readyAt) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(readyAt, "readyAt");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<RegisteredAttachment> found = findAuthorized(
                        connection, attachmentId, actor, true);
                if (found.isEmpty()
                        || found.orElseThrow().state() == AttachmentState.REVOKED) {
                    connection.rollback();
                    return AttachmentReadyTransition.Rejected.NOT_AVAILABLE;
                }
                RegisteredAttachment attachment = found.orElseThrow();
                if (attachment.state() == AttachmentState.READY) {
                    connection.rollback();
                    return new AttachmentReadyTransition.Ready(attachment, false);
                }
                RegisteredAttachment ready = updateReady(connection, attachmentId, readyAt)
                        .orElseThrow(() -> new SQLException(
                                "locked pending attachment was not updated"));
                connection.commit();
                return new AttachmentReadyTransition.Ready(ready, true);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new AttachmentPersistenceException(
                    "attachment READY transition failed", exception);
        }
    }

    @Override
    public int revokeExpiredPending(
            Instant createdAtOrBefore, Instant revokedAt, int limit) {
        Objects.requireNonNull(createdAtOrBefore, "createdAtOrBefore");
        Objects.requireNonNull(revokedAt, "revokedAt");
        requireCleanupLimit(limit);
        if (revokedAt.isBefore(createdAtOrBefore)) {
            throw new IllegalArgumentException("revokedAt must not precede cleanup cutoff");
        }
        String sql = """
                WITH expired AS (
                    SELECT id
                    FROM chat.attachment
                    WHERE state = 'UPLOAD_PENDING' AND created_at <= ?
                    ORDER BY created_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE chat.attachment att
                SET state = 'REVOKED', revoked_at = ?
                FROM expired
                WHERE att.id = expired.id
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, utc(createdAtOrBefore));
            statement.setInt(2, limit);
            statement.setObject(3, utc(revokedAt));
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AttachmentPersistenceException(
                    "expired attachment revocation failed", exception);
        }
    }

    @Override
    public List<AttachmentCleanupCandidate> findObjectCleanupRequired(int limit) {
        requireCleanupLimit(limit);
        String sql = """
                SELECT id, object_key
                FROM chat.attachment
                WHERE state = 'REVOKED' AND object_deleted_at IS NULL
                ORDER BY revoked_at, id
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            List<AttachmentCleanupCandidate> candidates = new ArrayList<>(limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    candidates.add(new AttachmentCleanupCandidate(
                            result.getObject(1, UUID.class), result.getString(2)));
                }
            }
            return List.copyOf(candidates);
        } catch (SQLException exception) {
            throw new AttachmentPersistenceException(
                    "attachment cleanup lookup failed", exception);
        }
    }

    @Override
    public boolean confirmObjectDeleted(UUID attachmentId, Instant deletedAt) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(deletedAt, "deletedAt");
        String sql = """
                UPDATE chat.attachment
                SET object_deleted_at = COALESCE(object_deleted_at, ?)
                WHERE id = ? AND state = 'REVOKED' AND revoked_at <= ?
                RETURNING object_deleted_at
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, utc(deletedAt));
            statement.setObject(2, attachmentId);
            statement.setObject(3, utc(deletedAt));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw new AttachmentPersistenceException(
                    "attachment object deletion confirmation failed", exception);
        }
    }

    private static boolean authorized(
            Connection connection, AttachmentRegistration value) throws SQLException {
        String sql = """
                SELECT 1
                FROM chat.conversation_member cm
                JOIN chat.conversation c ON c.id = cm.conversation_id
                LEFT JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = c.id
                JOIN chat.account a ON a.id = cm.account_id
                JOIN chat.device d ON d.account_id = cm.account_id
                WHERE cm.conversation_id = ? AND cm.account_id = ?
                  AND cm.left_at IS NULL AND a.disabled_at IS NULL
                  AND d.id = ? AND d.revoked_at IS NULL
                  AND (c.kind = 'DIRECT' OR (lifecycle.conversation_id IS NOT NULL
                       AND lifecycle.closed_at IS NULL))
                FOR SHARE OF cm, c, a, d
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value.conversationId());
            statement.setObject(2, value.ownerAccountId());
            statement.setObject(3, value.ownerDeviceId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && !result.next();
            }
        }
    }

    private static Optional<RegisteredAttachment> findExisting(
            Connection connection, AttachmentRegistration value) throws SQLException {
        String sql = """
                SELECT id, conversation_id, owner_account_id, owner_device_id,
                       client_attachment_id, object_key, file_name, media_type,
                       byte_size, content_sha256, state, created_at, ready_at, revoked_at
                FROM chat.attachment
                WHERE owner_account_id = ? AND client_attachment_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value.ownerAccountId());
            statement.setString(2, value.clientAttachmentId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<RegisteredAttachment> findAuthorized(
            Connection connection,
            UUID attachmentId,
            AttachmentActor actor,
            boolean lock) throws SQLException {
        String sql = """
                SELECT att.id, att.conversation_id, att.owner_account_id,
                       att.owner_device_id, att.client_attachment_id, att.object_key,
                       att.file_name, att.media_type, att.byte_size, att.content_sha256,
                       att.state, att.created_at, att.ready_at, att.revoked_at
                FROM chat.attachment att
                JOIN chat.conversation_member cm
                  ON cm.conversation_id = att.conversation_id
                 AND cm.account_id = att.owner_account_id
                JOIN chat.conversation c ON c.id = cm.conversation_id
                LEFT JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = c.id
                JOIN chat.account a ON a.id = att.owner_account_id
                JOIN chat.device d
                  ON d.id = att.owner_device_id
                 AND d.account_id = att.owner_account_id
                WHERE att.id = ? AND att.owner_account_id = ? AND att.owner_device_id = ?
                  AND cm.left_at IS NULL AND a.disabled_at IS NULL
                  AND d.revoked_at IS NULL
                  AND (c.kind = 'DIRECT' OR (lifecycle.conversation_id IS NOT NULL
                       AND lifecycle.closed_at IS NULL))
                """ + (lock ? "FOR UPDATE OF att FOR SHARE OF cm, c, a, d" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, attachmentId);
            statement.setObject(2, actor.accountId());
            statement.setObject(3, actor.deviceId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<RegisteredAttachment> updateReady(
            Connection connection, UUID attachmentId, Instant readyAt) throws SQLException {
        String sql = """
                UPDATE chat.attachment
                SET state = 'READY', ready_at = ?
                WHERE id = ? AND state = 'UPLOAD_PENDING'
                RETURNING id, conversation_id, owner_account_id, owner_device_id,
                          client_attachment_id, object_key, file_name, media_type,
                          byte_size, content_sha256, state, created_at, ready_at, revoked_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, OffsetDateTime.ofInstant(readyAt, java.time.ZoneOffset.UTC));
            statement.setObject(2, attachmentId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<RegisteredAttachment> insert(
            Connection connection,
            UUID attachmentId,
            AttachmentRegistration value) throws SQLException {
        String sql = """
                INSERT INTO chat.attachment(
                    id, conversation_id, owner_account_id, owner_device_id,
                    client_attachment_id, object_key, file_name, media_type,
                    byte_size, content_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_account_id, client_attachment_id) DO NOTHING
                RETURNING id, conversation_id, owner_account_id, owner_device_id,
                          client_attachment_id, object_key, file_name, media_type,
                          byte_size, content_sha256, state, created_at, ready_at, revoked_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, attachmentId);
            statement.setObject(2, value.conversationId());
            statement.setObject(3, value.ownerAccountId());
            statement.setObject(4, value.ownerDeviceId());
            statement.setString(5, value.clientAttachmentId());
            statement.setString(6, "attachments/" + attachmentId);
            statement.setString(7, value.fileName());
            statement.setString(8, value.mediaType());
            statement.setLong(9, value.byteSize());
            statement.setBytes(10, value.contentSha256());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static AttachmentRegistrationResult existingResult(
            RegisteredAttachment existing, AttachmentRegistration requested) {
        boolean exact = existing.conversationId().equals(requested.conversationId())
                && existing.ownerDeviceId().equals(requested.ownerDeviceId())
                && existing.fileName().equals(requested.fileName())
                && existing.mediaType().equals(requested.mediaType())
                && existing.byteSize() == requested.byteSize()
                && MessageDigest.isEqual(
                        existing.contentSha256(), requested.contentSha256());
        return exact
                ? new AttachmentRegistrationResult.Accepted(existing, true)
                : AttachmentRegistrationResult.Rejected.IDEMPOTENCY_CONFLICT;
    }

    private static RegisteredAttachment read(ResultSet result) throws SQLException {
        OffsetDateTime ready = result.getObject(13, OffsetDateTime.class);
        OffsetDateTime revoked = result.getObject(14, OffsetDateTime.class);
        return new RegisteredAttachment(
                result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                result.getObject(3, UUID.class), result.getObject(4, UUID.class),
                result.getString(5), result.getString(6), result.getString(7),
                result.getString(8), result.getLong(9), result.getBytes(10),
                AttachmentState.valueOf(result.getString(11)),
                result.getObject(12, OffsetDateTime.class).toInstant(),
                Optional.ofNullable(ready).map(OffsetDateTime::toInstant),
                Optional.ofNullable(revoked).map(OffsetDateTime::toInstant));
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }

    private static void requireCleanupLimit(int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("cleanup limit must be in 1..1000");
        }
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
