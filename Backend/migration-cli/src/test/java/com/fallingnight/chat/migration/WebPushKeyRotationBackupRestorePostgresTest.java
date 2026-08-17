package com.fallingnight.chat.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import com.fallingnight.chat.application.notification.WebPushSubscriptionReplaceResult;
import com.fallingnight.chat.identity.crypto.AesGcmWebPushCredentialProtector;
import com.fallingnight.chat.identity.crypto.FileWebPushKeyCustody;
import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import com.fallingnight.chat.persistence.postgres.PostgresWebPushSubscriptionAdapter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class WebPushKeyRotationBackupRestorePostgresTest {
    private static final String URL = System.getenv("CHATROOM_TEST_POSTGRES_URL");
    private static final String ADMIN_URL = System.getenv("CHATROOM_TEST_POSTGRES_ADMIN_URL");
    private static final String USER = System.getenv("CHATROOM_TEST_POSTGRES_USER");
    private static final String PASSWORD = System.getenv("CHATROOM_TEST_POSTGRES_PASSWORD");
    private static final String PG_DUMP = System.getenv("CHATROOM_TEST_PG_DUMP");
    private static final String PG_RESTORE = System.getenv("CHATROOM_TEST_PG_RESTORE");
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @TempDir
    Path temporary;

    @Test
    void rehearsesForwardRestoreRollbackRetirementAndErasure() throws Exception {
        assumeTrue(URL != null && !URL.isBlank() && ADMIN_URL != null
                        && PG_DUMP != null && PG_RESTORE != null,
                "run through tools/verify_postgres.py for backup/restore rehearsal");
        new PostgresMigrator(URL, USER, PASSWORD).migrate();
        truncate(URL);

        Path source = keyDirectory("source", "enc-old", (byte) 31, (byte) 32);
        Path target = keyDirectory("target", "enc-new", (byte) 41, (byte) 42);
        UUID account = UUID.randomUUID();
        UUID installation = UUID.randomUUID();
        byte[] endpoint = "https://push.example.test/send/backup-restore-token"
                .getBytes(StandardCharsets.US_ASCII);
        seed(URL, source, account, installation, endpoint);

        Path backup = temporary.resolve("pre-rotation.dump");
        postgresTool(PG_DUMP, "--format=custom", "--no-owner", "--no-privileges",
                "--file=" + backup, "--username=" + USER, postgresUrl(URL));
        assertTrue(Files.isRegularFile(backup));
        assertTrue(Files.size(backup) > 0);

        runRotation(URL, source, target);
        assertDecrypts(URL, target, "enc-new", account, endpoint);

        String restoredDatabase = "chat_restore_"
                + UUID.randomUUID().toString().replace("-", "");
        String restoredUrl = databaseUrl(URL, restoredDatabase);
        createDatabase(restoredDatabase);
        try {
            postgresTool(PG_RESTORE, "--exit-on-error", "--no-owner", "--no-privileges",
                    "--dbname=" + postgresUrl(restoredUrl), "--username=" + USER,
                    backup.toString());
            assertDecrypts(restoredUrl, source, "enc-old", account, endpoint);

            runRotation(restoredUrl, source, target);
            assertDecrypts(restoredUrl, target, "enc-new", account, endpoint);
            assertCannotDecrypt(restoredUrl, source, "enc-old", account);

            Files.delete(source.resolve("encryption-enc-old.key"));
            Files.delete(source.resolve("endpoint-lookup.key"));
            assertDecrypts(restoredUrl, target, "enc-new", account, endpoint);

            try (Connection connection = DriverManager.getConnection(
                        restoredUrl, USER, PASSWORD);
                    PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM chat.account WHERE id=?")) {
                delete.setObject(1, account);
                assertEquals(1, delete.executeUpdate());
            }
            assertEquals(0, subscriptionCount(restoredUrl));
        } finally {
            dropDatabase(restoredDatabase);
        }
    }

    private void seed(
            String url, Path source, UUID account, UUID installation, byte[] endpoint)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                                + "VALUES (?, ?, 'Backup Restore Fixture', "
                                + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')")) {
            statement.setObject(1, account);
            statement.setString(2, "backup-restore-" + account);
            statement.executeUpdate();
        }
        byte[] p256dh = new byte[65];
        p256dh[0] = 0x04;
        byte[] auth = new byte[16];
        Arrays.fill(auth, (byte) 9);
        try (FileWebPushKeyCustody custody = custody(source, "enc-old")) {
            var store = new PostgresWebPushSubscriptionAdapter(
                    dataSource(url), new AesGcmWebPushCredentialProtector(custody));
            try (var registration = WebPushSubscriptionRegistration.copyOf(
                    account, installation, Optional.empty(), endpoint, p256dh, auth)) {
                assertEquals(WebPushSubscriptionReplaceResult.REPLACED,
                        store.replace(registration));
            }
        } finally {
            Arrays.fill(p256dh, (byte) 0);
            Arrays.fill(auth, (byte) 0);
        }
    }

    private void runRotation(String url, Path source, Path target) {
        Map<String, String> environment = Map.ofEntries(
                Map.entry("CHATROOM_MIGRATION_POSTGRES_URL", url),
                Map.entry("CHATROOM_MIGRATION_POSTGRES_USER", USER),
                Map.entry("CHATROOM_MIGRATION_POSTGRES_PASSWORD", PASSWORD),
                Map.entry(WebPushKeyRotationCommand.SOURCE_DIRECTORY, source.toString()),
                Map.entry(WebPushKeyRotationCommand.SOURCE_KEY_IDS, "enc-old"),
                Map.entry(WebPushKeyRotationCommand.TARGET_DIRECTORY, target.toString()),
                Map.entry(WebPushKeyRotationCommand.TARGET_ACTIVE_KEY_ID, "enc-new"),
                Map.entry(WebPushKeyRotationCommand.TARGET_KEY_IDS, "enc-new"),
                Map.entry(WebPushKeyRotationCommand.GATEWAY_STOPPED, "CONFIRMED"),
                Map.entry(WebPushKeyRotationCommand.RESTORABLE_BACKUP, "CONFIRMED"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = IdentityMigrationMain.run(
                new String[] {"web-push-key-rotate", "10",
                    WebPushKeyRotationCommand.CONFIRMATION},
                environment,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        assertEquals(0, status, error.toString(StandardCharsets.UTF_8));
        String report = output.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains("rotated_subscriptions=1"));
        assertFalse(report.contains(source.toString()));
        assertFalse(report.contains(target.toString()));
        assertFalse(report.contains("backup-restore-token"));
    }

    private void assertDecrypts(
            String url, Path directory, String keyId, UUID account, byte[] endpoint) {
        try (FileWebPushKeyCustody custody = custody(directory, keyId)) {
            var protector = new AesGcmWebPushCredentialProtector(custody);
            var store = new PostgresWebPushSubscriptionAdapter(dataSource(url), protector);
            try (var batch = store.loadActive(account, Instant.now());
                    var registration = protector.unprotect(batch.subscriptions().getFirst())) {
                assertArrayEquals(endpoint,
                        registration.withEndpointCopy(value -> value.clone()));
            }
        }
    }

    private void assertCannotDecrypt(
            String url, Path directory, String keyId, UUID account) {
        try (FileWebPushKeyCustody custody = custody(directory, keyId)) {
            var protector = new AesGcmWebPushCredentialProtector(custody);
            var store = new PostgresWebPushSubscriptionAdapter(dataSource(url), protector);
            try (var batch = store.loadActive(account, Instant.now())) {
                assertThrows(RuntimeException.class,
                        () -> protector.unprotect(batch.subscriptions().getFirst()));
            }
        }
    }

    private static void truncate(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE chat.account CASCADE");
        }
    }

    private static int subscriptionCount(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM chat.web_push_subscription")) {
            result.next();
            return result.getInt(1);
        }
    }

    private void createDatabase(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + database + "\"");
        }
    }

    private void dropDatabase(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
        }
    }

    private static void postgresTool(String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().put("PGPASSWORD", PASSWORD == null ? "" : PASSWORD);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        try {
            assertEquals(0, process.waitFor(), "PostgreSQL backup/restore command failed");
        } finally {
            Arrays.fill(output, (byte) 0);
        }
    }

    private Path keyDirectory(String name, String keyId, byte encryption, byte lookup)
            throws Exception {
        Path directory = Files.createDirectory(temporary.resolve(name));
        writeKey(directory.resolve("encryption-" + keyId + ".key"), encryption);
        writeKey(directory.resolve("endpoint-lookup.key"), lookup);
        return directory;
    }

    private static void writeKey(Path path, byte fill) throws Exception {
        byte[] value = new byte[32];
        Arrays.fill(value, fill);
        try {
            Files.write(path, value);
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static FileWebPushKeyCustody custody(Path directory, String keyId) {
        return FileWebPushKeyCustody.load(
                keyId, Map.of(keyId, directory.resolve("encryption-" + keyId + ".key")),
                directory.resolve("endpoint-lookup.key"));
    }

    private static PGSimpleDataSource dataSource(String url) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    private static String postgresUrl(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("test PostgreSQL URL must be canonical");
        }
        return jdbcUrl.substring("jdbc:".length());
    }

    private static String databaseUrl(String jdbcUrl, String database) {
        int separator = jdbcUrl.lastIndexOf('/');
        if (separator < "jdbc:postgresql://".length() || jdbcUrl.indexOf('?', separator) >= 0) {
            throw new IllegalArgumentException("test PostgreSQL URL must not contain options");
        }
        return jdbcUrl.substring(0, separator + 1) + database;
    }
}
