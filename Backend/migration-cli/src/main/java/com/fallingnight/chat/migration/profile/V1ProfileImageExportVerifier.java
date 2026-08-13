package com.fallingnight.chat.migration.profile;

import com.fallingnight.chat.application.profile.CanonicalProfileImage;
import com.fallingnight.chat.application.profile.LegacyV1AvatarUpload;
import com.fallingnight.chat.application.profile.ProfileImageInspectionPort;
import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityBackup;
import com.fallingnight.chat.profile.imageio.BoundedPngProfileImageInspector;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Revalidates an export without trusting manifest counts, paths, or objects. */
public final class V1ProfileImageExportVerifier {
    private static final String FORMAT = "chat-room-v1-profile-image-export-v1";
    private static final String COLUMNS = "kind\tlegacy_id\tstate\treason\tobject_key"
            + "\tbyte_size\tsha256\twidth\theight\tupdated_at";
    private static final int MAX_ENTRIES = 2_000_000;
    private static final int MAX_LINE_CHARS = 1024;
    private final ProfileImageInspectionPort inspector;

    public V1ProfileImageExportVerifier() {
        this(new BoundedPngProfileImageInspector());
    }

    V1ProfileImageExportVerifier(ProfileImageInspectionPort inspector) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    public VerifiedV1ProfileImageExport verify(Path directory,
            VerifiedV1IdentityBackup proof, String expectedManifestSha256) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(proof, "proof");
        if (expectedManifestSha256 == null
                || !expectedManifestSha256.matches("[0-9a-f]{64}"))
            throw failure("expected profile image manifest SHA-256 is invalid");
        Path root = directory.toAbsolutePath().normalize();
        requireDirectory(root);
        Path manifest = root.resolve(V1ProfileImageExporter.MANIFEST_NAME);
        requireRegularFile(manifest);
        if (!sha256(manifest).equals(expectedManifestSha256))
            throw failure("profile image manifest hash does not match confirmation");

        Parsed parsed = parse(manifest, proof, root);
        requireExactTree(root, parsed.objectKeys());
        if (!sha256(manifest).equals(expectedManifestSha256))
            throw failure("profile image manifest changed during verification");
        return new VerifiedV1ProfileImageExport(root, expectedManifestSha256,
                proof.backupFileSha256(), proof.sourceFingerprintSha256(),
                parsed.entries(), parsed.objectKeys().size());
    }

    private Parsed parse(Path manifest, VerifiedV1IdentityBackup proof, Path root) {
        try (BufferedReader reader = Files.newBufferedReader(manifest,
                StandardCharsets.UTF_8)) {
            requireHeader(reader, "format", FORMAT);
            requireHeader(reader, "backup_sha256", proof.backupFileSha256());
            requireHeader(reader, "identity_fingerprint_sha256",
                    proof.sourceFingerprintSha256());
            int declaredEntries = requireCount(reader, "entries", MAX_ENTRIES);
            int declaredPresent = requireCount(reader, "present", MAX_ENTRIES);
            int declaredAbsent = requireCount(reader, "absent", MAX_ENTRIES);
            int declaredInvalid = requireCount(reader, "invalid", MAX_ENTRIES);
            int declaredObjects = requireCount(reader, "unique_objects", MAX_ENTRIES);
            if (!COLUMNS.equals(readLine(reader)))
                throw failure("profile image manifest columns are invalid");

            List<VerifiedV1ProfileImageExport.Entry> entries =
                    new ArrayList<>(Math.min(declaredEntries, 100_000));
            Set<String> objectKeys = new HashSet<>();
            int present = 0, absent = 0;
            VerifiedV1ProfileImageExport.Kind previousKind = null;
            long previousId = 0;
            for (int index = 0; index < declaredEntries; index++) {
                String line = readLine(reader);
                if (line == null) throw failure("profile image manifest ended early");
                String[] fields = line.split("\\t", -1);
                if (fields.length != 10)
                    throw failure("profile image manifest entry shape is invalid");
                VerifiedV1ProfileImageExport.Kind kind = parseKind(fields[0]);
                long legacyId = parsePositiveLong(fields[1]);
                if (previousKind != null && (kind.ordinal() < previousKind.ordinal()
                        || kind == previousKind && legacyId <= previousId))
                    throw failure("profile image manifest target order is invalid");
                previousKind = kind; previousId = legacyId;
                if ("PRESENT".equals(fields[2])) {
                    entries.add(parsePresent(root, kind, legacyId, fields, objectKeys));
                    present++;
                } else if ("ABSENT".equals(fields[2])) {
                    entries.add(parseAbsent(kind, legacyId, fields));
                    absent++;
                } else if ("INVALID".equals(fields[2])) {
                    throw failure("profile image manifest contains invalid source data");
                } else {
                    throw failure("profile image manifest state is invalid");
                }
            }
            if (readLine(reader) != null)
                throw failure("profile image manifest has trailing data");
            if (declaredInvalid != 0 || present != declaredPresent
                    || absent != declaredAbsent || present + absent != declaredEntries
                    || objectKeys.size() != declaredObjects)
                throw failure("profile image manifest counts do not reconcile");
            return new Parsed(List.copyOf(entries), Set.copyOf(objectKeys));
        } catch (IOException exception) {
            throw new V1ProfileImageExportException(
                    "profile image manifest cannot be verified", exception);
        }
    }

    private VerifiedV1ProfileImageExport.Entry parsePresent(Path root,
            VerifiedV1ProfileImageExport.Kind kind, long legacyId, String[] fields,
            Set<String> objectKeys) {
        if (!"-".equals(fields[3]) || !fields[6].matches("[0-9a-f]{64}"))
            throw failure("present profile image evidence is invalid");
        byte[] expectedDigest = null;
        try { expectedDigest = HexFormat.of().parseHex(fields[6]); }
        catch (IllegalArgumentException exception) {
            throw failure("present profile image digest is invalid");
        }
        ProfileImageObjectEvidence evidence;
        try {
            evidence = new ProfileImageObjectEvidence(fields[4],
                    parsePositiveLong(fields[5]), expectedDigest, "image/png");
        } catch (IllegalArgumentException exception) {
            throw failure("present profile image object evidence is invalid");
        } finally {
            if (expectedDigest != null) Arrays.fill(expectedDigest, (byte) 0);
        }
        int width = parsePositiveInt(fields[7]);
        int height = parsePositiveInt(fields[8]);
        Instant updatedAt = parseInstant(fields[9]);
        Path object = root.resolve("objects").resolve(evidence.objectKey()).normalize();
        if (!object.startsWith(root.resolve("objects")))
            throw failure("profile image object path escapes export directory");
        verifyObject(object, evidence, width, height);
        objectKeys.add(evidence.objectKey());
        return new VerifiedV1ProfileImageExport.Entry(
                kind, legacyId, evidence, width, height, updatedAt);
    }

    private static VerifiedV1ProfileImageExport.Entry parseAbsent(
            VerifiedV1ProfileImageExport.Kind kind, long legacyId, String[] fields) {
        if (!("NO_ROW".equals(fields[3]) || "EMPTY".equals(fields[3]))
                || !"-".equals(fields[4]) || !"0".equals(fields[5])
                || !"-".equals(fields[6]) || !"0".equals(fields[7])
                || !"0".equals(fields[8]) || !"-".equals(fields[9]))
            throw failure("absent profile image evidence is invalid");
        return new VerifiedV1ProfileImageExport.Entry(kind, legacyId, null, 0, 0, null);
    }

    private void verifyObject(Path object, ProfileImageObjectEvidence evidence,
            int width, int height) {
        requireRegularFile(object);
        byte[] bytes = null;
        try {
            if (Files.size(object) != evidence.byteSize())
                throw failure("profile image object size does not match manifest");
            try (var input = Files.newInputStream(object)) {
                bytes = input.readNBytes(LegacyV1AvatarUpload.MAX_BYTES + 1);
                if (bytes.length != evidence.byteSize() || input.read() >= 0)
                    throw failure("profile image object changed while read");
            }
            if (!MessageDigest.isEqual(digest(bytes), evidence.contentSha256()))
                throw failure("profile image object hash does not match manifest");
            Optional<CanonicalProfileImage> inspected;
            try (LegacyV1AvatarUpload upload = LegacyV1AvatarUpload.copyOf(bytes)) {
                inspected = inspector.inspect(upload);
            }
            if (inspected.isEmpty())
                throw failure("profile image object is not a bounded canonical PNG");
            CanonicalProfileImage image = inspected.orElseThrow();
            byte[] canonical = image.pngBytes();
            try {
                if (image.width() != width || image.height() != height
                        || canonical.length != bytes.length
                        || !MessageDigest.isEqual(canonical, bytes))
                    throw failure("profile image object canonical evidence changed");
            } finally { Arrays.fill(canonical, (byte) 0); }
        } catch (IOException exception) {
            throw new V1ProfileImageExportException(
                    "profile image object cannot be verified", exception);
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    private static void requireExactTree(Path root, Set<String> objectKeys) {
        Set<Path> expected = new HashSet<>();
        expected.add(root);
        expected.add(root.resolve(V1ProfileImageExporter.MANIFEST_NAME));
        Path objects = root.resolve("objects");
        expected.add(objects);
        if (!objectKeys.isEmpty()) {
            expected.add(objects.resolve("avatars"));
            expected.add(objects.resolve("avatars/sha256"));
        }
        for (String key : objectKeys) expected.add(objects.resolve(key));
        try (var paths = Files.walk(root)) {
            List<Path> actual = paths.sorted(Comparator.naturalOrder()).toList();
            for (Path path : actual) {
                BasicFileAttributes attributes = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !(attributes.isDirectory()
                        || attributes.isRegularFile()) || !expected.contains(path))
                    throw failure("profile image export contains an unexpected path");
            }
            if (!new HashSet<>(actual).equals(expected))
                throw failure("profile image export tree is incomplete");
        } catch (IOException exception) {
            throw new V1ProfileImageExportException(
                    "profile image export tree cannot be verified", exception);
        }
    }

    private static void requireDirectory(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink())
                throw failure("profile image export directory is invalid");
        } catch (IOException exception) {
            throw new V1ProfileImageExportException(
                    "profile image export directory cannot be verified", exception);
        }
    }

    private static void requireRegularFile(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink())
                throw failure("profile image export file is invalid");
        } catch (IOException exception) {
            throw new V1ProfileImageExportException(
                    "profile image export file cannot be verified", exception);
        }
    }

    private static void requireHeader(BufferedReader reader, String key, String value)
            throws IOException {
        if (!(key + "\t" + value).equals(readLine(reader)))
            throw failure("profile image manifest header is invalid");
    }

    private static int requireCount(BufferedReader reader, String key, int max)
            throws IOException {
        String line = readLine(reader);
        if (line == null) throw failure("profile image manifest header is incomplete");
        String[] fields = line.split("\\t", -1);
        if (fields.length != 2 || !key.equals(fields[0]))
            throw failure("profile image manifest count header is invalid");
        try {
            int value = Integer.parseInt(fields[1]);
            if (value < 0 || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw failure("profile image manifest count is invalid");
        }
    }

    private static String readLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int value; (value = reader.read()) >= 0; ) {
            if (value == '\n') return line.toString();
            if (value == '\r' || value == 0 || line.length() >= MAX_LINE_CHARS)
                throw failure("profile image manifest line is invalid");
            line.append((char) value);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static VerifiedV1ProfileImageExport.Kind parseKind(String value) {
        try { return VerifiedV1ProfileImageExport.Kind.valueOf(value); }
        catch (IllegalArgumentException exception) {
            throw failure("profile image manifest target kind is invalid");
        }
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw failure("profile image manifest positive number is invalid");
        }
    }

    private static int parsePositiveInt(String value) {
        long parsed = parsePositiveLong(value);
        if (parsed > Integer.MAX_VALUE)
            throw failure("profile image manifest integer is invalid");
        return (int) parsed;
    }

    private static Instant parseInstant(String value) {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value))
                throw failure("profile image timestamp is not canonical");
            return parsed;
        } catch (DateTimeParseException exception) {
            throw failure("profile image timestamp is invalid");
        }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = sha256Digest(); byte[] buffer = new byte[64 * 1024];
            try (var input = Files.newInputStream(path)) {
                for (int read; (read = input.read(buffer)) >= 0; )
                    if (read > 0) digest.update(buffer, 0, read);
            } finally { Arrays.fill(buffer, (byte) 0); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new V1ProfileImageExportException(
                    "profile image export file cannot be hashed", exception);
        }
    }

    private static byte[] digest(byte[] value) {
        return sha256Digest().digest(value);
    }

    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static V1ProfileImageExportException failure(String message) {
        return new V1ProfileImageExportException(message);
    }

    private record Parsed(List<VerifiedV1ProfileImageExport.Entry> entries,
            Set<String> objectKeys) { }
}
