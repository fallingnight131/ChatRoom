package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1SqliteConversationSourceTest {
    @TempDir
    Path temporary;

    @Test
    void readsCommittedWalConversationGraphWithoutWriting() throws Exception {
        Path database = temporary.resolve("v1-conversations.db");
        try (Connection writer = connect(database); Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            createCurrentSchema(statement);
            statement.execute("INSERT INTO users(id) VALUES (1), (2)");
            statement.execute("INSERT INTO rooms VALUES "
                    + "(10, 'Project Room', 1, '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_settings(room_id, max_file_size, "
                    + "total_file_space, max_file_count, max_members) "
                    + "VALUES (10, 2048, 8192, 42, 321)");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(10, 1, '2026-01-02 03:04:05', 7), "
                    + "(10, 2, '2026-01-02T03:04:06Z', 9)");
            statement.execute("INSERT INTO room_admins VALUES (10, 2)");
            statement.execute("INSERT INTO friendships VALUES "
                    + "(20, 1, 2, '2026-01-02T03:04:07+00:00', 11, 12)");

            long beforeChanges = totalChanges(writer);
            V1ConversationImportPlan plan =
                    new V1SqliteConversationSource(database).readPlan();

            assertTrue(plan.readyToCompareWithTarget());
            assertEquals(2, plan.conversations().size());
            assertEquals(4, plan.memberships().size());
            PlannedV1Conversation room = plan.conversations().stream()
                    .filter(conversation -> conversation.maxMembers() != null)
                    .findFirst().orElseThrow();
            assertEquals(321, room.maxMembers());
            assertEquals(2048, room.maxFileSize());
            assertEquals(8192, room.totalFileSpace());
            assertEquals(42, room.maxFileCount());
            assertTrue(plan.memberships().stream().anyMatch(
                    member -> "ADMIN".equals(member.role())
                            && member.legacyLastReadMessageId() == 9));
            assertEquals(beforeChanges, totalChanges(writer));
            assertEquals(1, count(writer, "rooms"));
            assertEquals(1, count(writer, "friendships"));
        }
    }

    @Test
    void convertsInvalidTimestampToSafeBlockingIssue() throws Exception {
        Path database = temporary.resolve("invalid-conversation-time.db");
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            createCurrentSchema(statement);
            statement.execute("INSERT INTO users(id) VALUES (1)");
            statement.execute("INSERT INTO rooms VALUES (3, 'secret-name', 1, 'not-a-time')");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(3, 1, '2026-01-02 03:04:05', 0)");
        }

        V1ConversationImportPlan plan =
                new V1SqliteConversationSource(database).readPlan();

        assertFalse(plan.readyToCompareWithTarget());
        assertEquals("INVALID_ROOM_CREATED_AT", plan.issues().getFirst().code());
        assertFalse(plan.issues().toString().contains("secret-name"));
    }

    @Test
    void refusesSchemaMissingReadCursorMigrationWithSafeError() throws Exception {
        Path database = temporary.resolve("old-conversation-schema.db");
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE rooms(id INTEGER, name TEXT, "
                    + "creator_id INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_members(room_id INTEGER, user_id INTEGER, "
                    + "joined_at TEXT, last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE room_settings(room_id INTEGER)");
            statement.execute("CREATE TABLE room_admins(room_id INTEGER, user_id INTEGER)");
            statement.execute("CREATE TABLE friendships(id INTEGER, user_id1 INTEGER, "
                    + "user_id2 INTEGER, created_at TEXT, user1_last_read_msg_id INTEGER)");
        }

        V1ConversationSourceException exception = assertThrows(
                V1ConversationSourceException.class,
                () -> new V1SqliteConversationSource(database).readPlan());
        assertEquals("V1 conversation schema is missing required migrated columns",
                exception.getMessage());
        assertFalse(exception.getMessage().contains(database.toString()));
    }

    private static Connection connect(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static void createCurrentSchema(Statement statement) throws Exception {
        statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY)");
        statement.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY, name TEXT, "
                + "creator_id INTEGER, created_at TEXT)");
        statement.execute("CREATE TABLE room_members(room_id INTEGER, user_id INTEGER, "
                + "joined_at TEXT, last_read_msg_id INTEGER, PRIMARY KEY(room_id, user_id))");
        statement.execute("CREATE TABLE room_settings(room_id INTEGER PRIMARY KEY, "
                + "max_file_size INTEGER DEFAULT 10737418240, "
                + "total_file_space INTEGER DEFAULT 10737418240, "
                + "max_file_count INTEGER DEFAULT 1500, max_members INTEGER)");
        statement.execute("CREATE TABLE room_admins(room_id INTEGER, user_id INTEGER, "
                + "PRIMARY KEY(room_id, user_id))");
        statement.execute("CREATE TABLE friendships(id INTEGER PRIMARY KEY, "
                + "user_id1 INTEGER, user_id2 INTEGER, created_at TEXT, "
                + "user1_last_read_msg_id INTEGER, user2_last_read_msg_id INTEGER)");
    }

    private static long totalChanges(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT total_changes()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static int count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
