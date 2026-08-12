package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.compatibility.v1.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Serializable V1 room leave with deterministic owner succession and dissolution. */
public final class PostgresLegacyV1RoomLeaveAdapter implements LegacyV1RoomLeavePort {
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;

    public PostgresLegacyV1RoomLeaveAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public LegacyV1RoomLeaveResult leave(LegacyV1RoomLeaveIntent intent) {
        Objects.requireNonNull(intent, "intent");
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return attempt(intent); }
            catch (SQLException exception) {
                last = exception;
                if (!retryable(exception) || attempt == MAX_ATTEMPTS) break;
            }
        }
        throw new ConversationPersistenceException("V1 room leave failed", last);
    }

    private LegacyV1RoomLeaveResult attempt(LegacyV1RoomLeaveIntent intent)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                if (!lockEligibleActor(connection, intent.actorAccountId())) {
                    connection.commit();
                    return LegacyV1RoomLeaveResult.Rejected.LEAVE_DENIED;
                }
                RoomTarget target = lockRoom(connection, intent.legacyRoomId());
                if (target == null) {
                    connection.commit();
                    return LegacyV1RoomLeaveResult.Rejected.NOT_FOUND;
                }
                Room room = new Room(target.conversationId(), target.closedAt(),
                        lockMembership(connection, target.conversationId(),
                                intent.actorAccountId()));
                Membership actor = room.actorMembership();
                if (room.closedAt() != null) {
                    if (actor == null) {
                        connection.commit();
                        return LegacyV1RoomLeaveResult.Rejected.NOT_FOUND;
                    }
                    if (actor.leftAt() == null) {
                        throw new SQLException("closed V1 room has active leaving actor");
                    }
                    LegacyV1RoomLeaveResult result = left(room, intent, false, true,
                            Optional.empty());
                    connection.commit(); return result;
                }
                if (actor == null) {
                    connection.commit();
                    return LegacyV1RoomLeaveResult.Rejected.NOT_MEMBER;
                }
                if (actor.leftAt() != null) {
                    LegacyV1RoomLeaveResult result = left(room, intent, false, false,
                            Optional.empty());
                    connection.commit(); return result;
                }

                List<Member> active = lockActiveMembers(connection, room.conversationId());
                validateActiveGraph(active, intent.actorAccountId());
                if (active.size() == 1) {
                    endMembership(connection, room.conversationId(), intent.actorAccountId());
                    closeRoom(connection, room.conversationId());
                    LegacyV1RoomLeaveResult result = left(room, intent, true, true,
                            Optional.empty());
                    connection.commit(); return result;
                }

                Optional<LegacyV1RoomLeaveResult.OwnershipTransfer> transfer = Optional.empty();
                if (actor.role() == Role.OWNER) {
                    Member successor = active.stream()
                            .filter(member -> !member.accountId().equals(intent.actorAccountId()))
                            .min(successorOrder()).orElseThrow();
                    promoteOwner(connection, room.conversationId(), successor.accountId());
                    transfer = Optional.of(new LegacyV1RoomLeaveResult.OwnershipTransfer(
                            successor.accountId(), successor.displayName()));
                }
                endMembership(connection, room.conversationId(), intent.actorAccountId());
                touchConversation(connection, room.conversationId());
                LegacyV1RoomLeaveResult result = left(room, intent, true, false, transfer);
                connection.commit(); return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception); throw exception;
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
                if (row.next()) throw new SQLException("V1 room leave actor mapping duplicated");
                return true;
            }
        }
    }

    private static RoomTarget lockRoom(Connection connection, long legacyRoomId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation.id, lifecycle.closed_at
                FROM chat.legacy_v1_conversation_map mapping
                JOIN chat.conversation conversation
                  ON conversation.id = mapping.conversation_id
                 AND conversation.kind = 'GROUP'
                JOIN chat.group_lifecycle lifecycle
                  ON lifecycle.conversation_id = conversation.id
                WHERE mapping.legacy_kind = 'ROOM'
                  AND mapping.legacy_conversation_id = ?
                FOR UPDATE OF conversation, lifecycle
                """)) {
            statement.setLong(1, legacyRoomId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                RoomTarget room = new RoomTarget(row.getObject("id", UUID.class),
                        row.getObject("closed_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 room leave mapping duplicated");
                return room;
            }
        }
    }

    private static Membership lockMembership(
            Connection connection, UUID conversation, UUID actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, left_at FROM chat.conversation_member
                WHERE conversation_id = ? AND account_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, actor);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Membership membership = new Membership(parseRole(row.getString("role")),
                        row.getObject("left_at", OffsetDateTime.class));
                if (row.next()) throw new SQLException("V1 room membership duplicated");
                return membership;
            }
        }
    }

    private static List<Member> lockActiveMembers(Connection connection, UUID conversation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member.account_id, member.role, member.joined_at,
                       account.display_name, account.disabled_at, mapping.legacy_user_id
                FROM chat.conversation_member member
                JOIN chat.account account ON account.id = member.account_id
                LEFT JOIN chat.legacy_v1_account_map mapping
                  ON mapping.account_id = member.account_id
                WHERE member.conversation_id = ? AND member.left_at IS NULL
                ORDER BY member.account_id
                FOR UPDATE OF member, account
                """)) {
            statement.setObject(1, conversation);
            List<Member> members = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    String displayName = row.getString("display_name");
                    Long legacyUserId = row.getObject("legacy_user_id", Long.class);
                    if (displayName == null || legacyUserId == null
                            || row.getObject("disabled_at") != null) {
                        throw new SQLException("V1 active room member projection is incomplete");
                    }
                    members.add(new Member(row.getObject("account_id", UUID.class),
                            parseRole(row.getString("role")),
                            row.getObject("joined_at", OffsetDateTime.class), displayName));
                }
            }
            return List.copyOf(members);
        }
    }

    private static void validateActiveGraph(List<Member> active, UUID actor)
            throws SQLException {
        if (active.isEmpty() || active.stream().noneMatch(member -> member.accountId().equals(actor))) {
            throw new SQLException("V1 room leave active membership changed");
        }
        if (active.stream().filter(member -> member.role() == Role.OWNER).count() != 1) {
            throw new SQLException("V1 active room must have exactly one owner");
        }
    }

    private static Comparator<Member> successorOrder() {
        return Comparator.comparingInt((Member member) -> member.role() == Role.ADMIN ? 0 : 1)
                .thenComparing(Member::joinedAt).thenComparing(Member::accountId);
    }

    private static void promoteOwner(Connection connection, UUID conversation, UUID successor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member SET role = 'OWNER'
                WHERE conversation_id = ? AND account_id = ? AND left_at IS NULL
                  AND role IN ('ADMIN', 'MEMBER')
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, successor);
            requireOne(statement, "V1 room owner promotion");
        }
    }

    private static void endMembership(Connection connection, UUID conversation, UUID actor)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation_member SET left_at = transaction_timestamp()
                WHERE conversation_id = ? AND account_id = ? AND left_at IS NULL
                """)) {
            statement.setObject(1, conversation); statement.setObject(2, actor);
            requireOne(statement, "V1 room membership leave");
        }
    }

    private static void closeRoom(Connection connection, UUID conversation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.group_lifecycle
                SET closed_at = transaction_timestamp(), updated_at = transaction_timestamp()
                WHERE conversation_id = ? AND closed_at IS NULL
                """)) {
            statement.setObject(1, conversation);
            requireOne(statement, "V1 room dissolution");
        }
        touchConversation(connection, conversation);
    }

    private static void touchConversation(Connection connection, UUID conversation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.conversation SET updated_at = transaction_timestamp() WHERE id = ?
                """)) {
            statement.setObject(1, conversation);
            requireOne(statement, "V1 room update timestamp");
        }
    }

    private static LegacyV1RoomLeaveResult.Left left(Room room,
            LegacyV1RoomLeaveIntent intent, boolean newLeave, boolean dissolved,
            Optional<LegacyV1RoomLeaveResult.OwnershipTransfer> transfer) {
        return new LegacyV1RoomLeaveResult.Left(room.conversationId(), intent.legacyRoomId(),
                intent.actorAccountId(), newLeave, dissolved, transfer);
    }

    private static Role parseRole(String role) throws SQLException {
        try { return Role.valueOf(role); }
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
            if ("40001".equals(current.getSQLState())) return true;
        }
        return false;
    }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private enum Role { OWNER, ADMIN, MEMBER }
    private record Membership(Role role, OffsetDateTime leftAt) { }
    private record RoomTarget(UUID conversationId, OffsetDateTime closedAt) { }
    private record Room(UUID conversationId, OffsetDateTime closedAt,
            Membership actorMembership) { }
    private record Member(UUID accountId, Role role, OffsetDateTime joinedAt,
            String displayName) { }
}
