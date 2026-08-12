package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Serializable V1 GROUP admission with credential-snapshot and capacity checks. */
public final class PostgresLegacyV1RoomJoinAdapter implements LegacyV1RoomJoinPort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomJoinAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomJoinAccess inspect(UUID actor, long roomId) {
        Objects.requireNonNull(actor, "actor");
        if (roomId <= 0 || roomId > Integer.MAX_VALUE) {
            return LegacyV1RoomJoinAccess.Rejected.NOT_FOUND;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            if (!eligibleActor(connection, actor)) {
                return LegacyV1RoomJoinAccess.Rejected.JOIN_DENIED;
            }
            Room room = findRoom(connection, actor, roomId, false);
            if (room == null) return LegacyV1RoomJoinAccess.Rejected.NOT_FOUND;
            if (room.activeMember()) {
                return new LegacyV1RoomJoinAccess.AlreadyMember(joined(room, actor, false));
            }
            return new LegacyV1RoomJoinAccess.Candidate(room.conversationId(), room.roomId(),
                    room.title(), actor, room.credential());
        } catch (SQLException exception) {
            throw new ConversationPersistenceException("V1 room join inspection failed", exception);
        }
    }

    @Override public LegacyV1RoomJoinResult join(LegacyV1RoomJoinIntent intent) {
        Objects.requireNonNull(intent, "intent");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room join failed", last);
    }

    private LegacyV1RoomJoinResult attempt(LegacyV1RoomJoinIntent intent) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                if (!lockEligibleActor(connection, intent.actorAccountId())) {
                    connection.commit(); return LegacyV1RoomJoinResult.Rejected.JOIN_DENIED;
                }
                Room room = findRoom(connection, intent.actorAccountId(),
                        intent.legacyRoomId(), true);
                if (room == null) {
                    connection.commit(); return LegacyV1RoomJoinResult.Rejected.NOT_FOUND;
                }
                if (!room.conversationId().equals(intent.conversationId())
                        || !room.credential().equals(intent.expectedJoinCredential())) {
                    connection.commit(); return LegacyV1RoomJoinResult.Rejected.ACCESS_CHANGED;
                }
                if (room.activeMember()) {
                    LegacyV1RoomJoinResult result = joined(room, intent.actorAccountId(), false);
                    connection.commit(); return result;
                }
                if (activeMembers(connection, room.conversationId()) >= room.maxMembers()) {
                    connection.commit(); return LegacyV1RoomJoinResult.Rejected.ROOM_FULL;
                }
                if (room.role() == null) insertMember(connection, room.conversationId(),
                        intent.actorAccountId());
                else reactivateMember(connection, room.conversationId(), intent.actorAccountId());
                Room admitted = findRoom(connection, intent.actorAccountId(),
                        intent.legacyRoomId(), false);
                if (admitted == null || !admitted.activeMember()) {
                    throw new SQLException("V1 room admission did not create active membership");
                }
                LegacyV1RoomJoinResult result = joined(admitted, intent.actorAccountId(), true);
                connection.commit(); return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
            }
        }
    }

    private static boolean eligibleActor(Connection connection, UUID actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL)
                """)) {
            statement.setObject(1, actor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room join actor lookup returned no row");
                return row.getBoolean(1);
            }
        }
    }

    private static boolean lockEligibleActor(Connection connection, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account.id FROM chat.account account
                JOIN chat.legacy_v1_account_map mapping ON mapping.account_id = account.id
                WHERE account.id = ? AND account.disabled_at IS NULL
                FOR SHARE OF account
                """)) {
            statement.setObject(1, actor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                if (row.next()) throw new SQLException("V1 room join actor mapping duplicated");
                return true;
            }
        }
    }

    private static Room findRoom(Connection connection, UUID actor, long roomId, boolean lock)
            throws SQLException {
        String sql = """
                SELECT conversation.id, conversation.title,
                       mapping.legacy_conversation_id, policy.max_members,
                       credential.encoded_password, member.role, member.left_at
                FROM chat.legacy_v1_conversation_map mapping
                JOIN chat.conversation conversation
                  ON conversation.id = mapping.conversation_id
                 AND conversation.kind = 'GROUP'
                JOIN chat.group_admission_policy policy
                  ON policy.conversation_id = conversation.id
                LEFT JOIN chat.group_join_credential credential
                  ON credential.conversation_id = conversation.id
                LEFT JOIN chat.conversation_member member
                  ON member.conversation_id = conversation.id AND member.account_id = ?
                WHERE mapping.legacy_kind = 'ROOM'
                  AND mapping.legacy_conversation_id = ?
                """ + (lock ? " FOR UPDATE OF conversation, policy" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, actor); statement.setLong(2, roomId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Integer maxMembers = (Integer) row.getObject("max_members");
                String title = row.getString("title");
                long mappedId = row.getLong("legacy_conversation_id");
                if (maxMembers == null || title == null || title.isBlank()
                        || mappedId <= 0 || mappedId > Integer.MAX_VALUE) {
                    throw new SQLException("V1 room join mapping or policy is incomplete");
                }
                String encoded = row.getString("encoded_password");
                String role = row.getString("role");
                boolean active = role != null && row.getObject("left_at") == null;
                Room result = new Room(row.getObject("id", UUID.class), mappedId, title,
                        maxMembers, encoded == null ? Optional.empty()
                                : Optional.of(new StoredCredential.Argon2id(encoded)),
                        role == null ? null : parseRole(role), active);
                if (row.next()) throw new SQLException("V1 room join mapping duplicated");
                return result;
            }
        }
    }

    private static int activeMembers(Connection connection, UUID conversation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM chat.conversation_member
                WHERE conversation_id = ? AND left_at IS NULL
                """)) {
            statement.setObject(1, conversation);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("V1 room member count returned no row");
                return row.getInt(1);
            }
        }
    }

    private static void insertMember(Connection connection, UUID conversation, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation_member(conversation_id, account_id, role)
                VALUES (?, ?, 'MEMBER')
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, actor);
            requireOne(statement, "V1 room member insertion");
        }
    }

    private static void reactivateMember(Connection connection, UUID conversation, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member
                SET joined_at = transaction_timestamp(), left_at = NULL
                WHERE conversation_id = ? AND account_id = ? AND left_at IS NOT NULL
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, actor);
            requireOne(statement, "V1 room member reactivation");
        }
    }

    private static LegacyV1RoomJoinResult.Joined joined(
            Room room, UUID actor, boolean newJoin) {
        return new LegacyV1RoomJoinResult.Joined(room.conversationId(), room.roomId(),
                room.title(), actor, Objects.requireNonNull(room.role(), "active member role"),
                newJoin);
    }

    private static LegacyV1RoomJoinResult.Role parseRole(String role) throws SQLException {
        try { return LegacyV1RoomJoinResult.Role.valueOf(role); }
        catch (IllegalArgumentException exception) {
            throw new SQLException("unsupported V1 room member role", exception);
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
    private record Room(UUID conversationId, long roomId, String title, int maxMembers,
            Optional<StoredCredential> credential, LegacyV1RoomJoinResult.Role role,
            boolean activeMember) { }
}
