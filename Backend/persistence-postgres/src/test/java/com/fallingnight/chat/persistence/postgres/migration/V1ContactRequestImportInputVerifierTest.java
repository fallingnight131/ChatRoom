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

class V1ContactRequestImportInputVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void verifiesContactPlanAgainstSameWholeFileBackupProof() throws Exception {
        Path source = source("verified.db");
        Path backup = temporary.resolve("verified-backup.db");
        VerifiedV1IdentityBackup proof = backup(source, backup);

        VerifiedV1ContactRequestImportInput input =
                new V1ContactRequestImportInputVerifier().verify(source, backup, proof);

        assertEquals(2, input.plan().sourceRows());
        assertEquals(1, input.plan().sourcePendingRows());
        assertEquals(1, input.plan().sourceTerminalRows());
        assertEquals(proof, input.backupProof());
    }

    @Test
    void rejectsContactDriftAndPhysicalBackupMismatch() throws Exception {
        Path source = source("drift.db");
        Path backup = temporary.resolve("drift-backup.db");
        VerifiedV1IdentityBackup proof = backup(source, backup);
        try (Connection connection = connect(source); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE friend_requests SET to_user_id = 3 WHERE id = 10");
        }
        assertEquals(
                "V1 contact request source and backup do not reconcile",
                assertThrows(V1ContactRequestSourceException.class,
                        () -> new V1ContactRequestImportInputVerifier()
                                .verify(source, backup, proof)).getMessage());

        VerifiedV1IdentityBackup wrongHash = new VerifiedV1IdentityBackup(
                proof.sourceFingerprintSha256(),
                "0".repeat(64),
                proof.identityRows(),
                proof.backupBytes(),
                proof.createdAt());
        assertEquals(
                "V1 contact request backup artifact does not match its proof",
                assertThrows(V1ContactRequestSourceException.class,
                        () -> new V1ContactRequestImportInputVerifier()
                                .verify(backup, backup, wrongHash)).getMessage());
    }

    private Path source(String name) throws Exception {
        Path source = temporary.resolve(name);
        try (Connection connection = connect(source); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT UNIQUE, "
                    + "display_name TEXT, password_hash TEXT, salt TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE friendships(user_id1 INTEGER, user_id2 INTEGER)");
            statement.execute("CREATE TABLE friend_requests(id INTEGER PRIMARY KEY, "
                    + "from_user_id INTEGER, to_user_id INTEGER, status TEXT, created_at TEXT)");
            statement.execute("INSERT INTO users VALUES "
                    + "(1, 'alice', 'Alice', '" + "a".repeat(64)
                    + "', 'salt-a', '2026-01-02 03:04:05'), "
                    + "(2, 'bob', 'Bob', '" + "b".repeat(64)
                    + "', 'salt-b', '2026-01-02 03:04:06'), "
                    + "(3, 'carol', 'Carol', '" + "c".repeat(64)
                    + "', 'salt-c', '2026-01-02 03:04:07')");
            statement.execute("INSERT INTO friend_requests VALUES "
                    + "(10, 1, 2, 'pending', '2026-01-02 03:04:08'), "
                    + "(11, 2, 3, 'rejected', '2026-01-02 03:04:09')");
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
