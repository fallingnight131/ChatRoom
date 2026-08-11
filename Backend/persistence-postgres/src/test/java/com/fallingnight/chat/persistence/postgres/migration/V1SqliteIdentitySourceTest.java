package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1SqliteIdentitySourceTest {
    @TempDir
    Path temporary;

    @Test
    void readsCurrentWalSourceWithoutWritingOrMissingCommittedRows() throws Exception {
        Path database = temporary.resolve("v1.db");
        try (Connection writer = connect(database)) {
            try (Statement statement = writer.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                createUsers(statement, true);
            }
            insertUser(
                    writer,
                    2,
                    "modern",
                    "Modern",
                    "$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA",
                    "",
                    "2026-01-02T03:04:06Z");
            insertUser(
                    writer,
                    1,
                    "legacy",
                    "Legacy",
                    "a".repeat(64),
                    "legacy-salt",
                    "2026-01-02 03:04:05");

            V1IdentityImportPlan plan = new V1SqliteIdentitySource(database).readPlan();

            assertTrue(plan.readyToCompareWithTarget());
            assertEquals(2, plan.sourceRows());
            assertEquals(Instant.parse("2026-01-02T03:04:05Z"),
                    plan.accounts().get(0).createdAt());
            assertEquals("legacy", plan.accounts().get(0).usernameKey());
            assertEquals(2, userCount(writer));
        }
    }

    @Test
    void turnsInvalidTimestampIntoSafeBlockingPlanIssue() throws Exception {
        Path database = temporary.resolve("invalid-time.db");
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            createUsers(statement, true);
            insertUser(
                    connection,
                    1,
                    "alice",
                    "Alice",
                    "$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA",
                    "",
                    "not-a-time");
        }

        V1IdentityImportPlan plan = new V1SqliteIdentitySource(database).readPlan();

        assertFalse(plan.readyToCompareWithTarget());
        assertEquals("INVALID_CREATED_AT", plan.issues().get(0).code());
        assertFalse(plan.issues().toString().contains("alice"));
    }

    @Test
    void refusesAPreMigrationUsersSchemaWithSafeError() throws Exception {
        Path database = temporary.resolve("old-schema.db");
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            createUsers(statement, false);
        }

        V1IdentitySourceException exception = assertThrows(
                V1IdentitySourceException.class,
                () -> new V1SqliteIdentitySource(database).readPlan());
        assertEquals("V1 users schema is missing required migrated columns",
                exception.getMessage());
        assertFalse(exception.getMessage().contains(database.toString()));
    }

    private static Connection connect(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static void createUsers(Statement statement, boolean current) throws Exception {
        String displayColumn = current ? ", display_name TEXT" : "";
        statement.execute("CREATE TABLE users ("
                + "id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL"
                + displayColumn
                + ", password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at TEXT)");
    }

    private static void insertUser(
            Connection connection,
            long id,
            String username,
            String displayName,
            String passwordHash,
            String salt,
            String createdAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users(id, username, display_name, password_hash, salt, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, username);
            statement.setString(3, displayName);
            statement.setString(4, passwordHash);
            statement.setString(5, salt);
            statement.setString(6, createdAt);
            statement.executeUpdate();
        }
    }

    private static int userCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM users")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
