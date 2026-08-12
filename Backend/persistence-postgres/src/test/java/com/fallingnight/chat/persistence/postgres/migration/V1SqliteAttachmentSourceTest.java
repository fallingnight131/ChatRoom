package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1SqliteAttachmentSourceTest {
    @TempDir
    Path temporary;

    @Test
    void readsCommittedWalAttachmentGraphWithoutWritingOrExposingLocators() throws Exception {
        Path database = temporary.resolve("v1-attachments.db");
        try (Connection writer = connect(database); Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            createCurrentSchema(statement);
            statement.execute("INSERT INTO files VALUES (11, 21, 31, 'report.pdf', "
                    + "'/private/source/report.pdf', 4096, 0, '', NULL, "
                    + "'2026-01-02 03:04:05', 'https://legacy.invalid/secret-room')");
            statement.execute("INSERT INTO messages VALUES (41, 21, 31, 'file', "
                    + "'report.pdf', 4096, 11, 0, '', '2026-01-02T03:04:06Z')");
            statement.execute("INSERT INTO friend_files VALUES (12, 22, 32, 'photo.png', "
                    + "'C:\\\\secret\\\\photo.png', 8192, 1, 'expired', "
                    + "'2026-02-03T04:05:07+00:00', '2026-01-03T04:05:06Z', "
                    + "'https://legacy.invalid/secret-friend')");
            statement.execute("INSERT INTO friend_messages VALUES (42, 22, 32, 'image', "
                    + "'photo.png', 8192, 12, 1, 'expired', '2026-01-03 04:05:07')");

            long beforeChanges = totalChanges(writer);
            V1AttachmentSourcePlan plan = new V1SqliteAttachmentSource(database).readPlan();

            assertTrue(plan.readyForObjectEvidence());
            assertEquals(2, plan.sourceFiles());
            assertEquals(2, plan.sourceMessageLinks());
            assertEquals(2, plan.attachments().size());
            PlannedV1AttachmentSource room = plan.attachments().stream()
                    .filter(row -> row.legacyKind() == LegacyV1ConversationKind.ROOM)
                    .findFirst().orElseThrow();
            assertEquals(Instant.parse("2026-01-02T03:04:05Z"), room.fileCreatedAt());
            assertEquals(Instant.parse("2026-01-02T03:04:06Z"), room.messageAcceptedAt());
            assertFalse(plan.toString().contains("/private/source"));
            assertFalse(plan.toString().contains("legacy.invalid"));
            assertEquals(beforeChanges, totalChanges(writer));

            String firstFingerprint = plan.sourceFingerprintSha256();
            statement.execute("UPDATE files SET file_path = '/moved/report.pdf' WHERE id = 11");
            V1AttachmentSourcePlan moved = new V1SqliteAttachmentSource(database).readPlan();
            assertTrue(moved.readyForObjectEvidence());
            assertNotEquals(firstFingerprint, moved.sourceFingerprintSha256());
        }
    }

    @Test
    void turnsInvalidTimestampIntoLocatorRedactedBlockingIssue() throws Exception {
        Path database = temporary.resolve("invalid-attachment-time.db");
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            createCurrentSchema(statement);
            statement.execute("INSERT INTO files VALUES (7, 8, 9, 'archive.zip', "
                    + "'/secret/archive.zip', 10, 0, '', NULL, 'not-a-time', "
                    + "'https://legacy.invalid/token')");
            statement.execute("INSERT INTO messages VALUES (10, 8, 9, 'file', "
                    + "'archive.zip', 10, 7, 0, '', '2026-01-02 03:04:05')");
        }

        V1AttachmentSourcePlan plan = new V1SqliteAttachmentSource(database).readPlan();

        assertFalse(plan.readyForObjectEvidence());
        assertEquals("INVALID_FILE_LIFECYCLE", plan.issues().getFirst().code());
        assertFalse(plan.toString().contains("/secret"));
        assertFalse(plan.toString().contains("legacy.invalid"));
    }

    @Test
    void refusesSchemaMissingObjectLocatorMigrationWithSafeError() throws Exception {
        Path database = temporary.resolve("old-attachment-schema.db");
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE files(id INTEGER, room_id INTEGER, user_id INTEGER, "
                    + "file_name TEXT, file_path TEXT, file_size INTEGER, cleared INTEGER, "
                    + "clear_reason TEXT, cleared_at TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE messages(id INTEGER)");
            statement.execute("CREATE TABLE friend_files(id INTEGER)");
            statement.execute("CREATE TABLE friend_messages(id INTEGER)");
        }

        V1AttachmentSourceException exception = assertThrows(
                V1AttachmentSourceException.class,
                () -> new V1SqliteAttachmentSource(database).readPlan());
        assertEquals("V1 attachment schema is missing required migrated columns",
                exception.getMessage());
        assertFalse(exception.getMessage().contains(database.toString()));
    }

    private static Connection connect(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static void createCurrentSchema(Statement statement) throws Exception {
        statement.execute("CREATE TABLE files(id INTEGER PRIMARY KEY, room_id INTEGER, "
                + "user_id INTEGER, file_name TEXT, file_path TEXT, file_size INTEGER, "
                + "cleared INTEGER, clear_reason TEXT, cleared_at TEXT, created_at TEXT, "
                + "cos_url TEXT)");
        statement.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY, room_id INTEGER, "
                + "user_id INTEGER, content_type TEXT, file_name TEXT, file_size INTEGER, "
                + "file_id INTEGER, file_cleared INTEGER, clear_reason TEXT, created_at TEXT)");
        statement.execute("CREATE TABLE friend_files(id INTEGER PRIMARY KEY, "
                + "friendship_id INTEGER, user_id INTEGER, file_name TEXT, file_path TEXT, "
                + "file_size INTEGER, cleared INTEGER, clear_reason TEXT, cleared_at TEXT, "
                + "created_at TEXT, cos_url TEXT)");
        statement.execute("CREATE TABLE friend_messages(id INTEGER PRIMARY KEY, "
                + "friendship_id INTEGER, sender_id INTEGER, content_type TEXT, file_name TEXT, "
                + "file_size INTEGER, file_id INTEGER, file_cleared INTEGER, clear_reason TEXT, "
                + "created_at TEXT)");
    }

    private static long totalChanges(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT total_changes()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
