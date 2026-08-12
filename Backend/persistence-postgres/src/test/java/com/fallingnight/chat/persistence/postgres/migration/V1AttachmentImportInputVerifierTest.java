package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1AttachmentImportInputVerifierTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesSourceBackupProofAndExactObjectManifestThenDetectsDrift() throws Exception {
        Path source = temporary.resolve("source.sqlite");
        createSource(source);
        Path backup = temporary.resolve("backup.sqlite");
        Files.copy(source, backup);
        V1AttachmentSourcePlan sourcePlan = new V1SqliteAttachmentSource(source).readPlan();
        PlannedV1AttachmentSource attachment = sourcePlan.attachments().getFirst();
        VerifiedV1IdentityBackup proof = proof(backup);
        V1AttachmentObjectEvidenceBundle evidence = new V1AttachmentObjectEvidenceBundle(
                sourcePlan.sourceFingerprintSha256(), List.of(new V1AttachmentObjectEvidence(
                        attachment.legacyKind(), attachment.legacyFileId(),
                        "attachments/" + attachment.attachmentId(), "application/pdf",
                        attachment.byteSize(), new byte[32],
                        attachment.fileCreatedAt().plusSeconds(2))));

        VerifiedV1AttachmentImportInput verified = new V1AttachmentImportInputVerifier()
                .verify(source, backup, proof, evidence);

        assertTrue(verified.plan().readyToCompareWithTarget());
        assertEquals(proof, verified.backupProof());
        verified.reverify();

        try (Connection connection = connect(source); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE files SET file_path = '/moved/private.pdf' WHERE id = 7");
        }
        V1AttachmentImportException changed = assertThrows(
                V1AttachmentImportException.class, verified::reverify);
        assertEquals("V1 attachment source and backup do not reconcile", changed.getMessage());
        assertFalse(changed.getMessage().contains(source.toString()));
    }

    @Test
    void rejectsWrongBackupProofAndStaleOrIncompleteEvidenceWithSafeErrors() throws Exception {
        Path source = temporary.resolve("source-fail.sqlite");
        createSource(source);
        Path backup = temporary.resolve("backup-fail.sqlite");
        Files.copy(source, backup);
        VerifiedV1IdentityBackup proof = proof(backup);
        V1AttachmentSourcePlan plan = new V1SqliteAttachmentSource(source).readPlan();

        VerifiedV1IdentityBackup wrong = new VerifiedV1IdentityBackup(
                proof.sourceFingerprintSha256(), "0".repeat(64), 1,
                proof.backupBytes(), proof.createdAt());
        V1AttachmentImportException badProof = assertThrows(
                V1AttachmentImportException.class,
                () -> new V1AttachmentImportInputVerifier().verify(
                        source, backup, wrong,
                        new V1AttachmentObjectEvidenceBundle(
                                plan.sourceFingerprintSha256(), List.of())));
        assertEquals("V1 attachment backup artifact does not match its proof",
                badProof.getMessage());

        V1AttachmentImportException missingEvidence = assertThrows(
                V1AttachmentImportException.class,
                () -> new V1AttachmentImportInputVerifier().verify(
                        source, backup, proof,
                        new V1AttachmentObjectEvidenceBundle(
                                plan.sourceFingerprintSha256(), List.of())));
        assertEquals("V1 attachment object evidence does not reconcile",
                missingEvidence.getMessage());
        assertFalse(missingEvidence.getMessage().contains("private.pdf"));
    }

    private static VerifiedV1IdentityBackup proof(Path backup) throws Exception {
        return new VerifiedV1IdentityBackup(
                "1".repeat(64), V1SqliteIdentityBackup.sha256(backup), 1,
                Files.size(backup), Instant.parse("2026-08-13T12:00:00Z"));
    }

    private static void createSource(Path database) throws Exception {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE files(id INTEGER PRIMARY KEY, room_id INTEGER, "
                    + "user_id INTEGER, file_name TEXT, file_path TEXT, file_size INTEGER, "
                    + "cleared INTEGER, clear_reason TEXT, cleared_at TEXT, created_at TEXT, "
                    + "cos_url TEXT)");
            statement.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY, room_id INTEGER, "
                    + "user_id INTEGER, content_type TEXT, file_name TEXT, file_size INTEGER, "
                    + "file_id INTEGER, file_cleared INTEGER, clear_reason TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE friend_files(id INTEGER PRIMARY KEY, "
                    + "friendship_id INTEGER, user_id INTEGER, file_name TEXT, file_path TEXT, "
                    + "file_size INTEGER, cleared INTEGER, clear_reason TEXT, cleared_at TEXT, "
                    + "created_at TEXT, cos_url TEXT)");
            statement.execute("CREATE TABLE friend_messages(id INTEGER PRIMARY KEY, "
                    + "friendship_id INTEGER, sender_id INTEGER, content_type TEXT, file_name TEXT, "
                    + "file_size INTEGER, file_id INTEGER, file_cleared INTEGER, clear_reason TEXT, "
                    + "created_at TEXT)");
            statement.execute("INSERT INTO files VALUES (7, 8, 9, 'private.pdf', "
                    + "'/private/private.pdf', 123, 0, '', NULL, "
                    + "'2026-01-02 03:04:05', 'https://legacy.invalid/secret')");
            statement.execute("INSERT INTO messages VALUES (10, 8, 9, 'file', "
                    + "'private.pdf', 123, 7, 0, '', '2026-01-02 03:04:06')");
        }
    }

    private static Connection connect(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }
}
