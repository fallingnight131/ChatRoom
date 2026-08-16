package com.fallingnight.chat.migration;

import com.fallingnight.chat.identity.crypto.AesGcmWebPushCredentialProtector;
import com.fallingnight.chat.identity.crypto.FileWebPushKeyCustody;
import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import com.fallingnight.chat.persistence.postgres.PostgresWebPushSubscriptionKeyRotation;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.postgresql.ds.PGSimpleDataSource;

/** Fail-closed offline composition for the Web Push subscription key rewrite. */
final class WebPushKeyRotationCommand {
    static final String CONFIRMATION = "ROTATE_WEB_PUSH_SUBSCRIPTIONS_OFFLINE";
    static final String SOURCE_DIRECTORY =
            "CHATROOM_WEB_PUSH_ROTATION_SOURCE_KEY_DIRECTORY";
    static final String SOURCE_KEY_IDS =
            "CHATROOM_WEB_PUSH_ROTATION_SOURCE_ENCRYPTION_KEY_IDS";
    static final String TARGET_DIRECTORY =
            "CHATROOM_WEB_PUSH_ROTATION_TARGET_KEY_DIRECTORY";
    static final String TARGET_ACTIVE_KEY_ID =
            "CHATROOM_WEB_PUSH_ROTATION_TARGET_ACTIVE_ENCRYPTION_KEY_ID";
    static final String TARGET_KEY_IDS =
            "CHATROOM_WEB_PUSH_ROTATION_TARGET_ENCRYPTION_KEY_IDS";
    static final String GATEWAY_STOPPED =
            "CHATROOM_WEB_PUSH_ROTATION_GATEWAY_STOPPED";
    static final String RESTORABLE_BACKUP =
            "CHATROOM_WEB_PUSH_ROTATION_RESTORABLE_BACKUP";

    private static final String DATABASE_URL = "CHATROOM_MIGRATION_POSTGRES_URL";
    private static final String DATABASE_USER = "CHATROOM_MIGRATION_POSTGRES_USER";
    private static final String DATABASE_PASSWORD = "CHATROOM_MIGRATION_POSTGRES_PASSWORD";
    private static final String EXACT_CONFIRMATION = "CONFIRMED";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_KEYS = 8;

    private WebPushKeyRotationCommand() {}

    static int run(
            String maximumRowsValue,
            String confirmation,
            Map<String, String> environment,
            PrintStream output) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(output, "output");
        if (!CONFIRMATION.equals(confirmation)
                || !EXACT_CONFIRMATION.equals(environment.get(GATEWAY_STOPPED))
                || !EXACT_CONFIRMATION.equals(environment.get(RESTORABLE_BACKUP))) {
            throw new IllegalArgumentException(
                    "offline gateway and restorable backup confirmation are required");
        }
        int maximumRows = boundedInteger(maximumRowsValue, 1, 1_000_000);
        KeyDirectory source = keyDirectory(
                environment, SOURCE_DIRECTORY, SOURCE_KEY_IDS, null);
        KeyDirectory target = keyDirectory(
                environment, TARGET_DIRECTORY, TARGET_KEY_IDS,
                required(environment, TARGET_ACTIVE_KEY_ID));
        String sourceActiveKey = source.keyFiles().keySet().iterator().next();

        try (FileWebPushKeyCustody sourceCustody = FileWebPushKeyCustody.load(
                    sourceActiveKey, source.keyFiles(), source.lookupKey());
                FileWebPushKeyCustody targetCustody = FileWebPushKeyCustody.load(
                    target.activeKeyId(), target.keyFiles(), target.lookupKey())) {
            var sourceProtector = new AesGcmWebPushCredentialProtector(sourceCustody);
            var targetProtector = new AesGcmWebPushCredentialProtector(targetCustody);
            var report = new PostgresWebPushSubscriptionKeyRotation(
                    dataSource(environment), sourceProtector, targetProtector,
                    target.activeKeyId()).rotate(Math.min(1_000, maximumRows), maximumRows);
            output.println("status=WEB_PUSH_KEYS_ROTATED");
            output.println("rotated_subscriptions=" + report.rotatedSubscriptions());
            output.println("source_encryption_key_ids="
                    + String.join(",", report.sourceEncryptionKeyIds().stream().sorted().toList()));
            output.println("target_encryption_key_id=" + report.targetEncryptionKeyId());
            return 0;
        }
    }

    private static PGSimpleDataSource dataSource(Map<String, String> environment) {
        String url = required(environment, DATABASE_URL);
        String user = required(environment, DATABASE_USER);
        String password = environment.getOrDefault(DATABASE_PASSWORD, "");
        new PostgresMigrator(url, user, password).validate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static KeyDirectory keyDirectory(
            Map<String, String> environment,
            String directoryName,
            String keyIdsName,
            String activeKeyId) {
        Path directory = Path.of(required(environment, directoryName))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException(directoryName + " must be a non-link directory");
        }
        LinkedHashSet<String> ids = keyIds(required(environment, keyIdsName));
        String active = activeKeyId == null ? ids.iterator().next() : keyId(activeKeyId);
        if (!ids.contains(active)) {
            throw new IllegalArgumentException("active Web Push key must be listed");
        }
        Map<String, Path> files = new LinkedHashMap<>();
        ids.forEach(id -> files.put(id, directory.resolve("encryption-" + id + ".key")));
        return new KeyDirectory(active, files, directory.resolve("endpoint-lookup.key"));
    }

    private static LinkedHashSet<String> keyIds(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : value.split(",", -1)) {
            if (!result.add(keyId(part))) {
                throw new IllegalArgumentException("duplicate Web Push key ID");
            }
        }
        if (result.isEmpty() || result.size() > MAX_KEYS) {
            throw new IllegalArgumentException("Web Push key ring must contain 1..8 IDs");
        }
        return result;
    }

    private static String keyId(String value) {
        if (!KEY_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid Web Push key ID");
        }
        return value;
    }

    private static int boundedInteger(String value, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid Web Push rotation row ceiling", exception);
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private record KeyDirectory(
            String activeKeyId, Map<String, Path> keyFiles, Path lookupKey) {
        private KeyDirectory {
            keyFiles = Map.copyOf(keyFiles);
        }
    }
}
