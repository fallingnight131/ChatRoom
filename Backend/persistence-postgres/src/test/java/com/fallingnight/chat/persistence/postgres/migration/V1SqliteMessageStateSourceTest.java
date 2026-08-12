package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1SqliteMessageStateSourceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void readsConversationAndMessageStateFromMigratedSchema() throws Exception {
        Path database = tempDirectory.resolve("source.db");
        createSource(database, true);

        V1MessageStateImportPlan plan = new V1SqliteMessageStateSource(database).readPlan();

        assertTrue(plan.readyToCompareWithTarget(), plan.issues().toString());
        assertEquals(2, plan.conversationCursors().size());
        assertEquals(4, plan.memberReadCursors().size());
        assertEquals(9, plan.conversationCursors().stream()
                .mapToLong(PlannedV1ConversationCursor::targetNextSequence)
                .max().orElseThrow());
        assertTrue(plan.memberReadCursors().stream().anyMatch(
                cursor -> cursor.legacyLastReadMessageId() == 105
                        && cursor.targetLastReadSequence() == 4));
    }

    @Test
    void rejectsSchemaWithoutRequiredMutationColumn() throws Exception {
        Path database = tempDirectory.resolve("old.db");
        createSource(database, false);

        V1MessageStateSourceException failure = assertThrows(
                V1MessageStateSourceException.class,
                () -> new V1SqliteMessageStateSource(database).readPlan());

        assertTrue(failure.getMessage().contains("missing required migrated columns"));
    }

    static void createSource(Path database, boolean includeMutationColumn)
            throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE rooms (id INTEGER PRIMARY KEY, name TEXT, "
                    + "creator_id INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_members (room_id INTEGER, user_id INTEGER, "
                    + "joined_at TEXT, last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE room_settings (room_id INTEGER PRIMARY KEY, "
                    + "max_members INTEGER)");
            statement.execute("CREATE TABLE room_admins (room_id INTEGER, user_id INTEGER)");
            statement.execute("CREATE TABLE friendships (id INTEGER PRIMARY KEY, "
                    + "user_id1 INTEGER, user_id2 INTEGER, created_at TEXT, "
                    + "user1_last_read_msg_id INTEGER, user2_last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY, room_id INTEGER, "
                    + "user_id INTEGER, sequence INTEGER, "
                    + (includeMutationColumn ? "mutation_sequence INTEGER, " : "")
                    + "recalled INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_message_sequences (room_id INTEGER PRIMARY KEY, "
                    + "last_sequence INTEGER)");
            statement.execute("CREATE TABLE room_message_deletion_events (id INTEGER PRIMARY KEY, "
                    + "room_id INTEGER, operator_user_id INTEGER, operator_name TEXT, "
                    + "client_operation_id TEXT, command_fingerprint TEXT, mode TEXT, "
                    + "message_ids_json TEXT, file_ids_json TEXT, cutoff_ms INTEGER, "
                    + "deleted_count INTEGER, sequence INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE friend_messages (id INTEGER PRIMARY KEY, "
                    + "friendship_id INTEGER, sender_id INTEGER, sequence INTEGER, "
                    + "mutation_sequence INTEGER, recalled INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE friendship_message_sequences ("
                    + "friendship_id INTEGER PRIMARY KEY, last_sequence INTEGER)");
            if (!includeMutationColumn) {
                return;
            }
            statement.execute("INSERT INTO users(id) VALUES (1), (2), (3)");
            statement.execute("INSERT INTO rooms VALUES "
                    + "(9, 'Room', 1, '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_settings VALUES (9, 83)");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(9, 1, '2026-01-02 03:04:05', 100), "
                    + "(9, 2, '2026-01-02 03:04:05', 105)");
            statement.execute("INSERT INTO friendships VALUES "
                    + "(4, 2, 3, '2026-01-02 03:04:05', 50, 60)");
            statement.execute("INSERT INTO messages VALUES "
                    + "(100, 9, 1, 2, NULL, 0, '2026-01-02 03:04:05'), "
                    + "(105, 9, 2, 4, 7, 1, '2026-01-02 03:05:05')");
            statement.execute("INSERT INTO room_message_sequences VALUES (9, 8)");
            statement.execute("INSERT INTO room_message_deletion_events VALUES "
                    + "(1, 9, 1, 'Admin', 'operation-1', 'fingerprint-1', "
                    + "'selected', '[100]', '[]', 0, 1, 6, '2026-01-02 03:06:05')");
            statement.execute("INSERT INTO friend_messages VALUES "
                    + "(50, 4, 2, 1, NULL, 0, '2026-01-02 03:04:05'), "
                    + "(60, 4, 3, 3, NULL, 0, '2026-01-02 03:05:05')");
            statement.execute("INSERT INTO friendship_message_sequences VALUES (4, 3)");
        }
    }
}
