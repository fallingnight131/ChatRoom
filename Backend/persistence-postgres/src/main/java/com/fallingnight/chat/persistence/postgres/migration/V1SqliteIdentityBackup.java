package com.fallingnight.chat.persistence.postgres.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import org.sqlite.SQLiteConnection;

/** Creates and re-reads a WAL-consistent SQLite online backup before target apply. */
public final class V1SqliteIdentityBackup {
    private final Clock clock;

    public V1SqliteIdentityBackup(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public VerifiedV1IdentityBackup createVerified(Path source, Path destination) {
        Objects.requireNonNull(destination, "destination");
        V1IdentityImportPlan sourcePlan = new V1SqliteIdentitySource(source).readPlan();
        if (!sourcePlan.readyToCompareWithTarget()) {
            throw new V1IdentitySourceException(
                    "V1 identity source must pass planning before backup");
        }
        Path sourceReal = realFile(source);
        Path destinationAbsolute = destination.toAbsolutePath().normalize();
        Path parent = destinationAbsolute.getParent();
        if (parent == null || !Files.isDirectory(parent) || Files.exists(destinationAbsolute)) {
            throw new V1IdentitySourceException(
                    "backup destination must be a new file in an existing directory");
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".v1-identity-backup-", ".sqlite");
            onlineBackup(sourceReal, temporary);
            V1IdentityImportPlan backupPlan = new V1SqliteIdentitySource(temporary).readPlan();
            if (!sourcePlan.sourceFingerprintSha256().equals(
                            backupPlan.sourceFingerprintSha256())
                    || !sourcePlan.accounts().equals(backupPlan.accounts())
                    || !backupPlan.readyToCompareWithTarget()) {
                throw new V1IdentitySourceException(
                        "V1 backup identity reconciliation failed");
            }
            String backupHash = sha256(temporary);
            long backupBytes = Files.size(temporary);
            moveNew(temporary, destinationAbsolute);
            temporary = null;
            return new VerifiedV1IdentityBackup(
                    sourcePlan.sourceFingerprintSha256(),
                    backupHash,
                    sourcePlan.sourceRows(),
                    backupBytes,
                    clock.instant());
        } catch (IOException | SQLException exception) {
            throw new V1IdentitySourceException("V1 SQLite online backup failed", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The randomized incomplete file is never returned as verified.
                }
            }
        }
    }

    private static void onlineBackup(Path source, Path destination) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toUri() + "?mode=ro")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            int result = sqlite.getDatabase().backup("main", destination.toString(), null);
            if (result != 0) {
                throw new SQLException("SQLite online backup did not return SQLITE_OK");
            }
        }
    }

    private static void moveNew(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static Path realFile(Path value) {
        Objects.requireNonNull(value, "source");
        try {
            Path real = value.toRealPath();
            if (!Files.isRegularFile(real)) {
                throw new V1IdentitySourceException("V1 SQLite source must be a file");
            }
            return real;
        } catch (IOException exception) {
            throw new V1IdentitySourceException("V1 SQLite source is not readable", exception);
        }
    }

    private static String sha256(Path value) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(value)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
