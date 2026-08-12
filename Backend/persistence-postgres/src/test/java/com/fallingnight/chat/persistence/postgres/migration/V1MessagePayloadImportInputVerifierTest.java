package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1MessagePayloadImportInputVerifierTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesFullPayloadFingerprintAndDetectsNonRenderedMetadataDrift() throws Exception {
        Path source = temporary.resolve("source.db");
        Path backup = temporary.resolve("backup.db");
        V1SqliteMessagePayloadSourceTest.createSchema(source, true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO messages VALUES "
                    + "(10, 5, 'hello', 'text', '', 0, 0, 0, '', '', 0)");
        }
        Files.copy(source, backup);
        VerifiedV1IdentityBackup proof = proof(backup);

        VerifiedV1MessagePayloadImportInput input =
                new V1MessagePayloadImportInputVerifier().verify(source, backup, proof);
        String before = input.plan().sourceFingerprintSha256();
        assertEquals(64, before.length());
        assertEquals(1, input.plan().sourceRows());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE messages SET thumbnail = 'changed-but-not-rendered' "
                    + "WHERE id = 10");
        }
        String after = new V1SqliteMessagePayloadSource(source)
                .readPlan().sourceFingerprintSha256();
        assertNotEquals(before, after);
        assertEquals(
                "V1 message payload source and backup do not reconcile",
                assertThrows(V1MessagePayloadSourceException.class,
                        () -> new V1MessagePayloadImportInputVerifier()
                                .verify(source, backup, proof)).getMessage());
    }

    @Test
    void rejectsPhysicalBackupProofMismatch() throws Exception {
        Path source = temporary.resolve("physical.db");
        Path backup = temporary.resolve("physical-backup.db");
        V1SqliteMessagePayloadSourceTest.createSchema(source, true);
        Files.copy(source, backup);
        VerifiedV1IdentityBackup proof = proof(backup);
        VerifiedV1IdentityBackup wrong = new VerifiedV1IdentityBackup(
                proof.sourceFingerprintSha256(), "0".repeat(64), proof.identityRows(),
                proof.backupBytes(), proof.createdAt());

        assertEquals(
                "V1 message payload backup artifact does not match its proof",
                assertThrows(V1MessagePayloadSourceException.class,
                        () -> new V1MessagePayloadImportInputVerifier()
                                .verify(source, backup, wrong)).getMessage());
    }

    private static VerifiedV1IdentityBackup proof(Path backup) throws Exception {
        return new VerifiedV1IdentityBackup(
                "0".repeat(64),
                V1SqliteIdentityBackup.sha256(backup),
                1,
                Files.size(backup),
                Instant.parse("2026-08-12T12:00:00Z"));
    }
}
