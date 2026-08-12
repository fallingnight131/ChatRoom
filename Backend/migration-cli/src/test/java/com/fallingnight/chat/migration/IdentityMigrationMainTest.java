package com.fallingnight.chat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityMigrationMainTest {
    @TempDir
    Path temporary;

    @Test
    void backupCreatesReconciledArtifactsAndNeverPrintsPathsOrCredentials() throws Exception {
        Path source = temporary.resolve("private-source.db");
        Path backup = temporary.resolve("protected-backup.db");
        Path proof = temporary.resolve("protected-proof.properties");
        createSource(source);
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        int result = IdentityMigrationMain.run(
                new String[] {"backup", source.toString(), backup.toString(), proof.toString()},
                Map.of(),
                new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errorBytes, true, StandardCharsets.UTF_8),
                Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC));

        assertEquals(0, result);
        assertTrue(Files.isRegularFile(backup));
        assertTrue(Files.isRegularFile(proof));
        String output = outputBytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("status=BACKUP_VERIFIED"));
        assertTrue(output.contains("identity_rows=1"));
        assertFalse(output.contains(source.toString()));
        assertFalse(output.contains("alice"));
        assertEquals("", errorBytes.toString(StandardCharsets.UTF_8));

        Path restored = temporary.resolve("isolated-restored-copy.db");
        Files.copy(backup, restored);
        ByteArrayOutputStream restoreOutput = new ByteArrayOutputStream();
        int restoredResult = IdentityMigrationMain.run(
                new String[] {"verify-backup", restored.toString(), proof.toString()},
                Map.of(),
                new PrintStream(restoreOutput, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC());
        assertEquals(0, restoredResult);
        assertTrue(restoreOutput.toString(StandardCharsets.UTF_8)
                .contains("status=BACKUP_VERIFIED"));

        String fingerprint = output.lines()
                .filter(line -> line.startsWith("source_fingerprint_sha256="))
                .findFirst().orElseThrow().substring("source_fingerprint_sha256=".length());
        ByteArrayOutputStream finalOutput = new ByteArrayOutputStream();
        assertEquals(0, IdentityMigrationMain.run(
                new String[] {"verify-final", source.toString(), restored.toString(),
                    proof.toString(), fingerprint},
                Map.of(),
                new PrintStream(finalOutput, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC()));
        assertTrue(finalOutput.toString(StandardCharsets.UTF_8)
                .contains("status=FINAL_INPUT_VERIFIED"));

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO users VALUES (2, 'bob', 'Bob', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA', '', "
                    + "'2026-01-02 03:04:06')");
        }
        assertEquals(70, IdentityMigrationMain.run(
                new String[] {"verify-final", source.toString(), restored.toString(),
                    proof.toString(), fingerprint},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC()));

        int retry = IdentityMigrationMain.run(
                new String[] {"backup", source.toString(), backup.toString(), proof.toString()},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC());
        assertEquals(70, retry);
    }

    @Test
    void defaultsToUsageAndRejectsApplyBeforeDatabaseAccess() throws Exception {
        ByteArrayOutputStream usage = new ByteArrayOutputStream();
        assertEquals(64, IdentityMigrationMain.run(
                new String[0],
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(usage),
                Clock.systemUTC()));
        assertTrue(usage.toString(StandardCharsets.UTF_8).contains("backup"));

        Path proof = temporary.resolve("invalid.properties");
        Files.writeString(proof, "not-a-proof", StandardCharsets.UTF_8);
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        assertEquals(70, IdentityMigrationMain.run(
                new String[] {"apply", "source", "backup", proof.toString(), "bad"},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(error),
                Clock.systemUTC()));
        assertEquals(
                "status=FAILED\nreason=migration_operation_failed\n",
                error.toString(StandardCharsets.UTF_8));

        Path oversized = temporary.resolve("oversized.properties");
        Files.writeString(oversized, "x".repeat(4097), StandardCharsets.UTF_8);
        assertEquals(70, IdentityMigrationMain.run(
                new String[] {"verify-backup", "backup", oversized.toString()},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC()));
    }

    @Test
    void verifiesConversationFinalInputWithoutDatabaseOrSensitiveOutput() throws Exception {
        Path source = temporary.resolve("conversation-private-source.db");
        Path backup = temporary.resolve("conversation-protected-backup.db");
        Path proof = temporary.resolve("conversation-proof.properties");
        createConversationSource(source);
        ByteArrayOutputStream backupOutput = new ByteArrayOutputStream();
        assertEquals(0, IdentityMigrationMain.run(
                new String[] {"backup", source.toString(), backup.toString(), proof.toString()},
                Map.of(),
                new PrintStream(backupOutput, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC)));

        var conversationPlan = new com.fallingnight.chat.persistence.postgres.migration
                .V1SqliteConversationSource(source).readPlan();
        ByteArrayOutputStream verifiedOutput = new ByteArrayOutputStream();
        assertEquals(0, IdentityMigrationMain.run(
                new String[] {"conversation-verify-final", source.toString(),
                    backup.toString(), proof.toString(),
                    conversationPlan.sourceFingerprintSha256()},
                Map.of(),
                new PrintStream(verifiedOutput, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC()));
        String output = verifiedOutput.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("status=CONVERSATION_FINAL_INPUT_VERIFIED"));
        assertTrue(output.contains("source_conversations=2"));
        assertTrue(output.contains("source_memberships=4"));
        assertFalse(output.contains(source.toString()));
        assertFalse(output.contains("private-room"));

        assertEquals(70, IdentityMigrationMain.run(
                new String[] {"conversation-verify-final", source.toString(),
                    backup.toString(), proof.toString(), "0".repeat(64)},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC()));
    }

    private static void createSource(Path source) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, "
                    + "username TEXT UNIQUE NOT NULL, display_name TEXT, "
                    + "password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at TEXT)");
            statement.execute("INSERT INTO users VALUES (1, 'alice', 'Alice', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA', '', "
                    + "'2026-01-02 03:04:05')");
        }
    }

    private static void createConversationSource(Path source) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT UNIQUE, "
                    + "display_name TEXT, password_hash TEXT, salt TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY, name TEXT, "
                    + "creator_id INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_members(room_id INTEGER, user_id INTEGER, "
                    + "joined_at TEXT, last_read_msg_id INTEGER, PRIMARY KEY(room_id, user_id))");
            statement.execute("CREATE TABLE room_admins(room_id INTEGER, user_id INTEGER, "
                    + "PRIMARY KEY(room_id, user_id))");
            statement.execute("CREATE TABLE friendships(id INTEGER PRIMARY KEY, user_id1 INTEGER, "
                    + "user_id2 INTEGER, created_at TEXT, user1_last_read_msg_id INTEGER, "
                    + "user2_last_read_msg_id INTEGER)");
            statement.execute("INSERT INTO users VALUES "
                    + "(1, 'operator-a', 'Operator A', '" + "a".repeat(64)
                    + "', 'salt-a', '2026-01-02 03:04:05'), "
                    + "(2, 'operator-b', 'Operator B', '" + "b".repeat(64)
                    + "', 'salt-b', '2026-01-02 03:04:06')");
            statement.execute("INSERT INTO rooms VALUES "
                    + "(10, 'private-room', 1, '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(10, 1, '2026-01-02 03:04:05', 0), "
                    + "(10, 2, '2026-01-02 03:04:06', 0)");
            statement.execute("INSERT INTO friendships VALUES "
                    + "(20, 1, 2, '2026-01-02 03:04:07', 0, 0)");
        }
    }
}
