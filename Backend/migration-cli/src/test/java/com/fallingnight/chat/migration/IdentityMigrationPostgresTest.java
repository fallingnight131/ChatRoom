package com.fallingnight.chat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityMigrationPostgresTest {
    private static final String URL = System.getenv("CHATROOM_TEST_POSTGRES_URL");
    private static final String USER = System.getenv("CHATROOM_TEST_POSTGRES_USER");
    private static final String PASSWORD = System.getenv("CHATROOM_TEST_POSTGRES_PASSWORD");

    @TempDir
    Path temporary;

    @Test
    void runsBackupPreviewAndExplicitApplyAgainstRealPostgres() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(),
                "set CHATROOM_TEST_POSTGRES_URL to run migration command tests");
        new PostgresMigrator(URL, USER, PASSWORD).migrate();
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE chat.account, chat.conversation, "
                    + "chat.identity_import_run, chat.conversation_import_run, "
                    + "chat.message_import_run CASCADE");
        }

        Path source = temporary.resolve("source.db");
        Path backup = temporary.resolve("backup.db");
        Path proof = temporary.resolve("proof.properties");
        createSource(source);
        CommandResult backupResult = run(
                new String[] {"backup", source.toString(), backup.toString(), proof.toString()},
                Map.of());
        assertEquals(0, backupResult.status());
        String fingerprint = value(backupResult.output(), "source_fingerprint_sha256");

        Map<String, String> database = Map.of(
                "CHATROOM_MIGRATION_POSTGRES_URL", URL,
                "CHATROOM_MIGRATION_POSTGRES_USER", USER,
                "CHATROOM_MIGRATION_POSTGRES_PASSWORD", PASSWORD);
        CommandResult preview = run(
                new String[] {"preview", source.toString()}, database);
        assertEquals(0, preview.status());
        assertTrue(preview.output().contains("status=READY"));
        assertEquals(0, count("chat.account"));

        CommandResult applied = run(new String[] {
                "apply", source.toString(), backup.toString(), proof.toString(), fingerprint
        }, database);
        assertEquals(0, applied.status());
        assertTrue(applied.output().contains("status=APPLIED"));
        assertTrue(applied.output().contains("inserted_rows=1"));
        assertFalse(applied.output().contains(source.toString()));
        assertEquals(1, count("chat.account"));
        assertEquals(1, count("chat.legacy_v1_account_map"));
        assertEquals(1, count("chat.identity_import_run"));

        CommandResult repeated = run(new String[] {
                "apply", source.toString(), backup.toString(), proof.toString(), fingerprint
        }, database);
        assertEquals(0, repeated.status());
        assertTrue(repeated.output().contains("inserted_rows=0"));
        assertEquals(1, count("chat.account"));
        assertEquals(1, count("chat.legacy_v1_account_map"));
        assertEquals(2, count("chat.identity_import_run"));

        CommandResult conversationPreview = run(
                new String[] {"conversation-preview", source.toString()}, database);
        assertEquals(0, conversationPreview.status());
        String conversationFingerprint = value(
                conversationPreview.output(), "conversation_fingerprint_sha256");
        CommandResult conversationApplied = run(new String[] {
                "conversation-apply", source.toString(), backup.toString(), proof.toString(),
                conversationFingerprint
        }, database);
        assertEquals(0, conversationApplied.status());

        CommandResult messagePreview = run(new String[] {
                "message-preview", source.toString(), backup.toString(), proof.toString()
        }, database);
        assertEquals(0, messagePreview.status(), messagePreview.error());
        assertTrue(messagePreview.output().contains("status=READY"));
        assertFalse(messagePreview.output().contains("private message"));
        String stateFingerprint = value(
                messagePreview.output(), "message_state_fingerprint_sha256");
        String payloadFingerprint = value(
                messagePreview.output(), "message_payload_fingerprint_sha256");

        CommandResult messageApplied = run(new String[] {
                "message-apply", source.toString(), backup.toString(), proof.toString(),
                stateFingerprint, payloadFingerprint
        }, database);
        assertEquals(0, messageApplied.status(), messageApplied.error());
        assertTrue(messageApplied.output().contains("status=APPLIED"));
        assertTrue(messageApplied.output().contains("insertable_messages=1"));
        assertFalse(messageApplied.output().contains(source.toString()));
        assertFalse(messageApplied.output().contains("private message"));
        assertEquals(1, count("chat.message"));
        assertEquals(1, count("chat.legacy_v1_message_map"));
        assertEquals(1, count("chat.message_import_run"));

        CommandResult messageRepeated = run(new String[] {
                "message-apply", source.toString(), backup.toString(), proof.toString(),
                stateFingerprint, payloadFingerprint
        }, database);
        assertEquals(0, messageRepeated.status(), messageRepeated.error());
        assertTrue(messageRepeated.output().contains("insertable_messages=0"));
        assertEquals(1, count("chat.message"));
        assertEquals(2, count("chat.message_import_run"));
    }

    private static CommandResult run(String[] args, Map<String, String> environment) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = IdentityMigrationMain.run(
                args,
                environment,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        return new CommandResult(
                status,
                output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private static String value(String output, String key) {
        return output.lines()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1))
                .findFirst()
                .orElseThrow();
    }

    private static int count(String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void createSource(Path source) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, "
                    + "username TEXT UNIQUE NOT NULL, display_name TEXT, "
                    + "password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at TEXT)");
            statement.execute("INSERT INTO users VALUES (1, 'operator-test', 'Operator Test', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA', '', "
                    + "'2026-01-02 03:04:05')");
            statement.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY, name TEXT, "
                    + "creator_id INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_members(room_id INTEGER, user_id INTEGER, "
                    + "joined_at TEXT, last_read_msg_id INTEGER)");
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
            statement.execute("INSERT INTO rooms VALUES "
                    + "(77, 'Private Room', 1, '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(77, 1, '2026-01-02 03:04:05', 100)");
            statement.execute("INSERT INTO messages VALUES "
                    + "(100, 77, 1, 'private message', 'text', '', 0, 0, 0, '', '', "
                    + "0, 1, NULL, '2026-01-02 03:04:06')");
            statement.execute("INSERT INTO room_message_sequences VALUES (77, 1)");
        }
    }

    private record CommandResult(int status, String output, String error) {}
}
