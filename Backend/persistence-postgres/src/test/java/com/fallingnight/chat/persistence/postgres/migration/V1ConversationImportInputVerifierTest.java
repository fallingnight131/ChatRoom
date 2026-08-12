package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1ConversationImportInputVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void verifiesConversationPlanAgainstSameWholeFileBackupProof() throws Exception {
        Path source = source("verified.db");
        Path backup = temporary.resolve("verified-backup.db");
        VerifiedV1IdentityBackup proof = backup(source, backup);

        VerifiedV1ConversationImportInput input =
                new V1ConversationImportInputVerifier().verify(source, backup, proof);

        assertEquals(1, input.plan().sourceRooms());
        assertEquals(1, input.plan().sourceFriendships());
        assertEquals(proof, input.backupProof());
    }

    @Test
    void rejectsConversationDriftAndPhysicalBackupMismatch() throws Exception {
        Path source = source("drift.db");
        Path backup = temporary.resolve("drift-backup.db");
        VerifiedV1IdentityBackup proof = backup(source, backup);
        try (Connection connection = connect(source); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rooms SET name = 'Changed After Backup' WHERE id = 10");
        }
        assertEquals(
                "V1 conversation source and backup do not reconcile",
                assertThrows(V1ConversationSourceException.class,
                        () -> new V1ConversationImportInputVerifier()
                                .verify(source, backup, proof)).getMessage());

        VerifiedV1IdentityBackup wrongHash = new VerifiedV1IdentityBackup(
                proof.sourceFingerprintSha256(),
                "0".repeat(64),
                proof.identityRows(),
                proof.backupBytes(),
                proof.createdAt());
        assertEquals(
                "V1 conversation backup artifact does not match its proof",
                assertThrows(V1ConversationSourceException.class,
                        () -> new V1ConversationImportInputVerifier()
                                .verify(backup, backup, wrongHash)).getMessage());
    }

    private Path source(String name) throws Exception {
        Path source = temporary.resolve(name);
        try (Connection connection = connect(source); Statement statement = connection.createStatement()) {
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
                    + "(1, 'alice', 'Alice', '" + "a".repeat(64)
                    + "', 'salt-a', '2026-01-02 03:04:05'), "
                    + "(2, 'bob', 'Bob', '" + "b".repeat(64)
                    + "', 'salt-b', '2026-01-02 03:04:06')");
            statement.execute("INSERT INTO rooms VALUES "
                    + "(10, 'Room', 1, '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_settings(room_id, max_members) VALUES (10, 91)");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(10, 1, '2026-01-02 03:04:05', 0)");
            statement.execute("INSERT INTO friendships VALUES "
                    + "(20, 1, 2, '2026-01-02 03:04:06', 0, 0)");
        }
        return source;
    }

    private VerifiedV1IdentityBackup backup(Path source, Path destination) {
        return new V1SqliteIdentityBackup(Clock.fixed(NOW, ZoneOffset.UTC))
                .createVerified(source, destination);
    }

    private static Connection connect(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }
}
