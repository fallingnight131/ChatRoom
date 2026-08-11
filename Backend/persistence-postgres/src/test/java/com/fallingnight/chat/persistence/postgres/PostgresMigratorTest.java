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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;

@TestMethodOrder(OrderAnnotation.class)
class PostgresMigratorTest {
    private static final String URL = System.getenv("CHATROOM_TEST_POSTGRES_URL");
    private static final String USER = System.getenv("CHATROOM_TEST_POSTGRES_USER");
    private static final String PASSWORD = System.getenv("CHATROOM_TEST_POSTGRES_PASSWORD");

    @Test
    @Order(1)
    void migratesCleanDatabaseAndRestartValidatesWithoutReapplying() throws Exception {
        requireDatabase();
        PostgresMigrator first = new PostgresMigrator(URL, USER, PASSWORD);
        MigrateResult initial = first.migrate();
        assertEquals(1, initial.migrationsExecuted);
        first.validate();

        PostgresMigrator restarted = new PostgresMigrator(URL, USER, PASSWORD);
        assertEquals(0, restarted.migrate().migrationsExecuted);
        restarted.validate();

        try (Connection connection = connect()) {
            assertEquals(
                    Set.of("account", "device", "device_session", "conversation",
                            "conversation_member", "direct_conversation", "message"),
                    applicationTables(connection));
            proveSequenceAndIdempotencyConstraints(connection);
        }
    }

    @Test
    @Order(3)
    void refusesNonPostgresUrlsBeforeConnecting() {
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresMigrator("jdbc:sqlite:test.db", "", ""));
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
        assertTrue(account.enabled());
        assertTrue(adapter.findByPresentedUsername("Alice").isEmpty());

        ClientDescriptor client = new ClientDescriptor(
                "browser-2", ClientPlatform.WEB, "0.1.0");
        Instant now = Instant.parse("2026-08-11T12:00:00Z");
        IssuedSession first = adapter.issue(account, client, now).orElseThrow();
        try (first) {
            byte[] rawToken = first.resumeToken().withCopy(byte[]::clone);
            byte[] expectedHash = sha256(rawToken);
            byte[] storedHash = sessionHash(first.sessionId());
            try {
                assertTrue(Arrays.equals(expectedHash, storedHash));
                assertFalse(Arrays.equals(rawToken, storedHash));
                assertFalse(Arrays.equals(new byte[32], storedHash));
            } finally {
                Arrays.fill(rawToken, (byte) 0);
                Arrays.fill(expectedHash, (byte) 0);
            }
            assertEquals("WEB", devicePlatform(first.deviceId()));

            IssuedSession restarted = adapter.issue(
                    account, client, now.plusSeconds(60)).orElseThrow();
            try (restarted) {
                assertEquals(first.deviceId(), restarted.deviceId());
                assertNotEquals(first.sessionId(), restarted.sessionId());
                assertFalse(Arrays.equals(
                        storedHash,
                        sessionHash(restarted.sessionId())));
            }

            revokeDevice(first.deviceId());
            assertTrue(adapter.issue(account, client, now.plusSeconds(120)).isEmpty());
            disableAccount(account.accountId());
            ClientDescriptor otherClient = new ClientDescriptor(
                    "browser-3", ClientPlatform.WEB, "0.1.0");
            assertTrue(adapter.issue(account, otherClient, now.plusSeconds(180)).isEmpty());
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
                        + "VALUES (?, 'alice', 'Alice', 'argon2id')")) {
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

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void requireDatabase() {
        assumeTrue(URL != null && !URL.isBlank(),
                "set CHATROOM_TEST_POSTGRES_URL to run PostgreSQL migration tests");
    }
}
