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

    @Test
    void verifiesContactFinalInputWithoutDatabaseOrSensitiveOutput() throws Exception {
        Path source = temporary.resolve("contact-private-source.db");
        Path backup = temporary.resolve("contact-protected-backup.db");
        Path proof = temporary.resolve("contact-proof.properties");
        createContactSource(source);
        assertEquals(0, IdentityMigrationMain.run(
                new String[] {"backup", source.toString(), backup.toString(), proof.toString()},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC)));

        var contactPlan = new com.fallingnight.chat.persistence.postgres.migration
                .V1SqliteContactRequestSource(source).readPlan();
        ByteArrayOutputStream verifiedOutput = new ByteArrayOutputStream();
        assertEquals(0, IdentityMigrationMain.run(
                new String[] {"contact-verify-final", source.toString(), backup.toString(),
                    proof.toString(), contactPlan.sourceFingerprint()},
                Map.of(),
                new PrintStream(verifiedOutput, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC()));

        String output = verifiedOutput.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("status=CONTACT_FINAL_INPUT_VERIFIED"));
        assertTrue(output.contains("source_requests=2"));
        assertTrue(output.contains("source_pending_requests=1"));
        assertTrue(output.contains("source_terminal_requests=1"));
        assertFalse(output.contains(source.toString()));
        assertFalse(output.contains("private-contact"));
        assertEquals(70, IdentityMigrationMain.run(
                new String[] {"contact-verify-final", source.toString(), backup.toString(),
                    proof.toString(), "0".repeat(64)},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC()));
    }

    @Test
    void verifiesComposedMessageInputWithoutDatabaseOrSensitiveOutput() throws Exception {
        Path source = temporary.resolve("message-private-source.db");
        Path backup = temporary.resolve("message-protected-backup.db");
        Path proof = temporary.resolve("message-proof.properties");
        createMessageSource(source);
        assertEquals(0, IdentityMigrationMain.run(
                new String[] {"backup", source.toString(), backup.toString(), proof.toString()},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC)));

        var backupProof = new com.fallingnight.chat.persistence.postgres.migration
                .V1IdentityBackupProofFile().read(proof);
        var state = new com.fallingnight.chat.persistence.postgres.migration
                .V1MessageStateImportInputVerifier().verify(source, backup, backupProof);
        var payload = new com.fallingnight.chat.persistence.postgres.migration
                .V1MessagePayloadImportInputVerifier().verify(source, backup, backupProof);
        ByteArrayOutputStream verifiedOutput = new ByteArrayOutputStream();

        assertEquals(0, IdentityMigrationMain.run(
                new String[] {"message-verify-final", source.toString(), backup.toString(),
                    proof.toString(), state.plan().sourceFingerprintSha256(),
                    payload.plan().sourceFingerprintSha256()},
                Map.of(),
                new PrintStream(verifiedOutput, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC()));

        String output = verifiedOutput.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("status=MESSAGE_FINAL_INPUT_VERIFIED"));
        assertTrue(output.contains("source_messages=1"));
        assertFalse(output.contains(source.toString()));
        assertFalse(output.contains("private message"));
        assertEquals(70, IdentityMigrationMain.run(
                new String[] {"message-verify-final", source.toString(), backup.toString(),
                    proof.toString(), "0".repeat(64),
                    payload.plan().sourceFingerprintSha256()},
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
            statement.execute("CREATE TABLE room_settings(room_id INTEGER PRIMARY KEY, "
                    + "max_file_size INTEGER DEFAULT 10737418240, "
                    + "total_file_space INTEGER DEFAULT 10737418240, "
                    + "max_file_count INTEGER DEFAULT 1500, max_members INTEGER)");
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
            statement.execute("INSERT INTO room_settings(room_id, max_members) VALUES (10, 71)");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(10, 1, '2026-01-02 03:04:05', 0), "
                    + "(10, 2, '2026-01-02 03:04:06', 0)");
            statement.execute("INSERT INTO friendships VALUES "
                    + "(20, 1, 2, '2026-01-02 03:04:07', 0, 0)");
        }
    }

    private static void createContactSource(Path source) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT UNIQUE, "
                    + "display_name TEXT, password_hash TEXT, salt TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE friendships(user_id1 INTEGER, user_id2 INTEGER)");
            statement.execute("CREATE TABLE friend_requests(id INTEGER PRIMARY KEY, "
                    + "from_user_id INTEGER, to_user_id INTEGER, status TEXT, created_at TEXT)");
            statement.execute("INSERT INTO users VALUES "
                    + "(1, 'private-contact-a', 'Private A', '" + "a".repeat(64)
                    + "', 'salt-a', '2026-01-02 03:04:01'), "
                    + "(2, 'private-contact-b', 'Private B', '" + "b".repeat(64)
                    + "', 'salt-b', '2026-01-02 03:04:02')");
            statement.execute("INSERT INTO friend_requests VALUES "
                    + "(10, 1, 2, 'pending', '2026-01-02 03:04:05'), "
                    + "(11, 2, 1, 'rejected', '2026-01-02 03:04:06')");
        }
    }

    private static void createMessageSource(Path source) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT UNIQUE, "
                    + "display_name TEXT, password_hash TEXT, salt TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY, name TEXT, "
                    + "creator_id INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_members(room_id INTEGER, user_id INTEGER, "
                    + "joined_at TEXT, last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE room_settings(room_id INTEGER PRIMARY KEY, "
                    + "max_file_size INTEGER DEFAULT 10737418240, "
                    + "total_file_space INTEGER DEFAULT 10737418240, "
                    + "max_file_count INTEGER DEFAULT 1500, max_members INTEGER)");
            statement.execute("CREATE TABLE room_admins(room_id INTEGER, user_id INTEGER)");
            statement.execute("CREATE TABLE friendships(id INTEGER PRIMARY KEY, user_id1 INTEGER, "
                    + "user_id2 INTEGER, created_at TEXT, user1_last_read_msg_id INTEGER, "
                    + "user2_last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY, room_id INTEGER, "
                    + "user_id INTEGER, content TEXT, content_type TEXT, file_name TEXT, "
                    + "file_size INTEGER, file_id INTEGER, file_cleared INTEGER, "
                    + "clear_reason TEXT, thumbnail TEXT, recalled INTEGER, sequence INTEGER, "
                    + "mutation_sequence INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_message_sequences(room_id INTEGER PRIMARY KEY, "
                    + "last_sequence INTEGER)");
            statement.execute("CREATE TABLE room_message_deletion_events(id INTEGER PRIMARY KEY, "
                    + "room_id INTEGER, operator_user_id INTEGER, operator_name TEXT, "
                    + "client_operation_id TEXT, command_fingerprint TEXT, mode TEXT, "
                    + "message_ids_json TEXT, file_ids_json TEXT, cutoff_ms INTEGER, "
                    + "deleted_count INTEGER, sequence INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE friend_messages(id INTEGER PRIMARY KEY, "
                    + "friendship_id INTEGER, sender_id INTEGER, content TEXT, content_type TEXT, "
                    + "file_name TEXT, file_size INTEGER, file_id INTEGER, file_cleared INTEGER, "
                    + "clear_reason TEXT, thumbnail TEXT, recalled INTEGER, sequence INTEGER, "
                    + "mutation_sequence INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE friendship_message_sequences("
                    + "friendship_id INTEGER PRIMARY KEY, last_sequence INTEGER)");
            statement.execute("INSERT INTO users VALUES (1, 'message-user', 'Message User', '"
                    + "a".repeat(64) + "', 'salt', '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO rooms VALUES "
                    + "(77, 'Private Room', 1, '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_settings(room_id, max_members) VALUES (77, 72)");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(77, 1, '2026-01-02 03:04:05', 100)");
            statement.execute("INSERT INTO messages VALUES "
                    + "(100, 77, 1, 'private message', 'text', '', 0, 0, 0, '', '', "
                    + "0, 1, NULL, '2026-01-02 03:04:06')");
            statement.execute("INSERT INTO room_message_sequences VALUES (77, 1)");
        }
    }
}
