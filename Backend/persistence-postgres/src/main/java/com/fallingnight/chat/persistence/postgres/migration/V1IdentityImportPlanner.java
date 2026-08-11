package com.fallingnight.chat.persistence.postgres.migration;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure deterministic validation/mapping phase used before any target write. */
public final class V1IdentityImportPlanner {
    private static final UUID USER_NAMESPACE =
            UUID.fromString("a8642d30-ae64-5b3f-8e4c-6f754a8d2742");
    private static final Pattern LEGACY_SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern ARGON2ID = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=[1-9]\\d*,t=[1-9]\\d*,p=[1-9]\\d*"
                    + "\\$[A-Za-z0-9+/]+\\$[A-Za-z0-9+/]+");

    public V1IdentityImportPlan plan(List<V1IdentityRow> sourceRows) {
        List<V1IdentityRow> ordered = new ArrayList<>(List.copyOf(sourceRows));
        ordered.sort(Comparator.comparingLong(V1IdentityRow::legacyId));
        List<PlannedIdentityAccount> accounts = new ArrayList<>();
        List<IdentityImportIssue> issues = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        Set<String> usernames = new HashSet<>();

        if (ordered.isEmpty()) {
            issues.add(issue(0, "EMPTY_SOURCE", "V1 identity source contains no users"));
        }

        for (V1IdentityRow row : ordered) {
            List<IdentityImportIssue> rowIssues = validate(row, ids, usernames);
            issues.addAll(rowIssues);
            if (rowIssues.isEmpty()) {
                ImportedCredentialScheme scheme = row.passwordHash().startsWith("$argon2id$")
                        ? ImportedCredentialScheme.ARGON2ID
                        : ImportedCredentialScheme.V1_SHA256;
                accounts.add(new PlannedIdentityAccount(
                        row.legacyId(),
                        deterministicUserId(row.legacyId()),
                        row.username(),
                        row.displayName(),
                        row.passwordHash(),
                        scheme,
                        scheme == ImportedCredentialScheme.V1_SHA256 ? row.legacySalt() : null,
                        row.createdAt()));
            }
        }
        return new V1IdentityImportPlan(
                fingerprint(ordered), ordered.size(), accounts, issues);
    }

    public static UUID deterministicUserId(long legacyId) {
        if (legacyId <= 0) {
            throw new IllegalArgumentException("legacyId must be positive");
        }
        byte[] namespace = uuidBytes(USER_NAMESPACE);
        byte[] name = ("v1-user:" + legacyId).getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = sha1();
        digest.update(namespace);
        byte[] hash = digest.digest(name);
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return bytesUuid(hash);
    }

    private static List<IdentityImportIssue> validate(
            V1IdentityRow row, Set<Long> ids, Set<String> usernames) {
        List<IdentityImportIssue> issues = new ArrayList<>();
        long id = row.legacyId();
        if (id <= 0) {
            issues.add(issue(id, "INVALID_LEGACY_ID", "legacy user id must be positive"));
        } else if (!ids.add(id)) {
            issues.add(issue(id, "DUPLICATE_LEGACY_ID", "legacy user id is duplicated"));
        }
        if (!boundedUtf8(row.username(), 1, 128)) {
            issues.add(issue(id, "INVALID_USERNAME", "username must contain 1..128 UTF-8 bytes"));
        } else if (!usernames.add(row.username())) {
            issues.add(issue(id, "DUPLICATE_USERNAME", "username is duplicated"));
        }
        if (!boundedCharacters(row.displayName(), 1, 100)) {
            issues.add(issue(id, "INVALID_DISPLAY_NAME", "display name must contain 1..100 characters"));
        }
        validateCredential(row, issues);
        if (row.createdAt() == null) {
            issues.add(issue(id, "INVALID_CREATED_AT", "creation timestamp is required"));
        }
        return List.copyOf(issues);
    }

    private static void validateCredential(
            V1IdentityRow row, List<IdentityImportIssue> issues) {
        String hash = row.passwordHash();
        String salt = row.legacySalt();
        if (hash != null && hash.startsWith("$argon2id$")) {
            int encodedBytes = hash.getBytes(StandardCharsets.UTF_8).length;
            if (encodedBytes > 255 || !ARGON2ID.matcher(hash).matches()) {
                issues.add(issue(
                        row.legacyId(),
                        "INVALID_ARGON2ID",
                        "Argon2id credential has an unsupported encoded shape"));
            }
            if (salt != null && !salt.isEmpty()) {
                issues.add(issue(
                        row.legacyId(),
                        "INCONSISTENT_ARGON2ID",
                        "Argon2id credential must not retain legacy salt"));
            }
            return;
        }
        if (hash == null || !LEGACY_SHA256.matcher(hash).matches()) {
            issues.add(issue(row.legacyId(), "INVALID_CREDENTIAL", "credential is neither supported Argon2id nor legacy SHA-256"));
        }
        if (!boundedCharacters(salt, 1, 512)) {
            issues.add(issue(row.legacyId(), "INVALID_LEGACY_SALT", "legacy SHA-256 salt must contain 1..512 characters"));
        }
    }

    private static IdentityImportIssue issue(long id, String code, String message) {
        return new IdentityImportIssue(id, code, message);
    }

    private static boolean boundedUtf8(String value, int minimum, int maximum) {
        if (value == null) {
            return false;
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= minimum && bytes <= maximum;
    }

    private static boolean boundedCharacters(String value, int minimum, int maximum) {
        if (value == null) {
            return false;
        }
        int characters = value.codePointCount(0, value.length());
        return characters >= minimum && characters <= maximum;
    }

    private static String fingerprint(List<V1IdentityRow> rows) {
        MessageDigest digest = sha256();
        try {
            try (DataOutputStream data = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                for (V1IdentityRow row : rows) {
                    data.writeLong(row.legacyId());
                    writeNullable(data, row.username());
                    writeNullable(data, row.displayName());
                    writeNullable(data, row.passwordHash());
                    writeNullable(data, row.legacySalt());
                    writeNullable(data, row.createdAt() == null ? null : row.createdAt().toString());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory identity fingerprint failed", exception);
        }
    }

    private static void writeNullable(DataOutputStream data, String value) throws IOException {
        if (value == null) {
            data.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(encoded.length);
        data.write(encoded);
    }

    private static MessageDigest sha1() {
        return digest("SHA-1");
    }

    private static MessageDigest sha256() {
        return digest("SHA-256");
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static byte[] uuidBytes(UUID value) {
        byte[] bytes = new byte[16];
        putLong(bytes, 0, value.getMostSignificantBits());
        putLong(bytes, 8, value.getLeastSignificantBits());
        return bytes;
    }

    private static UUID bytesUuid(byte[] bytes) {
        return new UUID(readLong(bytes, 0), readLong(bytes, 8));
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        for (int index = 7; index >= 0; index--) {
            bytes[offset + index] = (byte) value;
            value >>>= 8;
        }
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0;
        for (int index = 0; index < 8; index++) {
            value = (value << 8) | (bytes[offset + index] & 0xffL);
        }
        return value;
    }
}
