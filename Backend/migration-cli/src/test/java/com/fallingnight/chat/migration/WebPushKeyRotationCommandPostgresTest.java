package com.fallingnight.chat.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class WebPushKeyRotationCommandPostgresTest {
    private static final String URL = System.getenv("CHATROOM_TEST_POSTGRES_URL");
    private static final String USER = System.getenv("CHATROOM_TEST_POSTGRES_USER");
    private static final String PASSWORD = System.getenv("CHATROOM_TEST_POSTGRES_PASSWORD");
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @TempDir
    Path temporary;

    @Test
    void rotatesRealMountedKeysWithoutPrintingCredentialsOrPaths() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(),
                "set CHATROOM_TEST_POSTGRES_URL to run rotation command tests");
        new PostgresMigrator(URL, USER, PASSWORD).migrate();
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE chat.account CASCADE");
        }
        UUID account = UUID.randomUUID();
        UUID installation = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                                + "VALUES (?, ?, 'Rotation Fixture', "
                                + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')")) {
            statement.setObject(1, account);
            statement.setString(2, "rotation-" + account);
            statement.executeUpdate();
        }

        Path source = keyDirectory("source", "enc-old", (byte) 11, (byte) 12);
        Path target = keyDirectory("target", "enc-new", (byte) 21, (byte) 22);
        byte[] endpoint = "https://push.example.test/send/operator-private-token"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] p256dh = new byte[65];
        p256dh[0] = 0x04;
        byte[] auth = new byte[16];
        Arrays.fill(auth, (byte) 7);
        try (FileWebPushKeyCustody custody = FileWebPushKeyCustody.load(
                    "enc-old", Map.of("enc-old", source.resolve("encryption-enc-old.key")),
                    source.resolve("endpoint-lookup.key"))) {
            var store = new PostgresWebPushSubscriptionAdapter(
                    dataSource(), new AesGcmWebPushCredentialProtector(custody));
            try (var registration = WebPushSubscriptionRegistration.copyOf(
                    account, installation, Optional.empty(), endpoint, p256dh, auth)) {
                assertEquals(WebPushSubscriptionReplaceResult.REPLACED,
                        store.replace(registration));
            }
        }

        Map<String, String> environment = Map.ofEntries(
                Map.entry("CHATROOM_MIGRATION_POSTGRES_URL", URL),
                Map.entry("CHATROOM_MIGRATION_POSTGRES_USER", USER),
                Map.entry("CHATROOM_MIGRATION_POSTGRES_PASSWORD", PASSWORD),
                Map.entry(WebPushKeyRotationCommand.SOURCE_DIRECTORY, source.toString()),
                Map.entry(WebPushKeyRotationCommand.SOURCE_KEY_IDS, "enc-old"),
                Map.entry(WebPushKeyRotationCommand.TARGET_DIRECTORY, target.toString()),
                Map.entry(WebPushKeyRotationCommand.TARGET_ACTIVE_KEY_ID, "enc-new"),
                Map.entry(WebPushKeyRotationCommand.TARGET_KEY_IDS, "enc-new"),
                Map.entry(WebPushKeyRotationCommand.GATEWAY_STOPPED, "CONFIRMED"),
                Map.entry(WebPushKeyRotationCommand.RESTORABLE_BACKUP, "CONFIRMED"));
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        int status = IdentityMigrationMain.run(
                new String[] {"web-push-key-rotate", "10",
                    WebPushKeyRotationCommand.CONFIRMATION},
                environment,
                new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()),
                Clock.systemUTC());

        assertEquals(0, status);
        String output = outputBytes.toString(StandardCharsets.UTF_8);
        assertEquals("status=WEB_PUSH_KEYS_ROTATED\n"
                + "rotated_subscriptions=1\n"
                + "source_encryption_key_ids=enc-old\n"
                + "target_encryption_key_id=enc-new\n", output);
        assertFalse(output.contains(source.toString()));
        assertFalse(output.contains(target.toString()));
        assertFalse(output.contains("operator-private-token"));

        try (FileWebPushKeyCustody custody = FileWebPushKeyCustody.load(
                    "enc-new", Map.of("enc-new", target.resolve("encryption-enc-new.key")),
                    target.resolve("endpoint-lookup.key"))) {
            var protector = new AesGcmWebPushCredentialProtector(custody);
            var store = new PostgresWebPushSubscriptionAdapter(dataSource(), protector);
            try (var batch = store.loadActive(account, Instant.now());
                    var registration = protector.unprotect(batch.subscriptions().getFirst())) {
                assertArrayEquals(endpoint,
                        registration.withEndpointCopy(value -> value.clone()));
            }
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

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }
}
