package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.gateway.compatibility.v1.V1ConnectionAttributes;
import com.fallingnight.chat.gateway.compatibility.v1.V1WebLoginHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomDirectoryEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendDirectoryEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1PendingFriendRequestEventSink;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.AuthenticationEventSink;
import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation") // Netty's certificate generator is confined to this test.
class GatewayRuntimePostgresIntegrationTest {
    private static final String HASH =
            "$argon2id$v=19$m=65536,t=2,p=1$E1wX9i9QVyERI3DZqWy0Kg$"
                    + "nDO9/91zFAJGLsvBZudV4nKX4eGGHWTwuimwcjPzPcw";

    @Test
    void composesValidatedPostgresAdminReadinessAndWssLifecycle() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank());
        Assumptions.assumeTrue(username != null && !username.isBlank());

        int gatewayPort = availablePort();
        int adminPort = availablePort();
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        GatewayRuntime runtime = null;
        try {
            Map<String, String> environment = new HashMap<>();
            environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(gatewayPort));
            environment.put("CHATROOM_GATEWAY_ADMIN_PORT", Integer.toString(adminPort));
            environment.put(
                    "CHATROOM_GATEWAY_TLS_CERTIFICATE",
                    certificate.certificate().getAbsolutePath());
            environment.put(
                    "CHATROOM_GATEWAY_TLS_PRIVATE_KEY",
                    certificate.privateKey().getAbsolutePath());
            environment.put(
                    "CHATROOM_GATEWAY_ALLOWED_HOSTS", "127.0.0.1:" + gatewayPort);
            environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
            environment.put("CHATROOM_POSTGRES_URL", jdbcUrl);
            environment.put("CHATROOM_POSTGRES_USER", username);
            environment.put("CHATROOM_POSTGRES_PASSWORD", "test-trust-password");
            environment.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
            environment.put("CHATROOM_POSTGRES_POOL_MAXIMUM", "2");
            environment.put("CHATROOM_POSTGRES_POOL_MINIMUM_IDLE", "1");

            runtime = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(environment));
            assertFalse(runtime.isReady());
            runtime.start();
            assertTrue(runtime.isReady());

            HttpResponse<String> readiness = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                    .send(
                            HttpRequest.newBuilder(URI.create(
                                            "http://127.0.0.1:" + adminPort + "/health/ready"))
                                    .timeout(Duration.ofSeconds(2))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, readiness.statusCode());
            assertEquals("ready\n", readiness.body());
        } finally {
            if (runtime != null) {
                runtime.close();
                assertFalse(runtime.isReady());
            }
            certificate.delete();
        }
    }

    @Test
    void composesRealV1LoginOnlyForMappedImportedAccounts() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank());
        Assumptions.assumeTrue(username != null && !username.isBlank());
        new PostgresMigrator(jdbcUrl, username, password).migrate();
        seedV1CompatibilityAccounts(jdbcUrl, username, password);

        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(jdbcUrl);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(2);
        try (HikariDataSource dataSource = new HikariDataSource(pool)) {
            V1CompatibilityModule module = V1CompatibilityModule.create(
                    dataSource,
                    Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC));

            EmbeddedChannel imported = upgradedChannel(module,
                    Runnable::run,
                    AuthenticationAdmissionControl.allowAll(),
                    AuthenticationEventSink.noop());
            try {
                imported.writeInbound(loginFrame("imported-v1", "java-v2-test-password"));
                imported.runPendingTasks();
                TextWebSocketFrame response = imported.readOutbound();
                try {
                    assertTrue(response.text().contains("\"success\":true"));
                    assertTrue(response.text().contains("\"userId\":42"));
                } finally {
                    response.release();
                }
                assertEquals(42, imported.attr(V1ConnectionAttributes.AUTHENTICATED)
                        .get().legacyUserId());
                assertEquals(1, sessionCount(jdbcUrl, username, password));
                assertEquals(V1WebLoginHandler.COMPATIBILITY_DEVICE_ID,
                        storedDeviceAlias(jdbcUrl, username, password));

                imported.writeInbound(new TextWebSocketFrame(
                        "{\"type\":\"ROOM_LIST_REQ\",\"id\":\"rooms-1\",\"data\":{}}"));
                imported.runPendingTasks();
                TextWebSocketFrame rooms = imported.readOutbound();
                try {
                    assertTrue(rooms.text().contains("\"type\":\"ROOM_LIST_RSP\""));
                    assertTrue(rooms.text().contains("\"roomId\":7"));
                    assertTrue(rooms.text().contains("\"roomName\":\"Imported Room\""));
                    assertTrue(rooms.text().contains("\"unread\":4"));
                    assertTrue(rooms.text().contains("\"isAdmin\":true"));
                    assertFalse(rooms.text().contains("Unrelated Room"));
                    assertFalse(rooms.text().contains("10000000-0000"));
                } finally {
                    rooms.release();
                }

                imported.writeInbound(new TextWebSocketFrame(
                        "{\"type\":\"FRIEND_LIST_REQ\",\"id\":\"friends-1\",\"data\":{}}"));
                imported.runPendingTasks();
                TextWebSocketFrame friends = imported.readOutbound();
                try {
                    assertTrue(friends.text().contains("\"type\":\"FRIEND_LIST_RSP\""));
                    assertTrue(friends.text().contains("\"friendshipId\":9"));
                    assertTrue(friends.text().contains("\"friendId\":44"));
                    assertTrue(friends.text().contains("\"username\":\"imported-peer\""));
                    assertTrue(friends.text().contains("\"unread\":2"));
                    assertTrue(friends.text().contains("\"peerLastReadMessageId\":101"));
                    assertTrue(friends.text().contains("\"pendingFriendRequests\":1"));
                    assertFalse(friends.text().contains("10000000-0000"));
                } finally {
                    friends.release();
                }

                imported.writeInbound(new TextWebSocketFrame(
                        "{\"type\":\"FRIEND_PENDING_REQ\",\"id\":\"pending-1\",\"data\":{}}"));
                imported.runPendingTasks();
                TextWebSocketFrame pending = imported.readOutbound();
                try {
                    assertTrue(pending.text().contains("\"type\":\"FRIEND_PENDING_RSP\""));
                    assertTrue(pending.text().contains("\"requestId\":70"));
                    assertTrue(pending.text().contains("\"fromUserId\":44"));
                    assertTrue(pending.text().contains("\"fromUsername\":\"imported-peer\""));
                    assertFalse(pending.text().contains("10000000-0000"));
                } finally {
                    pending.release();
                }
            } finally {
                imported.finishAndReleaseAll();
            }

            EmbeddedChannel nativeV2 = upgradedChannel(module,
                    Runnable::run,
                    AuthenticationAdmissionControl.allowAll(),
                    AuthenticationEventSink.noop());
            try {
                nativeV2.writeInbound(loginFrame("native-v2", "java-v2-test-password"));
                nativeV2.runPendingTasks();
                TextWebSocketFrame response = nativeV2.readOutbound();
                try {
                    assertTrue(response.text().contains("\"success\":false"));
                } finally {
                    response.release();
                }
                assertFalse(nativeV2.isActive());
                assertEquals(1, sessionCount(jdbcUrl, username, password));
            } finally {
                nativeV2.finishAndReleaseAll();
            }
        }
    }

    private static EmbeddedChannel upgradedChannel(
            V1CompatibilityModule module,
            java.util.concurrent.Executor executor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events) {
        EmbeddedChannel channel = new EmbeddedChannel(module.newWebSocketUpgradeHandler(
                executor,
                executor,
                admission,
                events,
                V1RoomDirectoryEventSink.noop(),
                V1FriendDirectoryEventSink.noop(),
                V1PendingFriendRequestEventSink.noop(),
                java.time.Duration.ofSeconds(10),
                java.time.Duration.ofSeconds(15),
                java.time.Duration.ofSeconds(90)));
        channel.attr(V1ConnectionAttributes.WEB_UPGRADE_ACCEPTED).set(true);
        channel.pipeline().fireUserEventTriggered(
                new WebSocketServerProtocolHandler.HandshakeComplete(
                        "/v1/web",
                        EmptyHttpHeaders.INSTANCE,
                        "chat.v1"));
        return channel;
    }

    private static TextWebSocketFrame loginFrame(String username, String password) {
        return new TextWebSocketFrame(
                "{\"type\":\"LOGIN_REQ\",\"data\":{\"username\":\""
                        + username + "\",\"password\":\"" + password + "\"}}");
    }

    private static void seedV1CompatibilityAccounts(
            String url, String user, String password) throws Exception {
        UUID imported = UUID.fromString("10000000-0000-0000-0000-000000000042");
        UUID peer = UUID.fromString("15000000-0000-0000-0000-000000000044");
        UUID nativeV2 = UUID.fromString("20000000-0000-0000-0000-000000000043");
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement truncate = connection.createStatement()) {
            truncate.execute("TRUNCATE chat.account, chat.identity_import_run CASCADE");
            try (PreparedStatement account = connection.prepareStatement(
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, ?, ?, ?)")) {
                account.setObject(1, imported);
                account.setString(2, "imported-v1");
                account.setString(3, "Imported V1");
                account.setString(4, HASH);
                account.addBatch();
                account.setObject(1, nativeV2);
                account.setString(2, "native-v2");
                account.setString(3, "Native V2");
                account.setString(4, HASH);
                account.addBatch();
                account.setObject(1, peer);
                account.setString(2, "imported-peer");
                account.setString(3, "Imported Peer");
                account.setString(4, HASH);
                account.addBatch();
                account.executeBatch();
            }
            try (PreparedStatement mapping = connection.prepareStatement(
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (42, ?), (44, ?)")) {
                mapping.setObject(1, imported);
                mapping.setObject(2, peer);
                assertEquals(2, mapping.executeUpdate());
            }
            UUID importedRoom = UUID.fromString("30000000-0000-0000-0000-000000000007");
            UUID unrelatedRoom = UUID.fromString("30000000-0000-0000-0000-000000000008");
            try (PreparedStatement conversation = connection.prepareStatement(
                    "INSERT INTO chat.conversation(id, kind, title, next_sequence) "
                            + "VALUES (?, 'GROUP', ?, ?)")) {
                conversation.setObject(1, importedRoom);
                conversation.setString(2, "Imported Room");
                conversation.setLong(3, 8);
                conversation.addBatch();
                conversation.setObject(1, unrelatedRoom);
                conversation.setString(2, "Unrelated Room");
                conversation.setLong(3, 2);
                conversation.addBatch();
                conversation.executeBatch();
            }
            try (PreparedStatement member = connection.prepareStatement(
                    "INSERT INTO chat.conversation_member("
                            + "conversation_id, account_id, role, last_read_sequence) "
                            + "VALUES (?, ?, ?, ?)")) {
                member.setObject(1, importedRoom);
                member.setObject(2, imported);
                member.setString(3, "ADMIN");
                member.setLong(4, 3);
                member.addBatch();
                member.setObject(1, unrelatedRoom);
                member.setObject(2, nativeV2);
                member.setString(3, "MEMBER");
                member.setLong(4, 0);
                member.addBatch();
                member.executeBatch();
            }
            try (PreparedStatement mapping = connection.prepareStatement(
                    "INSERT INTO chat.legacy_v1_conversation_map("
                            + "legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('ROOM', ?, ?)")) {
                mapping.setLong(1, 7);
                mapping.setObject(2, importedRoom);
                mapping.addBatch();
                mapping.setLong(1, 8);
                mapping.setObject(2, unrelatedRoom);
                mapping.addBatch();
                mapping.executeBatch();
            }
            seedFriendDirectory(connection, imported, peer);
        }
    }

    private static void seedFriendDirectory(
            Connection connection, UUID imported, UUID peer) throws Exception {
        UUID direct = UUID.fromString("40000000-0000-0000-0000-000000000009");
        UUID device = UUID.fromString("50000000-0000-0000-0000-000000000044");
        UUID firstMessage = UUID.fromString("60000000-0000-0000-0000-000000000101");
        UUID secondMessage = UUID.fromString("60000000-0000-0000-0000-000000000102");
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                    + "VALUES ('" + device + "', '" + peer + "', 'peer-device', 'LEGACY')");
            statement.execute("INSERT INTO chat.conversation(id, kind, next_sequence) "
                    + "VALUES ('" + direct + "', 'DIRECT', 3)");
            statement.execute("INSERT INTO chat.direct_conversation VALUES ('" + direct
                    + "', '" + imported + "', '" + peer + "')");
            statement.execute("INSERT INTO chat.conversation_member("
                    + "conversation_id, account_id, last_read_sequence) VALUES "
                    + "('" + direct + "', '" + imported + "', 0), "
                    + "('" + direct + "', '" + peer + "', 1)");
            statement.execute("INSERT INTO chat.legacy_v1_conversation_map("
                    + "legacy_kind, legacy_conversation_id, conversation_id) "
                    + "VALUES ('FRIENDSHIP', 9, '" + direct + "')");
            for (int sequence = 1; sequence <= 2; sequence++) {
                UUID message = sequence == 1 ? firstMessage : secondMessage;
                statement.execute("INSERT INTO chat.conversation_entry("
                        + "conversation_id, conversation_sequence, entry_kind) VALUES ('"
                        + direct + "', " + sequence + ", 'MESSAGE')");
                statement.execute("INSERT INTO chat.message(id, conversation_id, "
                        + "conversation_sequence, sender_account_id, sender_device_id, "
                        + "client_message_id, message_type, payload, payload_sha256) VALUES ('"
                        + message + "', '" + direct + "', " + sequence + ", '" + peer
                        + "', '" + device + "', 'peer-" + sequence
                        + "', 100, decode('01','hex'), decode('"
                        + "00".repeat(32) + "','hex'))");
                statement.execute("INSERT INTO chat.legacy_v1_message_map(legacy_kind, "
                        + "legacy_message_id, legacy_conversation_id, conversation_id, message_id) "
                        + "VALUES ('FRIENDSHIP', " + (100 + sequence) + ", 9, '"
                        + direct + "', '" + message + "')");
            }
            statement.execute("INSERT INTO chat.contact_request("
                    + "id, requester_account_id, recipient_account_id) VALUES ('"
                    + UUID.fromString("70000000-0000-0000-0000-000000000001") + "', '"
                    + peer + "', '" + imported + "')");
            statement.execute("INSERT INTO chat.legacy_v1_contact_request_map("
                    + "legacy_request_id, contact_request_id) VALUES (70, '"
                    + UUID.fromString("70000000-0000-0000-0000-000000000001") + "')");
        }
    }

    private static int sessionCount(String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM chat.device_session")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String storedDeviceAlias(
            String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT client_device_id FROM chat.device "
                                + "WHERE client_device_id = 'legacy-v1-web'")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
