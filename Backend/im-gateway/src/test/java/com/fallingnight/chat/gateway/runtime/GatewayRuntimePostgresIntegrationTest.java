package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomMessageDeletionEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomRenameEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomPasswordEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomDissolutionEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1PasswordChangeEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RegistrationEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1NicknameChangeEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1UsernameChangeEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomAdminEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomKickEventSink;
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
import com.fallingnight.chat.routing.redis.LettuceGatewayRoutingAdapter;
import com.fallingnight.chat.routing.redis.RedisRoutingConfig;
import com.fallingnight.chat.protocol.v2.Authenticate;
import com.fallingnight.chat.protocol.v2.AccountBlockApplied;
import com.fallingnight.chat.protocol.v2.AccountBlockDirectoryPage;
import com.fallingnight.chat.protocol.v2.ClientHello;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ClientPlatform;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageAccepted;
import com.fallingnight.chat.protocol.v2.MessageContentType;
import com.fallingnight.chat.protocol.v2.MessageHistoryPage;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ListAccountBlocks;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ConversationMessageSearchPage;
import com.fallingnight.chat.protocol.v2.ReadMessageHistory;
import com.fallingnight.chat.protocol.v2.ResumeSession;
import com.fallingnight.chat.protocol.v2.SearchConversationMessages;
import com.fallingnight.chat.protocol.v2.ServerHello;
import com.fallingnight.chat.protocol.v2.SetAccountBlock;
import com.fallingnight.chat.protocol.v2.SessionEstablished;
import com.fallingnight.chat.protocol.v2.SubmitMessage;
import com.fallingnight.chat.protocol.v2.WebPushHttpCredentialIssued;
import com.google.protobuf.ByteString;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
            configureDistributedRouting(environment);

            runtime = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(environment));
            assertFalse(runtime.isReady());
            runtime.start();
            awaitReady(runtime);

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
            if (!System.getenv().getOrDefault("CHATROOM_TEST_REDIS_URI", "").isBlank()) {
                HttpResponse<String> metrics = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + adminPort + "/metrics")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, metrics.statusCode());
                assertTrue(metrics.body().contains(
                        "chat_gateway_distributed_metrics_available 1"));
                assertTrue(metrics.body().contains("chat_gateway_routing_lease_valid 1"));
                assertTrue(metrics.body().contains("chat_gateway_outbox_unpublished"));
            }
        } finally {
            if (runtime != null) {
                runtime.close();
                assertFalse(runtime.isReady());
            }
            certificate.delete();
        }
    }

    @Test
    void submitsAcknowledgesAndFansOutThroughRealTlsWebSockets() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank());
        Assumptions.assumeTrue(username != null && !username.isBlank());
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "wss-" + accountId;
        String peerLogin = "wss-" + peerAccountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, peerLogin);

        int gatewayPort = availablePort();
        int adminPort = availablePort();
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        GatewayRuntime runtime = null;
        WebSocket socket = null;
        WebSocket peerSocket = null;
        try {
            Map<String, String> environment = new HashMap<>();
            environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(gatewayPort));
            environment.put("CHATROOM_GATEWAY_ADMIN_PORT", Integer.toString(adminPort));
            environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE",
                    certificate.certificate().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY",
                    certificate.privateKey().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "localhost:" + gatewayPort);
            environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
            environment.put("CHATROOM_POSTGRES_URL", jdbcUrl);
            environment.put("CHATROOM_POSTGRES_USER", username);
            environment.put("CHATROOM_POSTGRES_PASSWORD", "test-trust-password");
            environment.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
            environment.put("CHATROOM_POSTGRES_POOL_MAXIMUM", "4");
            environment.put("CHATROOM_POSTGRES_POOL_MINIMUM_IDLE", "1");
            configureDistributedRouting(environment);

            runtime = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(environment));
            runtime.start();
            awaitReady(runtime);
            BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
            socket = connectWebSocket(gatewayPort, listener);
            SessionEstablished session = establish(
                    socket, listener, login, "network-device-1");
            assertEquals(accountId.toString(), session.getAccountId());

            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peerSocket = connectWebSocket(gatewayPort, peerListener);
            SessionEstablished peerSession = establish(
                    peerSocket, peerListener, peerLogin, "network-device-2");
            assertEquals(peerAccountId.toString(), peerSession.getAccountId());
            peerSocket.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            Envelope history = peerListener.next();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    history.getMessageType());
            assertEquals(0, MessageHistoryPage.parseFrom(history.getPayload()).getMessagesCount());

            socket.sendBinary(ByteBuffer.wrap(submit(
                    session.getSessionId(), conversationId).toByteArray()), true).join();
            Envelope acceptedEnvelope = listener.next();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    acceptedEnvelope.getMessageType());
            MessageAccepted accepted = MessageAccepted.parseFrom(acceptedEnvelope.getPayload());
            assertEquals(conversationId.toString(), accepted.getConversationId());
            assertEquals(1, accepted.getConversationSequence());
            assertFalse(accepted.getDuplicate());
            Envelope publishedEnvelope = peerListener.next();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE,
                    publishedEnvelope.getMessageType());
            MessageRecord published = MessageRecord.parseFrom(publishedEnvelope.getPayload());
            assertEquals("network integration message", published.getContent().toStringUtf8());
            assertEquals(accountId.toString(), published.getSenderAccountId());
            assertEquals(1, published.getConversationSequence());
            peerListener.assertNoEnvelope(Duration.ofMillis(500));
            assertEquals(1, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.message WHERE conversation_id = '"
                            + conversationId + "' AND client_message_id = 'network-message-1'"));
        } finally {
            if (peerSocket != null) peerSocket.abort();
            if (socket != null) socket.abort();
            if (runtime != null) runtime.close();
            certificate.delete();
        }
    }

    @Test
    void searchesAuthorizedCurrentMessagesThroughRealTlsWebSocket() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank());
        Assumptions.assumeTrue(username != null && !username.isBlank());
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "wss-search-" + accountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, "wss-search-" + peerAccountId);

        int gatewayPort = availablePort();
        int adminPort = availablePort();
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        GatewayRuntime runtime = null;
        WebSocket socket = null;
        try {
            Map<String, String> environment = new HashMap<>();
            environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(gatewayPort));
            environment.put("CHATROOM_GATEWAY_ADMIN_PORT", Integer.toString(adminPort));
            environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE",
                    certificate.certificate().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY",
                    certificate.privateKey().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "localhost:" + gatewayPort);
            environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
            environment.put("CHATROOM_POSTGRES_URL", jdbcUrl);
            environment.put("CHATROOM_POSTGRES_USER", username);
            environment.put("CHATROOM_POSTGRES_PASSWORD", "test-trust-password");
            environment.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
            environment.put("CHATROOM_POSTGRES_POOL_MAXIMUM", "4");
            environment.put("CHATROOM_POSTGRES_POOL_MINIMUM_IDLE", "1");
            environment.put("CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED", "true");

            runtime = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(environment));
            runtime.start();
            awaitReady(runtime);
            BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
            socket = connectWebSocket(gatewayPort, listener);

            socket.sendBinary(ByteBuffer.wrap(clientHelloWithSearch(
                    "network-search-device").toByteArray()), true).join();
            Envelope helloEnvelope = listener.next();
            ServerHello hello = ServerHello.parseFrom(helloEnvelope.getPayload());
            assertEquals(List.of(ClientCapability.CLIENT_CAPABILITY_MESSAGE_SEARCH),
                    hello.getEnabledCapabilitiesList());

            socket.sendBinary(ByteBuffer.wrap(authenticate(login).toByteArray()), true).join();
            SessionEstablished session = SessionEstablished.parseFrom(
                    listener.next().getPayload());
            socket.sendBinary(ByteBuffer.wrap(submit(
                    session.getSessionId(), conversationId, "search-submit-1",
                    "search-message-1", "Needle 世界").toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    listener.next().getMessageType());

            socket.sendBinary(ByteBuffer.wrap(search(
                    session.getSessionId(), conversationId, "needle").toByteArray()),
                    true).join();
            Envelope result = listener.next();
            assertEquals(MessageType.MESSAGE_TYPE_CONVERSATION_MESSAGE_SEARCH_PAGE_VALUE,
                    result.getMessageType());
            ConversationMessageSearchPage page =
                    ConversationMessageSearchPage.parseFrom(result.getPayload());
            assertEquals(1, page.getHitsCount());
            assertEquals("Needle 世界", page.getHits(0).getContent().toStringUtf8());
        } finally {
            if (socket != null) socket.abort();
            if (runtime != null) runtime.close();
            certificate.delete();
        }
    }

    @Test
    void mutatesAccountBlocksOnlyBehindNegotiatedDefaultOffCapability() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank());
        Assumptions.assumeTrue(username != null && !username.isBlank());
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String login = "wss-block-" + accountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, "wss-block-" + peerAccountId);

        int gatewayPort = availablePort();
        int adminPort = availablePort();
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        GatewayRuntime runtime = null;
        WebSocket socket = null;
        try {
            Map<String, String> environment = new HashMap<>();
            environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(gatewayPort));
            environment.put("CHATROOM_GATEWAY_ADMIN_PORT", Integer.toString(adminPort));
            environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE",
                    certificate.certificate().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY",
                    certificate.privateKey().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "localhost:" + gatewayPort);
            environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
            environment.put("CHATROOM_POSTGRES_URL", jdbcUrl);
            environment.put("CHATROOM_POSTGRES_USER", username);
            environment.put("CHATROOM_POSTGRES_PASSWORD", "test-trust-password");
            environment.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
            environment.put("CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED", "true");

            runtime = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(environment));
            runtime.start();
            awaitReady(runtime);
            BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
            socket = connectWebSocket(gatewayPort, listener);
            socket.sendBinary(ByteBuffer.wrap(clientHelloWithBlocking(
                    "network-block-device").toByteArray()), true).join();
            ServerHello hello = ServerHello.parseFrom(listener.next().getPayload());
            assertEquals(List.of(ClientCapability.CLIENT_CAPABILITY_ACCOUNT_BLOCKING),
                    hello.getEnabledCapabilitiesList());
            socket.sendBinary(ByteBuffer.wrap(authenticate(login).toByteArray()), true).join();
            SessionEstablished session = SessionEstablished.parseFrom(
                    listener.next().getPayload());

            Envelope command = block(
                    session.getSessionId(), peerAccountId, operationId, true, "block-1");
            socket.sendBinary(ByteBuffer.wrap(command.toByteArray()), true).join();
            AccountBlockApplied first = AccountBlockApplied.parseFrom(
                    listener.next().getPayload());
            assertEquals(accountId.toString(), first.getActorAccountId());
            assertEquals(peerAccountId.toString(), first.getTargetAccountId());
            assertTrue(first.getBlocked());
            assertTrue(first.getChanged());

            socket.sendBinary(ByteBuffer.wrap(command.toByteArray()), true).join();
            AccountBlockApplied duplicate = AccountBlockApplied.parseFrom(
                    listener.next().getPayload());
            assertEquals(first, duplicate);
            assertEquals(1, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.account_block WHERE blocker_account_id='"
                            + accountId + "' AND blocked_account_id='" + peerAccountId + "'"));

            socket.sendBinary(ByteBuffer.wrap(listAccountBlocks(
                    session.getSessionId(), "", 1).toByteArray()), true).join();
            Envelope directoryEnvelope = listener.next();
            assertEquals(MessageType.MESSAGE_TYPE_ACCOUNT_BLOCK_DIRECTORY_PAGE_VALUE,
                    directoryEnvelope.getMessageType());
            AccountBlockDirectoryPage directory = AccountBlockDirectoryPage.parseFrom(
                    directoryEnvelope.getPayload());
            assertEquals(1, directory.getBlocksCount());
            assertEquals(peerAccountId.toString(),
                    directory.getBlocks(0).getTargetAccountId());
            assertEquals("Network Peer", directory.getBlocks(0).getTargetDisplayName());
            assertTrue(directory.getBlocks(0).getBlockedAtEpochMs() > 0);
            assertFalse(directory.getHasMore());
            assertEquals("", directory.getNextAfterTargetAccountId());

            socket.sendBinary(ByteBuffer.wrap(submit(
                    session.getSessionId(), conversationId, "blocked-submit",
                    "blocked-message", "must fail").toByteArray()), true).join();
            Envelope denied = listener.next();
            assertEquals(MessageType.MESSAGE_TYPE_PROTOCOL_ERROR_VALUE,
                    denied.getMessageType());
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    ProtocolError.parseFrom(denied.getPayload()).getCode());
        } finally {
            if (socket != null) socket.abort();
            if (runtime != null) runtime.close();
            certificate.delete();
        }
    }

    @Test
    void issuesCredentialAndPersistsSubscriptionOnlyForNegotiatedWebRuntime() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank());
        Assumptions.assumeTrue(username != null && !username.isBlank());
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "wss-web-push-" + accountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, "wss-web-push-" + peerAccountId);

        int gatewayPort = availablePort();
        int adminPort = availablePort();
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        Path keyDirectory = Files.createTempDirectory("chat-web-push-keys-");
        Path encryptionKey = writeProtectedKey(
                keyDirectory.resolve("encryption-enc-v1.key"), 1);
        Path lookupKey = writeProtectedKey(
                keyDirectory.resolve("endpoint-lookup.key"), 2);
        KeyPairGenerator vapidGenerator = KeyPairGenerator.getInstance("EC");
        vapidGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        var vapidPair = vapidGenerator.generateKeyPair();
        Path vapidPrivate = writeProtectedBytes(
                keyDirectory.resolve("vapid-private.der"),
                vapidPair.getPrivate().getEncoded());
        Path vapidPublic = writeProtectedBytes(
                keyDirectory.resolve("vapid-public.der"),
                vapidPair.getPublic().getEncoded());
        GatewayRuntime runtime = null;
        WebSocket socket = null;
        try {
            Map<String, String> environment = new HashMap<>();
            environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(gatewayPort));
            environment.put("CHATROOM_GATEWAY_ADMIN_PORT", Integer.toString(adminPort));
            environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE",
                    certificate.certificate().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY",
                    certificate.privateKey().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "localhost:" + gatewayPort);
            environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
            environment.put("CHATROOM_POSTGRES_URL", jdbcUrl);
            environment.put("CHATROOM_POSTGRES_USER", username);
            environment.put("CHATROOM_POSTGRES_PASSWORD", "test-trust-password");
            environment.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
            environment.put("CHATROOM_GATEWAY_WEB_PUSH_ENABLED", "true");
            environment.put(WebPushSubscriptionRuntimeConfig.ENABLED, "true");
            environment.put(WebPushSubscriptionRuntimeConfig.KEY_DIRECTORY,
                    keyDirectory.toString());
            environment.put(WebPushSubscriptionRuntimeConfig.ACTIVE_KEY_ID, "enc-v1");
            environment.put(WebPushSubscriptionRuntimeConfig.KEY_IDS, "enc-v1");
            environment.put(WebPushDeliveryRuntimeConfig.ENABLED, "true");
            environment.put(WebPushDeliveryRuntimeConfig.VAPID_PRIVATE_KEY,
                    vapidPrivate.toString());
            environment.put(WebPushDeliveryRuntimeConfig.VAPID_PUBLIC_KEY,
                    vapidPublic.toString());
            environment.put(WebPushDeliveryRuntimeConfig.VAPID_SUBJECT,
                    "mailto:push@example.com");
            environment.put(WebPushDeliveryRuntimeConfig.PROVIDER_ORIGINS,
                    "https://push.example");

            runtime = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(environment));
            runtime.start();
            awaitReady(runtime);
            BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
            socket = connectWebSocketWeb(gatewayPort, listener);
            socket.sendBinary(ByteBuffer.wrap(clientHelloWithWebPush(
                    "network-web-push-device").toByteArray()), true).join();
            ServerHello hello = ServerHello.parseFrom(listener.next().getPayload());
            assertEquals(List.of(
                    ClientCapability.CLIENT_CAPABILITY_WEB_PUSH_HTTP_CREDENTIAL),
                    hello.getEnabledCapabilitiesList());
            socket.sendBinary(ByteBuffer.wrap(authenticate(login).toByteArray()), true).join();
            SessionEstablished session = SessionEstablished.parseFrom(
                    listener.next().getPayload());

            long issuedAfterEpochMs = System.currentTimeMillis();
            socket.sendBinary(ByteBuffer.wrap(issueWebPushHttpCredential(
                    session.getSessionId()).toByteArray()), true).join();
            Envelope response = listener.next();
            long receivedAtEpochMs = System.currentTimeMillis();
            assertEquals(MessageType.MESSAGE_TYPE_WEB_PUSH_HTTP_CREDENTIAL_ISSUED_VALUE,
                    response.getMessageType());
            assertEquals(session.getSessionId(), response.getSessionId());
            WebPushHttpCredentialIssued issued = WebPushHttpCredentialIssued.parseFrom(
                    response.getPayload());
            assertEquals(43, issued.getBearerTokenAscii().size());
            assertEquals(43, issued.getCsrfTokenAscii().size());
            assertTrue(issued.getExpiresAtEpochMs() > issuedAfterEpochMs);
            assertTrue(issued.getExpiresAtEpochMs()
                    <= receivedAtEpochMs + Duration.ofMinutes(10).toMillis());
            assertEquals(1, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.web_push_http_credential WHERE session_id='"
                            + session.getSessionId()
                            + "' AND octet_length(bearer_sha256)=32"
                            + " AND octet_length(csrf_sha256)=32"));
            UUID installationId = UUID.randomUUID();
            HttpResponse<String> put = HttpClient.newBuilder()
                    .sslContext(trustAllTls())
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(
                                    "https://localhost:" + gatewayPort
                                            + "/api/v2/web-push/subscriptions/"
                                            + installationId))
                            .header("Origin", "https://chat.example.com")
                            .header("Authorization", "Bearer "
                                    + issued.getBearerTokenAscii().toStringUtf8())
                            .header("X-CSRF-Token",
                                    issued.getCsrfTokenAscii().toStringUtf8())
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(
                                    webPushSubscriptionJson()))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, put.statusCode());
            assertEquals("", put.body());
            assertEquals(1, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.web_push_subscription"
                            + " WHERE account_id='" + accountId
                            + "' AND installation_id='" + installationId
                            + "' AND encryption_key_id='enc-v1'"
                            + " AND octet_length(endpoint_lookup_tag)=32"));
            String metrics = adminMetrics(adminPort);
            assertTrue(metrics.contains(
                    "chat_gateway_web_push_http_credentials_issued_total 1\n"));
            assertTrue(metrics.contains(
                    "chat_gateway_web_push_subscription_http_replaced_total 1\n"));
            awaitMetricAtLeast(adminPort,
                    "chat_gateway_web_push_delivery_reason_healthy", 1,
                    Duration.ofSeconds(3));
        } finally {
            if (socket != null) socket.abort();
            if (runtime != null) runtime.close();
            certificate.delete();
            Files.deleteIfExists(encryptionKey);
            Files.deleteIfExists(lookupKey);
            Files.deleteIfExists(vapidPrivate);
            Files.deleteIfExists(vapidPublic);
            Files.deleteIfExists(keyDirectory);
        }
    }

    @Test
    void withdrawsReadinessAndConvergesDurableMessageAcrossRedisRestart() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_REDIS_CONTROL_DIR");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank()
                && username != null && !username.isBlank()
                && redisUri != null && !redisUri.isBlank()
                && controlValue != null && !controlValue.isBlank(),
                "set disposable PostgreSQL, Redis, and outage control directory");
        Path control = Path.of(controlValue);
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "redis-outage-" + accountId;
        String peerLogin = "redis-outage-" + peerAccountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, peerLogin);

        int gatewayPort = availablePort();
        int adminPort = availablePort();
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        GatewayRuntime runtime = null;
        WebSocket socket = null;
        WebSocket peerSocket = null;
        try {
            Map<String, String> environment = new HashMap<>();
            environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(gatewayPort));
            environment.put("CHATROOM_GATEWAY_ADMIN_PORT", Integer.toString(adminPort));
            environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE",
                    certificate.certificate().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY",
                    certificate.privateKey().getAbsolutePath());
            environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "localhost:" + gatewayPort);
            environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
            environment.put("CHATROOM_POSTGRES_URL", jdbcUrl);
            environment.put("CHATROOM_POSTGRES_USER", username);
            environment.put("CHATROOM_POSTGRES_PASSWORD", "test-trust-password");
            environment.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
            environment.put("CHATROOM_POSTGRES_POOL_MAXIMUM", "4");
            environment.put("CHATROOM_POSTGRES_POOL_MINIMUM_IDLE", "1");
            environment.put(DistributedGatewayRoutingConfig.ENABLED, "true");
            environment.put(DistributedGatewayRoutingConfig.REDIS_URI, redisUri);
            environment.put(DistributedGatewayRoutingConfig.ALLOW_INSECURE_LOOPBACK, "true");
            environment.put(DistributedGatewayRoutingConfig.ROUTE_LEASE_SECONDS, "5");

            runtime = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(environment));
            runtime.start();
            awaitReady(runtime);
            assertReadiness(adminPort, 200, "ready\n");
            assertProductReadiness(gatewayPort, 200, "ready\n");

            BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
            socket = connectWebSocket(gatewayPort, listener);
            SessionEstablished session = establish(
                    socket, listener, login, "redis-outage-device-1");
            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peerSocket = connectWebSocket(gatewayPort, peerListener);
            SessionEstablished peerSession = establish(
                    peerSocket, peerListener, peerLogin, "redis-outage-device-2");
            peerSocket.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());

            Files.writeString(control.resolve("redis-stop-request"), "stop\n");
            awaitFile(control.resolve("redis-stopped"), Duration.ofSeconds(5));
            awaitNotReady(runtime, Duration.ofSeconds(8));
            assertReadiness(adminPort, 503, "not_ready\n");
            assertProductReadiness(gatewayPort, 503, "not_ready\n");
            assertAdminEndpoint(adminPort, "/health/live", 200, "live\n");

            socket.sendBinary(ByteBuffer.wrap(submit(
                    session.getSessionId(), conversationId).toByteArray()), true).join();
            Envelope acceptedEnvelope = listener.next();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    acceptedEnvelope.getMessageType());
            MessageAccepted accepted = MessageAccepted.parseFrom(acceptedEnvelope.getPayload());
            assertEquals(1, accepted.getConversationSequence());
            Envelope publishedEnvelope = peerListener.next();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE,
                    publishedEnvelope.getMessageType());
            MessageRecord published = MessageRecord.parseFrom(publishedEnvelope.getPayload());
            assertEquals("network integration message", published.getContent().toStringUtf8());
            assertEquals(1, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.message WHERE conversation_id = '"
                            + conversationId + "'"));
            assertEquals(1, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.conversation_event_outbox "
                            + "WHERE conversation_id = '" + conversationId
                            + "' AND published_at IS NULL"));

            Files.writeString(control.resolve("redis-start-request"), "start\n");
            awaitFile(control.resolve("redis-started"), Duration.ofSeconds(5));
            awaitReady(runtime, Duration.ofSeconds(12));
            assertReadiness(adminPort, 200, "ready\n");
            assertProductReadiness(gatewayPort, 200, "ready\n");
            awaitCount(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.conversation_event_outbox "
                            + "WHERE conversation_id = '" + conversationId
                            + "' AND published_at IS NOT NULL",
                    1, Duration.ofSeconds(15));
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));
            String metrics = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + adminPort + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(metrics.contains("chat_gateway_routing_lease_valid 1"));
            assertTrue(Pattern.compile(
                    "chat_gateway_routing_lease_failed_total [1-9][0-9]*")
                    .matcher(metrics).find());
        } finally {
            if (peerSocket != null) peerSocket.abort();
            if (socket != null) socket.abort();
            if (runtime != null) runtime.close();
            certificate.delete();
        }
    }

    @Test
    void relaysOneProductMessageAcrossTwoRealGatewayRuntimes() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank()
                && username != null && !username.isBlank()
                && redisUri != null && !redisUri.isBlank(),
                "set disposable PostgreSQL and Redis endpoints");
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "cross-gateway-" + accountId;
        String peerLogin = "cross-gateway-" + peerAccountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, peerLogin);

        int firstGatewayPort = availablePort();
        int firstAdminPort = availablePort();
        int secondGatewayPort = availablePort();
        int secondAdminPort = availablePort();
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        GatewayRuntime first = null;
        GatewayRuntime second = null;
        WebSocket sender = null;
        WebSocket peer = null;
        try {
            first = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(
                    distributedNetworkEnvironment(firstGatewayPort, firstAdminPort,
                            certificate, jdbcUrl, username, redisUri)));
            second = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(
                    distributedNetworkEnvironment(secondGatewayPort, secondAdminPort,
                            certificate, jdbcUrl, username, redisUri)));
            first.start();
            second.start();
            awaitReady(first);
            awaitReady(second);

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(firstGatewayPort, senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "cross-gateway-device-1");
            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(secondGatewayPort, peerListener);
            SessionEstablished peerSession = establish(
                    peer, peerListener, peerLogin, "cross-gateway-device-2");
            peer.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());

            sender.sendBinary(ByteBuffer.wrap(submit(
                    senderSession.getSessionId(), conversationId).toByteArray()), true).join();
            Envelope acceptedEnvelope = senderListener.next();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    acceptedEnvelope.getMessageType());
            MessageAccepted accepted = MessageAccepted.parseFrom(acceptedEnvelope.getPayload());
            assertEquals(1, accepted.getConversationSequence());
            Envelope publishedEnvelope = peerListener.next();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE,
                    publishedEnvelope.getMessageType());
            MessageRecord published = MessageRecord.parseFrom(publishedEnvelope.getPayload());
            assertEquals(accepted.getMessageId(), published.getMessageId());
            assertEquals(1, published.getConversationSequence());
            assertEquals("network integration message", published.getContent().toStringUtf8());
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));

            assertEquals(1, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.message WHERE conversation_id = '"
                            + conversationId + "'"));
            assertEquals(1, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.conversation_event_outbox "
                            + "WHERE conversation_id = '" + conversationId
                            + "' AND published_at IS NOT NULL"));
            String secondMetrics = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + secondAdminPort + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(Pattern.compile(
                    "chat_gateway_routing_hint_applied_total [1-9][0-9]*")
                    .matcher(secondMetrics).find());
        } finally {
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            if (second != null) second.close();
            if (first != null) first.close();
            certificate.delete();
        }
    }

    @Test
    void preservesPeerDeliveryWhileOneGatewayRollsToAReplacement() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank()
                && username != null && !username.isBlank()
                && redisUri != null && !redisUri.isBlank(),
                "set disposable PostgreSQL and Redis endpoints");
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "rolling-gateway-" + accountId;
        String peerLogin = "rolling-gateway-" + peerAccountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, peerLogin);

        int firstGatewayPort = availablePort();
        int firstAdminPort = availablePort();
        int stableGatewayPort = availablePort();
        int stableAdminPort = availablePort();
        int replacementGatewayPort = availablePort();
        int replacementAdminPort = availablePort();
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        GatewayRuntime first = null;
        GatewayRuntime stable = null;
        GatewayRuntime replacement = null;
        WebSocket sender = null;
        WebSocket peer = null;
        try {
            first = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(
                    distributedNetworkEnvironment(firstGatewayPort, firstAdminPort,
                            certificate, jdbcUrl, username, redisUri)));
            stable = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(
                    distributedNetworkEnvironment(stableGatewayPort, stableAdminPort,
                            certificate, jdbcUrl, username, redisUri)));
            first.start();
            stable.start();
            awaitReady(first);
            awaitReady(stable);

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(firstGatewayPort, senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "rolling-device-1");
            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(stableGatewayPort, peerListener);
            SessionEstablished peerSession = establish(
                    peer, peerListener, peerLogin, "rolling-device-2");
            peer.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());

            sender.sendBinary(ByteBuffer.wrap(submit(
                    senderSession.getSessionId(), conversationId,
                    "rolling-submit-1", "rolling-message-1", "before replacement")
                    .toByteArray()), true).join();
            MessageAccepted firstAccepted = accepted(senderListener.next());
            assertEquals(1, firstAccepted.getConversationSequence());
            MessageRecord firstPublished = published(peerListener.next());
            assertEquals(1, firstPublished.getConversationSequence());
            assertEquals("before replacement", firstPublished.getContent().toStringUtf8());

            sender.sendClose(WebSocket.NORMAL_CLOSURE, "gateway replacement")
                    .get(3, TimeUnit.SECONDS);
            sender = null;
            first.close();
            first = null;
            assertTrue(stable.isReady());
            assertReadiness(stableAdminPort, 200, "ready\n");

            replacement = GatewayRuntime.create(GatewayRuntimeConfig.fromEnvironment(
                    distributedNetworkEnvironment(replacementGatewayPort,
                            replacementAdminPort, certificate, jdbcUrl, username, redisUri)));
            replacement.start();
            awaitReady(replacement);
            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(replacementGatewayPort, replacementListener);
            SessionEstablished replacementSession = establish(
                    sender, replacementListener, login, "rolling-device-1");
            sender.sendBinary(ByteBuffer.wrap(history(
                    replacementSession.getSessionId(), conversationId).toByteArray()), true).join();
            Envelope replacementHistory = replacementListener.next();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    replacementHistory.getMessageType());
            MessageHistoryPage historyPage = MessageHistoryPage.parseFrom(
                    replacementHistory.getPayload());
            assertEquals(1, historyPage.getMessagesCount());
            assertEquals(1, historyPage.getMessages(0).getConversationSequence());
            assertEquals(2, activeRouteCount(redisUri, conversationId));

            sender.sendBinary(ByteBuffer.wrap(submit(
                    replacementSession.getSessionId(), conversationId,
                    "rolling-submit-2", "rolling-message-2", "after replacement")
                    .toByteArray()), true).join();
            MessageAccepted secondAccepted = accepted(replacementListener.next());
            assertEquals(2, secondAccepted.getConversationSequence());
            MessageRecord replacementLocal = published(replacementListener.next());
            assertEquals(secondAccepted.getMessageId(), replacementLocal.getMessageId());
            assertEquals(2, replacementLocal.getConversationSequence());
            awaitCount(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.conversation_event_outbox "
                            + "WHERE conversation_id = '" + conversationId
                            + "' AND published_at IS NOT NULL",
                    2, Duration.ofSeconds(10));
            assertEquals(2, activeRouteCount(redisUri, conversationId));
            awaitRoutingMetric(stableAdminPort, "hint_read", 2, Duration.ofSeconds(10));
            String routingMetrics = adminMetrics(stableAdminPort);
            assertEquals(2, routingMetric(routingMetrics, "hint_applied"),
                    "unexpected hint classification:\n" + routingMetrics);
            MessageRecord secondPublished = published(
                    peerListener.next(Duration.ofSeconds(15)));
            assertEquals(secondAccepted.getMessageId(), secondPublished.getMessageId());
            assertEquals(2, secondPublished.getConversationSequence());
            assertEquals("after replacement", secondPublished.getContent().toStringUtf8());
            replacementListener.assertNoEnvelope(Duration.ofSeconds(1));
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));

            assertTrue(stable.isReady());
            assertReadiness(stableAdminPort, 200, "ready\n");
            assertEquals(2, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.message WHERE conversation_id = '"
                            + conversationId + "'"));
        } finally {
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            if (replacement != null) replacement.close();
            if (stable != null) stable.close();
            if (first != null) first.close();
            certificate.delete();
        }
    }

    @Test
    void haproxyWithdrawsOneGatewayWhileItsExistingWssSessionDrains() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String proxyUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank()
                && username != null && !username.isBlank()
                && redisUri != null && !redisUri.isBlank()
                && controlValue != null && !controlValue.isBlank()
                && proxyUrl != null && !proxyUrl.isBlank()
                && certificatePath != null && !certificatePath.isBlank()
                && keyPath != null && !keyPath.isBlank(),
                "set disposable PostgreSQL, Redis, HAProxy, and gateway TLS material");
        Path control = Path.of(controlValue);
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "haproxy-gateway-" + accountId;
        String peerLogin = "haproxy-gateway-" + peerAccountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, peerLogin);

        int firstGatewayPort = availablePort();
        int firstAdminPort = availablePort();
        int secondGatewayPort = availablePort();
        int secondAdminPort = availablePort();
        GatewayRuntime first = null;
        GatewayRuntime second = null;
        WebSocket sender = null;
        WebSocket peer = null;
        WebSocket replacement = null;
        Thread drain = null;
        AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        try {
            Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                    firstGatewayPort, firstAdminPort, certificatePath, keyPath,
                    jdbcUrl, username, redisUri, proxyAuthority(proxyUrl));
            Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                    secondGatewayPort, secondAdminPort, certificatePath, keyPath,
                    jdbcUrl, username, redisUri, proxyAuthority(proxyUrl));
            firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            first = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(firstEnvironment));
            second = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(secondEnvironment));
            first.start();
            second.start();
            awaitReady(first);
            awaitReady(second);
            Files.writeString(control.resolve("gateway-ports"),
                    firstGatewayPort + "\n" + secondGatewayPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "haproxy-device-1");
            long firstAccepted = authenticationAccepted(firstAdminPort);
            long secondAccepted = authenticationAccepted(secondAdminPort);
            assertEquals(1, firstAccepted + secondAccepted);
            boolean drainFirst = firstAccepted == 1;

            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), peerListener);
            SessionEstablished peerSession = establish(
                    peer, peerListener, peerLogin, "haproxy-device-2");
            assertEquals(1, authenticationAccepted(firstAdminPort));
            assertEquals(1, authenticationAccepted(secondAdminPort));
            peer.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());

            GatewayRuntime draining = drainFirst ? first : second;
            GatewayRuntime stable = drainFirst ? second : first;
            int drainingPort = drainFirst ? firstGatewayPort : secondGatewayPort;
            int stableAdmin = drainFirst ? secondAdminPort : firstAdminPort;
            drain = new Thread(() -> {
                try { draining.close(); }
                catch (Throwable failure) { drainFailure.set(failure); }
            }, "haproxy-gateway-drain");
            drain.start();
            awaitProductNotReady(drainingPort, Duration.ofSeconds(3));
            Thread.sleep(2_500);

            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            replacement = connectWebSocket(
                    URI.create(proxyUrl + "/v2/windows"), replacementListener);
            SessionEstablished replacementSession = establish(
                    replacement, replacementListener, login, "haproxy-device-3");
            assertEquals(2, authenticationAccepted(stableAdmin));
            assertTrue(stable.isReady());

            sender.sendBinary(ByteBuffer.wrap(submit(
                    senderSession.getSessionId(), conversationId,
                    "haproxy-submit-1", "haproxy-message-1", "during drain")
                    .toByteArray()), true).join();
            MessageAccepted accepted = accepted(senderListener.next());
            assertEquals(1, accepted.getConversationSequence());
            MessageRecord peerPublished = published(peerListener.next(Duration.ofSeconds(10)));
            assertEquals(accepted.getMessageId(), peerPublished.getMessageId());

            sender.sendClose(WebSocket.NORMAL_CLOSURE, "drain complete")
                    .get(3, TimeUnit.SECONDS);
            sender = null;
            drain.join(Duration.ofSeconds(5).toMillis());
            assertFalse(drain.isAlive(), "draining gateway did not stop");
            assertNull(drainFailure.get(), "draining gateway failed");
            if (drainFirst) first = null; else second = null;

            replacement.sendBinary(ByteBuffer.wrap(history(
                    replacementSession.getSessionId(), conversationId).toByteArray()), true).join();
            MessageHistoryPage repaired = MessageHistoryPage.parseFrom(
                    replacementListener.next().getPayload());
            assertEquals(1, repaired.getMessagesCount());
            assertEquals(1, repaired.getMessages(0).getConversationSequence());
            replacement.sendBinary(ByteBuffer.wrap(submit(
                    replacementSession.getSessionId(), conversationId,
                    "haproxy-submit-2", "haproxy-message-2", "after removal")
                    .toByteArray()), true).join();
            MessageAccepted secondMessage = accepted(replacementListener.next());
            assertEquals(2, secondMessage.getConversationSequence());
            assertEquals(2, published(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));
            assertEquals(2, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.message WHERE conversation_id = '"
                            + conversationId + "'"));
            awaitCount(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.conversation_event_outbox "
                            + "WHERE conversation_id = '" + conversationId
                            + "' AND published_at IS NOT NULL",
                    2, Duration.ofSeconds(10));
        } finally {
            if (replacement != null) replacement.abort();
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            if (drain != null && drain.isAlive()) drain.interrupt();
            if (second != null) second.close();
            if (first != null) first.close();
        }
    }

    @Test
    void haproxyRemovesAbruptlyKilledGatewayAndClientRepairs() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String password = System.getenv().getOrDefault("CHATROOM_TEST_POSTGRES_PASSWORD", "");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String proxyUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        String runtimeClasspath = System.getenv("CHATROOM_TEST_GATEWAY_RUNTIME_CLASSPATH");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank()
                && username != null && !username.isBlank()
                && redisUri != null && !redisUri.isBlank()
                && controlValue != null && !controlValue.isBlank()
                && proxyUrl != null && !proxyUrl.isBlank()
                && certificatePath != null && !certificatePath.isBlank()
                && keyPath != null && !keyPath.isBlank()
                && runtimeClasspath != null && !runtimeClasspath.isBlank(),
                "set disposable services, HAProxy, gateway TLS, and runtime classpath");
        Path control = Path.of(controlValue);
        new PostgresMigrator(jdbcUrl, username, password).migrate();

        UUID accountId = UUID.randomUUID();
        UUID peerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "crash-gateway-" + accountId;
        String peerLogin = "crash-gateway-" + peerAccountId;
        seedV2NetworkAccounts(jdbcUrl, username, password, accountId, peerAccountId,
                conversationId, login, peerLogin);

        int firstPort = availablePort();
        int firstAdmin = availablePort();
        int secondPort = availablePort();
        int secondAdmin = availablePort();
        Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                firstPort, firstAdmin, certificatePath, keyPath, jdbcUrl, username,
                redisUri, proxyAuthority(proxyUrl));
        Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                secondPort, secondAdmin, certificatePath, keyPath, jdbcUrl, username,
                redisUri, proxyAuthority(proxyUrl));
        firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
        secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");

        Process first = null;
        Process second = null;
        WebSocket sender = null;
        WebSocket peer = null;
        WebSocket replacement = null;
        try {
            first = startGatewayProcess(firstEnvironment, runtimeClasspath,
                    control.resolve("gateway-a.log"));
            second = startGatewayProcess(secondEnvironment, runtimeClasspath,
                    control.resolve("gateway-b.log"));
            awaitProductReady(firstAdmin, first, control.resolve("gateway-a.log"),
                    Duration.ofSeconds(10));
            awaitProductReady(secondAdmin, second, control.resolve("gateway-b.log"),
                    Duration.ofSeconds(10));
            Files.writeString(control.resolve("gateway-ports"),
                    firstPort + "\n" + secondPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "crash-device-1");
            boolean killFirst = authenticationAccepted(firstAdmin) == 1;
            assertEquals(1, authenticationAccepted(firstAdmin)
                    + authenticationAccepted(secondAdmin));
            sender.sendBinary(ByteBuffer.wrap(history(
                    senderSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    senderListener.next().getMessageType());

            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), peerListener);
            SessionEstablished peerSession = establish(
                    peer, peerListener, peerLogin, "crash-device-2");
            assertEquals(1, authenticationAccepted(firstAdmin));
            assertEquals(1, authenticationAccepted(secondAdmin));
            peer.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());

            sender.sendBinary(ByteBuffer.wrap(submit(
                    senderSession.getSessionId(), conversationId,
                    "crash-submit-1", "crash-message-1", "before crash")
                    .toByteArray()), true).join();
            MessageAccepted firstMessage = accepted(senderListener.next());
            assertEquals(1, firstMessage.getConversationSequence());
            assertEquals(1, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            assertEquals(2, activeRouteCount(redisUri, conversationId));

            Process killed = killFirst ? first : second;
            killed.destroyForcibly();
            assertTrue(killed.waitFor(5, TimeUnit.SECONDS), "gateway process did not die");
            if (killFirst) first = null; else second = null;
            sender = null;
            Thread.sleep(2_500);

            int stableAdmin = killFirst ? secondAdmin : firstAdmin;
            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            replacement = connectWebSocket(
                    URI.create(proxyUrl + "/v2/windows"), replacementListener);
            SessionEstablished replacementSession = establish(
                    replacement, replacementListener, login, "crash-device-3");
            assertEquals(2, authenticationAccepted(stableAdmin));
            awaitActiveRouteCount(redisUri, conversationId, 1, Duration.ofSeconds(10));

            replacement.sendBinary(ByteBuffer.wrap(history(
                    replacementSession.getSessionId(), conversationId).toByteArray()), true).join();
            MessageHistoryPage repaired = MessageHistoryPage.parseFrom(
                    replacementListener.next().getPayload());
            assertEquals(1, repaired.getMessagesCount());
            assertEquals(firstMessage.getMessageId(), repaired.getMessages(0).getMessageId());
            replacement.sendBinary(ByteBuffer.wrap(submit(
                    replacementSession.getSessionId(), conversationId,
                    "crash-submit-2", "crash-message-2", "after crash")
                    .toByteArray()), true).join();
            assertEquals(2, accepted(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));
            assertEquals(2, countQuery(jdbcUrl, username, password,
                    "SELECT count(*) FROM chat.message WHERE conversation_id = '"
                            + conversationId + "'"));
        } finally {
            if (replacement != null) replacement.abort();
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            stopGatewayProcess(second);
            stopGatewayProcess(first);
        }
    }

    @Test
    void haproxyMeasuresBatchedReconnectAfterAbruptGatewayLoss() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String proxyUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        String runtimeClasspath = System.getenv("CHATROOM_TEST_GATEWAY_RUNTIME_CLASSPATH");
        String evidenceValue = System.getenv("CHATROOM_TEST_GATEWAY_CRASH_EVIDENCE");
        Assumptions.assumeTrue(allNonBlank(jdbcUrl, username, redisUri, controlValue,
                proxyUrl, certificatePath, keyPath, runtimeClasspath, evidenceValue),
                "set disposable services, HAProxy, runtime classpath, and evidence path");
        Path control = Path.of(controlValue);
        Path evidence = Path.of(evidenceValue);
        new PostgresMigrator(jdbcUrl, username, "").migrate();

        int firstPort = availablePort();
        int firstAdmin = availablePort();
        int secondPort = availablePort();
        int secondAdmin = availablePort();
        Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                firstPort, firstAdmin, certificatePath, keyPath, jdbcUrl, username,
                redisUri, proxyAuthority(proxyUrl));
        Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                secondPort, secondAdmin, certificatePath, keyPath, jdbcUrl, username,
                redisUri, proxyAuthority(proxyUrl));
        firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
        secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");

        Process first = null;
        Process second = null;
        List<CrashClient> clients = new ArrayList<>();
        List<WebSocket> replacements = new ArrayList<>();
        try {
            seedCrashAccounts(jdbcUrl, username, 12);
            first = startGatewayProcess(firstEnvironment, runtimeClasspath,
                    control.resolve("gateway-a.log"));
            second = startGatewayProcess(secondEnvironment, runtimeClasspath,
                    control.resolve("gateway-b.log"));
            awaitProductReady(firstAdmin, first, control.resolve("gateway-a.log"),
                    Duration.ofSeconds(10));
            awaitProductReady(secondAdmin, second, control.resolve("gateway-b.log"),
                    Duration.ofSeconds(10));
            Files.writeString(control.resolve("gateway-ports"),
                    firstPort + "\n" + secondPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            for (int index = 0; index < 12; index++) {
                long beforeFirst = authenticationAccepted(firstAdmin);
                BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
                WebSocket socket = connectWebSocket(
                        URI.create(proxyUrl + "/v2/windows"), listener);
                String deviceId = "crash-load-device-" + index;
                SessionEstablished session = establish(
                        socket, listener, "crash-load-user-" + index, deviceId);
                boolean onFirst = authenticationAccepted(firstAdmin) == beforeFirst + 1;
                clients.add(new CrashClient(socket, session, deviceId, onFirst));
            }
            long firstConnections = clients.stream().filter(CrashClient::onFirst).count();
            assertEquals(6, firstConnections);
            assertEquals(6, clients.size() - firstConnections);

            first.destroyForcibly();
            assertTrue(first.waitFor(5, TimeUnit.SECONDS), "gateway process did not die");
            first = null;
            Thread.sleep(2_500);

            List<CrashClient> affected = clients.stream()
                    .filter(CrashClient::onFirst).toList();
            int batchSize = 2;
            int intervalMillis = 100;
            CountDownLatch ready = new CountDownLatch(affected.size());
            CountDownLatch start = new CountDownLatch(1);
            long[] startNanos = new long[1];
            List<ReconnectSample> samples = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(affected.size())) {
                List<Future<ReconnectSample>> futures = new ArrayList<>();
                for (int index = 0; index < affected.size(); index++) {
                    int position = index;
                    CrashClient previous = affected.get(index);
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        long offset = (long) (position / batchSize) * intervalMillis;
                        long scheduled = startNanos[0] + TimeUnit.MILLISECONDS.toNanos(offset);
                        long remaining;
                        while ((remaining = scheduled - System.nanoTime()) > 0) {
                            LockSupport.parkNanos(remaining);
                        }
                        long began = System.nanoTime();
                        BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
                        WebSocket socket = connectWebSocket(
                                URI.create(proxyUrl + "/v2/windows"), listener);
                        SessionEstablished rotated = resume(
                                socket, listener, previous.session(), previous.deviceId());
                        long latency = TimeUnit.NANOSECONDS.toMicros(
                                System.nanoTime() - began);
                        long jitter = TimeUnit.NANOSECONDS.toMicros(
                                Math.abs(began - scheduled));
                        return new ReconnectSample(position, socket, rotated,
                                Math.max(1, latency), Math.max(1, jitter));
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                startNanos[0] = System.nanoTime();
                start.countDown();
                for (Future<ReconnectSample> future : futures) {
                    samples.add(future.get(10, TimeUnit.SECONDS));
                }
            }
            samples.sort(Comparator.comparingInt(ReconnectSample::position));
            samples.forEach(sample -> replacements.add(sample.socket()));
            assertEquals(12, authenticationAccepted(secondAdmin));
            writeCrashReconnectEvidence(evidence, clients.size(), affected.size(), batchSize,
                    intervalMillis, samples, System.nanoTime() - startNanos[0]);
        } finally {
            replacements.forEach(WebSocket::abort);
            clients.forEach(client -> client.socket().abort());
            stopGatewayProcess(second);
            stopGatewayProcess(first);
        }
    }

    @Test
    void haproxyRoutesAwayBeforeForcedDrainTimeoutClosesOldSession() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String proxyUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        Assumptions.assumeTrue(allNonBlank(jdbcUrl, username, redisUri, controlValue,
                proxyUrl, certificatePath, keyPath),
                "set disposable services, HAProxy, and gateway TLS material");
        Path control = Path.of(controlValue);
        new PostgresMigrator(jdbcUrl, username, "").migrate();
        UUID accountId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "forced-drain-" + accountId;
        String peerLogin = "forced-drain-" + peerId;
        seedV2NetworkAccounts(jdbcUrl, username, "", accountId, peerId,
                conversationId, login, peerLogin);

        int firstPort = availablePort();
        int firstAdmin = availablePort();
        int secondPort = availablePort();
        int secondAdmin = availablePort();
        GatewayRuntime first = null;
        GatewayRuntime second = null;
        WebSocket sender = null;
        WebSocket peer = null;
        WebSocket replacement = null;
        Thread closeThread = null;
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try {
            Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                    firstPort, firstAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(proxyUrl));
            Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                    secondPort, secondAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(proxyUrl));
            firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            firstEnvironment.put("CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS", "1");
            secondEnvironment.put("CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS", "1");
            first = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(firstEnvironment));
            second = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(secondEnvironment));
            first.start();
            second.start();
            awaitReady(first);
            awaitReady(second);
            Files.writeString(control.resolve("gateway-ports"),
                    firstPort + "\n" + secondPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "forced-drain-device");
            boolean closeFirst = authenticationAccepted(firstAdmin) == 1;
            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), peerListener);
            establish(peer, peerListener, peerLogin, "forced-drain-peer");
            assertEquals(1, authenticationAccepted(firstAdmin));
            assertEquals(1, authenticationAccepted(secondAdmin));

            GatewayRuntime closing = closeFirst ? first : second;
            int closingPort = closeFirst ? firstPort : secondPort;
            int stableAdmin = closeFirst ? secondAdmin : firstAdmin;
            long started = System.nanoTime();
            closeThread = new Thread(() -> {
                try { closing.close(); }
                catch (Throwable failure) { closeFailure.set(failure); }
            }, "forced-drain-timeout");
            closeThread.start();
            awaitProductNotReady(closingPort, Duration.ofSeconds(2));
            closeThread.join(Duration.ofSeconds(3).toMillis());
            assertFalse(closeThread.isAlive(), "forced drain did not terminate runtime");
            assertNull(closeFailure.get(), "forced drain failed");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis >= 900, "runtime skipped configured drain timeout");
            assertTrue(elapsedMillis < 3_000, "runtime exceeded bounded forced drain");
            senderListener.awaitTerminal(Duration.ofSeconds(2));
            sender = null;
            if (closeFirst) first = null; else second = null;

            Thread.sleep(2_500);
            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            replacement = connectWebSocket(
                    URI.create(proxyUrl + "/v2/windows"), replacementListener);
            SessionEstablished rotated = resume(
                    replacement, replacementListener, senderSession, "forced-drain-device");
            assertEquals(senderSession.getSessionId(), rotated.getSessionId());
            assertEquals(2, authenticationAccepted(stableAdmin));
        } finally {
            if (replacement != null) replacement.abort();
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            if (closeThread != null && closeThread.isAlive()) closeThread.interrupt();
            if (second != null) second.close();
            if (first != null) first.close();
        }
    }

    @Test
    void haproxyReloadKeepsOldTunnelAndMovesNewSessions() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String proxyUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        Assumptions.assumeTrue(allNonBlank(jdbcUrl, username, redisUri, controlValue,
                proxyUrl, certificatePath, keyPath),
                "set disposable services, HAProxy, and gateway TLS material");
        Path control = Path.of(controlValue);
        new PostgresMigrator(jdbcUrl, username, "").migrate();
        UUID accountId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "reload-" + accountId;
        String peerLogin = "reload-" + peerId;
        seedV2NetworkAccounts(jdbcUrl, username, "", accountId, peerId,
                conversationId, login, peerLogin);

        int firstPort = availablePort();
        int firstAdmin = availablePort();
        int secondPort = availablePort();
        int secondAdmin = availablePort();
        GatewayRuntime first = null;
        GatewayRuntime second = null;
        WebSocket sender = null;
        WebSocket peer = null;
        WebSocket replacement = null;
        try {
            Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                    firstPort, firstAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(proxyUrl));
            Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                    secondPort, secondAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(proxyUrl));
            firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            first = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(firstEnvironment));
            second = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(secondEnvironment));
            first.start();
            second.start();
            awaitReady(first);
            awaitReady(second);
            Files.writeString(control.resolve("gateway-ports"),
                    firstPort + "\n" + secondPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "reload-device");
            boolean senderOnFirst = authenticationAccepted(firstAdmin) == 1;
            sender.sendBinary(ByteBuffer.wrap(history(
                    senderSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    senderListener.next().getMessageType());

            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), peerListener);
            SessionEstablished peerSession = establish(
                    peer, peerListener, peerLogin, "reload-peer");
            assertEquals(1, authenticationAccepted(firstAdmin));
            assertEquals(1, authenticationAccepted(secondAdmin));
            peer.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());

            String retained = senderOnFirst ? "gateway-b" : "gateway-a";
            int stableAdmin = senderOnFirst ? secondAdmin : firstAdmin;
            Files.writeString(control.resolve("haproxy-reload-request"), retained + "\n");
            awaitFile(control.resolve("haproxy-reloaded"), Duration.ofSeconds(10));

            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            replacement = connectWebSocket(
                    URI.create(proxyUrl + "/v2/windows"), replacementListener);
            SessionEstablished replacementSession = establish(
                    replacement, replacementListener, login, "reload-replacement");
            assertEquals(2, authenticationAccepted(stableAdmin));

            sender.sendBinary(ByteBuffer.wrap(submit(
                    senderSession.getSessionId(), conversationId,
                    "reload-submit-1", "reload-message-1", "through old worker")
                    .toByteArray()), true).join();
            MessageAccepted firstMessage = accepted(senderListener.next());
            assertEquals(1, firstMessage.getConversationSequence());
            assertEquals(1, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());

            replacement.sendBinary(ByteBuffer.wrap(history(
                    replacementSession.getSessionId(), conversationId).toByteArray()), true).join();
            MessageHistoryPage repaired = MessageHistoryPage.parseFrom(
                    replacementListener.next().getPayload());
            assertEquals(firstMessage.getMessageId(), repaired.getMessages(0).getMessageId());
            replacement.sendBinary(ByteBuffer.wrap(submit(
                    replacementSession.getSessionId(), conversationId,
                    "reload-submit-2", "reload-message-2", "through new worker")
                    .toByteArray()), true).join();
            assertEquals(2, accepted(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));
        } finally {
            if (replacement != null) replacement.abort();
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            if (second != null) second.close();
            if (first != null) first.close();
        }
    }

    @Test
    void haproxyRotatesFrontendCertificateWithoutDroppingOldTunnel() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String proxyUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        Assumptions.assumeTrue(allNonBlank(jdbcUrl, username, redisUri, controlValue,
                proxyUrl, certificatePath, keyPath),
                "set disposable services, HAProxy, and gateway TLS material");
        Path control = Path.of(controlValue);
        List<String> fingerprints = Files.readAllLines(
                control.resolve("frontend-fingerprints"));
        assertEquals(2, fingerprints.size());
        assertFalse(fingerprints.get(0).equals(fingerprints.get(1)));
        new PostgresMigrator(jdbcUrl, username, "").migrate();
        UUID accountId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "certificate-rotation-" + accountId;
        String peerLogin = "certificate-rotation-" + peerId;
        seedV2NetworkAccounts(jdbcUrl, username, "", accountId, peerId,
                conversationId, login, peerLogin);

        int firstPort = availablePort();
        int firstAdmin = availablePort();
        int secondPort = availablePort();
        int secondAdmin = availablePort();
        GatewayRuntime first = null;
        GatewayRuntime second = null;
        WebSocket sender = null;
        WebSocket peer = null;
        WebSocket replacement = null;
        try {
            Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                    firstPort, firstAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(proxyUrl));
            Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                    secondPort, secondAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(proxyUrl));
            firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            first = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(firstEnvironment));
            second = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(secondEnvironment));
            first.start();
            second.start();
            awaitReady(first);
            awaitReady(second);
            Files.writeString(control.resolve("gateway-ports"),
                    firstPort + "\n" + secondPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));
            assertEquals(fingerprints.get(0), proxyCertificateSha256(proxyUrl));

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "certificate-device");
            boolean senderOnFirst = authenticationAccepted(firstAdmin) == 1;
            sender.sendBinary(ByteBuffer.wrap(history(
                    senderSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    senderListener.next().getMessageType());

            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), peerListener);
            SessionEstablished peerSession = establish(
                    peer, peerListener, peerLogin, "certificate-peer");
            peer.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());
            assertEquals(1, authenticationAccepted(firstAdmin));
            assertEquals(1, authenticationAccepted(secondAdmin));

            String retained = senderOnFirst ? "gateway-b" : "gateway-a";
            int stableAdmin = senderOnFirst ? secondAdmin : firstAdmin;
            Files.writeString(control.resolve("haproxy-reload-frontend"),
                    "frontend-next.pem\n");
            Files.writeString(control.resolve("haproxy-reload-request"), retained + "\n");
            awaitFile(control.resolve("haproxy-reloaded"), Duration.ofSeconds(10));
            assertEquals(fingerprints.get(1), proxyCertificateSha256(proxyUrl));

            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            replacement = connectWebSocket(
                    URI.create(proxyUrl + "/v2/windows"), replacementListener);
            SessionEstablished replacementSession = establish(
                    replacement, replacementListener, login, "certificate-replacement");
            assertEquals(2, authenticationAccepted(stableAdmin));

            sender.sendBinary(ByteBuffer.wrap(submit(
                    senderSession.getSessionId(), conversationId,
                    "certificate-submit-1", "certificate-message-1", "old certificate tunnel")
                    .toByteArray()), true).join();
            MessageAccepted firstMessage = accepted(senderListener.next());
            assertEquals(1, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            replacement.sendBinary(ByteBuffer.wrap(history(
                    replacementSession.getSessionId(), conversationId).toByteArray()), true).join();
            MessageHistoryPage repaired = MessageHistoryPage.parseFrom(
                    replacementListener.next().getPayload());
            assertEquals(firstMessage.getMessageId(), repaired.getMessages(0).getMessageId());
            replacement.sendBinary(ByteBuffer.wrap(submit(
                    replacementSession.getSessionId(), conversationId,
                    "certificate-submit-2", "certificate-message-2", "new certificate tunnel")
                    .toByteArray()), true).join();
            assertEquals(2, accepted(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));
        } finally {
            if (replacement != null) replacement.abort();
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            if (second != null) second.close();
            if (first != null) first.close();
        }
    }

    @Test
    void haproxyMigratesBackendCertificateAuthorityWithoutDroppingOldTunnel() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String proxyUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        String nextCertificatePath = System.getenv(
                "CHATROOM_TEST_GATEWAY_NEXT_CERTIFICATE");
        String nextKeyPath = System.getenv("CHATROOM_TEST_GATEWAY_NEXT_PRIVATE_KEY");
        Assumptions.assumeTrue(allNonBlank(jdbcUrl, username, redisUri, controlValue,
                proxyUrl, certificatePath, keyPath, nextCertificatePath, nextKeyPath),
                "set disposable services, HAProxy, and both gateway trust generations");
        Path control = Path.of(controlValue);
        new PostgresMigrator(jdbcUrl, username, "").migrate();
        UUID accountId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "backend-ca-" + accountId;
        String peerLogin = "backend-ca-" + peerId;
        seedV2NetworkAccounts(jdbcUrl, username, "", accountId, peerId,
                conversationId, login, peerLogin);

        int firstPort = availablePort();
        int firstAdmin = availablePort();
        int secondPort = availablePort();
        int secondAdmin = availablePort();
        GatewayRuntime first = null;
        GatewayRuntime second = null;
        WebSocket sender = null;
        WebSocket peer = null;
        WebSocket replacement = null;
        List<WebSocket> probes = new ArrayList<>();
        try {
            Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                    firstPort, firstAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(proxyUrl));
            Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                    secondPort, secondAdmin, nextCertificatePath, nextKeyPath, jdbcUrl,
                    username, redisUri, proxyAuthority(proxyUrl));
            firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            first = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(firstEnvironment));
            second = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(secondEnvironment));
            first.start();
            second.start();
            awaitReady(first);
            awaitReady(second);
            Files.writeString(control.resolve("gateway-ports"),
                    firstPort + "\n" + secondPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "backend-ca-old");
            assertEquals(1, authenticationAccepted(firstAdmin));
            assertEquals(0, authenticationAccepted(secondAdmin));
            sender.sendBinary(ByteBuffer.wrap(history(
                    senderSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    senderListener.next().getMessageType());

            Files.writeString(control.resolve("haproxy-reload-ca"), "ca-bundle.crt\n");
            Files.writeString(control.resolve("haproxy-reload-request"), "both\n");
            awaitFile(control.resolve("haproxy-reloaded"), Duration.ofSeconds(10));

            BinaryEnvelopeListener peerListener = null;
            boolean acceptedOldCertificate = false;
            long previousFirst = authenticationAccepted(firstAdmin);
            long previousSecond = authenticationAccepted(secondAdmin);
            for (int attempt = 0; attempt < 4
                    && (!acceptedOldCertificate || peer == null); attempt++) {
                BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
                WebSocket candidate = connectWebSocket(
                        URI.create(proxyUrl + "/v2/windows"), listener);
                SessionEstablished session = establish(
                        candidate, listener, peerLogin, "backend-ca-peer-" + attempt);
                candidate.sendBinary(ByteBuffer.wrap(history(
                        session.getSessionId(), conversationId).toByteArray()), true).join();
                assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                        listener.next().getMessageType());
                long currentFirst = authenticationAccepted(firstAdmin);
                long currentSecond = authenticationAccepted(secondAdmin);
                acceptedOldCertificate |= currentFirst > previousFirst;
                if (currentSecond > previousSecond && peer == null) {
                    peer = candidate;
                    peerListener = listener;
                } else {
                    probes.add(candidate);
                }
                previousFirst = currentFirst;
                previousSecond = currentSecond;
            }
            assertTrue(acceptedOldCertificate,
                    "expanded trust worker did not accept the old gateway certificate");
            assertNotNull(peer,
                    "expanded trust worker did not accept the new gateway certificate");
            assertNotNull(peerListener);

            Files.delete(control.resolve("haproxy-reload-request"));
            Files.delete(control.resolve("haproxy-reloaded"));
            Files.writeString(control.resolve("haproxy-reload-ca"), "ca-next.crt\n");
            Files.writeString(control.resolve("haproxy-reload-request"), "both\n");
            awaitFile(control.resolve("haproxy-reloaded"), Duration.ofSeconds(10));

            long acceptedByFirst = authenticationAccepted(firstAdmin);
            long acceptedBySecond = authenticationAccepted(secondAdmin);
            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            replacement = connectWebSocket(
                    URI.create(proxyUrl + "/v2/windows"), replacementListener);
            SessionEstablished replacementSession = establish(
                    replacement, replacementListener, login, "backend-ca-new");
            assertEquals(acceptedByFirst, authenticationAccepted(firstAdmin));
            assertEquals(acceptedBySecond + 1, authenticationAccepted(secondAdmin));

            sender.sendBinary(ByteBuffer.wrap(submit(
                    senderSession.getSessionId(), conversationId,
                    "backend-ca-submit-1", "backend-ca-message-1", "old trust worker")
                    .toByteArray()), true).join();
            MessageAccepted firstMessage = accepted(senderListener.next());
            assertEquals(1, firstMessage.getConversationSequence());
            assertEquals(1, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());

            replacement.sendBinary(ByteBuffer.wrap(history(
                    replacementSession.getSessionId(), conversationId).toByteArray()), true).join();
            MessageHistoryPage repaired = MessageHistoryPage.parseFrom(
                    replacementListener.next().getPayload());
            assertEquals(firstMessage.getMessageId(), repaired.getMessages(0).getMessageId());
            replacement.sendBinary(ByteBuffer.wrap(submit(
                    replacementSession.getSessionId(), conversationId,
                    "backend-ca-submit-2", "backend-ca-message-2", "new trust worker")
                    .toByteArray()), true).join();
            assertEquals(2, accepted(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));
        } finally {
            if (replacement != null) replacement.abort();
            if (peer != null) peer.abort();
            for (WebSocket probe : probes) probe.abort();
            if (sender != null) sender.abort();
            if (second != null) second.close();
            if (first != null) first.close();
        }
    }

    @Test
    void haproxyRollsAcrossTwoCommittedGatewayVersions() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String proxyUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        String previousClasspath = System.getenv(
                "CHATROOM_TEST_GATEWAY_PREVIOUS_CLASSPATH");
        String candidateClasspath = System.getenv(
                "CHATROOM_TEST_GATEWAY_CANDIDATE_CLASSPATH");
        String previousRevision = System.getenv(
                "CHATROOM_TEST_GATEWAY_PREVIOUS_REVISION");
        String candidateRevision = System.getenv(
                "CHATROOM_TEST_GATEWAY_CANDIDATE_REVISION");
        Assumptions.assumeTrue(allNonBlank(jdbcUrl, username, redisUri, controlValue,
                proxyUrl, certificatePath, keyPath, previousClasspath, candidateClasspath,
                previousRevision, candidateRevision),
                "set disposable services and two committed gateway distributions");
        assertFalse(previousRevision.equals(candidateRevision));
        Path control = Path.of(controlValue);
        new PostgresMigrator(jdbcUrl, username, "").migrate();
        UUID accountId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "mixed-version-" + accountId;
        String peerLogin = "mixed-version-" + peerId;
        seedV2NetworkAccounts(jdbcUrl, username, "", accountId, peerId,
                conversationId, login, peerLogin);

        int previousPort = availablePort();
        int previousAdmin = availablePort();
        int candidatePort = availablePort();
        int candidateAdmin = availablePort();
        Map<String, String> previousEnvironment = distributedNetworkEnvironment(
                previousPort, previousAdmin, certificatePath, keyPath, jdbcUrl, username,
                redisUri, proxyAuthority(proxyUrl));
        Map<String, String> candidateEnvironment = distributedNetworkEnvironment(
                candidatePort, candidateAdmin, certificatePath, keyPath, jdbcUrl, username,
                redisUri, proxyAuthority(proxyUrl));
        previousEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
        candidateEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
        previousEnvironment.put("CHATROOM_GATEWAY_RELEASE_VERSION", "1.0.0");
        previousEnvironment.put("CHATROOM_GATEWAY_SOURCE_REVISION", previousRevision);
        candidateEnvironment.put("CHATROOM_GATEWAY_RELEASE_VERSION", "1.1.0");
        candidateEnvironment.put("CHATROOM_GATEWAY_SOURCE_REVISION", candidateRevision);

        Process previous = null;
        Process candidate = null;
        WebSocket sender = null;
        WebSocket peer = null;
        WebSocket replacement = null;
        try {
            previous = startGatewayProcess(previousEnvironment, previousClasspath,
                    control.resolve("gateway-previous.log"));
            candidate = startGatewayProcess(candidateEnvironment, candidateClasspath,
                    control.resolve("gateway-candidate.log"));
            awaitProductReady(previousAdmin, previous,
                    control.resolve("gateway-previous.log"), Duration.ofSeconds(10));
            awaitProductReady(candidateAdmin, candidate,
                    control.resolve("gateway-candidate.log"), Duration.ofSeconds(10));
            assertAdminEndpoint(previousAdmin, "/identity", 200,
                    releaseIdentityJson("1.0.0", previousRevision));
            assertAdminEndpoint(candidateAdmin, "/identity", 200,
                    releaseIdentityJson("1.1.0", candidateRevision));
            assertFalse(adminMetrics(previousAdmin).contains("chat_gateway_release_info"));
            assertTrue(adminMetrics(candidateAdmin).contains("chat_gateway_release_info"));

            Files.writeString(control.resolve("gateway-ports"),
                    previousPort + "\n" + candidatePort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "mixed-version-old");
            boolean senderOnPrevious = authenticationAccepted(previousAdmin) == 1;
            assertEquals(1, authenticationAccepted(previousAdmin)
                    + authenticationAccepted(candidateAdmin));
            sender.sendBinary(ByteBuffer.wrap(history(
                    senderSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    senderListener.next().getMessageType());

            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(URI.create(proxyUrl + "/v2/windows"), peerListener);
            SessionEstablished peerSession = establish(
                    peer, peerListener, peerLogin, "mixed-version-new");
            assertEquals(1, authenticationAccepted(previousAdmin));
            assertEquals(1, authenticationAccepted(candidateAdmin));
            peer.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());

            WebSocket previousSocket = senderOnPrevious ? sender : peer;
            BinaryEnvelopeListener previousListener = senderOnPrevious
                    ? senderListener : peerListener;
            SessionEstablished previousSession = senderOnPrevious
                    ? senderSession : peerSession;
            String previousLogin = senderOnPrevious ? login : peerLogin;
            WebSocket candidateSocket = senderOnPrevious ? peer : sender;
            BinaryEnvelopeListener candidateListener = senderOnPrevious
                    ? peerListener : senderListener;
            SessionEstablished candidateSession = senderOnPrevious
                    ? peerSession : senderSession;

            previousSocket.sendBinary(ByteBuffer.wrap(submit(
                    previousSession.getSessionId(), conversationId,
                    "mixed-submit-1", "mixed-message-1", "previous to candidate")
                    .toByteArray()), true).join();
            assertEquals(1, accepted(previousListener.next()).getConversationSequence());
            assertEquals(1, published(previousListener.next()).getConversationSequence());
            assertEquals(1, published(candidateListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());

            candidateSocket.sendBinary(ByteBuffer.wrap(submit(
                    candidateSession.getSessionId(), conversationId,
                    "mixed-submit-2", "mixed-message-2", "candidate to previous")
                    .toByteArray()), true).join();
            assertEquals(2, accepted(candidateListener.next()).getConversationSequence());
            assertEquals(2, published(candidateListener.next()).getConversationSequence());
            assertEquals(2, published(previousListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());

            previousSocket.sendClose(WebSocket.NORMAL_CLOSURE, "roll previous")
                    .get(3, TimeUnit.SECONDS);
            if (senderOnPrevious) sender = null; else peer = null;
            stopGatewayProcess(previous);
            previous = null;
            Thread.sleep(2_500);

            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            replacement = connectWebSocket(
                    URI.create(proxyUrl + "/v2/windows"), replacementListener);
            SessionEstablished replacementSession = establish(
                    replacement, replacementListener,
                    previousLogin, "mixed-version-replacement");
            assertEquals(2, authenticationAccepted(candidateAdmin));
            replacement.sendBinary(ByteBuffer.wrap(history(
                    replacementSession.getSessionId(), conversationId).toByteArray()), true).join();
            MessageHistoryPage repaired = MessageHistoryPage.parseFrom(
                    replacementListener.next().getPayload());
            assertEquals(2, repaired.getMessagesCount());
            assertEquals(1, repaired.getMessages(0).getConversationSequence());
            assertEquals(2, repaired.getMessages(1).getConversationSequence());
            replacement.sendBinary(ByteBuffer.wrap(submit(
                    replacementSession.getSessionId(), conversationId,
                    "mixed-submit-3", "mixed-message-3", "candidate after rollout")
                    .toByteArray()), true).join();
            assertEquals(3, accepted(replacementListener.next()).getConversationSequence());
            assertEquals(3, published(replacementListener.next()).getConversationSequence());
            assertEquals(3, published(candidateListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            assertEquals(3, countQuery(jdbcUrl, username, "",
                    "SELECT count(*) FROM chat.message WHERE conversation_id = '"
                            + conversationId + "'"));
        } finally {
            if (replacement != null) replacement.abort();
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            stopGatewayProcess(candidate);
            stopGatewayProcess(previous);
        }
    }

    @Test
    void haproxySecondaryEdgeRepairsAfterPrimaryEdgeCrash() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String primaryUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String secondaryUrl = System.getenv("CHATROOM_TEST_HAPROXY_SECONDARY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        Assumptions.assumeTrue(allNonBlank(jdbcUrl, username, redisUri, controlValue,
                primaryUrl, secondaryUrl, certificatePath, keyPath),
                "set disposable services, two HAProxy edges, and gateway TLS material");
        assertFalse(primaryUrl.equals(secondaryUrl));
        Path control = Path.of(controlValue);
        new PostgresMigrator(jdbcUrl, username, "").migrate();
        UUID accountId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String login = "multi-edge-" + accountId;
        String peerLogin = "multi-edge-" + peerId;
        seedV2NetworkAccounts(jdbcUrl, username, "", accountId, peerId,
                conversationId, login, peerLogin);

        int firstPort = availablePort();
        int firstAdmin = availablePort();
        int secondPort = availablePort();
        int secondAdmin = availablePort();
        GatewayRuntime first = null;
        GatewayRuntime second = null;
        WebSocket sender = null;
        WebSocket peer = null;
        WebSocket replacement = null;
        try {
            Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                    firstPort, firstAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(primaryUrl));
            Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                    secondPort, secondAdmin, certificatePath, keyPath, jdbcUrl, username,
                    redisUri, proxyAuthority(secondaryUrl));
            firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
            first = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(firstEnvironment));
            second = GatewayRuntime.create(
                    GatewayRuntimeConfig.fromEnvironment(secondEnvironment));
            first.start();
            second.start();
            awaitReady(first);
            awaitReady(second);
            Files.writeString(control.resolve("gateway-ports"),
                    firstPort + "\n" + secondPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            BinaryEnvelopeListener senderListener = new BinaryEnvelopeListener();
            sender = connectWebSocket(
                    URI.create(primaryUrl + "/v2/windows"), senderListener);
            SessionEstablished senderSession = establish(
                    sender, senderListener, login, "multi-edge-sender");
            assertEquals(1, authenticationAccepted(firstAdmin));
            assertEquals(0, authenticationAccepted(secondAdmin));
            sender.sendBinary(ByteBuffer.wrap(history(
                    senderSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    senderListener.next().getMessageType());

            BinaryEnvelopeListener peerListener = new BinaryEnvelopeListener();
            peer = connectWebSocket(
                    URI.create(secondaryUrl + "/v2/windows"), peerListener);
            SessionEstablished peerSession = establish(
                    peer, peerListener, peerLogin, "multi-edge-peer");
            assertEquals(1, authenticationAccepted(secondAdmin));
            peer.sendBinary(ByteBuffer.wrap(history(
                    peerSession.getSessionId(), conversationId).toByteArray()), true).join();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    peerListener.next().getMessageType());

            sender.sendBinary(ByteBuffer.wrap(submit(
                    senderSession.getSessionId(), conversationId,
                    "multi-edge-submit-1", "multi-edge-message-1", "before edge loss")
                    .toByteArray()), true).join();
            MessageAccepted firstMessage = accepted(senderListener.next());
            assertEquals(1, firstMessage.getConversationSequence());
            assertEquals(1, published(senderListener.next()).getConversationSequence());
            assertEquals(1, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());

            Files.writeString(
                    control.resolve("haproxy-primary-stop-request"), "stop\n");
            awaitFile(control.resolve("haproxy-primary-stopped"), Duration.ofSeconds(10));
            senderListener.awaitTerminal(Duration.ofSeconds(3));
            sender = null;
            assertTrue(first.isReady(), "edge loss must not poison gateway readiness");
            assertTrue(second.isReady(), "secondary gateway must remain ready");

            BinaryEnvelopeListener replacementListener = new BinaryEnvelopeListener();
            replacement = connectWebSocket(
                    URI.create(secondaryUrl + "/v2/windows"), replacementListener);
            SessionEstablished resumed = resume(
                    replacement, replacementListener, senderSession, "multi-edge-sender");
            assertEquals(senderSession.getSessionId(), resumed.getSessionId());
            assertEquals(2, authenticationAccepted(secondAdmin));
            replacement.sendBinary(ByteBuffer.wrap(history(
                    resumed.getSessionId(), conversationId).toByteArray()), true).join();
            MessageHistoryPage repaired = MessageHistoryPage.parseFrom(
                    replacementListener.next().getPayload());
            assertEquals(1, repaired.getMessagesCount());
            assertEquals(firstMessage.getMessageId(), repaired.getMessages(0).getMessageId());

            replacement.sendBinary(ByteBuffer.wrap(submit(
                    resumed.getSessionId(), conversationId,
                    "multi-edge-submit-2", "multi-edge-message-2", "after edge loss")
                    .toByteArray()), true).join();
            assertEquals(2, accepted(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(replacementListener.next()).getConversationSequence());
            assertEquals(2, published(peerListener.next(Duration.ofSeconds(10)))
                    .getConversationSequence());
            peerListener.assertNoEnvelope(Duration.ofSeconds(1));
            assertEquals(2, countQuery(jdbcUrl, username, "",
                    "SELECT count(*) FROM chat.message WHERE conversation_id = '"
                            + conversationId + "'"));
        } finally {
            if (replacement != null) replacement.abort();
            if (peer != null) peer.abort();
            if (sender != null) sender.abort();
            if (second != null) second.close();
            if (first != null) first.close();
        }
    }

    @Test
    void haproxyMeasuresBatchedReconnectAfterPrimaryEdgeCrash() throws Exception {
        String jdbcUrl = System.getenv("CHATROOM_TEST_POSTGRES_URL");
        String username = System.getenv("CHATROOM_TEST_POSTGRES_USER");
        String redisUri = System.getenv("CHATROOM_TEST_REDIS_URI");
        String controlValue = System.getenv("CHATROOM_TEST_HAPROXY_CONTROL_DIR");
        String primaryUrl = System.getenv("CHATROOM_TEST_HAPROXY_WSS_URL");
        String secondaryUrl = System.getenv("CHATROOM_TEST_HAPROXY_SECONDARY_WSS_URL");
        String certificatePath = System.getenv("CHATROOM_TEST_GATEWAY_CERTIFICATE");
        String keyPath = System.getenv("CHATROOM_TEST_GATEWAY_PRIVATE_KEY");
        String runtimeClasspath = System.getenv("CHATROOM_TEST_GATEWAY_RUNTIME_CLASSPATH");
        String evidenceValue = System.getenv("CHATROOM_TEST_MULTI_EDGE_RECONNECT_EVIDENCE");
        Assumptions.assumeTrue(allNonBlank(jdbcUrl, username, redisUri, controlValue,
                primaryUrl, secondaryUrl, certificatePath, keyPath, runtimeClasspath,
                evidenceValue), "set disposable services, two edges, and evidence path");
        Path control = Path.of(controlValue);
        Path evidence = Path.of(evidenceValue);
        MultiEdgeReconnectWorkload workload = multiEdgeReconnectWorkload(
                System.getenv("CHATROOM_TEST_MULTI_EDGE_RECONNECT_WORKLOAD"));
        new PostgresMigrator(jdbcUrl, username, "").migrate();

        int firstPort = availablePort();
        int firstAdmin = availablePort();
        int secondPort = availablePort();
        int secondAdmin = availablePort();
        Map<String, String> firstEnvironment = distributedNetworkEnvironment(
                firstPort, firstAdmin, certificatePath, keyPath, jdbcUrl, username,
                redisUri, proxyAuthority(primaryUrl));
        Map<String, String> secondEnvironment = distributedNetworkEnvironment(
                secondPort, secondAdmin, certificatePath, keyPath, jdbcUrl, username,
                redisUri, proxyAuthority(secondaryUrl));
        firstEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");
        secondEnvironment.put("CHATROOM_GATEWAY_BIND_ADDRESS", "0.0.0.0");

        Process first = null;
        Process second = null;
        List<CrashClient> primaryClients = new ArrayList<>();
        List<BinaryEnvelopeListener> primaryListeners = new ArrayList<>();
        List<CrashClient> survivorClients = new ArrayList<>();
        List<WebSocket> replacements = new ArrayList<>();
        try {
            int failedConnections = workload.failedConnections();
            int survivingConnections = workload.survivingConnections();
            seedCrashAccounts(jdbcUrl, username, failedConnections + survivingConnections);
            first = startGatewayProcess(firstEnvironment, runtimeClasspath,
                    control.resolve("gateway-a.log"));
            second = startGatewayProcess(secondEnvironment, runtimeClasspath,
                    control.resolve("gateway-b.log"));
            awaitProductReady(firstAdmin, first, control.resolve("gateway-a.log"),
                    Duration.ofSeconds(10));
            awaitProductReady(secondAdmin, second, control.resolve("gateway-b.log"),
                    Duration.ofSeconds(10));
            Files.writeString(control.resolve("gateway-ports"),
                    firstPort + "\n" + secondPort + "\n");
            Files.writeString(control.resolve("haproxy-start-request"), "start\n");
            awaitFile(control.resolve("haproxy-started"), Duration.ofSeconds(10));

            for (int index = 0; index < failedConnections; index++) {
                BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
                WebSocket socket = connectWebSocket(
                        URI.create(primaryUrl + "/v2/windows"), listener);
                String deviceId = "crash-load-device-" + index;
                SessionEstablished session = establish(
                        socket, listener, "crash-load-user-" + index, deviceId);
                primaryClients.add(new CrashClient(socket, session, deviceId, true));
                primaryListeners.add(listener);
            }
            for (int index = failedConnections;
                    index < failedConnections + survivingConnections; index++) {
                BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
                WebSocket socket = connectWebSocket(
                        URI.create(secondaryUrl + "/v2/windows"), listener);
                String deviceId = "crash-load-device-" + index;
                SessionEstablished session = establish(
                        socket, listener, "crash-load-user-" + index, deviceId);
                survivorClients.add(new CrashClient(socket, session, deviceId, false));
            }
            assertEquals(failedConnections, authenticationAccepted(firstAdmin));
            assertEquals(survivingConnections, authenticationAccepted(secondAdmin));
            String poolMetrics = adminMetrics(secondAdmin);
            assertEquals(1, fixedGauge(
                    poolMetrics, "chat_gateway_postgres_pool_metrics_available"));
            assertEquals(4, fixedGauge(
                    poolMetrics, "chat_gateway_postgres_connections_maximum"));
            int totalConnections = fixedGauge(
                    poolMetrics, "chat_gateway_postgres_connections_total");
            assertTrue(totalConnections >= 1 && totalConnections <= 4);
            assertEquals(1, fixedGauge(
                    poolMetrics, "chat_gateway_event_loop_metrics_available"));
            assertEquals(4, fixedGauge(
                    poolMetrics, "chat_gateway_event_loop_workers"));
            assertTrue(fixedGauge(
                    poolMetrics, "chat_gateway_event_loop_probe_samples_total") >= 4);
            int cpuTimeAvailable = fixedGauge(
                    poolMetrics, "chat_gateway_process_cpu_time_available");
            assertTrue(cpuTimeAvailable == 0 || cpuTimeAvailable == 1);
            assertTrue(fixedLongGauge(
                    poolMetrics, "chat_gateway_jvm_heap_used_bytes") > 0);
            assertTrue(fixedLongGauge(
                    poolMetrics, "chat_gateway_jvm_heap_committed_bytes") > 0);
            assertTrue(fixedLongGauge(
                    poolMetrics, "chat_gateway_jvm_heap_maximum_bytes") > 0);
            assertTrue(fixedGauge(
                    poolMetrics, "chat_gateway_process_available_processors") >= 1);
            assertEquals(1, fixedGauge(
                    poolMetrics, "chat_gateway_jvm_gc_metrics_available"));
            assertTrue(fixedLongGauge(
                    poolMetrics, "chat_gateway_jvm_gc_collections_total") >= 0);
            assertTrue(fixedSecondsMillis(
                    poolMetrics, "chat_gateway_jvm_gc_collection_seconds_total") >= 0);
            int directBufferAvailable = fixedGauge(
                    poolMetrics,
                    "chat_gateway_jvm_direct_buffer_metrics_available");
            assertTrue(directBufferAvailable == 0 || directBufferAvailable == 1);
            long directBufferCount = fixedLongGauge(
                    poolMetrics, "chat_gateway_jvm_direct_buffer_count");
            long directBufferMemoryUsed = fixedLongGauge(
                    poolMetrics,
                    "chat_gateway_jvm_direct_buffer_memory_used_bytes");
            long directBufferCapacity = fixedLongGauge(
                    poolMetrics,
                    "chat_gateway_jvm_direct_buffer_total_capacity_bytes");
            assertTrue(directBufferCount >= 0);
            assertTrue(directBufferMemoryUsed >= 0);
            assertTrue(directBufferCapacity >= 0);
            if (directBufferAvailable == 0) {
                assertEquals(0, directBufferCount);
                assertEquals(0, directBufferMemoryUsed);
                assertEquals(0, directBufferCapacity);
            }
            int residentMemoryAvailable = fixedGauge(
                    poolMetrics, "chat_gateway_process_resident_memory_available");
            assertTrue(residentMemoryAvailable == 0 || residentMemoryAvailable == 1);
            long residentMemoryBytes = fixedLongGauge(
                    poolMetrics, "chat_gateway_process_resident_memory_bytes");
            assertEquals(residentMemoryAvailable == 1, residentMemoryBytes > 0);
            assertTrue(fixedSecondsMillis(
                    poolMetrics,
                    "chat_gateway_process_resident_memory_sample_age_seconds") >= 0);
            assertTrue(fixedLongGauge(
                    poolMetrics,
                    "chat_gateway_process_resident_memory_read_failures_total") >= 0);

            Files.writeString(control.resolve("haproxy-primary-stop-request"), "stop\n");
            awaitFile(control.resolve("haproxy-primary-stopped"), Duration.ofSeconds(10));
            assertTrue(first.isAlive(), "primary edge loss must not kill gateway A");
            assertTrue(second.isAlive(), "secondary gateway must remain alive");
            for (BinaryEnvelopeListener listener : primaryListeners) {
                listener.awaitTerminal(Duration.ofSeconds(3));
            }
            for (CrashClient survivor : survivorClients) {
                assertFalse(survivor.socket().isInputClosed(),
                        "secondary-edge survivor session must remain connected");
            }

            int batchSize = workload.batchSize();
            int intervalMillis = workload.intervalMillis();
            CountDownLatch ready = new CountDownLatch(primaryClients.size());
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch saturationSamplerReady = new CountDownLatch(1);
            AtomicBoolean sampleSaturation = new AtomicBoolean(true);
            long[] startNanos = new long[1];
            List<ReconnectSample> samples = new ArrayList<>();
            ReconnectSaturation saturation;
            try (ExecutorService sampler = Executors.newSingleThreadExecutor();
                    ExecutorService executor =
                            Executors.newFixedThreadPool(primaryClients.size())) {
                Future<ReconnectSaturation> saturationFuture = sampler.submit(
                        () -> sampleReconnectSaturation(
                                secondAdmin, saturationSamplerReady, sampleSaturation));
                assertTrue(saturationSamplerReady.await(5, TimeUnit.SECONDS),
                        "authentication saturation sampler did not start");
                List<Future<ReconnectSample>> futures = new ArrayList<>();
                try {
                    for (int index = 0; index < primaryClients.size(); index++) {
                        int position = index;
                        CrashClient previous = primaryClients.get(index);
                        futures.add(executor.submit(() -> {
                            ready.countDown();
                            start.await();
                            long offset = (long) (position / batchSize) * intervalMillis;
                            long scheduled = startNanos[0]
                                    + TimeUnit.MILLISECONDS.toNanos(offset);
                            long remaining;
                            while ((remaining = scheduled - System.nanoTime()) > 0) {
                                LockSupport.parkNanos(remaining);
                            }
                            long began = System.nanoTime();
                            BinaryEnvelopeListener listener = new BinaryEnvelopeListener();
                            WebSocket socket = connectWebSocket(
                                    URI.create(secondaryUrl + "/v2/windows"), listener);
                            SessionEstablished rotated = resume(
                                    socket, listener, previous.session(), previous.deviceId());
                            long latency = TimeUnit.NANOSECONDS.toMicros(
                                    System.nanoTime() - began);
                            long jitter = TimeUnit.NANOSECONDS.toMicros(
                                    Math.abs(began - scheduled));
                            return new ReconnectSample(position, socket, rotated,
                                    Math.max(1, latency), Math.max(1, jitter));
                        }));
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    startNanos[0] = System.nanoTime();
                    start.countDown();
                    for (Future<ReconnectSample> future : futures) {
                        samples.add(future.get(10, TimeUnit.SECONDS));
                    }
                } finally {
                    sampleSaturation.set(false);
                }
                saturation = saturationFuture.get(5, TimeUnit.SECONDS);
            }
            samples.sort(Comparator.comparingInt(ReconnectSample::position));
            samples.forEach(sample -> replacements.add(sample.socket()));
            assertEquals(survivingConnections + failedConnections,
                    authenticationAccepted(secondAdmin));
            writeMultiEdgeReconnectEvidence(
                    evidence, workload.name(), failedConnections, survivingConnections,
                    batchSize, intervalMillis, samples, saturation,
                    System.nanoTime() - startNanos[0]);
        } finally {
            replacements.forEach(WebSocket::abort);
            primaryClients.forEach(client -> client.socket().abort());
            survivorClients.forEach(client -> client.socket().abort());
            stopGatewayProcess(second);
            stopGatewayProcess(first);
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
                        seedRuntimeRoomAttachment(jdbcUrl, username, password);
                        assertRoomMessageDeletion(reconnected, peer,
                                jdbcUrl, username, password);
                        assertRoomRename(module, reconnected, peer,
                                jdbcUrl, username, password);
                        assertRoomPasswordSet(reconnected, peer,
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
                            assertRoomKick(reconnected, peer, newcomer,
                                    jdbcUrl, username, password);
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
                            assertRoomPasswordRecoveredAndCancelled(
                                    peerReplacement, reconnected,
                                    jdbcUrl, username, password);
                            assertNicknameChange(peerReplacement, reconnected,
                                    jdbcUrl, username, password);
                            assertUsernameChange(module, peerReplacement, reconnected,
                                    jdbcUrl, username, password);
                            assertRoomDissolution(module, peerReplacement, reconnected,
                                    jdbcUrl, username, password);
                            assertPasswordChange(module, dataSource, peerReplacement,
                                    jdbcUrl, username, password);
                        } finally { peerReplacement.finishAndReleaseAll(); }
                    } finally { reconnected.finishAndReleaseAll(); }
                } finally {
                    peer.finishAndReleaseAll();
                }
            } finally {
                imported.finishAndReleaseAll();
            }

            assertRegistrationAndLogin(module, dataSource, jdbcUrl, username, password);

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
                assertEquals(12, sessionCount(jdbcUrl, username, password));
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
                V1RoomMessageDeletionEventSink.noop(),
                V1RoomRenameEventSink.noop(),
                V1RoomPasswordEventSink.noop(),
                V1RoomDissolutionEventSink.noop(),
                V1PasswordChangeEventSink.noop(),
                V1RegistrationEventSink.noop(),
                V1NicknameChangeEventSink.noop(),
                V1UsernameChangeEventSink.noop(),
                V1RoomAdminEventSink.noop(),
                V1RoomKickEventSink.noop(),
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

    private static void assertRegistrationAndLogin(V1CompatibilityModule module,
            HikariDataSource dataSource, String url, String user, String password) throws Exception {
        String registration = "{\"type\":\"REGISTER_REQ\",\"data\":{"
                + "\"username\":\"runtime_user\",\"displayName\":\"Runtime User\","
                + "\"password\":\"runtime-password\"}}";
        long userId;
        EmbeddedChannel first = upgradedChannel(module, Runnable::run,
                AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
        try {
            first.writeInbound(new TextWebSocketFrame(registration)); first.runPendingTasks();
            TextWebSocketFrame response = first.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"REGISTER_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"duplicate\":false"));
                userId = numericDataField(response.text(), "userId");
                assertTrue(userId > 0); assertFalse(response.text().contains("runtime-password"));
            } finally { response.release(); }
        } finally { first.finishAndReleaseAll(); }

        try (V1RoomPasswordKeyMaterial key = V1RoomPasswordKeyMaterial.fromEnvironment(Map.of(
                    V1RoomPasswordKeyMaterial.ENVIRONMENT_KEY,
                    Base64.getEncoder().encodeToString(new byte[32])));
                V1CompatibilityModule restarted = V1CompatibilityModule.create(
                        dataSource, Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"),
                                ZoneOffset.UTC), key)) {
            EmbeddedChannel retry = upgradedChannel(restarted, Runnable::run,
                    AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
            try {
                retry.writeInbound(new TextWebSocketFrame(registration)); retry.runPendingTasks();
                TextWebSocketFrame response = retry.readOutbound();
                try {
                    assertTrue(response.text().contains("\"duplicate\":true"));
                    assertEquals(userId, numericDataField(response.text(), "userId"));
                } finally { response.release(); }
            } finally { retry.finishAndReleaseAll(); }

            EmbeddedChannel collision = upgradedChannel(restarted, Runnable::run,
                    AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
            try {
                collision.writeInbound(new TextWebSocketFrame(registration.replace(
                        "Runtime User", "Different User"))); collision.runPendingTasks();
                TextWebSocketFrame response = collision.readOutbound();
                try {
                    assertTrue(response.text().contains("\"success\":false"));
                    assertTrue(response.text().contains("USERNAME_TAKEN"));
                } finally { response.release(); }
            } finally { collision.finishAndReleaseAll(); }

            EmbeddedChannel login = upgradedChannel(restarted, Runnable::run,
                    AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
            try {
                login.writeInbound(loginFrame("runtime_user", "runtime-password"));
                login.runPendingTasks(); TextWebSocketFrame response = login.readOutbound();
                try {
                    assertTrue(response.text().contains("\"success\":true"));
                    assertTrue(response.text().contains("\"userId\":" + userId));
                } finally { response.release(); }
            } finally { login.finishAndReleaseAll(); }
        }
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.legacy_v1_registration_audit audit "
                        + "JOIN chat.account account ON account.id = audit.account_id "
                        + "WHERE account.username_key = 'runtime_user' "
                        + "AND account.password_hash LIKE '$argon2id$%'"));
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

    private static void seedRuntimeRoomAttachment(
            String url, String user, String password) throws Exception {
        UUID room = UUID.fromString("30000000-0000-0000-0000-000000000007");
        UUID owner = UUID.fromString("15000000-0000-0000-0000-000000000044");
        UUID device = UUID.fromString("50000000-0000-0000-0000-000000000044");
        UUID attachment = UUID.fromString("71000000-0000-0000-0000-000000000502");
        UUID message = UUID.fromString("72000000-0000-0000-0000-000000000702");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try {
                long sequence;
                try (PreparedStatement allocate = connection.prepareStatement(
                        "UPDATE chat.conversation SET next_sequence = next_sequence + 1 "
                                + "WHERE id = ? RETURNING next_sequence - 1")) {
                    allocate.setObject(1, room);
                    try (ResultSet row = allocate.executeQuery()) {
                        assertTrue(row.next()); sequence = row.getLong(1);
                    }
                }
                assertEquals(11, sequence);
                execute(connection, "INSERT INTO chat.attachment(id, conversation_id, "
                        + "owner_account_id, owner_device_id, client_attachment_id, object_key, "
                        + "file_name, media_type, byte_size, content_sha256, state, ready_at) "
                        + "VALUES (?, ?, ?, ?, 'runtime-room-file-502', "
                        + "'attachments/runtime-room-file-502', 'runtime.pdf', "
                        + "'application/pdf', 456, decode(?, 'hex'), 'READY', "
                        + "transaction_timestamp())", attachment, room, owner, device,
                        "33".repeat(32));
                execute(connection, "INSERT INTO chat.conversation_entry(conversation_id, "
                        + "conversation_sequence, entry_kind, occurred_at) "
                        + "VALUES (?, ?, 'MESSAGE', transaction_timestamp())", room, sequence);
                execute(connection, "INSERT INTO chat.message(id, conversation_id, "
                        + "conversation_sequence, sender_account_id, sender_device_id, "
                        + "client_message_id, message_type, payload, payload_sha256, "
                        + "attachment_id) VALUES (?, ?, ?, ?, ?, 'runtime-room-message-702', "
                        + "2, decode('', 'hex'), decode(?, 'hex'), ?)", message, room,
                        sequence, owner, device, "00".repeat(32), attachment);
                execute(connection, "INSERT INTO chat.legacy_v1_message_map(legacy_kind, "
                        + "legacy_message_id, legacy_conversation_id, conversation_id, "
                        + "message_id, legacy_content_type) VALUES "
                        + "('ROOM', 702, 7, ?, ?, 'file')", room, message);
                execute(connection, "INSERT INTO chat.legacy_v1_attachment_map(legacy_kind, "
                        + "legacy_file_id, legacy_conversation_id, conversation_id, "
                        + "attachment_id) VALUES ('ROOM', 502, 7, ?, ?)", room, attachment);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    private static void assertRoomMessageDeletion(EmbeddedChannel sender,
            EmbeddedChannel recipient, String url, String user, String password) throws Exception {
        String request = "{\"type\":\"DELETE_MSGS_REQ\",\"data\":{\"roomId\":7,"
                + "\"mode\":\"selected\",\"messageIds\":[702],"
                + "\"clientOperationId\":\"delete-message-702\"}}";
        sender.writeInbound(new TextWebSocketFrame(request)); sender.runPendingTasks();
        TextWebSocketFrame response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"DELETE_MSGS_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"duplicate\":false"));
            assertTrue(response.text().contains("\"messageIds\":[702]"));
            assertTrue(response.text().contains("\"deletedFileIds\":[502]"));
            assertTrue(response.text().contains("\"sequence\":12"));
            assertFalse(response.text().contains("71000000-0000"));
        } finally { response.release(); }
        sender.runPendingTasks(); TextWebSocketFrame ownSystem = sender.readOutbound();
        try { assertTrue(ownSystem.text().contains("\"type\":\"SYSTEM_MSG\"")); }
        finally { ownSystem.release(); }
        recipient.runPendingTasks();
        TextWebSocketFrame deletion = recipient.readOutbound(), system = recipient.readOutbound();
        try {
            assertTrue(deletion.text().contains("\"type\":\"DELETE_MSGS_NOTIFY\""));
            assertTrue(deletion.text().contains("\"messageIds\":[702]"));
            assertTrue(deletion.text().contains("\"syncSequence\":12"));
            assertTrue(system.text().contains("删除了 1 条消息"));
        } finally { deletion.release(); system.release(); }

        sender.writeInbound(new TextWebSocketFrame(request)); sender.runPendingTasks();
        response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"duplicate\":true"));
            assertTrue(response.text().contains("\"sequence\":12"));
        } finally { response.release(); }
        sender.runPendingTasks(); assertNull(sender.readOutbound());
        recipient.runPendingTasks(); assertNull(recipient.readOutbound());

        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"DELETE_MSGS_REQ\",\"data\":{\"roomId\":7,"
                        + "\"mode\":\"selected\",\"messageIds\":[700],"
                        + "\"clientOperationId\":\"delete-message-702\"}}"));
        sender.runPendingTasks(); response = sender.readOutbound();
        try { assertTrue(response.text().contains("CLIENT_OPERATION_ID_CONFLICT")); }
        finally { response.release(); }

        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.attachment WHERE id = "
                        + "'71000000-0000-0000-0000-000000000502' "
                        + "AND state = 'REVOKED' AND revoked_at IS NOT NULL "
                        + "AND object_deleted_at IS NULL"));
        assertEquals(0, countQuery(url, user, password,
                "SELECT count(*) FROM chat.legacy_v1_message_map "
                        + "WHERE legacy_kind = 'ROOM' AND legacy_message_id = 702"));
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.messages_deleted_event deletion "
                        + "JOIN chat.legacy_v1_deletion_event_map mapping "
                        + "ON mapping.conversation_id = deletion.conversation_id "
                        + "AND mapping.conversation_sequence = deletion.conversation_sequence "
                        + "WHERE deletion.client_operation_id = 'delete-message-702' "
                        + "AND deletion.message_ids = '[702]'::jsonb "
                        + "AND deletion.file_ids = '[502]'::jsonb"));

        sender.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"HISTORY_REQ\",\"data\":{\"roomId\":7,"
                        + "\"count\":10,\"afterSequence\":10}}"));
        sender.runPendingTasks(); response = sender.readOutbound();
        try {
            assertTrue(response.text().contains("\"eventType\":\"messagesDeleted\""));
            assertTrue(response.text().contains("\"messageIds\":[702]"));
            assertTrue(response.text().contains("\"deletedFileIds\":[502]"));
            assertTrue(response.text().contains("\"sequence\":12"));
            assertTrue(response.text().contains("\"nextSequence\":12"));
            assertTrue(response.text().contains("\"lastSequence\":12"));
            assertFalse(response.text().contains("runtime.pdf"));
        } finally { response.release(); }
    }

    private static void assertRoomRename(V1CompatibilityModule module,
            EmbeddedChannel owner, EmbeddedChannel member,
            String url, String user, String password) throws Exception {
        String request = "{\"type\":\"RENAME_ROOM_REQ\",\"data\":{\"roomId\":7,"
                + "\"newName\":\"Renamed Imported Room\"}}";
        member.writeInbound(new TextWebSocketFrame(request)); member.runPendingTasks();
        TextWebSocketFrame denied = member.readOutbound();
        try {
            assertTrue(denied.text().contains("\"type\":\"RENAME_ROOM_RSP\""));
            assertTrue(denied.text().contains("\"success\":false"));
            assertTrue(denied.text().contains("ROOM_ADMIN_REQUIRED"));
        } finally { denied.release(); }

        owner.writeInbound(new TextWebSocketFrame(request)); owner.runPendingTasks();
        TextWebSocketFrame response = owner.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"RENAME_ROOM_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":true"));
            assertTrue(response.text().contains("\"newName\":\"Renamed Imported Room\""));
            assertFalse(response.text().contains("30000000-0000"));
        } finally { response.release(); }
        owner.runPendingTasks();
        TextWebSocketFrame ownRename = owner.readOutbound(), ownSystem = owner.readOutbound();
        member.runPendingTasks();
        TextWebSocketFrame memberRename = member.readOutbound(), memberSystem = member.readOutbound();
        try {
            assertTrue(ownRename.text().contains("\"type\":\"RENAME_ROOM_NOTIFY\""));
            assertTrue(memberRename.text().contains("\"newName\":\"Renamed Imported Room\""));
            assertTrue(ownSystem.text().contains("\"type\":\"SYSTEM_MSG\""));
            assertTrue(memberSystem.text().contains("管理员 Imported V1"));
        } finally {
            ownRename.release(); ownSystem.release(); memberRename.release(); memberSystem.release();
        }

        owner.writeInbound(new TextWebSocketFrame(request)); owner.runPendingTasks();
        response = owner.readOutbound();
        try { assertTrue(response.text().contains("\"changed\":false")); }
        finally { response.release(); }
        owner.runPendingTasks(); assertNull(owner.readOutbound());
        member.runPendingTasks(); assertNull(member.readOutbound());
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.conversation conversation "
                        + "JOIN chat.legacy_v1_conversation_map mapping "
                        + "ON mapping.conversation_id = conversation.id "
                        + "WHERE mapping.legacy_kind = 'ROOM' "
                        + "AND mapping.legacy_conversation_id = 7 "
                        + "AND conversation.title = 'Renamed Imported Room'"));

        EmbeddedChannel freshMember = upgradedChannel(module, Runnable::run,
                AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
        try {
            freshMember.writeInbound(loginFrame(
                    "imported-newcomer", "java-v2-test-password"));
            freshMember.runPendingTasks(); ((TextWebSocketFrame) freshMember.readOutbound()).release();
            freshMember.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
            freshMember.runPendingTasks(); TextWebSocketFrame rooms = freshMember.readOutbound();
            try {
                assertTrue(rooms.text().contains("\"roomId\":7"));
                assertTrue(rooms.text().contains(
                        "\"roomName\":\"Renamed Imported Room\""));
                assertFalse(rooms.text().contains("30000000-0000"));
            } finally { rooms.release(); }
        } finally { freshMember.finishAndReleaseAll(); }
    }

    private static void assertRoomPasswordSet(EmbeddedChannel owner, EmbeddedChannel member,
            String url, String user, String password) throws Exception {
        String secret = "secure-room-password";
        String request = "{\"type\":\"SET_ROOM_PASSWORD_REQ\",\"data\":{\"roomId\":7,"
                + "\"password\":\"" + secret + "\"}}";
        member.writeInbound(new TextWebSocketFrame(request)); member.runPendingTasks();
        TextWebSocketFrame denied = member.readOutbound();
        try {
            assertTrue(denied.text().contains("\"type\":\"SET_ROOM_PASSWORD_RSP\""));
            assertTrue(denied.text().contains("\"success\":false"));
            assertTrue(denied.text().contains("ROOM_ADMIN_REQUIRED"));
            assertFalse(denied.text().contains(secret));
        } finally { denied.release(); }

        owner.writeInbound(new TextWebSocketFrame(request)); owner.runPendingTasks();
        TextWebSocketFrame response = owner.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"hasPassword\":true"));
            assertTrue(response.text().contains("\"changed\":true"));
            assertFalse(response.text().contains(secret));
            assertFalse(response.text().contains("argon2"));
        } finally { response.release(); }
        owner.runPendingTasks(); member.runPendingTasks();
        TextWebSocketFrame ownSystem = owner.readOutbound(), memberSystem = member.readOutbound();
        try {
            assertTrue(ownSystem.text().contains("已设置/修改聊天室密码"));
            assertTrue(memberSystem.text().contains("\"type\":\"SYSTEM_MSG\""));
            assertFalse(ownSystem.text().contains(secret));
            assertFalse(memberSystem.text().contains(secret));
        } finally { ownSystem.release(); memberSystem.release(); }

        owner.writeInbound(new TextWebSocketFrame(request)); owner.runPendingTasks();
        response = owner.readOutbound();
        try { assertTrue(response.text().contains("\"changed\":false")); }
        finally { response.release(); }
        owner.runPendingTasks(); member.runPendingTasks();
        assertNull(owner.readOutbound()); assertNull(member.readOutbound());

        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.group_join_credential credential "
                        + "JOIN chat.legacy_v1_conversation_map mapping "
                        + "ON mapping.conversation_id = credential.conversation_id "
                        + "WHERE mapping.legacy_kind = 'ROOM' "
                        + "AND mapping.legacy_conversation_id = 7 "
                        + "AND credential.encoded_password LIKE '$argon2id$%' "
                        + "AND credential.password_idempotency_tag LIKE 'hmac-sha256:v1:%' "
                        + "AND credential.encoded_password NOT LIKE '%secure-room-password%'"));
    }

    private static void assertRoomPasswordRecoveredAndCancelled(
            EmbeddedChannel replacementAdmin, EmbeddedChannel owner,
            String url, String user, String password) throws Exception {
        replacementAdmin.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"GET_ROOM_PASSWORD_REQ\",\"data\":{\"roomId\":7}}"));
        replacementAdmin.runPendingTasks(); TextWebSocketFrame status = replacementAdmin.readOutbound();
        try {
            assertTrue(status.text().contains("\"type\":\"GET_ROOM_PASSWORD_RSP\""));
            assertTrue(status.text().contains("\"success\":true"));
            assertTrue(status.text().contains("\"hasPassword\":true"));
            assertFalse(status.text().contains("secure-room-password"));
            assertFalse(status.text().contains("argon2"));
        } finally { status.release(); }

        String cancel = "{\"type\":\"SET_ROOM_PASSWORD_REQ\",\"data\":{"
                + "\"roomId\":7,\"password\":\"\"}}";
        replacementAdmin.writeInbound(new TextWebSocketFrame(cancel));
        replacementAdmin.runPendingTasks(); TextWebSocketFrame response = replacementAdmin.readOutbound();
        try {
            assertTrue(response.text().contains("\"hasPassword\":false"));
            assertTrue(response.text().contains("\"changed\":true"));
        } finally { response.release(); }
        replacementAdmin.runPendingTasks(); owner.runPendingTasks();
        TextWebSocketFrame ownSystem = replacementAdmin.readOutbound();
        TextWebSocketFrame ownerSystem = owner.readOutbound();
        try {
            assertTrue(ownSystem.text().contains("已取消聊天室密码"));
            assertTrue(ownerSystem.text().contains("已取消聊天室密码"));
        } finally { ownSystem.release(); ownerSystem.release(); }

        replacementAdmin.writeInbound(new TextWebSocketFrame(cancel));
        replacementAdmin.runPendingTasks(); response = replacementAdmin.readOutbound();
        try { assertTrue(response.text().contains("\"changed\":false")); }
        finally { response.release(); }
        assertEquals(0, countQuery(url, user, password,
                "SELECT count(*) FROM chat.group_join_credential credential "
                        + "JOIN chat.legacy_v1_conversation_map mapping "
                        + "ON mapping.conversation_id = credential.conversation_id "
                        + "WHERE mapping.legacy_kind = 'ROOM' "
                        + "AND mapping.legacy_conversation_id = 7"));
    }

    private static void assertRoomDissolution(V1CompatibilityModule module,
            EmbeddedChannel admin, EmbeddedChannel owner,
            String url, String user, String password) throws Exception {
        String request = "{\"type\":\"DELETE_ROOM_REQ\",\"data\":{\"roomId\":7,"
                + "\"roomName\":\"Spoofed Room\"}}";
        admin.writeInbound(new TextWebSocketFrame(request)); admin.runPendingTasks();
        TextWebSocketFrame response = admin.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"DELETE_ROOM_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":true"));
            assertTrue(response.text().contains("\"roomName\":\"Renamed Imported Room\""));
            assertFalse(response.text().contains("Spoofed Room"));
        } finally { response.release(); }
        admin.runPendingTasks(); owner.runPendingTasks();
        TextWebSocketFrame ownNotify = admin.readOutbound(), ownerNotify = owner.readOutbound();
        try {
            assertTrue(ownNotify.text().contains("\"type\":\"DELETE_ROOM_NOTIFY\""));
            assertTrue(ownerNotify.text().contains("\"operator\":\"Modern Peer\""));
            assertFalse(ownNotify.text().contains("Spoofed Room"));
        } finally { ownNotify.release(); ownerNotify.release(); }

        admin.writeInbound(new TextWebSocketFrame(request)); admin.runPendingTasks();
        response = admin.readOutbound();
        try { assertTrue(response.text().contains("\"changed\":false")); }
        finally { response.release(); }
        admin.runPendingTasks(); owner.runPendingTasks();
        assertNull(admin.readOutbound()); assertNull(owner.readOutbound());
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.legacy_v1_room_dissolution dissolution "
                        + "JOIN chat.legacy_v1_conversation_map mapping "
                        + "ON mapping.conversation_id = dissolution.conversation_id "
                        + "WHERE mapping.legacy_kind = 'ROOM' "
                        + "AND mapping.legacy_conversation_id = 7 "
                        + "AND dissolution.room_name = 'Renamed Imported Room'"));
        assertEquals(0, countQuery(url, user, password,
                "SELECT count(*) FROM chat.conversation_member member "
                        + "JOIN chat.legacy_v1_conversation_map mapping "
                        + "ON mapping.conversation_id = member.conversation_id "
                        + "WHERE mapping.legacy_kind = 'ROOM' "
                        + "AND mapping.legacy_conversation_id = 7 AND member.left_at IS NULL"));

        EmbeddedChannel replacementOwner = upgradedChannel(module, Runnable::run,
                AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
        try {
            replacementOwner.writeInbound(loginFrame(
                    "imported-v1", "java-v2-test-password"));
            replacementOwner.runPendingTasks();
            ((TextWebSocketFrame) replacementOwner.readOutbound()).release();
            replacementOwner.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
            replacementOwner.runPendingTasks(); TextWebSocketFrame rooms = replacementOwner.readOutbound();
            try {
                assertTrue(rooms.text().contains("\"type\":\"ROOM_LIST_RSP\""));
                assertFalse(rooms.text().contains("\"roomId\":7"));
                assertFalse(rooms.text().contains("Renamed Imported Room"));
            } finally { rooms.release(); }
        } finally { replacementOwner.finishAndReleaseAll(); }
    }

    private static void assertNicknameChange(EmbeddedChannel actor, EmbeddedChannel roomPeer,
            String url, String user, String password) throws Exception {
        String request = "{\"type\":\"CHANGE_NICKNAME_REQ\",\"data\":{"
                + "\"displayName\":\"  Modern Peer  \"}}";
        actor.writeInbound(new TextWebSocketFrame(request)); actor.runPendingTasks();
        TextWebSocketFrame response = actor.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"CHANGE_NICKNAME_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":true"));
            assertTrue(response.text().contains("\"displayName\":\"Modern Peer\""));
            assertFalse(response.text().contains("15000000-0000"));
        } finally { response.release(); }
        assertEquals("Modern Peer", actor.attr(V1ConnectionAttributes.AUTHENTICATED)
                .get().displayName());
        actor.runPendingTasks(); roomPeer.runPendingTasks();
        TextWebSocketFrame ownNotify = actor.readOutbound(), peerNotify = roomPeer.readOutbound();
        try {
            assertTrue(ownNotify.text().contains("\"type\":\"NICKNAME_CHANGE_NOTIFY\""));
            assertTrue(ownNotify.text().contains("\"roomId\":7"));
            assertTrue(peerNotify.text().contains("\"username\":\"imported-peer\""));
            assertTrue(peerNotify.text().contains("\"displayName\":\"Modern Peer\""));
        } finally { ownNotify.release(); peerNotify.release(); }

        actor.writeInbound(new TextWebSocketFrame(request)); actor.runPendingTasks();
        response = actor.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":false"));
        } finally { response.release(); }
        actor.runPendingTasks(); roomPeer.runPendingTasks();
        assertNull(actor.readOutbound()); assertNull(roomPeer.readOutbound());
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.account_display_name_change_audit audit "
                        + "JOIN chat.account account ON account.id = audit.account_id "
                        + "WHERE account.username_key = 'imported-peer' "
                        + "AND audit.old_display_name = 'Imported Peer' "
                        + "AND audit.new_display_name = 'Modern Peer'"));
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.account WHERE username_key = 'imported-peer' "
                        + "AND display_name = 'Modern Peer'"));
    }

    private static void assertUsernameChange(V1CompatibilityModule module,
            EmbeddedChannel actor, EmbeddedChannel roomPeer,
            String url, String user, String password) throws Exception {
        String request = "{\"type\":\"CHANGE_UID_REQ\",\"data\":{"
                + "\"newUid\":\"  modern_peer  \"}}";
        actor.writeInbound(new TextWebSocketFrame(request)); actor.runPendingTasks();
        TextWebSocketFrame response = actor.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"CHANGE_UID_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":true"));
            assertTrue(response.text().contains("\"oldUid\":\"imported-peer\""));
            assertTrue(response.text().contains("\"newUid\":\"modern_peer\""));
            assertFalse(response.text().contains("15000000-0000"));
        } finally { response.release(); }
        assertEquals("modern_peer", actor.attr(V1ConnectionAttributes.AUTHENTICATED)
                .get().username());
        actor.runPendingTasks(); assertNull(actor.readOutbound());
        roomPeer.runPendingTasks(); TextWebSocketFrame peerNotify = roomPeer.readOutbound();
        try {
            assertTrue(peerNotify.text().contains("\"type\":\"UID_CHANGE_NOTIFY\""));
            assertTrue(peerNotify.text().contains("\"roomId\":7"));
            assertTrue(peerNotify.text().contains("\"oldUid\":\"imported-peer\""));
            assertTrue(peerNotify.text().contains("\"newUid\":\"modern_peer\""));
            assertTrue(peerNotify.text().contains("\"displayName\":\"Modern Peer\""));
        } finally { peerNotify.release(); }

        actor.writeInbound(new TextWebSocketFrame(request)); actor.runPendingTasks();
        response = actor.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":false"));
        } finally { response.release(); }
        actor.runPendingTasks(); roomPeer.runPendingTasks();
        assertNull(actor.readOutbound()); assertNull(roomPeer.readOutbound());
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.account_username_change_audit audit "
                        + "JOIN chat.account account ON account.id = audit.account_id "
                        + "WHERE account.username_key = 'modern_peer' "
                        + "AND audit.old_username = 'imported-peer' "
                        + "AND audit.new_username = 'modern_peer'"));

        EmbeddedChannel oldLogin = upgradedChannel(module, Runnable::run,
                AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
        try {
            oldLogin.writeInbound(loginFrame(
                    "imported-peer", "java-v2-test-password")); oldLogin.runPendingTasks();
            TextWebSocketFrame denied = oldLogin.readOutbound();
            try { assertTrue(denied.text().contains("\"success\":false")); }
            finally { denied.release(); }
            assertFalse(oldLogin.isActive());
        } finally { oldLogin.finishAndReleaseAll(); }
    }

    private static void assertPasswordChange(V1CompatibilityModule module,
            HikariDataSource dataSource, EmbeddedChannel current,
            String url, String user, String password) throws Exception {
        String request = "{\"type\":\"CHANGE_PASSWORD_REQ\",\"data\":{"
                + "\"oldPassword\":\"java-v2-test-password\","
                + "\"newPassword\":\"changed-v1-password\"}}";
        current.writeInbound(new TextWebSocketFrame(request)); current.runPendingTasks();
        TextWebSocketFrame response = current.readOutbound();
        int revoked;
        try {
            assertTrue(response.text().contains("\"type\":\"CHANGE_PASSWORD_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":true"));
            revoked = (int) numericDataField(response.text(), "otherSessionsRevoked");
            assertTrue(revoked >= 1);
            assertFalse(response.text().contains("java-v2-test-password"));
            assertFalse(response.text().contains("changed-v1-password"));
            assertFalse(response.text().contains("10000000-0000"));
        } finally { response.release(); }

        current.writeInbound(new TextWebSocketFrame(request)); current.runPendingTasks();
        response = current.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":false"));
            assertTrue(response.text().contains("\"otherSessionsRevoked\":0"));
        } finally { response.release(); }
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.account_password_change_audit audit "
                        + "JOIN chat.account account ON account.id = audit.account_id "
                        + "WHERE account.username_key = 'modern_peer' "
                        + "AND audit.other_sessions_revoked = " + revoked));
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.account WHERE username_key = 'modern_peer' "
                        + "AND password_scheme = 'ARGON2ID' "
                        + "AND password_hash LIKE '$argon2id$%' "
                        + "AND password_hash NOT LIKE '%changed-v1-password%'"));

        EmbeddedChannel oldLogin = upgradedChannel(module, Runnable::run,
                AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
        try {
            oldLogin.writeInbound(loginFrame(
                    "modern_peer", "java-v2-test-password")); oldLogin.runPendingTasks();
            TextWebSocketFrame denied = oldLogin.readOutbound();
            try { assertTrue(denied.text().contains("\"success\":false")); }
            finally { denied.release(); }
            assertFalse(oldLogin.isActive());
        } finally { oldLogin.finishAndReleaseAll(); }

        try (V1RoomPasswordKeyMaterial key = V1RoomPasswordKeyMaterial.fromEnvironment(Map.of(
                    V1RoomPasswordKeyMaterial.ENVIRONMENT_KEY,
                    Base64.getEncoder().encodeToString(new byte[32])));
                V1CompatibilityModule restarted = V1CompatibilityModule.create(
                        dataSource, Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"),
                                ZoneOffset.UTC), key)) {
            EmbeddedChannel newLogin = upgradedChannel(restarted, Runnable::run,
                    AuthenticationAdmissionControl.allowAll(), AuthenticationEventSink.noop());
            try {
                newLogin.writeInbound(loginFrame(
                        "modern_peer", "changed-v1-password")); newLogin.runPendingTasks();
                TextWebSocketFrame accepted = newLogin.readOutbound();
                try {
                    assertTrue(accepted.text().contains("\"success\":true"));
                    assertTrue(accepted.text().contains("\"displayName\":\"Modern Peer\""));
                }
                finally { accepted.release(); }
            } finally { newLogin.finishAndReleaseAll(); }
        }
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

    private static void assertRoomKick(EmbeddedChannel owner, EmbeddedChannel remaining,
            EmbeddedChannel target, String url, String user, String password) throws Exception {
        String request = "{\"type\":\"KICK_USER_REQ\",\"data\":{\"roomId\":7,"
                + "\"username\":\"imported-newcomer\"}}";
        owner.writeInbound(new TextWebSocketFrame(request)); owner.runPendingTasks();
        TextWebSocketFrame response = owner.readOutbound();
        try {
            assertTrue(response.text().contains("\"type\":\"KICK_USER_RSP\""));
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":true"));
            assertTrue(response.text().contains("\"username\":\"imported-newcomer\""));
            assertFalse(response.text().contains("16000000-0000"));
        } finally { response.release(); }

        target.runPendingTasks(); TextWebSocketFrame kicked = target.readOutbound();
        try {
            assertTrue(kicked.text().contains("\"type\":\"KICK_USER_NOTIFY\""));
            assertTrue(kicked.text().contains("\"roomId\":7"));
            assertTrue(kicked.text().contains(
                    "\"roomName\":\"Renamed Imported Room\""));
            assertTrue(kicked.text().contains("\"operator\":\"Imported V1\""));
        } finally { kicked.release(); }
        remaining.runPendingTasks();
        TextWebSocketFrame left = remaining.readOutbound(), system = remaining.readOutbound();
        try {
            assertTrue(left.text().contains("\"type\":\"USER_LEFT\""));
            assertTrue(left.text().contains("\"username\":\"imported-newcomer\""));
            assertTrue(system.text().contains("\"type\":\"SYSTEM_MSG\""));
            assertTrue(system.text().contains("Imported Newcomer"));
        } finally { left.release(); system.release(); }
        assertEquals(1, countQuery(url, user, password,
                "SELECT count(*) FROM chat.legacy_v1_room_kick_event event "
                        + "JOIN chat.conversation_member member "
                        + "ON member.conversation_id = event.conversation_id "
                        + "AND member.account_id = event.target_account_id "
                        + "AND member.left_at = event.kicked_at "
                        + "WHERE event.legacy_room_id = 7 "
                        + "AND event.target_username_snapshot = 'imported-newcomer'"));

        owner.writeInbound(new TextWebSocketFrame(request)); owner.runPendingTasks();
        response = owner.readOutbound();
        try {
            assertTrue(response.text().contains("\"success\":true"));
            assertTrue(response.text().contains("\"changed\":false"));
        } finally { response.release(); }
        target.runPendingTasks(); assertNull(target.readOutbound());
        remaining.runPendingTasks(); assertNull(remaining.readOutbound());

        target.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}"));
        target.runPendingTasks(); response = target.readOutbound();
        try {
            assertFalse(response.text().contains("\"roomId\":7"));
            assertFalse(response.text().contains("Imported Room"));
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
            assertTrue(response.text().contains("\"memberCount\":3"));
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

    private static void execute(Connection connection, String sql, Object... values)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++)
                statement.setObject(index + 1, values[index]);
            assertEquals(1, statement.executeUpdate());
        }
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
                member.setObject(1, importedRoom);
                member.setObject(2, newcomer);
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

    private static void awaitCount(String url, String user, String password,
            String sql, int expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int actual;
        do {
            actual = countQuery(url, user, password, sql);
            if (actual == expected) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, actual, "database count did not converge");
    }

    private static void awaitFile(Path path, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.isRegularFile(path) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.isRegularFile(path), "timed out waiting for " + path.getFileName());
    }

    private static Process startGatewayProcess(
            Map<String, String> environment, String classpath, Path log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, "com.fallingnight.chat.gateway.GatewayMain");
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private static void awaitProductReady(
            int adminPort, Process process, Path log, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), "gateway process exited before readiness");
            try {
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + adminPort + "/health/ready"))
                                .timeout(Duration.ofMillis(500)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return;
            } catch (java.io.IOException ignored) {
                // Listener startup is asynchronous relative to the child process.
            }
            Thread.sleep(25);
        }
        String output = Files.isRegularFile(log) ? Files.readString(log) : "log unavailable";
        throw new AssertionError("gateway process did not become ready; log:\n" + output);
    }

    private static void stopGatewayProcess(Process process) throws Exception {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "gateway process did not stop");
        }
    }

    private static void awaitNotReady(GatewayRuntime runtime, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (runtime.isReady() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(runtime.isReady(), "gateway remained ready after Redis lease expiry");
    }

    private static void assertReadiness(int adminPort, int status, String body)
            throws Exception {
        assertAdminEndpoint(adminPort, "/health/ready", status, body);
    }

    private static void assertAdminEndpoint(
            int adminPort, String path, int status, String body) throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build().send(
                        HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + adminPort + path))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode());
        assertEquals(body, response.body());
    }

    private static void assertProductReadiness(
            int gatewayPort, int status, String body) throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .sslContext(trustAllTls()).connectTimeout(Duration.ofSeconds(2)).build().send(
                        HttpRequest.newBuilder(URI.create(
                                "https://localhost:" + gatewayPort + "/health/ready"))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode());
        assertEquals(body, response.body());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
    }

    private static Map<String, String> distributedNetworkEnvironment(
            int gatewayPort, int adminPort, SelfSignedCertificate certificate,
            String jdbcUrl, String username, String redisUri) {
        return distributedNetworkEnvironment(gatewayPort, adminPort,
                certificate.certificate().getAbsolutePath(),
                certificate.privateKey().getAbsolutePath(), jdbcUrl, username, redisUri,
                "localhost:" + gatewayPort);
    }

    private static Map<String, String> distributedNetworkEnvironment(
            int gatewayPort, int adminPort, String certificatePath, String keyPath,
            String jdbcUrl, String username, String redisUri, String allowedAuthority) {
        Map<String, String> environment = new HashMap<>();
        environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(gatewayPort));
        environment.put("CHATROOM_GATEWAY_ADMIN_PORT", Integer.toString(adminPort));
        environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE", certificatePath);
        environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY", keyPath);
        environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS",
                allowedAuthority + ",chat.example.com");
        environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
        environment.put("CHATROOM_POSTGRES_URL", jdbcUrl);
        environment.put("CHATROOM_POSTGRES_USER", username);
        environment.put("CHATROOM_POSTGRES_PASSWORD", "test-trust-password");
        environment.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
        environment.put("CHATROOM_POSTGRES_POOL_MAXIMUM", "4");
        environment.put("CHATROOM_POSTGRES_POOL_MINIMUM_IDLE", "1");
        environment.put(DistributedGatewayRoutingConfig.ENABLED, "true");
        environment.put(DistributedGatewayRoutingConfig.REDIS_URI, redisUri);
        environment.put(DistributedGatewayRoutingConfig.ALLOW_INSECURE_LOOPBACK, "true");
        environment.put(DistributedGatewayRoutingConfig.ROUTE_LEASE_SECONDS, "5");
        return environment;
    }

    private static String proxyAuthority(String proxyUrl) {
        URI uri = URI.create(proxyUrl);
        return uri.getHost() + ":" + uri.getPort();
    }

    private static String proxyCertificateSha256(String proxyUrl) throws Exception {
        URI proxy = URI.create(proxyUrl);
        URI endpoint = new URI("https", null, proxy.getHost(), proxy.getPort(),
                "/health/ready", null, null);
        HttpResponse<String> response = HttpClient.newBuilder()
                .sslContext(trustAllTls()).connectTimeout(Duration.ofSeconds(2))
                .build().send(HttpRequest.newBuilder(
                        endpoint)
                        .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        byte[] certificate = response.sslSession().orElseThrow()
                .getPeerCertificates()[0].getEncoded();
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate));
    }

    private static String releaseIdentityJson(String version, String revision) {
        return "{\"schemaVersion\":1,\"releaseVersion\":\"" + version
                + "\",\"sourceRevision\":\"" + revision
                + "\",\"protocolVersion\":2,\"compatibilityEpoch\":1}\n";
    }

    private static long authenticationAccepted(int adminPort) throws Exception {
        String metrics = adminMetrics(adminPort);
        var matcher = Pattern.compile(
                "chat_gateway_authentication_total\\{outcome=\"accepted\"} ([0-9]+)")
                .matcher(metrics);
        assertTrue(matcher.find(), "missing accepted authentication metric");
        return Long.parseLong(matcher.group(1));
    }

    private static ReconnectSaturation sampleReconnectSaturation(
            int adminPort, CountDownLatch ready, AtomicBoolean running) throws Exception {
        int samples = 0;
        int activeWorkersMaximum = 0;
        int queuedWorkMaximum = 0;
        int authenticationQueuePositiveSamples = 0;
        int authenticationQueueCurrentStreak = 0;
        int authenticationQueueLongestStreak = 0;
        int postgresMetricsUnavailableSamples = 0;
        int postgresActiveConnectionsMaximum = 0;
        int postgresTotalConnectionsMaximum = 0;
        int postgresThreadsAwaitingConnectionMaximum = 0;
        int postgresWaitingPositiveSamples = 0;
        int postgresWaitingCurrentStreak = 0;
        int postgresWaitingLongestStreak = 0;
        int postgresMaximumConnections = -1;
        int eventLoopMetricsUnavailableSamples = 0;
        int eventLoopWorkers = -1;
        long eventLoopProbeSamplesBefore = -1;
        long eventLoopProbeSamplesAfter = -1;
        long eventLoopLatestMaximumLagMicros = 0;
        long eventLoopSinceStartMaximumLagMicrosBefore = -1;
        long eventLoopSinceStartMaximumLagMicrosAfter = -1;
        long eventLoopPendingTasksMaximum = 0;
        int eventLoopPendingPositiveSamples = 0;
        int eventLoopPendingCurrentStreak = 0;
        int eventLoopPendingLongestStreak = 0;
        int processCpuTimeUnavailableSamples = 0;
        long processCpuTimeMicrosBefore = -1;
        long processCpuTimeMicrosAfter = -1;
        long heapUsedBytesBefore = -1;
        long heapUsedBytesAfter = -1;
        long heapUsedBytesMaximum = 0;
        long heapCommittedBytesBefore = -1;
        long heapCommittedBytesAfter = -1;
        long heapMaximumBytes = -1;
        long uptimeMillisBefore = -1;
        long uptimeMillisAfter = -1;
        int availableProcessors = -1;
        int gcMetricsUnavailableSamples = 0;
        long gcCollectionsBefore = -1;
        long gcCollectionsAfter = -1;
        long gcCollectionTimeMillisBefore = -1;
        long gcCollectionTimeMillisAfter = -1;
        int residentMemoryUnavailableSamples = 0;
        long residentMemoryBytesBefore = -1;
        long residentMemoryBytesAfter = -1;
        long residentMemoryBytesMaximum = 0;
        long residentMemorySampleAgeMillisMaximum = 0;
        long residentMemoryReadFailuresBefore = -1;
        long residentMemoryReadFailuresAfter = -1;
        int directBufferMetricsUnavailableSamples = 0;
        long directBufferCountBefore = -1;
        long directBufferCountAfter = -1;
        long directBufferCountMaximum = 0;
        long directBufferMemoryUsedBytesBefore = -1;
        long directBufferMemoryUsedBytesAfter = -1;
        long directBufferMemoryUsedBytesMaximum = 0;
        long directBufferTotalCapacityBytesBefore = -1;
        long directBufferTotalCapacityBytesAfter = -1;
        long directBufferTotalCapacityBytesMaximum = 0;
        long sampleIntervalNanos = TimeUnit.MILLISECONDS.toNanos(5);
        long nextSampleNanos = System.nanoTime();
        do {
            String metrics = adminMetrics(adminPort);
            activeWorkersMaximum = Math.max(activeWorkersMaximum,
                    fixedGauge(metrics,
                            "chat_gateway_authentication_workers_active"));
            int queuedWork = fixedGauge(
                    metrics, "chat_gateway_authentication_queue_size");
            queuedWorkMaximum = Math.max(queuedWorkMaximum, queuedWork);
            if (queuedWork > 0) {
                authenticationQueuePositiveSamples++;
                authenticationQueueCurrentStreak++;
                authenticationQueueLongestStreak = Math.max(
                        authenticationQueueLongestStreak,
                        authenticationQueueCurrentStreak);
            } else {
                authenticationQueueCurrentStreak = 0;
            }
            int configuredMaximum = fixedGauge(
                    metrics, "chat_gateway_postgres_connections_maximum");
            if (postgresMaximumConnections < 0) {
                postgresMaximumConnections = configuredMaximum;
            } else {
                assertEquals(postgresMaximumConnections, configuredMaximum,
                        "PostgreSQL pool maximum changed during reconnect sampling");
            }
            if (fixedGauge(metrics,
                    "chat_gateway_postgres_pool_metrics_available") == 0) {
                postgresMetricsUnavailableSamples++;
            } else {
                postgresActiveConnectionsMaximum = Math.max(
                        postgresActiveConnectionsMaximum,
                        fixedGauge(metrics,
                                "chat_gateway_postgres_connections_active"));
                postgresTotalConnectionsMaximum = Math.max(
                        postgresTotalConnectionsMaximum,
                        fixedGauge(metrics,
                                "chat_gateway_postgres_connections_total"));
                int waiting = fixedGauge(
                        metrics, "chat_gateway_postgres_threads_awaiting_connection");
                postgresThreadsAwaitingConnectionMaximum = Math.max(
                        postgresThreadsAwaitingConnectionMaximum, waiting);
                if (waiting > 0) {
                    postgresWaitingPositiveSamples++;
                    postgresWaitingCurrentStreak++;
                    postgresWaitingLongestStreak = Math.max(
                            postgresWaitingLongestStreak, postgresWaitingCurrentStreak);
                } else {
                    postgresWaitingCurrentStreak = 0;
                }
            }
            if (fixedGauge(metrics,
                    "chat_gateway_event_loop_metrics_available") == 0) {
                eventLoopMetricsUnavailableSamples++;
            } else {
                int observedWorkers = fixedGauge(
                        metrics, "chat_gateway_event_loop_workers");
                if (eventLoopWorkers < 0) {
                    eventLoopWorkers = observedWorkers;
                } else {
                    assertEquals(eventLoopWorkers, observedWorkers,
                            "event-loop worker count changed during reconnect sampling");
                }
                long probeSamples = fixedLongGauge(
                        metrics, "chat_gateway_event_loop_probe_samples_total");
                if (eventLoopProbeSamplesBefore < 0) {
                    eventLoopProbeSamplesBefore = probeSamples;
                    eventLoopSinceStartMaximumLagMicrosBefore = fixedSecondsMicros(
                            metrics, "chat_gateway_event_loop_max_lag_seconds");
                }
                eventLoopProbeSamplesAfter = probeSamples;
                eventLoopSinceStartMaximumLagMicrosAfter = fixedSecondsMicros(
                        metrics, "chat_gateway_event_loop_max_lag_seconds");
                eventLoopLatestMaximumLagMicros = Math.max(
                        eventLoopLatestMaximumLagMicros,
                        fixedSecondsMicros(
                                metrics,
                                "chat_gateway_event_loop_latest_max_lag_seconds"));
                long pendingTasks = fixedLongGauge(
                        metrics, "chat_gateway_event_loop_pending_tasks");
                eventLoopPendingTasksMaximum = Math.max(
                        eventLoopPendingTasksMaximum, pendingTasks);
                if (pendingTasks > 0) {
                    eventLoopPendingPositiveSamples++;
                    eventLoopPendingCurrentStreak++;
                    eventLoopPendingLongestStreak = Math.max(
                            eventLoopPendingLongestStreak,
                            eventLoopPendingCurrentStreak);
                } else {
                    eventLoopPendingCurrentStreak = 0;
                }
            }
            if (fixedGauge(metrics,
                    "chat_gateway_process_cpu_time_available") == 0) {
                processCpuTimeUnavailableSamples++;
            } else {
                long cpuMicros = fixedSecondsMicros(
                        metrics, "chat_gateway_process_cpu_seconds_total");
                if (processCpuTimeMicrosBefore < 0) processCpuTimeMicrosBefore = cpuMicros;
                processCpuTimeMicrosAfter = cpuMicros;
            }
            long heapUsed = fixedLongGauge(metrics, "chat_gateway_jvm_heap_used_bytes");
            long committed = fixedLongGauge(
                    metrics, "chat_gateway_jvm_heap_committed_bytes");
            long maximum = fixedLongGauge(metrics, "chat_gateway_jvm_heap_maximum_bytes");
            long uptimeMillis = fixedSecondsMillis(
                    metrics, "chat_gateway_process_uptime_seconds");
            int processors = fixedGauge(
                    metrics, "chat_gateway_process_available_processors");
            if (fixedGauge(metrics,
                    "chat_gateway_jvm_gc_metrics_available") == 0) {
                gcMetricsUnavailableSamples++;
            } else {
                long collections = fixedLongGauge(
                        metrics, "chat_gateway_jvm_gc_collections_total");
                long collectionTimeMillis = fixedSecondsMillis(
                        metrics, "chat_gateway_jvm_gc_collection_seconds_total");
                if (gcCollectionsBefore < 0) {
                    gcCollectionsBefore = collections;
                    gcCollectionTimeMillisBefore = collectionTimeMillis;
                }
                gcCollectionsAfter = collections;
                gcCollectionTimeMillisAfter = collectionTimeMillis;
            }
            int residentMemoryAvailable = fixedGauge(
                    metrics, "chat_gateway_process_resident_memory_available");
            long residentMemoryBytes = fixedLongGauge(
                    metrics, "chat_gateway_process_resident_memory_bytes");
            if (residentMemoryAvailable == 0) {
                residentMemoryUnavailableSamples++;
                assertEquals(0, residentMemoryBytes,
                        "unavailable resident memory must report zero bytes");
            } else {
                assertEquals(1, residentMemoryAvailable,
                        "resident-memory availability must be binary");
                assertTrue(residentMemoryBytes > 0,
                        "available resident memory must be positive");
                residentMemoryBytesMaximum = Math.max(
                        residentMemoryBytesMaximum, residentMemoryBytes);
            }
            long residentMemorySampleAgeMillis = fixedSecondsMillis(
                    metrics,
                    "chat_gateway_process_resident_memory_sample_age_seconds");
            long residentMemoryReadFailures = fixedLongGauge(
                    metrics,
                    "chat_gateway_process_resident_memory_read_failures_total");
            if (residentMemoryBytesBefore < 0) {
                residentMemoryBytesBefore = residentMemoryBytes;
                residentMemoryReadFailuresBefore = residentMemoryReadFailures;
            }
            residentMemoryBytesAfter = residentMemoryBytes;
            residentMemorySampleAgeMillisMaximum = Math.max(
                    residentMemorySampleAgeMillisMaximum,
                    residentMemorySampleAgeMillis);
            residentMemoryReadFailuresAfter = residentMemoryReadFailures;
            int directBufferAvailable = fixedGauge(
                    metrics, "chat_gateway_jvm_direct_buffer_metrics_available");
            long directBufferCount = fixedLongGauge(
                    metrics, "chat_gateway_jvm_direct_buffer_count");
            long directBufferMemoryUsedBytes = fixedLongGauge(
                    metrics, "chat_gateway_jvm_direct_buffer_memory_used_bytes");
            long directBufferTotalCapacityBytes = fixedLongGauge(
                    metrics, "chat_gateway_jvm_direct_buffer_total_capacity_bytes");
            if (directBufferAvailable == 0) {
                directBufferMetricsUnavailableSamples++;
                assertEquals(0, directBufferCount);
                assertEquals(0, directBufferMemoryUsedBytes);
                assertEquals(0, directBufferTotalCapacityBytes);
            } else {
                assertEquals(1, directBufferAvailable,
                        "direct-buffer availability must be binary");
            }
            if (directBufferCountBefore < 0) {
                directBufferCountBefore = directBufferCount;
                directBufferMemoryUsedBytesBefore = directBufferMemoryUsedBytes;
                directBufferTotalCapacityBytesBefore = directBufferTotalCapacityBytes;
            }
            directBufferCountAfter = directBufferCount;
            directBufferCountMaximum = Math.max(
                    directBufferCountMaximum, directBufferCount);
            directBufferMemoryUsedBytesAfter = directBufferMemoryUsedBytes;
            directBufferMemoryUsedBytesMaximum = Math.max(
                    directBufferMemoryUsedBytesMaximum,
                    directBufferMemoryUsedBytes);
            directBufferTotalCapacityBytesAfter = directBufferTotalCapacityBytes;
            directBufferTotalCapacityBytesMaximum = Math.max(
                    directBufferTotalCapacityBytesMaximum,
                    directBufferTotalCapacityBytes);
            if (heapUsedBytesBefore < 0) {
                heapUsedBytesBefore = heapUsed;
                heapCommittedBytesBefore = committed;
                heapMaximumBytes = maximum;
                uptimeMillisBefore = uptimeMillis;
                availableProcessors = processors;
            } else {
                assertEquals(heapMaximumBytes, maximum,
                        "heap maximum bytes changed during reconnect sampling");
                assertEquals(availableProcessors, processors,
                        "available processors changed during reconnect sampling");
            }
            heapUsedBytesAfter = heapUsed;
            heapUsedBytesMaximum = Math.max(heapUsedBytesMaximum, heapUsed);
            heapCommittedBytesAfter = committed;
            uptimeMillisAfter = uptimeMillis;
            samples++;
            ready.countDown();
            if (!running.get()) break;
            nextSampleNanos += sampleIntervalNanos;
            long remaining = nextSampleNanos - System.nanoTime();
            if (remaining > 0) LockSupport.parkNanos(remaining);
        } while (true);
        return new ReconnectSaturation(
                samples, activeWorkersMaximum, queuedWorkMaximum,
                authenticationQueuePositiveSamples,
                authenticationQueueLongestStreak,
                postgresMetricsUnavailableSamples, postgresActiveConnectionsMaximum,
                postgresTotalConnectionsMaximum,
                postgresThreadsAwaitingConnectionMaximum, postgresMaximumConnections,
                postgresWaitingPositiveSamples, postgresWaitingLongestStreak,
                eventLoopMetricsUnavailableSamples, eventLoopWorkers,
                eventLoopProbeSamplesBefore, eventLoopProbeSamplesAfter,
                eventLoopLatestMaximumLagMicros,
                eventLoopSinceStartMaximumLagMicrosBefore,
                eventLoopSinceStartMaximumLagMicrosAfter,
                eventLoopPendingTasksMaximum, eventLoopPendingPositiveSamples,
                eventLoopPendingLongestStreak, processCpuTimeUnavailableSamples,
                processCpuTimeMicrosBefore, processCpuTimeMicrosAfter,
                heapUsedBytesBefore, heapUsedBytesAfter, heapUsedBytesMaximum,
                heapCommittedBytesBefore, heapCommittedBytesAfter,
                heapMaximumBytes, uptimeMillisBefore,
                uptimeMillisAfter, availableProcessors,
                gcMetricsUnavailableSamples, gcCollectionsBefore,
                gcCollectionsAfter, gcCollectionTimeMillisBefore,
                gcCollectionTimeMillisAfter, residentMemoryUnavailableSamples,
                residentMemoryBytesBefore, residentMemoryBytesAfter,
                residentMemoryBytesMaximum, residentMemorySampleAgeMillisMaximum,
                residentMemoryReadFailuresBefore, residentMemoryReadFailuresAfter,
                directBufferMetricsUnavailableSamples,
                directBufferCountBefore, directBufferCountAfter,
                directBufferCountMaximum,
                directBufferMemoryUsedBytesBefore,
                directBufferMemoryUsedBytesAfter,
                directBufferMemoryUsedBytesMaximum,
                directBufferTotalCapacityBytesBefore,
                directBufferTotalCapacityBytesAfter,
                directBufferTotalCapacityBytesMaximum);
    }

    private static int fixedGauge(String metrics, String name) {
        return Math.toIntExact(fixedLongGauge(metrics, name));
    }

    private static long fixedLongGauge(String metrics, String name) {
        var matcher = Pattern.compile(Pattern.quote(name) + " ([0-9]+)")
                .matcher(metrics);
        assertTrue(matcher.find(), "missing fixed gauge " + name);
        return Long.parseLong(matcher.group(1));
    }

    private static long fixedSecondsMicros(String metrics, String name) {
        return fixedSeconds(metrics, name, 6);
    }

    private static long fixedSecondsMillis(String metrics, String name) {
        return fixedSeconds(metrics, name, 3);
    }

    private static long fixedSeconds(String metrics, String name, int decimalPlaces) {
        var matcher = Pattern.compile(
                Pattern.quote(name) + " ([0-9]+(?:\\.[0-9]+)?)")
                .matcher(metrics);
        assertTrue(matcher.find(), "missing seconds gauge " + name);
        return new BigDecimal(matcher.group(1))
                .movePointRight(decimalPlaces)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static void awaitProductNotReady(int gatewayPort, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = HttpClient.newBuilder()
                        .sslContext(trustAllTls()).connectTimeout(Duration.ofMillis(500))
                        .build().send(HttpRequest.newBuilder(URI.create(
                                "https://localhost:" + gatewayPort + "/health/ready"))
                                .timeout(Duration.ofMillis(500)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 503) return;
            } catch (java.io.IOException ignored) {
                // Listener closure after readiness withdrawal is also no longer routable.
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("gateway product readiness did not withdraw");
    }

    private static int activeRouteCount(String redisUri, UUID conversationId) {
        try (var redis = new LettuceGatewayRoutingAdapter(new RedisRoutingConfig(
                redisUri, Duration.ofSeconds(1), 64, true))) {
            return redis.findConversationGateways(
                    conversationId, Instant.now(), 64).gatewayIds().size();
        }
    }

    private static void awaitActiveRouteCount(
            String redisUri, UUID conversationId, int expected, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int actual;
        do {
            actual = activeRouteCount(redisUri, conversationId);
            if (actual == expected) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, actual, "active Redis routes did not converge");
    }

    private static void awaitRoutingMetric(
            int adminPort, String name, long minimum, Duration timeout) throws Exception {
        Pattern pattern = Pattern.compile(
                "chat_gateway_routing_" + Pattern.quote(name) + "_total ([0-9]+)");
        long deadline = System.nanoTime() + timeout.toNanos();
        long observed = -1;
        do {
            String metrics = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + adminPort + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            var matcher = pattern.matcher(metrics);
            if (matcher.find()) observed = Long.parseLong(matcher.group(1));
            if (observed >= minimum) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertTrue(observed >= minimum,
                "routing metric " + name + " did not reach " + minimum
                        + "; observed=" + observed);
    }

    private static void awaitMetricAtLeast(
            int adminPort, String name, long minimum, Duration timeout) throws Exception {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(name) + " ([0-9]+)$", Pattern.MULTILINE);
        long deadline = System.nanoTime() + timeout.toNanos();
        long observed = -1;
        do {
            var matcher = pattern.matcher(adminMetrics(adminPort));
            if (matcher.find()) observed = Long.parseLong(matcher.group(1));
            if (observed >= minimum) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertTrue(observed >= minimum,
                "metric " + name + " did not reach " + minimum
                        + "; observed=" + observed);
    }

    private static String adminMetrics(int adminPort) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + adminPort + "/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static long routingMetric(String metrics, String name) {
        var matcher = Pattern.compile(
                "chat_gateway_routing_" + Pattern.quote(name) + "_total ([0-9]+)")
                .matcher(metrics);
        assertTrue(matcher.find(), "missing routing metric " + name);
        return Long.parseLong(matcher.group(1));
    }

    private static void seedV2NetworkAccounts(
            String url, String user, String password, UUID accountId, UUID peerAccountId,
            UUID conversationId, String login, String peerLogin) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, ?, 'Network Test', ?)",
                    accountId, login, HASH);
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, ?, 'Network Peer', ?)",
                    peerAccountId, peerLogin, HASH);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')",
                    conversationId);
            UUID first = accountId.toString().compareTo(peerAccountId.toString()) <= 0
                    ? accountId : peerAccountId;
            UUID second = first.equals(accountId) ? peerAccountId : accountId;
            execute(connection,
                    "INSERT INTO chat.direct_conversation("
                            + "conversation_id, first_account_id, second_account_id) "
                            + "VALUES (?, ?, ?)",
                    conversationId, first, second);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?)",
                    conversationId, accountId);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?)",
                    conversationId, peerAccountId);
            connection.commit();
        }
    }

    private static void seedCrashAccounts(String url, String user, int count)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, "")) {
            connection.setAutoCommit(false);
            for (int index = 0; index < count; index++) {
                execute(connection,
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                                + "VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), "crash-load-user-" + index,
                        "Crash load user " + index, HASH);
            }
            connection.commit();
        }
    }

    private static WebSocket connectWebSocket(
            int gatewayPort, BinaryEnvelopeListener listener) throws Exception {
        return connectWebSocket(URI.create(
                "wss://localhost:" + gatewayPort + "/v2/windows"), listener);
    }

    private static WebSocket connectWebSocketWeb(
            int gatewayPort, BinaryEnvelopeListener listener) throws Exception {
        return HttpClient.newBuilder()
                .sslContext(trustAllTls())
                .connectTimeout(Duration.ofSeconds(2))
                .build()
                .newWebSocketBuilder()
                .header("Origin", "https://chat.example.com")
                .subprotocols("chat.v2")
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(URI.create(
                        "wss://localhost:" + gatewayPort + "/v2/web"), listener)
                .get(3, TimeUnit.SECONDS);
    }

    private static WebSocket connectWebSocket(
            URI endpoint, BinaryEnvelopeListener listener) throws Exception {
        return HttpClient.newBuilder()
                .sslContext(trustAllTls())
                .connectTimeout(Duration.ofSeconds(2))
                .build()
                .newWebSocketBuilder()
                .subprotocols("chat.v2")
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(endpoint, listener)
                .get(3, TimeUnit.SECONDS);
    }

    private static SessionEstablished establish(
            WebSocket socket, BinaryEnvelopeListener listener, String login, String deviceId)
            throws Exception {
        socket.sendBinary(ByteBuffer.wrap(clientHello(deviceId).toByteArray()), true).join();
        assertEquals(MessageType.MESSAGE_TYPE_SERVER_HELLO_VALUE,
                listener.next().getMessageType());
        socket.sendBinary(ByteBuffer.wrap(authenticate(login).toByteArray()), true).join();
        Envelope session = listener.next();
        assertEquals(MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED_VALUE,
                session.getMessageType());
        return SessionEstablished.parseFrom(session.getPayload());
    }

    private static SessionEstablished resume(
            WebSocket socket, BinaryEnvelopeListener listener,
            SessionEstablished previous, String deviceId) throws Exception {
        socket.sendBinary(ByteBuffer.wrap(clientHello(deviceId).toByteArray()), true).join();
        assertEquals(MessageType.MESSAGE_TYPE_SERVER_HELLO_VALUE,
                listener.next().getMessageType());
        ResumeSession payload = ResumeSession.newBuilder()
                .setSessionId(previous.getSessionId())
                .setResumeToken(previous.getResumeToken())
                .build();
        socket.sendBinary(ByteBuffer.wrap(command(
                MessageType.MESSAGE_TYPE_RESUME_SESSION, "crash-resume-" + deviceId,
                "", "", payload.toByteString()).toByteArray()), true).join();
        Envelope response = listener.next();
        assertEquals(MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED_VALUE,
                response.getMessageType());
        SessionEstablished rotated = SessionEstablished.parseFrom(response.getPayload());
        assertEquals(previous.getSessionId(), rotated.getSessionId());
        assertEquals(previous.getAccountId(), rotated.getAccountId());
        assertEquals(previous.getDeviceId(), rotated.getDeviceId());
        assertFalse(previous.getResumeToken().equals(rotated.getResumeToken()));
        return rotated;
    }

    private static boolean allNonBlank(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) return false;
        }
        return true;
    }

    private static void writeCrashReconnectEvidence(
            Path output, int connections, int affected, int batchSize,
            int intervalMillis, List<ReconnectSample> samples, long elapsedNanos)
            throws Exception {
        List<Long> latency = samples.stream().map(ReconnectSample::latencyMicros)
                .sorted().toList();
        List<Long> jitter = samples.stream().map(ReconnectSample::jitterMicros)
                .sorted().toList();
        int batches = (affected + batchSize - 1) / batchSize;
        String json = """
                {
                  "schemaVersion": 1,
                  "benchmark": "java-v2-haproxy-crash-reconnect",
                  "warning": "local failure-recovery evidence; not a production capacity claim",
                  "recordedAt": "%s",
                  "environment": {
                    "javaVersion": "%s",
                    "os": "%s",
                    "architecture": "%s",
                    "availableProcessors": %d,
                    "maximumHeapBytes": %d
                  },
                  "scenario": {
                    "connections": %d,
                    "failedGatewayConnections": %d,
                    "survivingGatewayConnections": %d,
                    "reconnectBatchSize": %d,
                    "reconnectBatchIntervalMillis": %d,
                    "reconnectBatches": %d,
                    "scheduledReconnectSpanMillis": %d
                  },
                  "results": {
                    "reconnectAttempts": %d,
                    "reconnectSuccesses": %d,
                    "reconnectErrors": 0,
                    "elapsedMillis": %.3f,
                    "reconnectThroughputPerSecond": %.3f,
                    "sessionResumeLatencyMicros": %s,
                    "scheduledStartJitterMicros": %s
                  }
                }
                """.formatted(
                        Instant.now(), System.getProperty("java.version"),
                        System.getProperty("os.name"), System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(),
                        Runtime.getRuntime().maxMemory(),
                        connections, affected, connections - affected,
                        batchSize, intervalMillis, batches,
                        (batches - 1) * intervalMillis, affected, samples.size(),
                        elapsedNanos / 1_000_000.0,
                        affected * 1_000_000_000.0 / elapsedNanos,
                        distributionJson(latency), distributionJson(jitter));
        Files.writeString(output, json);
    }

    private static void writeMultiEdgeReconnectEvidence(
            Path output, String workloadName, int affected, int surviving,
            int batchSize, int intervalMillis, List<ReconnectSample> samples,
            ReconnectSaturation saturation, long elapsedNanos)
            throws Exception {
        List<Long> latency = samples.stream().map(ReconnectSample::latencyMicros)
                .sorted().toList();
        List<Long> jitter = samples.stream().map(ReconnectSample::jitterMicros)
                .sorted().toList();
        int batches = (affected + batchSize - 1) / batchSize;
        String json = """
                {
                  "schemaVersion": 10,
                  "benchmark": "java-v2-haproxy-multi-edge-reconnect",
                  "warning": "local dual-edge recovery evidence; not a production capacity claim",
                  "recordedAt": "%s",
                  "environment": {
                    "javaVersion": "%s",
                    "os": "%s",
                    "architecture": "%s",
                    "availableProcessors": %d,
                    "maximumHeapBytes": %d
                  },
                  "scenario": {
                    "workloadProfile": "%s",
                    "edgeProcesses": 2,
                    "gatewayProcesses": 2,
                    "primaryEdgeKilled": true,
                    "connections": %d,
                    "failedEdgeConnections": %d,
                    "survivingEdgeConnections": %d,
                    "reconnectBatchSize": %d,
                    "reconnectBatchIntervalMillis": %d,
                    "reconnectBatches": %d,
                    "scheduledReconnectSpanMillis": %d
                  },
                  "results": {
                    "reconnectAttempts": %d,
                    "reconnectSuccesses": %d,
                    "reconnectErrors": 0,
                    "secondaryGatewayAuthenticationBefore": %d,
                    "secondaryGatewayAuthenticationAfter": %d,
                    "authenticationSaturation": {
                      "sampleIntervalMillis": 5,
                      "samples": %d,
                      "activeWorkersMaximum": %d,
                      "queuedWorkMaximum": %d
                    },
                    "postgresPoolSaturation": {
                      "sampleIntervalMillis": 5,
                      "samples": %d,
                      "metricsUnavailableSamples": %d,
                      "activeConnectionsMaximum": %d,
                      "totalConnectionsMaximum": %d,
                      "threadsAwaitingConnectionMaximum": %d,
                      "configuredMaximumConnections": %d
                    },
                    "eventLoopSaturation": {
                      "sampleIntervalMillis": 5,
                      "samples": %d,
                      "metricsUnavailableSamples": %d,
                      "workers": %d,
                      "probeSamplesBefore": %d,
                      "probeSamplesAfter": %d,
                      "probeSamplesDelta": %d,
                      "latestMaximumLagMicros": %d,
                      "sinceStartMaximumLagMicrosBefore": %d,
                      "sinceStartMaximumLagMicrosAfter": %d,
                      "pendingTasksMaximum": %d
                    },
                    "processResourceSaturation": {
                      "sampleIntervalMillis": 5,
                      "samples": %d,
                      "cpuTimeUnavailableSamples": %d,
                      "cpuTimeMicrosBefore": %d,
                      "cpuTimeMicrosAfter": %d,
                      "cpuTimeMicrosDelta": %d,
                      "heapUsedBytesBefore": %d,
                      "heapUsedBytesAfter": %d,
                      "heapUsedBytesMaximum": %d,
                      "heapCommittedBytesBefore": %d,
                      "heapCommittedBytesAfter": %d,
                      "heapMaximumBytes": %d,
                      "uptimeMillisBefore": %d,
                      "uptimeMillisAfter": %d,
                      "uptimeMillisDelta": %d,
                      "availableProcessors": %d
                    },
                    "pressureDuration": {
                      "sampleIntervalMillis": 5,
                      "samples": %d,
                      "authenticationQueuePositiveSamples": %d,
                      "authenticationQueueLongestConsecutiveSamples": %d,
                      "postgresWaitingPositiveSamples": %d,
                      "postgresWaitingLongestConsecutiveSamples": %d,
                      "eventLoopPendingPositiveSamples": %d,
                      "eventLoopPendingLongestConsecutiveSamples": %d
                    },
                    "gcCollectionActivity": {
                      "sampleIntervalMillis": 5,
                      "samples": %d,
                      "metricsUnavailableSamples": %d,
                      "collectionsBefore": %d,
                      "collectionsAfter": %d,
                      "collectionsDelta": %d,
                      "collectionTimeMillisBefore": %d,
                      "collectionTimeMillisAfter": %d,
                      "collectionTimeMillisDelta": %d
                    },
                    "residentMemoryActivity": {
                      "sampleIntervalMillis": 5,
                      "configuredRefreshIntervalMillis": 250,
                      "samples": %d,
                      "metricsUnavailableSamples": %d,
                      "residentBytesBefore": %d,
                      "residentBytesAfter": %d,
                      "residentBytesMaximum": %d,
                      "sampleAgeMillisMaximum": %d,
                      "readFailuresBefore": %d,
                      "readFailuresAfter": %d,
                      "readFailuresDelta": %d
                    },
                    "directBufferActivity": {
                      "sampleIntervalMillis": 5,
                      "samples": %d,
                      "metricsUnavailableSamples": %d,
                      "bufferCountBefore": %d,
                      "bufferCountAfter": %d,
                      "bufferCountMaximum": %d,
                      "memoryUsedBytesBefore": %d,
                      "memoryUsedBytesAfter": %d,
                      "memoryUsedBytesMaximum": %d,
                      "totalCapacityBytesBefore": %d,
                      "totalCapacityBytesAfter": %d,
                      "totalCapacityBytesMaximum": %d
                    },
                    "elapsedMillis": %.3f,
                    "reconnectThroughputPerSecond": %.3f,
                    "sessionResumeLatencyMicros": %s,
                    "scheduledStartJitterMicros": %s
                  }
                }
                """.formatted(
                        Instant.now(), System.getProperty("java.version"),
                        System.getProperty("os.name"), System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(),
                        Runtime.getRuntime().maxMemory(), workloadName,
                        affected + surviving, affected,
                        surviving, batchSize, intervalMillis, batches,
                        (batches - 1) * intervalMillis, affected, samples.size(),
                        surviving, surviving + affected, saturation.samples(),
                        saturation.activeWorkersMaximum(), saturation.queuedWorkMaximum(),
                        saturation.samples(), saturation.postgresMetricsUnavailableSamples(),
                        saturation.postgresActiveConnectionsMaximum(),
                        saturation.postgresTotalConnectionsMaximum(),
                        saturation.postgresThreadsAwaitingConnectionMaximum(),
                        saturation.postgresMaximumConnections(),
                        saturation.samples(),
                        saturation.eventLoopMetricsUnavailableSamples(),
                        saturation.eventLoopWorkers(),
                        saturation.eventLoopProbeSamplesBefore(),
                        saturation.eventLoopProbeSamplesAfter(),
                        saturation.eventLoopProbeSamplesAfter()
                                - saturation.eventLoopProbeSamplesBefore(),
                        saturation.eventLoopLatestMaximumLagMicros(),
                        saturation.eventLoopSinceStartMaximumLagMicrosBefore(),
                        saturation.eventLoopSinceStartMaximumLagMicrosAfter(),
                        saturation.eventLoopPendingTasksMaximum(),
                        saturation.samples(),
                        saturation.processCpuTimeUnavailableSamples(),
                        saturation.processCpuTimeMicrosBefore(),
                        saturation.processCpuTimeMicrosAfter(),
                        saturation.processCpuTimeMicrosAfter()
                                - saturation.processCpuTimeMicrosBefore(),
                        saturation.heapUsedBytesBefore(),
                        saturation.heapUsedBytesAfter(),
                        saturation.heapUsedBytesMaximum(),
                        saturation.heapCommittedBytesBefore(),
                        saturation.heapCommittedBytesAfter(),
                        saturation.heapMaximumBytes(),
                        saturation.uptimeMillisBefore(),
                        saturation.uptimeMillisAfter(),
                        saturation.uptimeMillisAfter() - saturation.uptimeMillisBefore(),
                        saturation.availableProcessors(),
                        saturation.samples(),
                        saturation.authenticationQueuePositiveSamples(),
                        saturation.authenticationQueueLongestStreak(),
                        saturation.postgresWaitingPositiveSamples(),
                        saturation.postgresWaitingLongestStreak(),
                        saturation.eventLoopPendingPositiveSamples(),
                        saturation.eventLoopPendingLongestStreak(),
                        saturation.samples(),
                        saturation.gcMetricsUnavailableSamples(),
                        saturation.gcCollectionsBefore(),
                        saturation.gcCollectionsAfter(),
                        saturation.gcCollectionsAfter()
                                - saturation.gcCollectionsBefore(),
                        saturation.gcCollectionTimeMillisBefore(),
                        saturation.gcCollectionTimeMillisAfter(),
                        saturation.gcCollectionTimeMillisAfter()
                                - saturation.gcCollectionTimeMillisBefore(),
                        saturation.samples(),
                        saturation.residentMemoryUnavailableSamples(),
                        saturation.residentMemoryBytesBefore(),
                        saturation.residentMemoryBytesAfter(),
                        saturation.residentMemoryBytesMaximum(),
                        saturation.residentMemorySampleAgeMillisMaximum(),
                        saturation.residentMemoryReadFailuresBefore(),
                        saturation.residentMemoryReadFailuresAfter(),
                        saturation.residentMemoryReadFailuresAfter()
                                - saturation.residentMemoryReadFailuresBefore(),
                        saturation.samples(),
                        saturation.directBufferMetricsUnavailableSamples(),
                        saturation.directBufferCountBefore(),
                        saturation.directBufferCountAfter(),
                        saturation.directBufferCountMaximum(),
                        saturation.directBufferMemoryUsedBytesBefore(),
                        saturation.directBufferMemoryUsedBytesAfter(),
                        saturation.directBufferMemoryUsedBytesMaximum(),
                        saturation.directBufferTotalCapacityBytesBefore(),
                        saturation.directBufferTotalCapacityBytesAfter(),
                        saturation.directBufferTotalCapacityBytesMaximum(),
                        elapsedNanos / 1_000_000.0,
                        affected * 1_000_000_000.0 / elapsedNanos,
                        distributionJson(latency), distributionJson(jitter));
        Files.writeString(output, json);
    }

    private static MultiEdgeReconnectWorkload multiEdgeReconnectWorkload(String value) {
        String name = value == null || value.isBlank() ? "step-12" : value;
        return switch (name) {
            case "step-12" -> new MultiEdgeReconnectWorkload(name, 12, 6, 3, 100);
            case "step-24" -> new MultiEdgeReconnectWorkload(name, 24, 6, 6, 100);
            case "step-48" -> new MultiEdgeReconnectWorkload(name, 48, 6, 12, 100);
            default -> throw new IllegalArgumentException(
                    "unknown multi-edge reconnect workload: " + name);
        };
    }

    private static String distributionJson(List<Long> sorted) {
        long sum = sorted.stream().mapToLong(Long::longValue).sum();
        return "{\"samples\":%d,\"min\":%d,\"p50\":%d,\"p95\":%d,"
                .concat("\"p99\":%d,\"max\":%d,\"mean\":%.3f}")
                .formatted(sorted.size(), sorted.getFirst(), percentile(sorted, 0.50),
                        percentile(sorted, 0.95), percentile(sorted, 0.99),
                        sorted.getLast(), (double) sum / sorted.size());
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private record CrashClient(
            WebSocket socket, SessionEstablished session, String deviceId, boolean onFirst) {
    }

    private record ReconnectSample(
            int position, WebSocket socket, SessionEstablished session,
            long latencyMicros, long jitterMicros) {
    }

    private record MultiEdgeReconnectWorkload(
            String name, int failedConnections, int survivingConnections,
            int batchSize, int intervalMillis) {
    }

    private record ReconnectSaturation(
            int samples,
            int activeWorkersMaximum,
            int queuedWorkMaximum,
            int authenticationQueuePositiveSamples,
            int authenticationQueueLongestStreak,
            int postgresMetricsUnavailableSamples,
            int postgresActiveConnectionsMaximum,
            int postgresTotalConnectionsMaximum,
            int postgresThreadsAwaitingConnectionMaximum,
            int postgresMaximumConnections,
            int postgresWaitingPositiveSamples,
            int postgresWaitingLongestStreak,
            int eventLoopMetricsUnavailableSamples,
            int eventLoopWorkers,
            long eventLoopProbeSamplesBefore,
            long eventLoopProbeSamplesAfter,
            long eventLoopLatestMaximumLagMicros,
            long eventLoopSinceStartMaximumLagMicrosBefore,
            long eventLoopSinceStartMaximumLagMicrosAfter,
            long eventLoopPendingTasksMaximum,
            int eventLoopPendingPositiveSamples,
            int eventLoopPendingLongestStreak,
            int processCpuTimeUnavailableSamples,
            long processCpuTimeMicrosBefore,
            long processCpuTimeMicrosAfter,
            long heapUsedBytesBefore,
            long heapUsedBytesAfter,
            long heapUsedBytesMaximum,
            long heapCommittedBytesBefore,
            long heapCommittedBytesAfter,
            long heapMaximumBytes,
            long uptimeMillisBefore,
            long uptimeMillisAfter,
            int availableProcessors,
            int gcMetricsUnavailableSamples,
            long gcCollectionsBefore,
            long gcCollectionsAfter,
            long gcCollectionTimeMillisBefore,
            long gcCollectionTimeMillisAfter,
            int residentMemoryUnavailableSamples,
            long residentMemoryBytesBefore,
            long residentMemoryBytesAfter,
            long residentMemoryBytesMaximum,
            long residentMemorySampleAgeMillisMaximum,
            long residentMemoryReadFailuresBefore,
            long residentMemoryReadFailuresAfter,
            int directBufferMetricsUnavailableSamples,
            long directBufferCountBefore,
            long directBufferCountAfter,
            long directBufferCountMaximum,
            long directBufferMemoryUsedBytesBefore,
            long directBufferMemoryUsedBytesAfter,
            long directBufferMemoryUsedBytesMaximum,
            long directBufferTotalCapacityBytesBefore,
            long directBufferTotalCapacityBytesAfter,
            long directBufferTotalCapacityBytesMaximum) {
    }

    private static Envelope clientHello(String deviceId) {
        ClientHello payload = ClientHello.newBuilder()
                .setMinimumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setMaximumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_WINDOWS)
                .setAppVersion("integration-test")
                .setClientDeviceId(deviceId)
                .build();
        return command(MessageType.MESSAGE_TYPE_CLIENT_HELLO, "hello-1", "", "",
                payload.toByteString());
    }

    private static Envelope clientHelloWithSearch(String deviceId) {
        ClientHello payload = ClientHello.newBuilder()
                .setMinimumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setMaximumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_WINDOWS)
                .setAppVersion("integration-test")
                .setClientDeviceId(deviceId)
                .addCapabilities(ClientCapability.CLIENT_CAPABILITY_MESSAGE_SEARCH)
                .build();
        return command(MessageType.MESSAGE_TYPE_CLIENT_HELLO, "hello-search-1", "", "",
                payload.toByteString());
    }

    private static Envelope clientHelloWithBlocking(String deviceId) {
        ClientHello payload = ClientHello.newBuilder()
                .setMinimumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setMaximumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_WINDOWS)
                .setAppVersion("integration-test")
                .setClientDeviceId(deviceId)
                .addCapabilities(ClientCapability.CLIENT_CAPABILITY_ACCOUNT_BLOCKING)
                .build();
        return command(MessageType.MESSAGE_TYPE_CLIENT_HELLO, "hello-block-1", "", "",
                payload.toByteString());
    }

    private static Envelope clientHelloWithWebPush(String deviceId) {
        ClientHello payload = ClientHello.newBuilder()
                .setMinimumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setMaximumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_WEB)
                .setAppVersion("integration-test")
                .setClientDeviceId(deviceId)
                .addCapabilities(
                        ClientCapability.CLIENT_CAPABILITY_WEB_PUSH_HTTP_CREDENTIAL)
                .build();
        return command(MessageType.MESSAGE_TYPE_CLIENT_HELLO, "hello-web-push-1", "", "",
                payload.toByteString());
    }

    private static Envelope issueWebPushHttpCredential(String sessionId) {
        return command(MessageType.MESSAGE_TYPE_ISSUE_WEB_PUSH_HTTP_CREDENTIAL,
                "issue-web-push-http-credential-1", sessionId, "", ByteString.EMPTY);
    }

    private static Path writeProtectedKey(Path path, int fill) throws Exception {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) fill);
        try {
            Files.write(path, value);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            return path;
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static Path writeProtectedBytes(Path path, byte[] value) throws Exception {
        Files.write(path, value);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        return path;
    }

    private static byte[] webPushSubscriptionJson() {
        byte[] p256dh = new byte[65];
        p256dh[0] = 0x04;
        byte[] auth = new byte[16];
        Arrays.fill(auth, (byte) 7);
        String json = "{\"endpoint\":\"https://push.example/sub/opaque\","
                + "\"expirationTime\":null,\"keys\":{\"p256dh\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(p256dh)
                + "\",\"auth\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(auth) + "\"}}";
        Arrays.fill(p256dh, (byte) 0);
        Arrays.fill(auth, (byte) 0);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Envelope authenticate(String login) {
        Authenticate payload = Authenticate.newBuilder()
                .setUsername(login)
                .setPasswordUtf8(ByteString.copyFromUtf8("java-v2-test-password"))
                .build();
        return command(MessageType.MESSAGE_TYPE_AUTHENTICATE, "auth-1", "", "",
                payload.toByteString());
    }

    private static Envelope submit(String sessionId, UUID conversationId) {
        return submit(sessionId, conversationId, "submit-1", "network-message-1",
                "network integration message");
    }

    private static Envelope submit(String sessionId, UUID conversationId,
            String requestId, String clientMessageId, String content) {
        SubmitMessage payload = SubmitMessage.newBuilder()
                .setConversationId(conversationId.toString())
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8(content))
                .build();
        return command(MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE, requestId, sessionId,
                clientMessageId, payload.toByteString());
    }

    private static Envelope search(String sessionId, UUID conversationId, String query) {
        SearchConversationMessages payload = SearchConversationMessages.newBuilder()
                .setConversationId(conversationId.toString())
                .setLiteralQuery(query)
                .setLimit(20)
                .build();
        return command(MessageType.MESSAGE_TYPE_SEARCH_CONVERSATION_MESSAGES,
                "search-1", sessionId, "", payload.toByteString());
    }

    private static Envelope block(String sessionId, UUID target, UUID operation,
            boolean blocked, String requestId) {
        SetAccountBlock payload = SetAccountBlock.newBuilder()
                .setTargetAccountId(target.toString()).setBlocked(blocked)
                .setClientOperationId(operation.toString()).build();
        return command(MessageType.MESSAGE_TYPE_SET_ACCOUNT_BLOCK, requestId,
                sessionId, "", payload.toByteString());
    }

    private static Envelope listAccountBlocks(String sessionId, String afterTarget, int limit) {
        ListAccountBlocks payload = ListAccountBlocks.newBuilder()
                .setAfterTargetAccountId(afterTarget).setLimit(limit).build();
        return command(MessageType.MESSAGE_TYPE_LIST_ACCOUNT_BLOCKS, "block-directory-1",
                sessionId, "", payload.toByteString());
    }

    private static MessageAccepted accepted(Envelope envelope) throws Exception {
        assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                envelope.getMessageType());
        return MessageAccepted.parseFrom(envelope.getPayload());
    }

    private static MessageRecord published(Envelope envelope) throws Exception {
        assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE,
                envelope.getMessageType());
        return MessageRecord.parseFrom(envelope.getPayload());
    }

    private static Envelope history(String sessionId, UUID conversationId) {
        ReadMessageHistory payload = ReadMessageHistory.newBuilder()
                .setConversationId(conversationId.toString())
                .setAfterSequence(0)
                .setLimit(100)
                .build();
        return command(MessageType.MESSAGE_TYPE_READ_MESSAGE_HISTORY, "history-1",
                sessionId, "", payload.toByteString());
    }

    private static Envelope command(
            MessageType type, String requestId, String sessionId,
            String clientMessageId, ByteString payload) {
        return Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(type.getNumber())
                .setRequestId(requestId)
                .setSessionId(sessionId)
                .setClientMessageId(clientMessageId)
                .setSentAtEpochMs(System.currentTimeMillis())
                .setPayload(payload)
                .build();
    }

    private static SSLContext trustAllTls() throws Exception {
        TrustManager[] trustManagers = {new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
            @Override public void checkClientTrusted(
                    X509Certificate[] chain, String authenticationType) {
            }
            @Override public void checkServerTrusted(
                    X509Certificate[] chain, String authenticationType) {
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context;
    }

    private static final class BinaryEnvelopeListener implements WebSocket.Listener {
        private final BlockingQueue<Envelope> envelopes = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();

        @Override public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override public synchronized CompletionStage<?> onBinary(
                WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            fragments.write(bytes, 0, bytes.length);
            if (last) {
                try {
                    envelopes.add(Envelope.parseFrom(fragments.toByteArray()));
                } catch (Exception exception) {
                    failure.compareAndSet(null, exception);
                } finally {
                    fragments.reset();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            failure.compareAndSet(null, error);
            terminal.countDown();
        }

        @Override public CompletionStage<?> onClose(
                WebSocket webSocket, int statusCode, String reason) {
            terminal.countDown();
            return null;
        }

        private Envelope next() throws Exception {
            return next(Duration.ofSeconds(5));
        }

        private Envelope next(Duration timeout) throws Exception {
            Envelope envelope = envelopes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Throwable error = failure.get();
            if (error != null) throw new AssertionError("WebSocket listener failed", error);
            assertNotNull(envelope, "timed out waiting for a V2 envelope");
            return assertInstanceOf(Envelope.class, envelope);
        }

        private void assertNoEnvelope(Duration timeout) throws Exception {
            Envelope envelope = envelopes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Throwable error = failure.get();
            if (error != null) throw new AssertionError("WebSocket listener failed", error);
            assertNull(envelope, "duplicate V2 envelope received");
        }

        private void awaitTerminal(Duration timeout) throws Exception {
            assertTrue(terminal.await(timeout.toMillis(), TimeUnit.MILLISECONDS),
                    "WebSocket did not terminate after forced drain");
        }
    }

    private static void configureDistributedRouting(Map<String, String> environment) {
        String redis = System.getenv("CHATROOM_TEST_REDIS_URI");
        if (redis == null || redis.isBlank()) return;
        environment.put(DistributedGatewayRoutingConfig.ENABLED, "true");
        environment.put(DistributedGatewayRoutingConfig.REDIS_URI, redis);
        environment.put(DistributedGatewayRoutingConfig.ALLOW_INSECURE_LOOPBACK, "true");
    }

    private static void awaitReady(GatewayRuntime runtime) throws Exception {
        awaitReady(runtime, Duration.ofSeconds(5));
    }

    private static void awaitReady(GatewayRuntime runtime, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!runtime.isReady() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(runtime.isReady(), "gateway did not become ready");
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
