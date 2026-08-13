package com.fallingnight.chat.migration.profile;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.persistence.postgres.migration.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class V1ProfileImageExporterTest {
    @TempDir Path temporary;

    @Test void exportsDeterministicCanonicalObjectsAndExplicitAbsenceInvalidEvidence()
            throws Exception {
        Path backup = temporary.resolve("backup.sqlite"); byte[] png = png();
        createFixture(backup, png, true);
        VerifiedV1IdentityBackup proof = proof(backup);

        V1ProfileImageExportReport first = new V1ProfileImageExporter().export(
                backup, proof, temporary.resolve("export-one"));
        V1ProfileImageExportReport second = new V1ProfileImageExporter().export(
                backup, proof, temporary.resolve("export-two"));

        assertEquals(5, first.entries()); assertEquals(2, first.present());
        assertEquals(2, first.absent()); assertEquals(1, first.invalid());
        assertEquals(1, first.uniqueObjects()); assertFalse(first.readyToImport());
        assertEquals(first.manifestSha256(), second.manifestSha256());
        byte[] firstManifest = Files.readAllBytes(
                first.destination().resolve(V1ProfileImageExporter.MANIFEST_NAME));
        assertArrayEquals(firstManifest, Files.readAllBytes(
                second.destination().resolve(V1ProfileImageExporter.MANIFEST_NAME)));
        String manifest = new String(firstManifest, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(manifest.contains("ACCOUNT\t1\tPRESENT\t-\tavatars/sha256/"));
        assertTrue(manifest.contains("ACCOUNT\t2\tABSENT\tNO_ROW"));
        assertTrue(manifest.contains("ROOM\t10\tINVALID\tINVALID_IMAGE"));
        assertTrue(manifest.contains("ROOM\t11\tABSENT\tEMPTY"));
        try (var objects = Files.walk(first.destination().resolve("objects"))) {
            Path object = objects.filter(Files::isRegularFile).findFirst().orElseThrow();
            assertTrue(object.toString().endsWith(".png"));
            assertTrue(Files.size(object) > 0);
        }
        assertThrows(V1ProfileImageExportException.class,
                () -> new V1ProfileImageExporter().export(
                        backup, proof, first.destination()));
    }

    @Test void recordsOversizedBlobWithoutSendingItToImageDecoder() throws Exception {
        Path backup = temporary.resolve("oversized.sqlite");
        createFixture(backup, png(), false);
        byte[] oversized = new byte[256 * 1024 + 1];
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO user_avatars VALUES (1, ?, '2026-01-01 00:00:00')")) {
            statement.setBytes(1, oversized); statement.executeUpdate();
        }
        var report = new V1ProfileImageExporter().export(
                backup, proof(backup), temporary.resolve("oversized-export"));
        assertEquals(1, report.invalid()); assertEquals(0, report.uniqueObjects());
        String manifest = Files.readString(
                report.destination().resolve(V1ProfileImageExporter.MANIFEST_NAME));
        assertTrue(manifest.contains("ACCOUNT\t1\tINVALID\tOVERSIZED"));
    }

    @Test void missingAvatarSchemaFailsWithoutPublishingPartialDirectory() throws Exception {
        Path backup = temporary.resolve("missing.sqlite"); createFixture(backup, png(), false);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE room_avatars");
        }
        Path destination = temporary.resolve("must-not-exist");
        assertThrows(V1ProfileImageExportException.class,
                () -> new V1ProfileImageExporter().export(backup, proof(backup), destination));
        assertFalse(Files.exists(destination));
    }

    @Test void refusesMutableWalSidecarEvenWhenMainBackupHashStillMatches() throws Exception {
        Path backup = temporary.resolve("sidecar.sqlite"); createFixture(backup, png(), false);
        VerifiedV1IdentityBackup proof = proof(backup);
        Files.write(Path.of(backup.toString() + "-wal"), new byte[] {1},
                StandardOpenOption.CREATE_NEW);
        Path destination = temporary.resolve("sidecar-export");
        assertThrows(V1ProfileImageExportException.class,
                () -> new V1ProfileImageExporter().export(backup, proof, destination));
        assertFalse(Files.exists(destination));
    }

    @Test void verifierRechecksManifestCanonicalObjectsAndExactTree() throws Exception {
        Path backup = temporary.resolve("verified.sqlite"); createFixture(backup, png(), true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM room_avatars WHERE room_id = 10");
        }
        VerifiedV1IdentityBackup proof = proof(backup);
        var report = new V1ProfileImageExporter().export(
                backup, proof, temporary.resolve("verified-export"));
        assertTrue(report.readyToImport());

        var verified = new V1ProfileImageExportVerifier().verify(
                report.destination(), proof, report.manifestSha256());
        assertEquals(report.entries(), verified.entries().size());
        assertEquals(1, verified.uniqueObjects());
        assertEquals(2, verified.entries().stream().filter(
                VerifiedV1ProfileImageExport.Entry::present).count());

        java.util.concurrent.atomic.AtomicInteger uploads =
                new java.util.concurrent.atomic.AtomicInteger();
        var uploaded = new V1ProfileImageObjectUploader(image -> {
            uploads.incrementAndGet();
            byte[] digest = image.contentSha256();
            return new com.fallingnight.chat.application.profile.ProfileImageObjectWriteResult(
                    new com.fallingnight.chat.application.profile.ProfileImageObjectEvidence(
                            com.fallingnight.chat.application.profile.ProfileImageObjectEvidence
                                    .objectKey(digest),
                            image.pngBytes().length, digest, "image/png"), true);
        }).upload(verified);
        assertEquals(1, uploads.get());
        assertEquals(1, uploaded.uniqueObjects()); assertEquals(1, uploaded.created());
        assertEquals(verified.manifestSha256(),
                uploaded.input().plan().manifestSha256());
        var independentlyReverified = new V1ProfileImageExportVerifier().verify(
                report.destination(), proof, report.manifestSha256());
        assertEquals(verified.manifestSha256(), independentlyReverified.manifestSha256());
        assertEquals(verified.entries().size(), independentlyReverified.entries().size());

        Files.writeString(report.destination().resolve("unexpected.txt"), "unexpected");
        assertThrows(V1ProfileImageExportException.class,
                () -> new V1ProfileImageExportVerifier().verify(
                        report.destination(), proof, report.manifestSha256()));
    }

    @Test void verifierRejectsObjectTamperingEvenWithUntouchedManifest() throws Exception {
        Path backup = temporary.resolve("tampered.sqlite"); createFixture(backup, png(), true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM room_avatars WHERE room_id = 10");
        }
        VerifiedV1IdentityBackup proof = proof(backup);
        var report = new V1ProfileImageExporter().export(
                backup, proof, temporary.resolve("tampered-export"));
        Path object;
        try (var paths = Files.walk(report.destination().resolve("objects"))) {
            object = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(object); bytes[bytes.length - 1] ^= 1;
        Files.write(object, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(V1ProfileImageExportException.class,
                () -> new V1ProfileImageExportVerifier().verify(
                        report.destination(), proof, report.manifestSha256()));
    }

    @Test void verifierRejectsInvalidEvidenceBundle() throws Exception {
        Path backup = temporary.resolve("invalid-bundle.sqlite");
        createFixture(backup, png(), true);
        VerifiedV1IdentityBackup proof = proof(backup);
        var report = new V1ProfileImageExporter().export(
                backup, proof, temporary.resolve("invalid-bundle-export"));
        assertFalse(report.readyToImport());
        assertThrows(V1ProfileImageExportException.class,
                () -> new V1ProfileImageExportVerifier().verify(
                        report.destination(), proof, report.manifestSha256()));
    }

    private static void createFixture(Path database, byte[] png, boolean populate)
            throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT, "
                    + "display_name TEXT, password_hash TEXT, salt TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE user_avatars(user_id INTEGER PRIMARY KEY, "
                    + "avatar_data BLOB, updated_at TEXT)");
            statement.execute("CREATE TABLE room_avatars(room_id INTEGER PRIMARY KEY, "
                    + "avatar_data BLOB, updated_at TEXT)");
            int users = populate ? 3 : 1;
            for (int id = 1; id <= users; id++)
                statement.execute("INSERT INTO users VALUES (" + id + ", 'user" + id
                        + "', 'User " + id + "', '" + "a".repeat(64)
                        + "', 'salt', '2026-01-01 00:00:00')");
            if (populate) {
                statement.execute("INSERT INTO rooms VALUES (10), (11)");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO user_avatars VALUES (?, ?, '2026-01-02 03:04:05')")) {
                    insert.setInt(1, 1); insert.setBytes(2, png); insert.executeUpdate();
                    insert.setInt(1, 3); insert.setBytes(2, png); insert.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO room_avatars VALUES (?, ?, '2026-01-02 03:04:05')")) {
                    insert.setInt(1, 10); insert.setBytes(2, new byte[] {1, 2, 3});
                    insert.executeUpdate();
                    insert.setInt(1, 11); insert.setBytes(2, new byte[0]); insert.executeUpdate();
                }
            }
        }
    }

    private static VerifiedV1IdentityBackup proof(Path database) throws Exception {
        V1IdentityImportPlan plan = new V1SqliteIdentitySource(database).readPlan();
        return new VerifiedV1IdentityBackup(plan.sourceFingerprintSha256(), sha256(database),
                plan.sourceRows(), Files.size(database), Instant.parse("2026-08-13T00:00:00Z"));
    }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; )
                if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static byte[] png() throws Exception {
        var image = new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xff336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output)); return output.toByteArray();
    }
}
