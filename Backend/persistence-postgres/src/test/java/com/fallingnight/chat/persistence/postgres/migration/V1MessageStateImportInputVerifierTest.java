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

class V1MessageStateImportInputVerifierTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesExactMessageStateAgainstWholeFileBackup() throws Exception {
        Path source = temporary.resolve("source.db");
        Path backup = temporary.resolve("backup.db");
        V1SqliteMessageStateSourceTest.createSource(source, true);
        Files.copy(source, backup);
        VerifiedV1IdentityBackup proof = proof(backup);

        VerifiedV1MessageStateImportInput input =
                new V1MessageStateImportInputVerifier().verify(source, backup, proof);

        assertEquals(4, input.plan().sourceMessages());
        assertEquals(1, input.plan().sourceRoomDeletionEvents());
        assertEquals(64, input.plan().sourceFingerprintSha256().length());
        assertEquals(proof, input.backupProof());
    }

    @Test
    void detectsSequenceDriftEvenWhenTranslatedReadCursorIsUnchanged() throws Exception {
        Path source = temporary.resolve("drift.db");
        Path backup = temporary.resolve("drift-backup.db");
        V1SqliteMessageStateSourceTest.createSource(source, true);
        Files.copy(source, backup);
        VerifiedV1IdentityBackup proof = proof(backup);
        String before = new V1SqliteMessageStateSource(source)
                .readPlan().sourceFingerprintSha256();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE room_message_deletion_events "
                    + "SET operator_user_id = 2 WHERE id = 1");
        }
        String after = new V1SqliteMessageStateSource(source)
                .readPlan().sourceFingerprintSha256();

        assertNotEquals(before, after);
        assertEquals(
                "V1 message state source and backup do not reconcile",
                assertThrows(V1MessageStateSourceException.class,
                        () -> new V1MessageStateImportInputVerifier()
                                .verify(source, backup, proof)).getMessage());
    }

    @Test
    void rejectsBackupWhosePhysicalProofDoesNotMatch() throws Exception {
        Path source = temporary.resolve("physical.db");
        Path backup = temporary.resolve("physical-backup.db");
        V1SqliteMessageStateSourceTest.createSource(source, true);
        Files.copy(source, backup);
        VerifiedV1IdentityBackup proof = proof(backup);
        VerifiedV1IdentityBackup wrongProof = new VerifiedV1IdentityBackup(
                proof.sourceFingerprintSha256(), "0".repeat(64), proof.identityRows(),
                proof.backupBytes(), proof.createdAt());

        assertEquals(
                "V1 message state backup artifact does not match its proof",
                assertThrows(V1MessageStateSourceException.class,
                        () -> new V1MessageStateImportInputVerifier()
                                .verify(source, backup, wrongProof)).getMessage());
    }

    private static VerifiedV1IdentityBackup proof(Path backup) throws Exception {
        return new VerifiedV1IdentityBackup(
                "0".repeat(64),
                V1SqliteIdentityBackup.sha256(backup),
                3,
                Files.size(backup),
                Instant.parse("2026-08-12T12:00:00Z"));
    }
}
