package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1SqliteMessagePayloadSourceTest {
    @TempDir
    Path temporary;

    @Test
    void readsTextLikeBodiesFromBothNamespaces() throws Exception {
        Path database = temporary.resolve("payload.db");
        createSchema(database, true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO messages VALUES "
                    + "(10, 5, 'hello', 'text', '', 0, 0, 0, '', '', 0)");
            statement.execute("INSERT INTO friend_messages VALUES "
                    + "(10, 7, '🙂', 'emoji', '', 0, 0, 0, '', '', 0)");
        }

        V1MessagePayloadImportPlan plan =
                new V1SqliteMessagePayloadSource(database).readPlan();

        assertTrue(plan.readyToCompareWithTarget());
        assertEquals(2, plan.messages().size());
        assertTrue(plan.messages().stream().anyMatch(
                message -> message.targetText().equals("hello")));
        assertTrue(plan.messages().stream().anyMatch(
                message -> message.targetText().equals("🙂")));
    }

    @Test
    void surfacesAttachmentAsSafeBlockingIssueWithoutReadingFileStorage() throws Exception {
        Path database = temporary.resolve("attachment.db");
        createSchema(database, true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO messages VALUES "
                    + "(11, 5, 'private-name.zip', 'file', 'private-name.zip', "
                    + "123, 99, 0, '', 'base64-thumbnail', 0)");
        }

        V1MessagePayloadImportPlan plan =
                new V1SqliteMessagePayloadSource(database).readPlan();

        assertFalse(plan.readyToCompareWithTarget());
        assertEquals("ATTACHMENT_MAPPING_REQUIRED", plan.issues().getFirst().code());
        assertFalse(plan.issues().toString().contains("private-name.zip"));
        assertFalse(plan.issues().toString().contains("base64-thumbnail"));
    }

    @Test
    void rejectsPreMigrationSchema() throws Exception {
        Path database = temporary.resolve("old.db");
        createSchema(database, false);

        V1MessagePayloadSourceException failure = assertThrows(
                V1MessagePayloadSourceException.class,
                () -> new V1SqliteMessagePayloadSource(database).readPlan());

        assertTrue(failure.getMessage().contains("missing required migrated columns"));
    }

    static void createSchema(Path database, boolean current) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            String clearedColumns = current
                    ? "file_cleared INTEGER, clear_reason TEXT, "
                    : "";
            statement.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY, room_id INTEGER, "
                    + "content TEXT, content_type TEXT, file_name TEXT, file_size INTEGER, "
                    + "file_id INTEGER, " + clearedColumns
                    + "thumbnail TEXT, recalled INTEGER)");
            statement.execute("CREATE TABLE friend_messages (id INTEGER PRIMARY KEY, "
                    + "friendship_id INTEGER, content TEXT, content_type TEXT, file_name TEXT, "
                    + "file_size INTEGER, file_id INTEGER, " + clearedColumns
                    + "thumbnail TEXT, recalled INTEGER)");
        }
    }
}
