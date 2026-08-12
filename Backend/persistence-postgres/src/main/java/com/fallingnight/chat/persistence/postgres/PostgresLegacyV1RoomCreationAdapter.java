package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Atomic retry-convergent V1 GROUP/OWNER/ROOM creation. */
public final class PostgresLegacyV1RoomCreationAdapter implements LegacyV1RoomCreationPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;
    public PostgresLegacyV1RoomCreationAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomCreationResult create(LegacyV1RoomCreationIntent intent) {
        Objects.requireNonNull(intent, "intent"); SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room creation failed", last);
    }

    private LegacyV1RoomCreationResult attempt(LegacyV1RoomCreationIntent intent)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                if (!lockActor(connection, intent.actorAccountId())) {
                    connection.commit();
                    return LegacyV1RoomCreationResult.Rejected.CREATION_DENIED;
                }
                Existing existing = findExisting(connection, intent);
                if (existing != null) {
                    LegacyV1RoomCreationResult result = matches(existing, intent)
                            ? new LegacyV1RoomCreationResult.Created(existing.conversationId(),
                                    existing.legacyRoomId(), existing.roomName(),
                                    intent.actorAccountId(), true)
                            : LegacyV1RoomCreationResult.Rejected.CLIENT_REQUEST_ID_CONFLICT;
                    connection.commit(); return result;
                }
                UUID conversationId = UUID.randomUUID();
                insertConversation(connection, conversationId, intent.roomName());
                insertAdmissionPolicy(connection, conversationId);
                insertOwner(connection, conversationId, intent.actorAccountId());
                if (intent.encodedPassword().isPresent()) {
                    insertCredential(connection, conversationId,
                            intent.encodedPassword().orElseThrow().encodedHash());
                }
                long legacyRoomId = nextUnusedRoomId(connection);
                insertMapping(connection, conversationId, legacyRoomId);
                insertIdempotency(connection, intent, conversationId);
                connection.commit();
                return new LegacyV1RoomCreationResult.Created(conversationId, legacyRoomId,
                        intent.roomName(), intent.actorAccountId(), false);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static boolean lockActor(Connection connection, UUID actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                FOR SHARE OF account
                """)) {
            statement.setObject(1, actor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                if (row.next()) throw new SQLException("V1 room creator mapping duplicated");
                return true;
            }
        }
    }

    private static Existing findExisting(Connection connection, LegacyV1RoomCreationIntent intent)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT creation.conversation_id, creation.room_name,
                       creation.password_idempotency_tag,
                       mapping.legacy_conversation_id, credential.encoded_password
                FROM chat.legacy_v1_room_creation creation
                JOIN chat.conversation conversation
                  ON conversation.id = creation.conversation_id AND conversation.kind = 'GROUP'
                JOIN chat.conversation_member owner
                  ON owner.conversation_id = creation.conversation_id
                 AND owner.account_id = creation.actor_account_id
                 AND owner.role = 'OWNER' AND owner.left_at IS NULL
                JOIN chat.legacy_v1_conversation_map mapping
                  ON mapping.conversation_id = creation.conversation_id
                 AND mapping.legacy_kind = 'ROOM'
                LEFT JOIN chat.group_join_credential credential
                  ON credential.conversation_id = creation.conversation_id
                WHERE creation.actor_account_id = ? AND creation.client_request_id = ?
                FOR UPDATE OF creation
                """)) {
            statement.setObject(1, intent.actorAccountId());
            statement.setString(2, intent.clientRequestId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Existing result = new Existing(row.getObject("conversation_id", UUID.class),
                        row.getString("room_name"), row.getString("password_idempotency_tag"),
                        row.getLong("legacy_conversation_id"),
                        row.getString("encoded_password") != null);
                if (row.next()) throw new SQLException("V1 room creation result duplicated");
                boolean protectedByTag = result.passwordTag() != null;
                if (protectedByTag != result.hasCredential()
                        || result.legacyRoomId() <= 0
                        || result.legacyRoomId() > Integer.MAX_VALUE) {
                    throw new SQLException("V1 room creation state is incomplete");
                }
                return result;
            }
        }
    }

    private static boolean matches(Existing existing, LegacyV1RoomCreationIntent intent) {
        String expectedTag = intent.encodedPassword()
                .map(LegacyV1RoomPasswordEncoding::idempotencyTag).orElse(null);
        return existing.roomName().equals(intent.roomName())
                && constantTimeNullableEquals(existing.passwordTag(), expectedTag);
    }

    private static boolean constantTimeNullableEquals(String left, String right) {
        if (left == null || right == null) return left == right;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static void insertConversation(Connection connection, UUID id, String title)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.conversation(id, kind, title) VALUES (?, 'GROUP', ?)")) {
            statement.setObject(1, id); statement.setString(2, title);
            requireOne(statement, "conversation");
        }
    }
    private static void insertOwner(Connection connection, UUID conversation, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation_member(conversation_id, account_id, role)
                VALUES (?, ?, 'OWNER')
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, actor);
            requireOne(statement, "owner membership");
        }
    }
    private static void insertAdmissionPolicy(Connection connection, UUID conversation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.group_admission_policy(conversation_id) VALUES (?)
                """)) {
            statement.setObject(1, conversation);
            requireOne(statement, "group admission policy");
        }
    }
    private static void insertCredential(Connection connection, UUID conversation, String hash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.group_join_credential(conversation_id, encoded_password)
                VALUES (?, ?)
                """)) {
            statement.setObject(1, conversation); statement.setString(2, hash);
            requireOne(statement, "group credential");
        }
    }
    private static long nextUnusedRoomId(Connection connection) throws SQLException {
        while (true) {
            long candidate;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT nextval('chat.legacy_v1_room_id_seq')");
                    ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room ID allocation returned no row");
                candidate = row.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM chat.legacy_v1_conversation_map
                     WHERE legacy_kind = 'ROOM' AND legacy_conversation_id = ?)
                    """)) {
                statement.setLong(1, candidate);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new SQLException("V1 room ID occupancy returned no row");
                    if (!row.getBoolean(1)) return candidate;
                }
            }
        }
    }
    private static void insertMapping(Connection connection, UUID conversation, long roomId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_conversation_map(
                    legacy_kind, legacy_conversation_id, conversation_id)
                VALUES ('ROOM', ?, ?)
                """)) {
            statement.setLong(1, roomId); statement.setObject(2, conversation);
            requireOne(statement, "V1 room mapping");
        }
    }
    private static void insertIdempotency(Connection connection,
            LegacyV1RoomCreationIntent intent, UUID conversation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_room_creation(actor_account_id, client_request_id,
                    room_name, password_idempotency_tag, conversation_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, intent.actorAccountId());
            statement.setString(2, intent.clientRequestId());
            statement.setString(3, intent.roomName());
            String tag = intent.encodedPassword()
                    .map(LegacyV1RoomPasswordEncoding::idempotencyTag).orElse(null);
            statement.setString(4, tag); statement.setObject(5, conversation);
            requireOne(statement, "V1 room creation idempotency");
        }
    }
    private static void requireOne(PreparedStatement statement, String operation)
            throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new SQLException(operation + " affected unexpected rows");
        }
    }
    private static boolean retryable(SQLException exception) {
        for (SQLException current = exception; current != null;
                current = current.getNextException()) {
            if ("40001".equals(current.getSQLState()) || "23505".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }
    private record Existing(UUID conversationId, String roomName, String passwordTag,
            long legacyRoomId, boolean hasCredential) { }
}
