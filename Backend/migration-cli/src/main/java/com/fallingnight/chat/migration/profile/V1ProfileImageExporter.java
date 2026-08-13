package com.fallingnight.chat.migration.profile;

import com.fallingnight.chat.application.profile.*;
import com.fallingnight.chat.persistence.postgres.migration.*;
import com.fallingnight.chat.profile.imageio.BoundedPngProfileImageInspector;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Deterministic offline export from one proof-bound SQLite backup. */
public final class V1ProfileImageExporter {
    public static final String MANIFEST_NAME = "profile-images.tsv";
    private static final String FORMAT = "chat-room-v1-profile-image-export-v1";
    private static final int MAX_ENTRIES = 2_000_000;
    private static final DateTimeFormatter SQLITE_UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private final ProfileImageInspectionPort inspector;

    public V1ProfileImageExporter() { this(new BoundedPngProfileImageInspector()); }
    V1ProfileImageExporter(ProfileImageInspectionPort inspector) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    public V1ProfileImageExportReport export(Path backup,
            VerifiedV1IdentityBackup proof, Path destination) {
        Objects.requireNonNull(backup, "backup"); Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(destination, "destination");
        requireNoSidecars(backup);
        verifyBackupFile(backup, proof);
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent) || Files.exists(target))
            throw new V1ProfileImageExportException(
                    "profile image export destination must be new in an existing directory");
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory(parent, ".v1-profile-images-");
            Path stagedBackup = temporary.resolve(".verified-input.sqlite");
            Files.copy(backup, stagedBackup, StandardCopyOption.COPY_ATTRIBUTES);
            verifyBackupFile(stagedBackup, proof);
            V1IdentityImportPlan identity = new V1SqliteIdentitySource(stagedBackup).readPlan();
            if (!identity.readyToCompareWithTarget()
                    || identity.sourceRows() != proof.identityRows()
                    || !identity.sourceFingerprintSha256().equals(
                            proof.sourceFingerprintSha256()))
                throw new V1ProfileImageExportException(
                        "staged SQLite backup identity does not match proof");
            Path objects = temporary.resolve("objects"); Files.createDirectory(objects);
            List<Entry> entries = readAndExport(stagedBackup, objects);
            Counts counts = counts(entries, objects);
            Path manifest = temporary.resolve(MANIFEST_NAME);
            writeManifest(manifest, proof, entries, counts);
            String manifestHash = sha256(manifest);
            requireNoSidecars(backup);
            verifyBackupFile(backup, proof);
            deleteStagedInput(stagedBackup);
            moveNew(temporary, target); temporary = null;
            return new V1ProfileImageExportReport(target, proof.backupFileSha256(),
                    manifestHash, entries.size(), counts.present(), counts.absent(),
                    counts.invalid(), counts.objects());
        } catch (IOException | SQLException exception) {
            throw new V1ProfileImageExportException("profile image export failed", exception);
        } finally { if (temporary != null) deleteTree(temporary); }
    }

    private List<Entry> readAndExport(Path backup, Path objects)
            throws SQLException, IOException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + backup.toUri() + "?mode=ro")) {
            configure(connection); requireHealthy(connection); requireSchema(connection);
            requireNoOrphans(connection);
            List<Entry> entries = new ArrayList<>();
            readTargets(connection, objects, entries, TargetKind.ACCOUNT, """
                    SELECT owner.id AS legacy_id,
                           CASE WHEN avatar.user_id IS NULL THEN 0 ELSE 1 END AS has_row,
                           length(avatar.avatar_data) AS byte_size,
                           typeof(avatar.avatar_data) AS storage_type,
                           avatar.avatar_data, avatar.updated_at
                    FROM users owner LEFT JOIN user_avatars avatar ON avatar.user_id = owner.id
                    ORDER BY owner.id
                    """);
            readTargets(connection, objects, entries, TargetKind.ROOM, """
                    SELECT owner.id AS legacy_id,
                           CASE WHEN avatar.room_id IS NULL THEN 0 ELSE 1 END AS has_row,
                           length(avatar.avatar_data) AS byte_size,
                           typeof(avatar.avatar_data) AS storage_type,
                           avatar.avatar_data, avatar.updated_at
                    FROM rooms owner LEFT JOIN room_avatars avatar ON avatar.room_id = owner.id
                    ORDER BY owner.id
                    """);
            if (entries.size() > MAX_ENTRIES)
                throw new V1ProfileImageExportException(
                        "profile image export exceeds reviewed entry bound");
            return List.copyOf(entries);
        }
    }

    private void readTargets(Connection connection, Path objects, List<Entry> output,
            TargetKind kind, String sql) throws SQLException, IOException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            while (row.next()) {
                if (output.size() >= MAX_ENTRIES)
                    throw new V1ProfileImageExportException(
                            "profile image export exceeds reviewed entry bound");
                long legacyId = row.getLong("legacy_id");
                if (legacyId <= 0)
                    throw new V1ProfileImageExportException("legacy profile target ID is invalid");
                boolean hasRow = row.getInt("has_row") == 1;
                long size = row.getLong("byte_size");
                if (!hasRow || row.wasNull() || size == 0) {
                    output.add(Entry.absent(kind, legacyId,
                            hasRow ? "EMPTY" : "NO_ROW"));
                    continue;
                }
                if (size < 0 || size > Integer.MAX_VALUE)
                    throw new V1ProfileImageExportException("legacy avatar size is invalid");
                if (!"blob".equals(row.getString("storage_type"))) {
                    output.add(Entry.invalid(kind, legacyId, "INVALID_STORAGE_TYPE", size,
                            hash(row.getBinaryStream("avatar_data")), "-"));
                    continue;
                }
                if (size > LegacyV1AvatarUpload.MAX_BYTES) {
                    output.add(Entry.invalid(kind, legacyId, "OVERSIZED", size,
                            hash(row.getBinaryStream("avatar_data")), "-"));
                    continue;
                }
                byte[] raw = row.getBytes("avatar_data");
                if (raw == null || raw.length != size)
                    throw new V1ProfileImageExportException("legacy avatar bytes changed while read");
                try {
                    Instant updatedAt = parseTimestamp(row.getString("updated_at"));
                    if (updatedAt == null) {
                        output.add(Entry.invalid(kind, legacyId, "INVALID_UPDATED_AT", size,
                                digest(raw), "-"));
                        continue;
                    }
                    Optional<CanonicalProfileImage> inspected;
                    try (LegacyV1AvatarUpload upload = LegacyV1AvatarUpload.copyOf(raw)) {
                        inspected = inspector.inspect(upload);
                    }
                    if (inspected.isEmpty()) {
                        output.add(Entry.invalid(kind, legacyId, "INVALID_IMAGE", size,
                                digest(raw), updatedAt.toString()));
                        continue;
                    }
                    CanonicalProfileImage image = inspected.orElseThrow();
                    writeObject(objects, image);
                    byte[] contentHash = image.contentSha256();
                    output.add(Entry.present(kind, legacyId,
                            ProfileImageObjectEvidence.objectKey(contentHash),
                            image.pngBytes().length, HexFormat.of().formatHex(contentHash),
                            image.width(), image.height(), updatedAt.toString()));
                } finally { Arrays.fill(raw, (byte) 0); }
            }
        }
    }

    private static void writeObject(Path root, CanonicalProfileImage image) throws IOException {
        byte[] digest = image.contentSha256(); byte[] bytes = image.pngBytes();
        try {
            Path target = root.resolve(ProfileImageObjectEvidence.objectKey(digest));
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                if (!MessageDigest.isEqual(digest(target), digest)
                        || Files.size(target) != bytes.length)
                    throw new V1ProfileImageExportException(
                            "content-addressed profile image export collision");
                return;
            }
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!MessageDigest.isEqual(digest(target), digest)) {
                Files.deleteIfExists(target);
                throw new V1ProfileImageExportException(
                        "exported profile image verification failed");
            }
        } finally {
            Arrays.fill(bytes, (byte) 0); Arrays.fill(digest, (byte) 0);
        }
    }

    private static void writeManifest(Path target, VerifiedV1IdentityBackup proof,
            List<Entry> entries, Counts counts) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write("format\t" + FORMAT + "\n");
            writer.write("backup_sha256\t" + proof.backupFileSha256() + "\n");
            writer.write("identity_fingerprint_sha256\t"
                    + proof.sourceFingerprintSha256() + "\n");
            writer.write("entries\t" + entries.size() + "\n");
            writer.write("present\t" + counts.present() + "\n");
            writer.write("absent\t" + counts.absent() + "\n");
            writer.write("invalid\t" + counts.invalid() + "\n");
            writer.write("unique_objects\t" + counts.objects() + "\n");
            writer.write("kind\tlegacy_id\tstate\treason\tobject_key\tbyte_size\tsha256"
                    + "\twidth\theight\tupdated_at\n");
            for (Entry entry : entries) writer.write(entry.line() + "\n");
        }
    }

    private static Counts counts(List<Entry> entries, Path objects) throws IOException {
        int present = 0, absent = 0, invalid = 0;
        for (Entry entry : entries) switch (entry.state()) {
            case "PRESENT" -> present++;
            case "ABSENT" -> absent++;
            case "INVALID" -> invalid++;
            default -> throw new IllegalStateException("unknown export state");
        };
        int objectCount;
        try (var paths = Files.walk(objects)) {
            objectCount = Math.toIntExact(paths.filter(Files::isRegularFile).count());
        }
        return new Counts(present, absent, invalid, objectCount);
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }
    private static void requireHealthy(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("PRAGMA quick_check")) {
            if (!row.next() || !"ok".equals(row.getString(1)) || row.next())
                throw new V1ProfileImageExportException("V1 SQLite quick_check did not pass");
        }
    }
    private static void requireSchema(Connection connection) throws SQLException {
        requireColumns(connection, "users", Set.of("id"));
        requireColumns(connection, "rooms", Set.of("id"));
        requireColumns(connection, "user_avatars",
                Set.of("user_id", "avatar_data", "updated_at"));
        requireColumns(connection, "room_avatars",
                Set.of("room_id", "avatar_data", "updated_at"));
    }
    private static void requireColumns(Connection connection, String table,
            Set<String> required) throws SQLException {
        Set<String> found = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (row.next()) found.add(row.getString("name"));
        }
        if (!found.containsAll(required))
            throw new V1ProfileImageExportException(
                    "V1 profile image schema is missing required columns");
    }
    private static void requireNoOrphans(Connection connection) throws SQLException {
        String sql = """
                SELECT (SELECT count(*) FROM user_avatars avatar
                        LEFT JOIN users owner ON owner.id = avatar.user_id
                        WHERE owner.id IS NULL)
                     + (SELECT count(*) FROM room_avatars avatar
                        LEFT JOIN rooms owner ON owner.id = avatar.room_id
                        WHERE owner.id IS NULL)
                """;
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            if (!row.next() || row.getLong(1) != 0 || row.next())
                throw new V1ProfileImageExportException("V1 avatar rows contain orphan targets");
        }
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value, SQLITE_UTC).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) { return null; }
    }
    private static String hash(InputStream input) throws IOException {
        if (input == null) return "-";
        MessageDigest digest = sha256Digest(); byte[] buffer = new byte[64 * 1024];
        try (input) {
            for (int read; (read = input.read(buffer)) >= 0; )
                if (read > 0) digest.update(buffer, 0, read);
        } finally { Arrays.fill(buffer, (byte) 0); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String digest(byte[] value) {
        return HexFormat.of().formatHex(sha256Digest().digest(value));
    }
    private static byte[] digest(Path value) throws IOException {
        MessageDigest digest = sha256Digest(); byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(value)) {
            for (int read; (read = input.read(buffer)) >= 0; )
                if (read > 0) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }
    private static String sha256(Path value) throws IOException {
        return HexFormat.of().formatHex(digest(value));
    }
    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static void moveNew(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, destination); }
    }
    private static void requireNoSidecars(Path database) {
        Path absolute = database.toAbsolutePath().normalize();
        for (String suffix : List.of("-wal", "-shm", "-journal"))
            if (Files.exists(Path.of(absolute.toString() + suffix)))
                throw new V1ProfileImageExportException(
                        "verified SQLite backup must not have mutable sidecars");
    }
    private static void verifyBackupFile(Path backup, VerifiedV1IdentityBackup proof) {
        try {
            if (!Files.isRegularFile(backup) || Files.size(backup) != proof.backupBytes()
                    || !sha256(backup).equals(proof.backupFileSha256()))
                throw new V1ProfileImageExportException(
                        "SQLite backup file does not match identity backup proof");
        } catch (IOException exception) {
            throw new V1ProfileImageExportException(
                    "SQLite backup file cannot be verified", exception);
        }
    }
    private static void deleteStagedInput(Path staged) throws IOException {
        for (String suffix : List.of("-wal", "-shm", "-journal"))
            Files.deleteIfExists(Path.of(staged.toString() + suffix));
        Files.delete(staged);
    }
    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private enum TargetKind { ACCOUNT, ROOM }
    private record Counts(int present, int absent, int invalid, int objects) { }
    private record Entry(TargetKind kind, long legacyId, String state, String reason,
            String objectKey, long byteSize, String sha256, int width, int height,
            String updatedAt) {
        static Entry absent(TargetKind kind, long id, String reason) {
            return new Entry(kind, id, "ABSENT", reason, "-", 0, "-", 0, 0, "-");
        }
        static Entry invalid(TargetKind kind, long id, String reason,
                long bytes, String hash, String updatedAt) {
            return new Entry(kind, id, "INVALID", reason, "-", bytes, hash, 0, 0, updatedAt);
        }
        static Entry present(TargetKind kind, long id, String key, long bytes,
                String hash, int width, int height, String updatedAt) {
            return new Entry(kind, id, "PRESENT", "-", key, bytes, hash,
                    width, height, updatedAt);
        }
        String line() {
            return String.join("\t", kind.name(), Long.toString(legacyId), state, reason,
                    objectKey, Long.toString(byteSize), sha256, Integer.toString(width),
                    Integer.toString(height), updatedAt);
        }
    }
}
