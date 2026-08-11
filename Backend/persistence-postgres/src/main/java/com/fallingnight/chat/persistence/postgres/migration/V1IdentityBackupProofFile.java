package com.fallingnight.chat.persistence.postgres.migration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Strict versioned file codec for the non-secret backup proof artifact. */
public final class V1IdentityBackupProofFile {
    private static final long MAX_PROOF_BYTES = 4096;
    private static final String FORMAT = "chat-room-v1-identity-backup-proof-v1";
    private static final Set<String> KEYS = Set.of(
            "format",
            "source_fingerprint_sha256",
            "backup_file_sha256",
            "identity_rows",
            "backup_bytes",
            "created_at");

    public void writeNew(Path destination, VerifiedV1IdentityBackup proof) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(proof, "proof");
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent) || Files.exists(absolute)) {
            throw new V1IdentitySourceException(
                    "backup proof destination must be a new file in an existing directory");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".v1-identity-proof-", ".properties");
            Properties properties = properties(proof);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Chat Room V1 identity backup proof; do not edit");
            }
            if (!proof.equals(read(temporary))) {
                throw new V1IdentitySourceException("backup proof file reconciliation failed");
            }
            moveNew(temporary, absolute);
            temporary = null;
        } catch (IOException exception) {
            throw new V1IdentitySourceException("backup proof file write failed", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // An incomplete randomized file is never returned as a proof.
                }
            }
        }
    }

    public VerifiedV1IdentityBackup read(Path source) {
        Objects.requireNonNull(source, "source");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(source)) {
            byte[] encoded = input.readNBytes((int) MAX_PROOF_BYTES + 1);
            if (encoded.length == 0 || encoded.length > MAX_PROOF_BYTES) {
                throw new V1IdentitySourceException(
                        "backup proof file size is invalid");
            }
            properties.load(new ByteArrayInputStream(encoded));
        } catch (IOException exception) {
            throw new V1IdentitySourceException("backup proof file is not readable", exception);
        }
        if (!properties.stringPropertyNames().equals(KEYS)
                || !FORMAT.equals(properties.getProperty("format"))) {
            throw new V1IdentitySourceException("backup proof file format is unsupported");
        }
        try {
            return new VerifiedV1IdentityBackup(
                    properties.getProperty("source_fingerprint_sha256"),
                    properties.getProperty("backup_file_sha256"),
                    Integer.parseInt(properties.getProperty("identity_rows")),
                    Long.parseLong(properties.getProperty("backup_bytes")),
                    Instant.parse(properties.getProperty("created_at")));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new V1IdentitySourceException("backup proof file fields are invalid", exception);
        }
    }

    private static Properties properties(VerifiedV1IdentityBackup proof) {
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty("source_fingerprint_sha256", proof.sourceFingerprintSha256());
        properties.setProperty("backup_file_sha256", proof.backupFileSha256());
        properties.setProperty("identity_rows", Integer.toString(proof.identityRows()));
        properties.setProperty("backup_bytes", Long.toString(proof.backupBytes()));
        properties.setProperty("created_at", proof.createdAt().toString());
        return properties;
    }

    private static void moveNew(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }
}
