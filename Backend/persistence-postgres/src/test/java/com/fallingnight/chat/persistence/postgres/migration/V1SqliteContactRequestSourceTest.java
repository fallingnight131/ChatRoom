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

class V1SqliteContactRequestSourceTest {
    @TempDir
    Path temporary;

    @Test
    void readsCommittedWalRequestsWithoutWritingAndPlansOnlyPending() throws Exception {
        Path database = temporary.resolve("v1-contact-requests.db");
        try (Connection writer = connect(database); Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            createCurrentSchema(statement);
            statement.execute("INSERT INTO users(id) VALUES (1), (2), (3)");
            statement.execute("INSERT INTO friendships VALUES (2, 3)");
            statement.execute("INSERT INTO friend_requests VALUES "
                    + "(7, 1, 2, 'pending', '2026-01-02 03:04:05'), "
                    + "(8, 2, 3, 'accepted', '2026-01-02T03:04:06Z'), "
                    + "(9, 3, 1, 'rejected', '2026-01-02T03:04:07+00:00')");

            long beforeChanges = totalChanges(writer);
            V1ContactRequestImportPlan plan =
                    new V1SqliteContactRequestSource(database).readPlan();

            assertTrue(plan.readyToCompareWithTarget());
            assertEquals(3, plan.sourceRows());
            assertEquals(1, plan.sourcePendingRows());
            assertEquals(2, plan.sourceTerminalRows());
            assertEquals(7, plan.pendingRequests().getFirst().legacyRequestId());
            assertEquals(beforeChanges, totalChanges(writer));
            assertEquals(3, count(writer, "friend_requests"));
        }
    }

    @Test
    void convertsInvalidTimestampToSafeBlockingIssue() throws Exception {
        Path database = temporary.resolve("invalid-contact-request-time.db");
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            createCurrentSchema(statement);
            statement.execute("INSERT INTO users(id) VALUES (1), (2)");
            statement.execute("INSERT INTO friend_requests VALUES "
                    + "(1, 1, 2, 'pending', 'private-invalid-time')");
        }

        V1ContactRequestImportPlan plan =
                new V1SqliteContactRequestSource(database).readPlan();

        assertFalse(plan.readyToCompareWithTarget());
        assertEquals("INVALID_REQUEST_CREATED_AT", plan.issues().getFirst().code());
        assertFalse(plan.issues().toString().contains("private-invalid-time"));
    }

    @Test
    void refusesSchemaMissingMigratedStatusColumnWithSafeError() throws Exception {
        Path database = temporary.resolve("old-contact-request-schema.db");
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE friendships(user_id1 INTEGER, user_id2 INTEGER)");
            statement.execute("CREATE TABLE friend_requests(id INTEGER, from_user_id INTEGER, "
                    + "to_user_id INTEGER, created_at TEXT)");
        }

        V1ContactRequestSourceException exception = assertThrows(
                V1ContactRequestSourceException.class,
                () -> new V1SqliteContactRequestSource(database).readPlan());
        assertEquals("V1 contact request schema is missing required columns",
                exception.getMessage());
        assertFalse(exception.getMessage().contains(database.toString()));
    }

    private static Connection connect(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static void createCurrentSchema(Statement statement) throws Exception {
        statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY)");
        statement.execute("CREATE TABLE friendships(user_id1 INTEGER, user_id2 INTEGER)");
        statement.execute("CREATE TABLE friend_requests(id INTEGER PRIMARY KEY, "
                + "from_user_id INTEGER, to_user_id INTEGER, status TEXT, created_at TEXT)");
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
