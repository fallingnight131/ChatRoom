package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.gateway.compatibility.v1.V1ConnectionAttributes;
import com.fallingnight.chat.gateway.compatibility.v1.V1DirectHistoryEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomHistoryEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomRecallEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomReadEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomSearchEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1DirectRecallEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1DirectReadEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1DirectMessageEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1WebLoginHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomDirectoryEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomCreationEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomJoinEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomLeaveEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomMemberListEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomSettingsEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomFilesEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomFileDeletionEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomAdminEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomMessageEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendDirectoryEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestAcceptanceEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestCreationEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestRejectionEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRemovalEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1PendingFriendRequestEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1UserSearchEventSink;
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
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
        try (HikariDataSource dataSource = new HikariDataSource(pool);
                V1RoomPasswordKeyMaterial roomPasswordKey =
                        V1RoomPasswordKeyMaterial.fromEnvironment(Map.of(
                                V1RoomPasswordKeyMaterial.ENVIRONMENT_KEY,
                                Base64.getEncoder().encodeToString(new byte[32])));
                V1CompatibilityModule module = V1CompatibilityModule.create(
                        dataSource,
                        Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC),
                        roomPasswordKey)) {

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
                assertDirectAttachmentHistory(imported);
                removeDirectAttachmentCompatibilityMappings(jdbcUrl, username, password);

                assertUserSearch(imported, false);
                assertRoomSearch(imported);
                long createdRoomId = assertRoomCreationRetryAndConflict(imported);
                assertEquals(1, countQuery(jdbcUrl, username, password,
                        "SELECT count(*) FROM chat.legacy_v1_room_creation creation "
                                + "JOIN chat.legacy_v1_conversation_map mapping "
                                + "ON mapping.conversation_id = creation.conversation_id "
                                + "JOIN chat.group_join_credential credential "
                                + "ON credential.conversation_id = creation.conversation_id "
                                + "WHERE mapping.legacy_kind = 'ROOM' "
                                + "AND mapping.legacy_conversation_id = " + createdRoomId
                                + " AND credential.encoded_password LIKE '$argon2id$%' "
                                + "AND creation.password_idempotency_tag "
                                + "LIKE 'hmac-sha256:v1:%'"));
                assertProtectedRoomJoinAndRecovery(module, imported, createdRoomId);
                assertEquals(1, countQuery(jdbcUrl, username, password,
                        "SELECT count(*) FROM chat.conversation_member member "
                                + "JOIN chat.legacy_v1_conversation_map mapping "
                                + "ON mapping.conversation_id = member.conversation_id "
                                + "WHERE mapping.legacy_kind = 'ROOM' "
                                + "AND mapping.legacy_conversation_id = " + createdRoomId
                                + " AND member.left_at IS NULL"));

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

                assertFriendRejectionSuccess(imported);
                assertFriendRejectionSuccess(imported);
                assertEquals(1, rejectedRequestCount(jdbcUrl, username, password));

                imported.writeInbound(new TextWebSocketFrame(
                        "{\"type\":\"FRIEND_PENDING_REQ\",\"id\":\"pending-2\",\"data\":{}}"));
                imported.runPendingTasks();
                TextWebSocketFrame refreshed = imported.readOutbound();
                try {
                    assertTrue(refreshed.text().contains("\"type\":\"FRIEND_PENDING_RSP\""));
                    assertTrue(refreshed.text().contains("\"requests\":[]"));
                    assertFalse(refreshed.text().contains("10000000-0000"));
                } finally {
                    refreshed.release();
                }

                insertPendingRequest(jdbcUrl, username, password, 71);
                EmbeddedChannel peer = upgradedChannel(module,
                        Runnable::run,
                        AuthenticationAdmissionControl.allowAll(),
                        AuthenticationEventSink.noop());
                try {
                    peer.writeInbound(loginFrame("imported-peer", "java-v2-test-password"));
                    peer.runPendingTasks();
                    ((TextWebSocketFrame) peer.readOutbound()).release();
                    assertUserSearch(imported, true);
                    assertRoomMembersOnline(imported);
                    assertRoomSettings(imported);
                    assertRoomFiles(imported);
                    long roomMessageId = assertRoomMessageFirst(imported, peer);
                    assertRoomMessageDuplicate(imported, peer);

                    imported.writeInbound(new TextWebSocketFrame(
                            "{\"type\":\"FRIEND_ACCEPT_REQ\",\"data\":{"
                                    + "\"requestId\":71,\"fromUsername\":\"spoofed\"}}"));
                    imported.runPendingTasks();
                    TextWebSocketFrame accepted = imported.readOutbound();
                    try {
                        assertTrue(accepted.text().contains("\"type\":\"FRIEND_ACCEPT_RSP\""));
                        assertTrue(accepted.text().contains("\"success\":true"));
                    } finally { accepted.release(); }
                    peer.runPendingTasks();
                    TextWebSocketFrame notification = peer.readOutbound();
                    try {
                        assertTrue(notification.text().contains(
                                "\"type\":\"FRIEND_ACCEPT_NOTIFY\""));
                        assertTrue(notification.text().contains(
                                "\"acceptedBy\":\"imported-v1\""));
                        assertTrue(notification.text().contains(
                                "\"acceptedByDisplay\":\"Imported V1\""));
                        assertFalse(notification.text().contains("spoofed"));
                    } finally { notification.release(); }

                    assertFriendAcceptanceSuccess(imported);
                    peer.runPendingTasks();
                    assertNull(peer.readOutbound());
                    assertEquals(1, acceptedRequestCount(jdbcUrl, username, password));

                    long directMessageId = assertDirectMessageFirst(imported, peer);
                    assertDirectMessageDuplicate(imported, peer);
                    assertDirectReadNotifiesPeer(peer, imported, directMessageId);

                    EmbeddedChannel reconnected = upgradedChannel(module, Runnable::run,
                            AuthenticationAdmissionControl.allowAll(),
                            AuthenticationEventSink.noop());
                    try {
                        reconnected.writeInbound(loginFrame(
                                "imported-v1", "java-v2-test-password"));
                        reconnected.runPendingTasks();
                        ((TextWebSocketFrame) reconnected.readOutbound()).release();
                        assertCreatedRoomRecovered(reconnected, createdRoomId);
                        assertDirectReadRecovered(reconnected, directMessageId);
                        assertRoomHistoryAfterReconnect(reconnected);
                        assertRoomRecallFirst(reconnected, peer, roomMessageId);
                        assertRoomRecallDuplicate(reconnected, peer, roomMessageId);
                        assertRecalledRoomHistoryAfterSequence(reconnected, roomMessageId);
                        assertRoomReadClearsUnread(reconnected);
                        assertRoomFileDeletion(reconnected, peer,
                                jdbcUrl, username, password);
                        assertRoomAdminPromotion(reconnected, peer,
                                jdbcUrl, username, password);
                        assertDirectHistoryAfterReconnect(reconnected);
                        assertDirectRecallFirst(reconnected, peer, directMessageId);
                        assertDirectRecallDuplicate(reconnected, peer, directMessageId);
                        assertRecalledHistoryAfterSequence(reconnected, directMessageId);

                        assertFriendRemovalSuccess(reconnected, "imported-peer");
                        peer.runPendingTasks();
                        TextWebSocketFrame removalNotification = peer.readOutbound();
                        try {
                            assertTrue(removalNotification.text().contains(
                                    "\"type\":\"FRIEND_REMOVE_NOTIFY\""));
                            assertTrue(removalNotification.text().contains(
                                    "\"username\":\"imported-v1\""));
                            assertTrue(removalNotification.text().contains(
                                    "\"displayName\":\"Imported V1\""));
                        } finally { removalNotification.release(); }
                        assertFriendRemovalSuccess(reconnected, "imported-peer");
                        peer.runPendingTasks();
                        assertNull(peer.readOutbound());
                        assertEquals(2, inactiveFriendMembers(jdbcUrl, username, password));
                        assertEquals(4, retainedFriendEntries(jdbcUrl, username, password));
                        assertEmptyFriendList(reconnected);
                        assertEmptyFriendList(peer);

                        EmbeddedChannel newcomer = upgradedChannel(module, Runnable::run,
                                AuthenticationAdmissionControl.allowAll(),
                                AuthenticationEventSink.noop());
                        try {
                            newcomer.writeInbound(loginFrame(
                                    "imported-newcomer", "java-v2-test-password"));
                            newcomer.runPendingTasks();
                            ((TextWebSocketFrame) newcomer.readOutbound()).release();
                            assertFriendRequestSuccess(peer, "imported-newcomer");
                            newcomer.runPendingTasks();
                            TextWebSocketFrame requestNotification = newcomer.readOutbound();
                            try {
                                assertTrue(requestNotification.text().contains(
                                        "\"type\":\"FRIEND_REQUEST_NOTIFY\""));
                                assertTrue(requestNotification.text().contains(
                                        "\"fromUsername\":\"imported-peer\""));
                            } finally { requestNotification.release(); }
                            assertFriendRequestSuccess(peer, "imported-newcomer");
                            newcomer.runPendingTasks();
                            assertNull(newcomer.readOutbound());
                            newcomer.writeInbound(new TextWebSocketFrame(
                                    "{\"type\":\"FRIEND_PENDING_REQ\",\"data\":{}}"));
                            newcomer.runPendingTasks();
                            TextWebSocketFrame newPending = newcomer.readOutbound();
                            try {
                                assertTrue(newPending.text().contains("\"fromUserId\":44"));
                                assertFalse(newPending.text().contains("15000000-0000"));
                            } finally { newPending.release(); }
                        } finally { newcomer.finishAndReleaseAll(); }

                        EmbeddedChannel peerReplacement = upgradedChannel(module,
                                Runnable::run, AuthenticationAdmissionControl.allowAll(),
                                AuthenticationEventSink.noop());
                        try {
                            peerReplacement.writeInbound(loginFrame(
                                    "imported-peer", "java-v2-test-password"));
                            peerReplacement.runPendingTasks();
                            ((TextWebSocketFrame) peerReplacement.readOutbound()).release();
                            assertRoomAdminRecovered(peerReplacement);
                        } finally { peerReplacement.finishAndReleaseAll(); }
                    } finally { reconnected.finishAndReleaseAll(); }
                } finally {
                    peer.finishAndReleaseAll();
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
                assertEquals(8, sessionCount(jdbcUrl, username, password));
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
                V1RoomCreationEventSink.noop(),
                V1RoomJoinEventSink.noop(),
                V1RoomLeaveEventSink.noop(),
                V1RoomMemberListEventSink.noop(),
                V1RoomSettingsEventSink.noop(),
                V1RoomFilesEventSink.noop(),
                V1RoomFileDeletionEventSink.noop(),
                V1RoomAdminEventSink.noop(),
                V1RoomDirectoryEventSink.noop(),
                V1RoomMessageEventSink.noop(),
                V1RoomHistoryEventSink.noop(),
                V1RoomRecallEventSink.noop(),
                V1RoomReadEventSink.noop(),
                V1RoomSearchEventSink.noop(),
                V1FriendDirectoryEventSink.noop(),
                V1PendingFriendRequestEventSink.noop(),
                V1FriendRequestCreationEventSink.noop(),
                V1FriendRequestAcceptanceEventSink.noop(),
                V1FriendRequestRejectionEventSink.noop(),
                V1FriendRemovalEventSink.noop(),
                V1DirectHistoryEventSink.noop(),
                V1DirectRecallEventSink.noop(),
                V1DirectReadEventSink.noop(),
                V1DirectMessageEventSink.noop(),
                V1UserSearchEventSink.noop(),
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

    private static void assertFriendRejectionSuccess(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_REJECT_REQ\",\"data\":{\"requestId\":70}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_REJECT_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertFalse(response.text().contains("10000000-0000"));
        } finally {
            response.release();
        }
    }

    private static void assertFriendAcceptanceSuccess(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_ACCEPT_REQ\",\"data\":{\"requestId\":71}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_ACCEPT_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertFalse(response.text().contains("10000000-0000"));
        } finally { response.release(); }
    }

    private static void assertFriendRemovalSuccess(
            EmbeddedChannel channel, String targetUsername) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_REMOVE_REQ\",\"data\":{\"username\":\""
                        + targetUsername + "\"}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_REMOVE_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"username\":\""
                    + targetUsername + "\""));
            assertFalse(response.text().contains("10000000-0000"));
        } finally { response.release(); }
    }

    private static long assertDirectMessageFirst(
            EmbeddedChannel sender, EmbeddedChannel recipient) {
        sendDirectMessage(sender);
        sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound();
        TextWebSocketFrame senderLive = sender.readOutbound();
        long messageId;
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_CHAT_SEND_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":false"));
            assertTrue(response.text().contains("\"friendshipId\":9"));
            messageId = numericDataField(response.text(), "id");
            assertFalse(response.text().contains("40000000-0000"));
            assertTrue(senderLive.text().contains("\"type\":\"FRIEND_CHAT_MSG\""));
            assertTrue(senderLive.text().contains("\"sender\":\"imported-v1\""));
            assertTrue(senderLive.text().contains("\"content\":\"hello Java V1\""));
        } finally { response.release(); senderLive.release(); }
        recipient.runPendingTasks();
        TextWebSocketFrame recipientLive = recipient.readOutbound();
        try {
            assertTrue(recipientLive.text().contains("\"type\":\"FRIEND_CHAT_MSG\""));
            assertTrue(recipientLive.text().contains("\"senderName\":\"Imported V1\""));
            assertFalse(recipientLive.text().contains("10000000-0000"));
        } finally { recipientLive.release(); }
        return messageId;
    }

    private static long assertRoomMessageFirst(
            EmbeddedChannel sender, EmbeddedChannel recipient) {
        sendRoomMessage(sender); sender.runPendingTasks();
        Object outbound = sender.readOutbound();
        if (outbound instanceof io.netty.handler.codec.http.websocketx.CloseWebSocketFrame close) {
            String reason = close.reasonText(); close.release();
            throw new AssertionError("room message closed: " + reason);
        }
        TextWebSocketFrame response = (TextWebSocketFrame) outbound;
        TextWebSocketFrame senderLive = sender.readOutbound();
        long messageId;
        try {
            assertTrue(response.text().contains("\"type\":\"CHAT_SEND_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":false"));
            assertTrue(response.text().contains("\"roomId\":7"));
            messageId = numericDataField(response.text(), "id");
            assertTrue(senderLive.text().contains("\"type\":\"CHAT_MSG\""));
            assertTrue(senderLive.text().contains("\"sender\":\"imported-v1\""));
            assertTrue(senderLive.text().contains("\"sequence\":8"));
        } finally { response.release(); senderLive.release(); }
        recipient.runPendingTasks(); TextWebSocketFrame live = recipient.readOutbound();
        try {
            assertTrue(live.text().contains("\"content\":\"hello Java room\""));
            assertFalse(live.text().contains("30000000-0000"));
        } finally { live.release(); }
        return messageId;
    }

    private static void assertRoomMessageDuplicate(
            EmbeddedChannel sender, EmbeddedChannel recipient) {
        sendRoomMessage(sender); sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound();
        try { assertTrue(response.text().contains("\"duplicate\":true")); }
        finally { response.release(); }
        assertNull(sender.readOutbound()); recipient.runPendingTasks(); assertNull(recipient.readOutbound());
    }

    private static void assertRoomHistoryAfterReconnect(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"HISTORY_REQ\",\"data\":{\"roomId\":7,"
                        + "\"count\":50,\"afterSequence\":5}}"));
        channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"HISTORY_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"content\":\"hello Java room\""));
            assertTrue(response.text().contains("\"content\":\"design.pdf\""));
            assertTrue(response.text().contains("\"contentType\":\"file\""));
            assertTrue(response.text().contains("\"fileId\":501"));
            assertTrue(response.text().contains("\"fileName\":\"design.pdf\""));
            assertTrue(response.text().contains("\"fileSize\":321"));
            assertTrue(response.text().contains("\"fileId\":500"));
            assertTrue(response.text().contains("\"fileName\":\"cleared.zip\""));
            assertTrue(response.text().contains("\"fileCleared\":true"));
            assertTrue(response.text().contains("\"clearReason\":\"source file cleared\""));
            assertTrue(response.text().contains("\"sequence\":8"));
            assertTrue(response.text().contains("\"nextSequence\":8"));
            assertTrue(response.text().contains("\"lastSequence\":8"));
            assertFalse(response.text().contains("10000000-0000"));
        } finally { response.release(); }
    }

    private static void assertRoomRecallFirst(
            EmbeddedChannel sender, EmbeddedChannel recipient, long messageId) {
        sendRoomRecall(sender, messageId); sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound(), echo = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"RECALL_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":false"));
            assertTrue(response.text().contains("\"mutationSequence\":9"));
            assertTrue(echo.text().contains("\"type\":\"RECALL_NOTIFY\""));
            assertTrue(echo.text().contains("\"username\":\"imported-v1\""));
            assertTrue(echo.text().contains("\"messageId\":" + messageId));
        } finally { response.release(); echo.release(); }
        recipient.runPendingTasks(); TextWebSocketFrame notification = recipient.readOutbound();
        try {
            assertTrue(notification.text().contains("\"type\":\"RECALL_NOTIFY\""));
            assertTrue(notification.text().contains("\"mutationSequence\":9"));
            assertFalse(notification.text().contains("10000000-0000"));
        } finally { notification.release(); }
    }

    private static void assertRoomRecallDuplicate(
            EmbeddedChannel sender, EmbeddedChannel recipient, long messageId) {
        sendRoomRecall(sender, messageId); sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":true"));
            assertTrue(response.text().contains("\"mutationSequence\":9"));
        } finally { response.release(); }
        assertNull(sender.readOutbound()); recipient.runPendingTasks();
        assertNull(recipient.readOutbound());
    }

    private static void assertRecalledRoomHistoryAfterSequence(
            EmbeddedChannel channel, long messageId) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"HISTORY_REQ\",\"data\":{\"roomId\":7,"
                        + "\"count\":50,\"afterSequence\":8}}"));
        channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"id\":" + messageId));
            assertTrue(response.text().contains("\"recalled\":true"));
            assertTrue(response.text().contains("\"sequence\":8"));
            assertTrue(response.text().contains("\"mutationSequence\":9"));
            assertTrue(response.text().contains("\"syncSequence\":9"));
            assertTrue(response.text().contains("\"nextSequence\":9"));
            assertTrue(response.text().contains("\"lastSequence\":9"));
        } finally { response.release(); }
    }

    private static void sendRoomRecall(EmbeddedChannel sender, long messageId) {
        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"RECALL_REQ\",\"data\":{\"roomId\":7,\"messageId\":"
                        + messageId + "}}"));
    }

    private static void assertRoomReadClearsUnread(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"MARK_ROOM_READ\",\"data\":{\"roomId\":7}}"));
        channel.runPendingTasks(); assertNull(channel.readOutbound());
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
        channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"ROOM_LIST_RSP\""));
            assertTrue(response.text().contains("\"roomId\":7"));
            assertTrue(response.text().contains("\"unread\":0"));
        } finally { response.release(); }
    }

    private static void sendRoomMessage(EmbeddedChannel sender) {
        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"CHAT_MSG\",\"id\":\"room-envelope\",\"data\":{"
                        + "\"roomId\":7,\"sender\":\"spoofed\","
                        + "\"clientMessageId\":\"room-client-1\","
                        + "\"content\":\"hello Java room\",\"contentType\":\"text\"}}"));
    }

    private static void assertDirectMessageDuplicate(
            EmbeddedChannel sender, EmbeddedChannel recipient) {
        sendDirectMessage(sender);
        sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":true"));
        } finally { response.release(); }
        assertNull(sender.readOutbound());
        recipient.runPendingTasks();
        assertNull(recipient.readOutbound());
    }

    private static void sendDirectMessage(EmbeddedChannel sender) {
        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_CHAT_MSG\",\"id\":\"direct-envelope\",\"data\":{"
                        + "\"friendUsername\":\"imported-peer\","
                        + "\"clientMessageId\":\"direct-client-1\","
                        + "\"content\":\"hello Java V1\",\"contentType\":\"text\"}}"));
    }

    private static void assertDirectReadNotifiesPeer(
            EmbeddedChannel reader, EmbeddedChannel recipient, long messageId) {
        reader.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"MARK_FRIEND_READ\",\"data\":{\"friendshipId\":9}}"));
        reader.runPendingTasks();
        assertNull(reader.readOutbound());
        recipient.runPendingTasks();
        TextWebSocketFrame notification = recipient.readOutbound();
        try {
            assertTrue(notification.text().contains("\"type\":\"FRIEND_READ_NOTIFY\""));
            assertTrue(notification.text().contains("\"friendshipId\":9"));
            assertTrue(notification.text().contains(
                    "\"readerUsername\":\"imported-peer\""));
            assertTrue(notification.text().contains("\"lastReadMessageId\":" + messageId));
        } finally { notification.release(); }
    }

    private static void assertDirectReadRecovered(EmbeddedChannel channel, long messageId) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_LIST_REQ\",\"data\":{}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_LIST_RSP\""));
            assertTrue(response.text().contains("\"friendshipId\":9"));
            assertTrue(response.text().contains(
                    "\"peerLastReadMessageId\":" + messageId));
        } finally { response.release(); }
    }

    private static void assertDirectHistoryAfterReconnect(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_HISTORY_REQ\",\"data\":{"
                        + "\"friendUsername\":\"imported-peer\",\"count\":1,"
                        + "\"afterSequence\":2}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_HISTORY_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"friendshipId\":9"));
            assertTrue(response.text().contains("\"clientMessageId\":\"direct-client-1\""));
            assertTrue(response.text().contains("\"content\":\"hello Java V1\""));
            assertTrue(response.text().contains("\"contentType\":\"text\""));
            assertTrue(response.text().contains("\"sequence\":3"));
            assertTrue(response.text().contains("\"syncSequence\":3"));
            assertTrue(response.text().contains("\"nextSequence\":3"));
            assertTrue(response.text().contains("\"lastSequence\":3"));
            assertTrue(response.text().contains("\"hasMore\":false"));
            assertFalse(response.text().contains("40000000-0000"));
        } finally { response.release(); }
    }

    private static void assertDirectAttachmentHistory(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_HISTORY_REQ\",\"data\":{"
                        + "\"friendUsername\":\"native-v2\",\"count\":10,"
                        + "\"afterSequence\":0}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_HISTORY_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"friendshipId\":10"));
            assertTrue(response.text().contains("\"fileId\":-601"));
            assertTrue(response.text().contains("\"fileName\":\"direct-ready.pdf\""));
            assertTrue(response.text().contains("\"fileSize\":222"));
            assertTrue(response.text().contains("\"fileId\":-600"));
            assertTrue(response.text().contains("\"fileName\":\"direct-cleared.zip\""));
            assertTrue(response.text().contains("\"fileCleared\":true"));
            assertTrue(response.text().contains(
                    "\"clearReason\":\"source direct file cleared\""));
            assertTrue(response.text().contains("\"nextSequence\":2"));
            assertTrue(response.text().contains("\"lastSequence\":2"));
            assertFalse(response.text().contains("73000000-0000"));
            assertFalse(response.text().contains("legacy/friendship-10"));
        } finally { response.release(); }
    }

    private static void assertRoomFileDeletion(EmbeddedChannel sender,
            EmbeddedChannel recipient, String url, String user, String password) throws Exception {
        recipient.runPendingTasks();
        assertNull(recipient.readOutbound());
        recipient.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_FILES_DELETE_REQ\",\"data\":{\"roomId\":7,"
                        + "\"fileIds\":[501],\"clientOperationId\":\"member-delete-501\"}}"));
        recipient.runPendingTasks();
        TextWebSocketFrame denied = recipient.readOutbound();
        try {
            assertTrue(denied.text().contains("\"type\":\"ROOM_FILES_DELETE_RSP\""));
            assertTrue(denied.text().contains("\"success\":false"));
            assertTrue(denied.text().contains("ADMIN_DELETE_ACCESS_DENIED"));
            assertTrue(recipient.isActive());
        } finally { denied.release(); }
        assertEquals(0, countQuery(url, user, password,
                "SELECT count(*) FROM chat.messages_deleted_event "
                        + "WHERE client_operation_id = 'member-delete-501'"));

        TextWebSocketFrame request = new TextWebSocketFrame(
                "{\"type\":\"ROOM_FILES_DELETE_REQ\",\"data\":{\"roomId\":7,"
                        + "\"fileIds\":[501],\"clientOperationId\":\"delete-file-501\"}}");
        sender.writeInbound(request); sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"ROOM_FILES_DELETE_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":false"));
            assertTrue(response.text().contains("\"deletedCount\":1"));
            assertTrue(response.text().contains("\"messageIds\":[701]"));
            assertTrue(response.text().contains("\"deletedFileIds\":[501]"));
            assertTrue(response.text().contains("\"sequence\":10"));
            assertTrue(response.text().contains("\"usedFileSpace\":0"));
            assertTrue(response.text().contains("\"maxFileSpace\":8192"));
            assertFalse(response.text().contains("71000000-0000"));
        } finally { response.release(); }

        recipient.runPendingTasks();
        TextWebSocketFrame deletion = recipient.readOutbound(), files = recipient.readOutbound();
        try {
            assertTrue(deletion.text().contains("\"type\":\"DELETE_MSGS_NOTIFY\""));
            assertTrue(deletion.text().contains("\"messageIds\":[701]"));
            assertTrue(deletion.text().contains("\"syncSequence\":10"));
            assertTrue(files.text().contains("\"type\":\"ROOM_FILES_NOTIFY\""));
            assertTrue(files.text().contains("\"deletedFileIds\":[501]"));
        } finally { deletion.release(); files.release(); }

        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_FILES_DELETE_REQ\",\"data\":{\"roomId\":7,"
                        + "\"fileIds\":[501],\"clientOperationId\":\"delete-file-501\"}}"));
        sender.runPendingTasks(); response = sender.readOutbound();
        try { assertTrue(response.text().contains("\"duplicate\":true")); }
        finally { response.release(); }
        recipient.runPendingTasks(); assertNull(recipient.readOutbound());

        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_FILES_DELETE_REQ\",\"data\":{\"roomId\":7,"
                        + "\"fileIds\":[500],\"clientOperationId\":\"delete-file-501\"}}"));
        sender.runPendingTasks(); response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":false"));
            assertTrue(response.text().contains("CLIENT_OPERATION_ID_CONFLICT"));
        } finally { response.release(); }

        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.attachment WHERE id = "
                        + "'71000000-0000-0000-0000-000000000501' "
                        + "AND state = 'REVOKED' AND revoked_at IS NOT NULL "
                        + "AND object_deleted_at IS NULL"));
        assertEquals(0, countQuery(url, user, password,
                "SELECT count(*) FROM chat.legacy_v1_message_map "
                        + "WHERE legacy_kind = 'ROOM' AND legacy_message_id = 701"));
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.messages_deleted_event deletion "
                        + "JOIN chat.legacy_v1_deletion_event_map mapping "
                        + "ON mapping.conversation_id = deletion.conversation_id "
                        + "AND mapping.conversation_sequence = deletion.conversation_sequence "
                        + "WHERE deletion.client_operation_id = 'delete-file-501' "
                        + "AND deletion.file_ids = '[501]'::jsonb"));

        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"HISTORY_REQ\",\"data\":{\"roomId\":7,"
                        + "\"count\":10,\"afterSequence\":9}}"));
        sender.runPendingTasks(); response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"eventType\":\"messagesDeleted\""));
            assertTrue(response.text().contains("\"deletedFileIds\":[501]"));
            assertTrue(response.text().contains("\"sequence\":10"));
            assertTrue(response.text().contains("\"nextSequence\":10"));
            assertTrue(response.text().contains("\"lastSequence\":10"));
            assertFalse(response.text().contains("design.pdf"));
        } finally { response.release(); }

        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_FILES_REQ\",\"data\":{\"roomId\":7}}"));
        sender.runPendingTasks(); response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"usedFileSpace\":0"));
            assertFalse(response.text().contains("\"fileId\":501"));
        } finally { response.release(); }
    }

    private static void assertRoomAdminPromotion(EmbeddedChannel owner,
            EmbeddedChannel target, String url, String user, String password) throws Exception {
        String request = "{\"type\":\"SET_ADMIN_REQ\",\"data\":{\"roomId\":7,"
                + "\"username\":\"imported-peer\",\"isAdmin\":true}}";
        owner.writeInbound(new TextWebSocketFrame(request)); owner.runPendingTasks();
        TextWebSocketFrame response = owner.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"SET_ADMIN_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":true"));
            assertTrue(response.text().contains("\"username\":\"imported-peer\""));
            assertFalse(response.text().contains("15000000-0000"));
        } finally { response.release(); }
        target.runPendingTasks(); TextWebSocketFrame notification = target.readOutbound();
        try {
            assertTrue(notification.text().contains("\"type\":\"ADMIN_STATUS\""));
            assertTrue(notification.text().contains("\"roomId\":7"));
            assertTrue(notification.text().contains("\"isAdmin\":true"));
        } finally { notification.release(); }
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.conversation_member member "
                        + "JOIN chat.legacy_v1_conversation_map mapping "
                        + "ON mapping.conversation_id = member.conversation_id "
                        + "WHERE mapping.legacy_kind = 'ROOM' "
                        + "AND mapping.legacy_conversation_id = 7 "
                        + "AND member.account_id = "
                        + "'15000000-0000-0000-0000-000000000044' "
                        + "AND member.role = 'ADMIN' AND member.left_at IS NULL"));

        owner.writeInbound(new TextWebSocketFrame(request)); owner.runPendingTasks();
        response = owner.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":false"));
        } finally { response.release(); }
        target.runPendingTasks(); assertNull(target.readOutbound());

        owner.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"USER_LIST_REQ\",\"data\":{\"roomId\":7}}"));
        owner.runPendingTasks(); response = owner.readOutbound();
        try {
            assertEquals(2, occurrences(response.text(), "\"isAdmin\":true"));
            assertFalse(response.text().contains("15000000-0000"));
        } finally { response.release(); }
    }

    private static void assertRoomAdminRecovered(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
        channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"roomId\":7"));
            assertTrue(response.text().contains("\"isAdmin\":true"));
            assertFalse(response.text().contains("15000000-0000"));
        } finally { response.release(); }
    }

    private static void assertDirectRecallFirst(
            EmbeddedChannel sender, EmbeddedChannel recipient, long messageId) {
        sendDirectRecall(sender, messageId);
        sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_RECALL_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":false"));
            assertTrue(response.text().contains("\"messageId\":" + messageId));
            assertTrue(response.text().contains("\"mutationSequence\":4"));
            assertFalse(response.text().contains("spoofed-peer"));
        } finally { response.release(); }
        recipient.runPendingTasks();
        TextWebSocketFrame notification = recipient.readOutbound();
        try {
            assertTrue(notification.text().contains("\"type\":\"FRIEND_RECALL_NOTIFY\""));
            assertTrue(notification.text().contains("\"messageId\":" + messageId));
            assertTrue(notification.text().contains("\"friendUsername\":\"imported-v1\""));
            assertTrue(notification.text().contains("\"mutationSequence\":4"));
        } finally { notification.release(); }
    }

    private static void assertDirectRecallDuplicate(
            EmbeddedChannel sender, EmbeddedChannel recipient, long messageId) {
        sendDirectRecall(sender, messageId);
        sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":true"));
            assertTrue(response.text().contains("\"mutationSequence\":4"));
        } finally { response.release(); }
        recipient.runPendingTasks();
        assertNull(recipient.readOutbound());
    }

    private static void sendDirectRecall(EmbeddedChannel sender, long messageId) {
        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_RECALL_REQ\",\"data\":{\"messageId\":"
                        + messageId + ",\"friendUsername\":\"spoofed-peer\"}}"));
    }

    private static void assertRecalledHistoryAfterSequence(
            EmbeddedChannel channel, long messageId) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_HISTORY_REQ\",\"data\":{"
                        + "\"friendUsername\":\"imported-peer\",\"count\":10,"
                        + "\"afterSequence\":3}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"messageId\":" + messageId)
                    || response.text().contains("\"id\":" + messageId));
            assertTrue(response.text().contains("\"recalled\":true"));
            assertTrue(response.text().contains("\"sequence\":3"));
            assertTrue(response.text().contains("\"mutationSequence\":4"));
            assertTrue(response.text().contains("\"syncSequence\":4"));
            assertTrue(response.text().contains("\"nextSequence\":4"));
            assertTrue(response.text().contains("\"lastSequence\":4"));
            assertTrue(response.text().contains("\"hasMore\":false"));
        } finally { response.release(); }
    }

    private static long numericDataField(String json, String field) {
        var matcher = Pattern.compile("\\\"data\\\":\\{[^}]*\\\""
                + Pattern.quote(field) + "\\\":(\\d+)").matcher(json);
        assertTrue(matcher.find());
        return Long.parseLong(matcher.group(1));
    }

    private static void assertEmptyFriendList(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_LIST_REQ\",\"data\":{}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_LIST_RSP\""));
            assertTrue(response.text().contains("\"friends\":[]"));
            assertFalse(response.text().contains("10000000-0000"));
        } finally { response.release(); }
    }

    private static void assertUserSearch(EmbeddedChannel channel, boolean online) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"USER_SEARCH_REQ\",\"data\":{\"keyword\":\"IMPORTED-PEER\"}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"USER_SEARCH_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"userId\":44"));
            assertTrue(response.text().contains("\"username\":\"imported-peer\""));
            assertTrue(response.text().contains("\"online\":" + online));
            assertFalse(response.text().contains("10000000-0000"));
            assertFalse(response.text().contains("native-v2"));
        } finally { response.release(); }
    }

    private static void assertRoomSearch(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_SEARCH_REQ\",\"data\":{\"keyword\":\"Imported\"}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"ROOM_SEARCH_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"roomId\":7"));
            assertTrue(response.text().contains("\"roomName\":\"Imported Room\""));
            assertTrue(response.text().contains("\"creatorId\":42"));
            assertTrue(response.text().contains("\"memberCount\":2"));
            assertFalse(response.text().contains("30000000-0000"));
            assertFalse(response.text().contains("Unrelated Room"));
        } finally { response.release(); }
    }

    private static long assertRoomCreationRetryAndConflict(EmbeddedChannel channel) {
        String first = "{\"type\":\"CREATE_ROOM_REQ\",\"id\":\"room-create-1\","
                + "\"data\":{\"roomName\":\"Java Protected Room\","
                + "\"password\":\"room-secret\"}}";
        channel.writeInbound(new TextWebSocketFrame(first)); channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound(); long roomId;
        try {
            assertTrue(response.text().contains("\"type\":\"CREATE_ROOM_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":false"));
            assertTrue(response.text().contains("\"isAdmin\":true"));
            roomId = numericDataField(response.text(), "roomId");
        } finally { response.release(); }
        channel.writeInbound(new TextWebSocketFrame(first)); channel.runPendingTasks();
        TextWebSocketFrame duplicate = channel.readOutbound();
        try {
            assertTrue(duplicate.text().contains("\"duplicate\":true"));
            assertEquals(roomId, numericDataField(duplicate.text(), "roomId"));
        } finally { duplicate.release(); }
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"CREATE_ROOM_REQ\",\"id\":\"room-create-1\","
                        + "\"data\":{\"roomName\":\"Conflicting Room\","
                        + "\"password\":\"room-secret\"}}"));
        channel.runPendingTasks(); TextWebSocketFrame conflict = channel.readOutbound();
        try {
            assertTrue(conflict.text().contains("\"success\":false"));
            assertTrue(conflict.text().contains(
                    "\"errorCode\":\"CLIENT_REQUEST_ID_CONFLICT\""));
        } finally { conflict.release(); }
        return roomId;
    }

    private static void assertCreatedRoomRecovered(EmbeddedChannel channel, long roomId) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
        channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"roomId\":" + roomId));
            assertTrue(response.text().contains(
                    "\"roomName\":\"Java Protected Room\""));
            assertTrue(response.text().contains("\"isAdmin\":true"));
        } finally { response.release(); }
    }

    private static void assertProtectedRoomJoinAndRecovery(
            V1CompatibilityModule module, EmbeddedChannel owner, long roomId) {
        EmbeddedChannel joining = upgradedChannel(module, Runnable::run,
                AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
        try {
            joining.writeInbound(loginFrame("imported-newcomer", "java-v2-test-password"));
            joining.runPendingTasks(); ((TextWebSocketFrame) joining.readOutbound()).release();

            joining.writeInbound(joinFrame(roomId, null)); joining.runPendingTasks();
            TextWebSocketFrame missing = joining.readOutbound();
            try {
                assertTrue(missing.text().contains("\"errorCode\":\"PASSWORD_REQUIRED\""));
                assertTrue(missing.text().contains("\"needPassword\":true"));
            } finally { missing.release(); }
            owner.runPendingTasks(); assertNull(owner.readOutbound());

            joining.writeInbound(joinFrame(roomId, "wrong-password")); joining.runPendingTasks();
            TextWebSocketFrame wrong = joining.readOutbound();
            try {
                assertTrue(wrong.text().contains("\"errorCode\":\"INVALID_PASSWORD\""));
                assertTrue(wrong.text().contains("\"needPassword\":true"));
            } finally { wrong.release(); }
            owner.runPendingTasks(); assertNull(owner.readOutbound());

            joining.writeInbound(joinFrame(roomId, "room-secret")); joining.runPendingTasks();
            TextWebSocketFrame accepted = joining.readOutbound();
            try {
                assertTrue(accepted.text().contains("\"success\":true"));
                assertTrue(accepted.text().contains("\"newJoin\":true"));
                assertTrue(accepted.text().contains("\"isAdmin\":false"));
            } finally { accepted.release(); }
            owner.runPendingTasks(); TextWebSocketFrame notification = owner.readOutbound();
            try {
                assertTrue(notification.text().contains("\"type\":\"USER_JOINED\""));
                assertTrue(notification.text().contains(
                        "\"username\":\"imported-newcomer\""));
            } finally { notification.release(); }

            joining.writeInbound(joinFrame(roomId, null)); joining.runPendingTasks();
            TextWebSocketFrame duplicate = joining.readOutbound();
            try {
                assertTrue(duplicate.text().contains("\"success\":true"));
                assertTrue(duplicate.text().contains("\"newJoin\":false"));
            } finally { duplicate.release(); }
            owner.runPendingTasks(); assertNull(owner.readOutbound());
        } finally { joining.finishAndReleaseAll(); }

        EmbeddedChannel replacement = upgradedChannel(module, Runnable::run,
                AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
        try {
            replacement.writeInbound(loginFrame(
                    "imported-newcomer", "java-v2-test-password"));
            replacement.runPendingTasks();
            ((TextWebSocketFrame) replacement.readOutbound()).release();
            replacement.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
            replacement.runPendingTasks(); TextWebSocketFrame rooms = replacement.readOutbound();
            try {
                assertTrue(rooms.text().contains("\"roomId\":" + roomId));
                assertTrue(rooms.text().contains("\"roomName\":\"Java Protected Room\""));
                assertTrue(rooms.text().contains("\"isAdmin\":false"));
            } finally { rooms.release(); }
            replacement.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"LEAVE_ROOM\",\"data\":{\"roomId\":" + roomId + "}}"));
            replacement.runPendingTasks(); TextWebSocketFrame left = replacement.readOutbound();
            try {
                assertTrue(left.text().contains("\"type\":\"LEAVE_ROOM_RSP\""));
                assertTrue(left.text().contains("\"success\":true"));
            } finally { left.release(); }
            owner.runPendingTasks(); TextWebSocketFrame notification = owner.readOutbound();
            try {
                assertTrue(notification.text().contains("\"type\":\"USER_LEFT\""));
                assertTrue(notification.text().contains(
                        "\"username\":\"imported-newcomer\""));
            } finally { notification.release(); }
            replacement.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"LEAVE_ROOM\",\"data\":{\"roomId\":" + roomId + "}}"));
            replacement.runPendingTasks(); TextWebSocketFrame repeated = replacement.readOutbound();
            try { assertTrue(repeated.text().contains("\"success\":true")); }
            finally { repeated.release(); }
            owner.runPendingTasks(); assertNull(owner.readOutbound());
        } finally { replacement.finishAndReleaseAll(); }

        EmbeddedChannel afterLeave = upgradedChannel(module, Runnable::run,
                AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
        try {
            afterLeave.writeInbound(loginFrame(
                    "imported-newcomer", "java-v2-test-password"));
            afterLeave.runPendingTasks();
            ((TextWebSocketFrame) afterLeave.readOutbound()).release();
            afterLeave.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
            afterLeave.runPendingTasks(); TextWebSocketFrame rooms = afterLeave.readOutbound();
            try {
                assertFalse(rooms.text().contains("\"roomId\":" + roomId));
                assertFalse(rooms.text().contains("Java Protected Room"));
            } finally { rooms.release(); }
        } finally { afterLeave.finishAndReleaseAll(); }
    }

    private static void assertRoomMembersOnline(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"USER_LIST_REQ\",\"data\":{\"roomId\":7}}"));
        channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"USER_LIST_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"username\":\"imported-v1\""));
            assertTrue(response.text().contains("\"username\":\"imported-peer\""));
            assertEquals(2, occurrences(response.text(), "\"isOnline\":true"));
            assertTrue(response.text().contains("\"isAdmin\":true"));
            assertFalse(response.text().contains("10000000-0000"));
        } finally { response.release(); }
    }

    private static void assertRoomSettings(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_SETTINGS_REQ\",\"data\":{\"roomId\":7}}"));
        channel.runPendingTasks(); TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"ROOM_SETTINGS_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"roomId\":7"));
            assertTrue(response.text().contains("\"maxFileSize\":2048"));
            assertTrue(response.text().contains("\"totalFileSpace\":8192"));
            assertTrue(response.text().contains("\"maxFileCount\":42"));
            assertTrue(response.text().contains("\"maxMembers\":73"));
            assertFalse(response.text().contains("10000000-0000"));
        } finally { response.release(); }
    }

    private static void assertRoomFiles(EmbeddedChannel channel) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_FILES_REQ\",\"data\":{\"roomId\":7}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"ROOM_FILES_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"roomId\":7"));
            assertTrue(response.text().contains("\"fileId\":501"));
            assertTrue(response.text().contains("\"fileName\":\"design.pdf\""));
            assertTrue(response.text().contains("\"fileSize\":321"));
            assertTrue(response.text().contains("\"cleared\":false"));
            assertTrue(response.text().contains(
                    "\"createdAt\":\"2026-08-11 01:02:03\""));
            assertTrue(response.text().contains("\"usedFileSpace\":321"));
            assertTrue(response.text().contains("\"maxFileSpace\":8192"));
            assertFalse(response.text().contains("\"fileId\":500"));
            assertFalse(response.text().contains("cleared.zip"));
            assertFalse(response.text().contains("71000000-0000"));
            assertFalse(response.text().contains("attachments/71000000"));
        } finally { response.release(); }
    }

    private static int occurrences(String value, String needle) {
        int count = 0, offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++; offset += needle.length();
        }
        return count;
    }

    private static TextWebSocketFrame joinFrame(long roomId, String password) {
        return new TextWebSocketFrame("{\"type\":\"JOIN_ROOM_REQ\",\"id\":\"join-"
                + roomId + "\",\"data\":{\"roomId\":" + roomId
                + (password == null ? "" : ",\"password\":\"" + password + "\"")
                + "}}");
    }

    private static void assertFriendRequestSuccess(
            EmbeddedChannel channel, String targetUsername) {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_REQUEST_REQ\",\"data\":{\"username\":\""
                        + targetUsername + "\"}}"));
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"FRIEND_REQUEST_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
        } finally { response.release(); }
    }

    private static void seedV1CompatibilityAccounts(
            String url, String user, String password) throws Exception {
        UUID imported = UUID.fromString("10000000-0000-0000-0000-000000000042");
        UUID peer = UUID.fromString("15000000-0000-0000-0000-000000000044");
        UUID newcomer = UUID.fromString("16000000-0000-0000-0000-000000000045");
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
                account.setObject(1, newcomer);
                account.setString(2, "imported-newcomer");
                account.setString(3, "Imported Newcomer");
                account.setString(4, HASH);
                account.addBatch();
                account.executeBatch();
            }
            try (PreparedStatement mapping = connection.prepareStatement(
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (42, ?), (44, ?), (45, ?)")) {
                mapping.setObject(1, imported);
                mapping.setObject(2, peer);
                mapping.setObject(3, newcomer);
                assertEquals(3, mapping.executeUpdate());
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
                member.setString(3, "OWNER");
                member.setLong(4, 3);
                member.addBatch();
                member.setObject(1, importedRoom);
                member.setObject(2, peer);
                member.setString(3, "MEMBER");
                member.setLong(4, 0);
                member.addBatch();
                member.setObject(1, unrelatedRoom);
                member.setObject(2, nativeV2);
                member.setString(3, "MEMBER");
                member.setLong(4, 0);
                member.addBatch();
                member.executeBatch();
            }
            try (PreparedStatement policy = connection.prepareStatement(
                    "UPDATE chat.group_resource_policy SET max_file_size = 2048, "
                            + "total_file_space = 8192, max_file_count = 42 "
                            + "WHERE conversation_id = ?")) {
                policy.setObject(1, importedRoom); assertEquals(1, policy.executeUpdate());
            }
            try (PreparedStatement policy = connection.prepareStatement(
                    "INSERT INTO chat.group_admission_policy(conversation_id, max_members) "
                            + "VALUES (?, ?)")) {
                policy.setObject(1, importedRoom); policy.setInt(2, 73); policy.addBatch();
                policy.setObject(1, unrelatedRoom); policy.setInt(2, 50); policy.addBatch();
                assertEquals(2, policy.executeBatch().length);
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
            seedRoomAttachment(connection, importedRoom, peer);
            seedDirectAttachmentHistory(connection, imported, nativeV2);
        }
    }

    private static void seedDirectAttachmentHistory(
            Connection connection, UUID actor, UUID target) throws Exception {
        UUID conversation = UUID.fromString("40000000-0000-0000-0000-000000000010");
        UUID device = UUID.fromString("50000000-0000-0000-0000-000000000043");
        try (PreparedStatement mapping = connection.prepareStatement(
                "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                        + "VALUES (43, ?)")) {
            mapping.setObject(1, target);
            assertEquals(1, mapping.executeUpdate());
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                    + "VALUES ('" + device + "', '" + target
                    + "', 'attachment-peer-device', 'LEGACY')");
            statement.execute("INSERT INTO chat.conversation(id, kind, next_sequence) VALUES ('"
                    + conversation + "', 'DIRECT', 3)");
            statement.execute("INSERT INTO chat.direct_conversation VALUES ('" + conversation
                    + "', '" + actor + "', '" + target + "')");
            statement.execute("INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, last_read_sequence) VALUES ('" + conversation + "', '"
                    + actor + "', 0), ('" + conversation + "', '" + target + "', 0)");
            statement.execute("INSERT INTO chat.legacy_v1_conversation_map(legacy_kind, "
                    + "legacy_conversation_id, conversation_id) VALUES ('FRIENDSHIP', 10, '"
                    + conversation + "')");
        }
        seedDirectAttachment(connection, conversation, target, device,
                1, 801, 601, "direct-ready.pdf", 222, true);
        seedDirectAttachment(connection, conversation, target, device,
                2, 800, 600, "direct-cleared.zip", 111, false);
    }

    private static void seedDirectAttachment(Connection connection, UUID conversation,
            UUID owner, UUID device, long sequence, long messageId, long fileId,
            String fileName, long size, boolean ready) throws Exception {
        UUID attachment = UUID.fromString("73000000-0000-0000-0000-"
                + String.format("%012d", fileId));
        UUID message = UUID.fromString("74000000-0000-0000-0000-"
                + String.format("%012d", messageId));
        String stateColumns = ready
                ? "object_key, media_type, content_sha256, ready_at"
                : "unavailable_at, unavailable_reason";
        String stateValues = ready
                ? "'legacy/friendship-10/file-" + fileId
                        + "', 'application/octet-stream', decode('" + "22".repeat(32)
                        + "', 'hex'), TIMESTAMPTZ '2026-08-09 01:02:04+00'"
                : "TIMESTAMPTZ '2026-08-09 01:02:04+00', 'source direct file cleared'";
        try (PreparedStatement file = connection.prepareStatement(
                "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                        + "owner_device_id, client_attachment_id, file_name, byte_size, state, "
                        + "created_at, " + stateColumns + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, "
                        + "TIMESTAMPTZ '2026-08-09 01:02:03+00', " + stateValues + ")")) {
            file.setObject(1, attachment);
            file.setObject(2, conversation);
            file.setObject(3, owner);
            file.setObject(4, device);
            file.setString(5, "legacy-direct-file-" + fileId);
            file.setString(6, fileName);
            file.setLong(7, size);
            file.setString(8, ready ? "READY" : "UNAVAILABLE");
            assertEquals(1, file.executeUpdate());
        }
        try (PreparedStatement entry = connection.prepareStatement(
                "INSERT INTO chat.conversation_entry(conversation_id, "
                        + "conversation_sequence, entry_kind, occurred_at) VALUES (?, ?, "
                        + "'MESSAGE', TIMESTAMPTZ '2026-08-09 01:02:03+00')")) {
            entry.setObject(1, conversation);
            entry.setLong(2, sequence);
            assertEquals(1, entry.executeUpdate());
        }
        try (PreparedStatement persisted = connection.prepareStatement(
                "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                        + "sender_account_id, sender_device_id, client_message_id, "
                        + "message_type, payload, payload_sha256, attachment_id, accepted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 2, decode('', 'hex'), decode(?, 'hex'), "
                        + "?, TIMESTAMPTZ '2026-08-09 01:02:03+00')")) {
            persisted.setObject(1, message);
            persisted.setObject(2, conversation);
            persisted.setLong(3, sequence);
            persisted.setObject(4, owner);
            persisted.setObject(5, device);
            persisted.setString(6, "legacy-direct-message-" + messageId);
            persisted.setString(7, "00".repeat(32));
            persisted.setObject(8, attachment);
            assertEquals(1, persisted.executeUpdate());
        }
        try (PreparedStatement mapping = connection.prepareStatement(
                "INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id, "
                        + "legacy_conversation_id, conversation_id, message_id, "
                        + "legacy_content_type) VALUES ('FRIENDSHIP', ?, 10, ?, ?, 'file')")) {
            mapping.setLong(1, messageId);
            mapping.setObject(2, conversation);
            mapping.setObject(3, message);
            assertEquals(1, mapping.executeUpdate());
        }
        try (PreparedStatement mapping = connection.prepareStatement(
                "INSERT INTO chat.legacy_v1_attachment_map(legacy_kind, legacy_file_id, "
                        + "legacy_conversation_id, conversation_id, attachment_id) "
                        + "VALUES ('FRIENDSHIP', ?, 10, ?, ?)")) {
            mapping.setLong(1, fileId);
            mapping.setObject(2, conversation);
            mapping.setObject(3, attachment);
            assertEquals(1, mapping.executeUpdate());
        }
    }

    private static void removeDirectAttachmentCompatibilityMappings(
            String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try (PreparedStatement membership = connection.prepareStatement(
                    "UPDATE chat.conversation_member SET left_at = transaction_timestamp() "
                            + "WHERE conversation_id = "
                            + "'40000000-0000-0000-0000-000000000010' "
                            + "AND left_at IS NULL")) {
                assertEquals(2, membership.executeUpdate());
            }
            try (PreparedStatement mapping = connection.prepareStatement(
                    "DELETE FROM chat.legacy_v1_conversation_map "
                            + "WHERE legacy_kind = 'FRIENDSHIP' "
                            + "AND legacy_conversation_id = 10")) {
                assertEquals(1, mapping.executeUpdate());
            }
            try (PreparedStatement mapping = connection.prepareStatement(
                    "DELETE FROM chat.legacy_v1_account_map WHERE legacy_user_id = 43")) {
                assertEquals(1, mapping.executeUpdate());
            }
        }
    }

    private static void seedRoomAttachment(
            Connection connection, UUID room, UUID owner) throws Exception {
        UUID device = UUID.fromString("50000000-0000-0000-0000-000000000044");
        UUID attachment = UUID.fromString("71000000-0000-0000-0000-000000000501");
        UUID message = UUID.fromString("72000000-0000-0000-0000-000000000701");
        try (PreparedStatement file = connection.prepareStatement(
                "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                        + "owner_device_id, client_attachment_id, object_key, file_name, "
                        + "media_type, byte_size, content_sha256, state, created_at, ready_at) "
                        + "VALUES (?, ?, ?, ?, 'legacy-room-file-501', "
                        + "'attachments/71000000-0000-0000-0000-000000000501', "
                        + "'design.pdf', 'application/pdf', 321, "
                        + "decode(?, 'hex'), 'READY', "
                        + "TIMESTAMPTZ '2026-08-11 01:02:03+00', "
                        + "TIMESTAMPTZ '2026-08-11 01:02:04+00')")) {
            file.setObject(1, attachment);
            file.setObject(2, room);
            file.setObject(3, owner);
            file.setObject(4, device);
            file.setString(5, "11".repeat(32));
            assertEquals(1, file.executeUpdate());
        }
        try (PreparedStatement entry = connection.prepareStatement(
                "INSERT INTO chat.conversation_entry(conversation_id, "
                        + "conversation_sequence, entry_kind, occurred_at) "
                        + "VALUES (?, 7, 'MESSAGE', TIMESTAMPTZ '2026-08-11 01:02:03+00')")) {
            entry.setObject(1, room);
            assertEquals(1, entry.executeUpdate());
        }
        try (PreparedStatement persisted = connection.prepareStatement(
                "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                        + "sender_account_id, sender_device_id, client_message_id, "
                        + "message_type, payload, payload_sha256, attachment_id, accepted_at) "
                        + "VALUES (?, ?, 7, ?, ?, 'legacy-room-message-701', 2, "
                        + "decode('', 'hex'), decode(?, 'hex'), ?, "
                        + "TIMESTAMPTZ '2026-08-11 01:02:03+00')")) {
            persisted.setObject(1, message);
            persisted.setObject(2, room);
            persisted.setObject(3, owner);
            persisted.setObject(4, device);
            persisted.setString(5, "00".repeat(32));
            persisted.setObject(6, attachment);
            assertEquals(1, persisted.executeUpdate());
        }
        try (PreparedStatement mapping = connection.prepareStatement(
                "INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id, "
                        + "legacy_conversation_id, conversation_id, message_id, "
                        + "legacy_content_type) VALUES ('ROOM', 701, 7, ?, ?, 'file')")) {
            mapping.setObject(1, room);
            mapping.setObject(2, message);
            assertEquals(1, mapping.executeUpdate());
        }
        try (PreparedStatement mapping = connection.prepareStatement(
                "INSERT INTO chat.legacy_v1_attachment_map(legacy_kind, legacy_file_id, "
                        + "legacy_conversation_id, conversation_id, attachment_id) "
                        + "VALUES ('ROOM', 501, 7, ?, ?)")) {
            mapping.setObject(1, room);
            mapping.setObject(2, attachment);
            assertEquals(1, mapping.executeUpdate());
        }
        seedUnavailableRoomAttachment(connection, room, owner, device);
    }

    private static void seedUnavailableRoomAttachment(
            Connection connection, UUID room, UUID owner, UUID device) throws Exception {
        UUID attachment = UUID.fromString("71000000-0000-0000-0000-000000000500");
        UUID message = UUID.fromString("72000000-0000-0000-0000-000000000700");
        try (PreparedStatement file = connection.prepareStatement(
                "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                        + "owner_device_id, client_attachment_id, file_name, byte_size, state, "
                        + "created_at, unavailable_at, unavailable_reason) VALUES (?, ?, ?, ?, "
                        + "'legacy-room-file-500', 'cleared.zip', 111, 'UNAVAILABLE', "
                        + "TIMESTAMPTZ '2026-08-10 01:02:03+00', "
                        + "TIMESTAMPTZ '2026-08-10 01:02:04+00', 'source file cleared')")) {
            file.setObject(1, attachment);
            file.setObject(2, room);
            file.setObject(3, owner);
            file.setObject(4, device);
            assertEquals(1, file.executeUpdate());
        }
        try (PreparedStatement entry = connection.prepareStatement(
                "INSERT INTO chat.conversation_entry(conversation_id, "
                        + "conversation_sequence, entry_kind, occurred_at) "
                        + "VALUES (?, 6, 'MESSAGE', TIMESTAMPTZ '2026-08-10 01:02:03+00')")) {
            entry.setObject(1, room);
            assertEquals(1, entry.executeUpdate());
        }
        try (PreparedStatement persisted = connection.prepareStatement(
                "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                        + "sender_account_id, sender_device_id, client_message_id, "
                        + "message_type, payload, payload_sha256, attachment_id, accepted_at) "
                        + "VALUES (?, ?, 6, ?, ?, 'legacy-room-message-700', 2, "
                        + "decode('', 'hex'), decode(?, 'hex'), ?, "
                        + "TIMESTAMPTZ '2026-08-10 01:02:03+00')")) {
            persisted.setObject(1, message);
            persisted.setObject(2, room);
            persisted.setObject(3, owner);
            persisted.setObject(4, device);
            persisted.setString(5, "00".repeat(32));
            persisted.setObject(6, attachment);
            assertEquals(1, persisted.executeUpdate());
        }
        try (PreparedStatement mapping = connection.prepareStatement(
                "INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id, "
                        + "legacy_conversation_id, conversation_id, message_id, "
                        + "legacy_content_type) VALUES ('ROOM', 700, 7, ?, ?, 'file')")) {
            mapping.setObject(1, room);
            mapping.setObject(2, message);
            assertEquals(1, mapping.executeUpdate());
        }
        try (PreparedStatement mapping = connection.prepareStatement(
                "INSERT INTO chat.legacy_v1_attachment_map(legacy_kind, legacy_file_id, "
                        + "legacy_conversation_id, conversation_id, attachment_id) "
                        + "VALUES ('ROOM', 500, 7, ?, ?)")) {
            mapping.setObject(1, room);
            mapping.setObject(2, attachment);
            assertEquals(1, mapping.executeUpdate());
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
                        + "', 1, decode('01','hex'), decode('"
                        + "00".repeat(32) + "','hex'))");
                statement.execute("INSERT INTO chat.legacy_v1_message_map(legacy_kind, "
                        + "legacy_message_id, legacy_conversation_id, conversation_id, "
                        + "message_id, legacy_content_type) "
                        + "VALUES ('FRIENDSHIP', " + (100 + sequence) + ", 9, '"
                        + direct + "', '" + message + "', 'text')");
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

    private static int rejectedRequestCount(
            String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM chat.contact_request "
                                + "WHERE state = 'REJECTED' AND resolved_at IS NOT NULL")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void insertPendingRequest(
            String url, String user, String password, long legacyRequestId) throws Exception {
        UUID requestId = UUID.fromString("70000000-0000-0000-0000-000000000071");
        UUID requester = UUID.fromString("15000000-0000-0000-0000-000000000044");
        UUID recipient = UUID.fromString("10000000-0000-0000-0000-000000000042");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try (PreparedStatement request = connection.prepareStatement(
                    "INSERT INTO chat.contact_request("
                            + "id, requester_account_id, recipient_account_id) VALUES (?, ?, ?)")) {
                request.setObject(1, requestId);
                request.setObject(2, requester);
                request.setObject(3, recipient);
                assertEquals(1, request.executeUpdate());
            }
            try (PreparedStatement mapping = connection.prepareStatement(
                    "INSERT INTO chat.legacy_v1_contact_request_map("
                            + "legacy_request_id, contact_request_id) VALUES (?, ?)")) {
                mapping.setLong(1, legacyRequestId);
                mapping.setObject(2, requestId);
                assertEquals(1, mapping.executeUpdate());
            }
        }
    }

    private static int acceptedRequestCount(
            String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM chat.contact_request "
                                + "WHERE id = '70000000-0000-0000-0000-000000000071' "
                                + "AND state = 'ACCEPTED' AND resolved_at IS NOT NULL")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static int inactiveFriendMembers(
            String url, String user, String password) throws Exception {
        return countQuery(url, user, password,
                "SELECT count(*) FROM chat.conversation_member member "
                        + "JOIN chat.legacy_v1_conversation_map mapping "
                        + "ON mapping.conversation_id = member.conversation_id "
                        + "WHERE mapping.legacy_kind = 'FRIENDSHIP' "
                        + "AND mapping.legacy_conversation_id = 9 "
                        + "AND member.left_at IS NOT NULL");
    }

    private static int retainedFriendEntries(
            String url, String user, String password) throws Exception {
        return countQuery(url, user, password,
                "SELECT count(*) FROM chat.conversation_entry entry "
                        + "JOIN chat.legacy_v1_conversation_map mapping "
                        + "ON mapping.conversation_id = entry.conversation_id "
                        + "WHERE mapping.legacy_kind = 'FRIENDSHIP' "
                        + "AND mapping.legacy_conversation_id = 9");
    }

    private static int countQuery(
            String url, String user, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
