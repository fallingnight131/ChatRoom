package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1SqliteIdentityBackupTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void createsWalConsistentBackupAndReconcilesIdentityPlan() throws Exception {
        Path source = temporary.resolve("source.db");
        Path backup = temporary.resolve("verified-backup.db");
        try (Connection connection = currentSource(source, true);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO users VALUES (1, 'alice', 'Alice', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA', '', "
                    + "'2026-01-02 03:04:05')");

            VerifiedV1IdentityBackup proof = backupService().createVerified(source, backup);

            assertTrue(Files.isRegularFile(backup));
            assertEquals(NOW, proof.createdAt());
            assertEquals(1, proof.identityRows());
            assertEquals(Files.size(backup), proof.backupBytes());
            assertEquals(64, proof.backupFileSha256().length());
            V1IdentityImportPlan sourcePlan = new V1SqliteIdentitySource(source).readPlan();
            V1IdentityImportPlan backupPlan = new V1SqliteIdentitySource(backup).readPlan();
            assertEquals(sourcePlan, backupPlan);
            assertEquals(sourcePlan.sourceFingerprintSha256(),
                    proof.sourceFingerprintSha256());
        }
    }

    @Test
    void neverOverwritesDestinationOrVerifiesAnInvalidSource() throws Exception {
        Path source = temporary.resolve("source.db");
        try (Connection connection = currentSource(source, false)) {
            // Empty current schema is deliberately not an importable identity source.
            assertFalse(connection.isClosed());
        }
        Path existing = temporary.resolve("existing.db");
        byte[] original = new byte[] {1, 2, 3};
        Files.write(existing, original);

        assertThrows(V1IdentitySourceException.class,
                () -> backupService().createVerified(source, existing));
        assertArrayEquals(original, Files.readAllBytes(existing));

        Path absent = temporary.resolve("must-not-exist.db");
        V1IdentitySourceException invalid = assertThrows(
                V1IdentitySourceException.class,
                () -> backupService().createVerified(source, absent));
        assertEquals("V1 identity source must pass planning before backup",
                invalid.getMessage());
        assertFalse(Files.exists(absent));
    }

    private V1SqliteIdentityBackup backupService() {
        return new V1SqliteIdentityBackup(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Connection currentSource(Path database, boolean wal) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            if (wal) {
                statement.execute("PRAGMA journal_mode = WAL");
            }
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, "
                    + "username TEXT UNIQUE NOT NULL, display_name TEXT, "
                    + "password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at TEXT)");
        }
        return connection;
    }
}
