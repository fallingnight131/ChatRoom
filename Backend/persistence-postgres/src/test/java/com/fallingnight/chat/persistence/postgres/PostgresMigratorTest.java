package com.fallingnight.chat.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fallingnight.chat.application.identity.AccountCredential;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.identity.StoredCredential;
import com.fallingnight.chat.application.security.SecretBytes;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1IdentityImporter;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportException;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentitySourceException;
import com.fallingnight.chat.persistence.postgres.migration.V1SqliteIdentityBackup;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityBackup;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityImportInput;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

@TestMethodOrder(OrderAnnotation.class)
class PostgresMigratorTest {
    private static final String URL = System.getenv("CHATROOM_TEST_POSTGRES_URL");
    private static final String USER = System.getenv("CHATROOM_TEST_POSTGRES_USER");
    private static final String PASSWORD = System.getenv("CHATROOM_TEST_POSTGRES_PASSWORD");

    @TempDir
    Path temporary;

    @Test
    @Order(1)
    void migratesCleanDatabaseAndRestartValidatesWithoutReapplying() throws Exception {
        requireDatabase();
        PostgresMigrator first = new PostgresMigrator(URL, USER, PASSWORD);
        MigrateResult initial = first.migrate();
        assertEquals(3, initial.migrationsExecuted);
        first.validate();

        PostgresMigrator restarted = new PostgresMigrator(URL, USER, PASSWORD);
        assertEquals(0, restarted.migrate().migrationsExecuted);
        restarted.validate();

        try (Connection connection = connect()) {
            assertEquals(
                    Set.of("account", "device", "device_session", "conversation",
                            "conversation_member", "direct_conversation", "message",
                            "identity_import_run"),
                    applicationTables(connection));
            proveSequenceAndIdempotencyConstraints(connection);
        }
    }

    @Test
    @Order(4)
    void refusesNonPostgresUrlsBeforeConnecting() {
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresMigrator("jdbc:sqlite:test.db", "", ""));
    }

    @Test
    @Order(3)
    void previewsAppliesReconcilesAndAuditsV1IdentityImport() throws Exception {
        requireDatabase();
        truncateApplicationData();
        Path source = temporary.resolve("v1-source.db");
        Path backup = temporary.resolve("v1-backup.db");
        createIdentitySource(source);
        Instant backupTime = Instant.parse("2026-08-11T12:00:00Z");
        VerifiedV1IdentityBackup proof = new V1SqliteIdentityBackup(
                Clock.fixed(backupTime, ZoneOffset.UTC)).createVerified(source, backup);
        VerifiedV1IdentityImportInput input = new V1IdentityImportInputVerifier()
                .verify(source, backup, proof);
        PostgresV1IdentityImporter importer = new PostgresV1IdentityImporter(dataSource());

        V1IdentityImportReport preview = importer.preview(input.plan());
        assertTrue(preview.readyToApply());
        assertEquals(2, preview.insertableRows());
        assertEquals(0, preview.alreadyImportedRows());
        assertEquals(0, accountCount());

        insertUnexpectedTargetAccount();
        V1IdentityImportReport unexpected = importer.preview(input.plan());
        assertFalse(unexpected.readyToApply());
        assertEquals(1, unexpected.unexpectedTargetRows());
        assertThrows(V1IdentityImportException.class, () -> importer.apply(input));
        assertEquals(1, accountCount());
        assertEquals(0, importRunCount());
        truncateApplicationData();

        V1IdentityImportReport applied = importer.apply(input);
        assertTrue(applied.applied());
        assertTrue(applied.reconciled());
        assertEquals(2, applied.insertedRows());
        assertEquals(2, accountCount());
        assertEquals(1, importRunCount());
        assertEquals(proof.backupFileSha256(), storedBackupHash(applied.importRunId()));

        V1IdentityImportReport rerun = importer.apply(input);
        assertEquals(0, rerun.insertedRows());
        assertEquals(2, rerun.alreadyImportedRows());
        assertEquals(2, importRunCount());
        assertEquals(2, accountCount());

        deleteOneImportedAccountAndCorruptAnother(input);
        V1IdentityImportReport blocked = importer.preview(input.plan());
        assertFalse(blocked.readyToApply());
        assertEquals(1, blocked.insertableRows());
        assertEquals("TARGET_ACCOUNT_CONFLICT", blocked.issues().getFirst().code());
        assertThrows(V1IdentityImportException.class, () -> importer.apply(input));
        assertEquals(1, accountCount());
        assertEquals(2, importRunCount());

        try (Connection sqlite = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                PreparedStatement statement = sqlite.prepareStatement(
                        "INSERT INTO users VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, 3);
            statement.setString(2, "late-user");
            statement.setString(3, "Late User");
            statement.setString(4, "a".repeat(64));
            statement.setString(5, "late-salt");
            statement.setString(6, "2026-01-02 03:04:07");
            statement.executeUpdate();
        }
        assertThrows(V1IdentitySourceException.class,
                () -> new V1IdentityImportInputVerifier().verify(source, backup, proof));
    }

    @Test
    @Order(2)
    void looksUpExactV1UsernameAndIssuesOnlyHashedRestartableSession() throws Exception {
        requireDatabase();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        PostgresIdentityAdapter adapter = new PostgresIdentityAdapter(dataSource);

        AccountCredential account = adapter.findByPresentedUsername("alice").orElseThrow();
        assertEquals("Alice", account.displayName());
        assertTrue(account.credential() instanceof StoredCredential.Argon2id);
        assertTrue(account.enabled());
        assertTrue(adapter.findByPresentedUsername("Alice").isEmpty());
        insertLegacyAccount();
        AccountCredential legacy = adapter.findByPresentedUsername("legacy-user").orElseThrow();
        assertEquals(
                new StoredCredential.LegacySha256("a".repeat(64), "legacy-salt-1234"),
                legacy.credential());
        StoredCredential.Argon2id replacement = new StoredCredential.Argon2id(
                "$argon2id$v=19$m=65536,t=2,p=1$test$replacement");
        assertTrue(adapter.replace(legacy.accountId(), legacy.credential(), replacement));
        assertEquals(replacement,
                adapter.findByPresentedUsername("legacy-user").orElseThrow().credential());
        assertFalse(adapter.replace(legacy.accountId(), legacy.credential(), replacement));
        assertCredentialShapeConstraint();

        ClientDescriptor client = new ClientDescriptor(
                "browser-2", ClientPlatform.WEB, "0.1.0");
        Instant now = Instant.parse("2026-08-11T12:00:00Z");
        IssuedSession first = adapter.issue(account, client, now).orElseThrow();
        try (first) {
            byte[] rawToken = first.resumeToken().withCopy(byte[]::clone);
            byte[] expectedHash = sha256(rawToken);
            byte[] storedHash = sessionHash(first.sessionId());
            byte[] rotatedToken = null;
            try {
                assertTrue(Arrays.equals(expectedHash, storedHash));
                assertFalse(Arrays.equals(rawToken, storedHash));
                assertFalse(Arrays.equals(new byte[32], storedHash));
                assertEquals("WEB", devicePlatform(first.deviceId()));

                try (SecretBytes proof = SecretBytes.copyOf(rawToken);
                        IssuedSession rotated = adapter.resumeAndRotate(
                                first.sessionId(), proof, client, now.plusSeconds(30))
                                .orElseThrow()) {
                    assertEquals(first.accountId(), rotated.accountId());
                    assertEquals(first.deviceId(), rotated.deviceId());
                    assertEquals(first.sessionId(), rotated.sessionId());
                    rotatedToken = rotated.resumeToken().withCopy(byte[]::clone);
                    assertFalse(Arrays.equals(rawToken, rotatedToken));
                    assertFalse(Arrays.equals(storedHash, sessionHash(first.sessionId())));
                    assertTrue(Arrays.equals(
                            sha256(rotatedToken), sessionHash(first.sessionId())));
                }
                try (SecretBytes replay = SecretBytes.copyOf(rawToken)) {
                    assertTrue(adapter.resumeAndRotate(
                            first.sessionId(), replay, client, now.plusSeconds(31)).isEmpty());
                }
                ClientDescriptor wrongDevice = new ClientDescriptor(
                        "other-browser", ClientPlatform.WEB, "0.1.0");
                try (SecretBytes proof = SecretBytes.copyOf(rotatedToken)) {
                    assertTrue(adapter.resumeAndRotate(
                            first.sessionId(), proof, wrongDevice, now.plusSeconds(31)).isEmpty());
                }
                try (SecretBytes proof = SecretBytes.copyOf(rotatedToken)) {
                    assertTrue(adapter.resumeAndRotate(
                            first.sessionId(),
                            proof,
                            client,
                            now.plus(PostgresIdentityAdapter.DEFAULT_SESSION_LIFETIME)
                                    .plusSeconds(31))
                            .isEmpty());
                }

                IssuedSession restarted = adapter.issue(
                        account, client, now.plusSeconds(60)).orElseThrow();
                try (restarted) {
                    assertEquals(first.deviceId(), restarted.deviceId());
                    assertNotEquals(first.sessionId(), restarted.sessionId());
                    assertFalse(Arrays.equals(
                            storedHash,
                            sessionHash(restarted.sessionId())));
                }

                IssuedSession concurrentBase = adapter.issue(
                        account, client, now.plusSeconds(70)).orElseThrow();
                try (concurrentBase) {
                    byte[] concurrentToken = concurrentBase.resumeToken().withCopy(byte[]::clone);
                    try {
                        List<Optional<IssuedSession>> outcomes = raceResume(
                                adapter,
                                concurrentBase.sessionId(),
                                concurrentToken,
                                client,
                                now.plusSeconds(71));
                        try {
                            assertEquals(
                                    1, outcomes.stream().filter(Optional::isPresent).count());
                        } finally {
                            outcomes.stream()
                                    .flatMap(Optional::stream)
                                    .forEach(IssuedSession::close);
                        }
                    } finally {
                        Arrays.fill(concurrentToken, (byte) 0);
                    }
                }

                revokeDevice(first.deviceId());
                try (SecretBytes proof = SecretBytes.copyOf(rotatedToken)) {
                    assertTrue(adapter.resumeAndRotate(
                            first.sessionId(), proof, client, now.plusSeconds(120)).isEmpty());
                }
                assertTrue(adapter.issue(account, client, now.plusSeconds(120)).isEmpty());
                disableAccount(account.accountId());
                ClientDescriptor otherClient = new ClientDescriptor(
                        "browser-3", ClientPlatform.WEB, "0.1.0");
                assertTrue(adapter.issue(account, otherClient, now.plusSeconds(180)).isEmpty());
            } finally {
                Arrays.fill(rawToken, (byte) 0);
                Arrays.fill(expectedHash, (byte) 0);
                if (rotatedToken != null) {
                    Arrays.fill(rotatedToken, (byte) 0);
                }
            }
        }
    }

    private static void proveSequenceAndIdempotencyConstraints(Connection connection)
            throws SQLException {
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                        + "VALUES (?, 'alice', 'Alice', "
                        + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')")) {
            statement.setObject(1, account);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                        + "VALUES (?, ?, 'browser-1', 'WEB')")) {
            statement.setObject(1, device);
            statement.setObject(2, account);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')")) {
            statement.setObject(1, conversation);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.conversation_member(conversation_id, account_id) VALUES (?, ?)")) {
            statement.setObject(1, conversation);
            statement.setObject(2, account);
            statement.executeUpdate();
        }

        long sequence;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.conversation SET next_sequence = next_sequence + 1, "
                        + "updated_at = transaction_timestamp() WHERE id = ? "
                        + "RETURNING next_sequence - 1")) {
            statement.setObject(1, conversation);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                sequence = result.getLong(1);
            }
        }
        insertMessage(connection, message, conversation, sequence, account, device, "client-1");
        SQLException duplicateClientId = assertThrows(SQLException.class,
                () -> insertMessage(connection, UUID.randomUUID(), conversation, sequence + 1,
                        account, device, "client-1"));
        assertEquals("23505", duplicateClientId.getSQLState());
        SQLException duplicateSequence = assertThrows(SQLException.class,
                () -> insertMessage(connection, UUID.randomUUID(), conversation, sequence,
                        account, device, "client-2"));
        assertEquals("23505", duplicateSequence.getSQLState());
    }

    private static List<Optional<IssuedSession>> raceResume(
            PostgresIdentityAdapter adapter,
            UUID sessionId,
            byte[] token,
            ClientDescriptor client,
            Instant now) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<IssuedSession>>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        try (SecretBytes proof = SecretBytes.copyOf(token)) {
                            return adapter.resumeAndRotate(sessionId, proof, client, now);
                        }
                    }))
                    .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static void insertMessage(
            Connection connection,
            UUID id,
            UUID conversation,
            long sequence,
            UUID account,
            UUID device,
            String clientMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                        + "sender_account_id, sender_device_id, client_message_id, message_type, "
                        + "payload, payload_sha256) VALUES (?, ?, ?, ?, ?, ?, 100, ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, conversation);
            statement.setLong(3, sequence);
            statement.setObject(4, account);
            statement.setObject(5, device);
            statement.setString(6, clientMessageId);
            statement.setBytes(7, new byte[] {1});
            statement.setBytes(8, new byte[32]);
            statement.executeUpdate();
        }
    }

    private static Set<String> applicationTables(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'chat' AND table_name <> 'flyway_schema_history'");
                ResultSet result = statement.executeQuery()) {
            Set<String> tables = new java.util.HashSet<>();
            while (result.next()) {
                tables.add(result.getString(1));
            }
            return Set.copyOf(tables);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static byte[] sessionHash(UUID sessionId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT token_sha256 FROM chat.device_session WHERE id = ?")) {
            statement.setObject(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBytes(1);
            }
        }
    }

    private static String devicePlatform(UUID deviceId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT platform FROM chat.device WHERE id = ?")) {
            statement.setObject(1, deviceId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static void revokeDevice(UUID deviceId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE chat.device SET revoked_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, deviceId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void disableAccount(UUID accountId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE chat.account SET disabled_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, accountId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertLegacyAccount() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash, "
                                + "password_scheme, legacy_password_salt) "
                                + "VALUES (?, 'legacy-user', 'Legacy', ?, 'V1_SHA256', ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "a".repeat(64));
            statement.setString(3, "legacy-salt-1234");
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertCredentialShapeConstraint() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash, "
                                + "password_scheme, legacy_password_salt) "
                                + "VALUES (?, 'invalid-legacy', 'Invalid', 'not-hex', "
                                + "'V1_SHA256', '')")) {
            statement.setObject(1, UUID.randomUUID());
            SQLException exception = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals("23514", exception.getSQLState());
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    private static void truncateApplicationData() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "TRUNCATE chat.account, chat.identity_import_run CASCADE")) {
            statement.execute();
        }
    }

    private static void insertUnexpectedTargetAccount() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                                + "VALUES (?, 'unexpected', 'Unexpected', "
                                + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')")) {
            statement.setObject(1, UUID.randomUUID());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void createIdentitySource(Path source) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, "
                    + "username TEXT UNIQUE NOT NULL, display_name TEXT, "
                    + "password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at TEXT)");
            statement.execute("INSERT INTO users VALUES (1, 'alice-v1', 'Alice V1', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA', '', "
                    + "'2026-01-02 03:04:05')");
            statement.execute("INSERT INTO users VALUES (2, 'legacy-v1', 'Legacy V1', '"
                    + "a".repeat(64) + "', 'legacy-salt', '2026-01-02 03:04:06')");
        }
    }

    private static int accountCount() throws SQLException {
        return count("SELECT count(*) FROM chat.account");
    }

    private static int importRunCount() throws SQLException {
        return count("SELECT count(*) FROM chat.identity_import_run");
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String storedBackupHash(UUID runId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT backup_file_sha256 FROM chat.identity_import_run WHERE id = ?")) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static void deleteOneImportedAccountAndCorruptAnother(
            VerifiedV1IdentityImportInput input) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM chat.account WHERE id = ?");
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE chat.account SET display_name = 'Changed' WHERE id = ?")) {
            delete.setObject(1, input.plan().accounts().get(1).accountId());
            assertEquals(1, delete.executeUpdate());
            update.setObject(1, input.plan().accounts().get(0).accountId());
            assertEquals(1, update.executeUpdate());
        }
    }

    private static void requireDatabase() {
        assumeTrue(URL != null && !URL.isBlank(),
                "set CHATROOM_TEST_POSTGRES_URL to run PostgreSQL migration tests");
    }
}
