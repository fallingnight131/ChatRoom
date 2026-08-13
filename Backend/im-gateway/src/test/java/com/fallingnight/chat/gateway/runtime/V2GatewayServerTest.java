package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.identity.DeviceManagementService;
import com.fallingnight.chat.gateway.transport.DeviceManagementEventSink;
import com.fallingnight.chat.gateway.transport.DeviceConnectionRegistry;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.AuthenticationEventSink;
import com.fallingnight.chat.gateway.transport.MessagingEventSink;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation") // Netty's test-certificate helper is intentionally test-only.
class V2GatewayServerTest {
    @TempDir
    Path temporary;

    @Test
    void bindsTlsAcceptsHandshakeAndClosesDeterministically() throws Exception {
        int port = availablePort();
        GatewayRuntimeConfig config = config(port);
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        SslContext serverTls = SslContextBuilder.forServer(
                        certificate.certificate(), certificate.privateKey())
                .build();
        V2GatewayServer server = server(config, serverTls);
        try {
            assertFalse(server.isRunning());
            server.start();
            assertTrue(server.isRunning());
            assertThrows(IllegalStateException.class, server::start);

            try (SSLSocket socket = (SSLSocket) trustAllTls()
                    .getSocketFactory()
                    .createSocket("127.0.0.1", server.address().getPort())) {
                socket.setSoTimeout(2_000);
                socket.startHandshake();
                assertTrue(socket.getSession().isValid());
                assertEquals(1, server.activeConnections());
                assertTrue(upgrade(socket, port, true).startsWith("HTTP/1.1 101"));
            }

            try (SSLSocket socket = (SSLSocket) trustAllTls()
                    .getSocketFactory()
                    .createSocket("127.0.0.1", server.address().getPort())) {
                socket.setSoTimeout(2_000);
                socket.startHandshake();
                assertTrue(upgrade(socket, port, false).startsWith("HTTP/1.1 400"));
            }
        } finally {
            server.close();
            server.close();
            certificate.delete();
        }
        assertFalse(server.isRunning());
        server.awaitClose();
    }

    @Test
    void publicConstructionRejectsPlaceholderTlsBeforeBind() throws Exception {
        GatewayRuntimeConfig config = config(availablePort());
        assertThrows(IllegalArgumentException.class, () -> new V2GatewayServer(
                config,
                command -> AuthenticationResult.Rejected.INSTANCE,
                command -> AuthenticationResult.Rejected.INSTANCE,
                command -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                query -> new com.fallingnight.chat.application.conversation
                        .ConversationDirectoryPage(
                                java.util.List.of(), java.util.Optional.empty(), false),
                command -> com.fallingnight.chat.application.messaging
                        .MessageReactionResult.Rejected.NOT_AUTHORIZED,
                rejectingDevices(),
                Runnable::run,
                Runnable::run,
                AuthenticationAdmissionControl.allowAll(),
                AuthenticationEventSink.noop(),
                MessagingEventSink.noop(),
                DeviceManagementEventSink.noop(),
                new DeviceConnectionRegistry()));
    }

    private V2GatewayServer server(GatewayRuntimeConfig config, SslContext tls) {
        return new V2GatewayServer(
                config,
                command -> AuthenticationResult.Rejected.INSTANCE,
                command -> AuthenticationResult.Rejected.INSTANCE,
                command -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                query -> new com.fallingnight.chat.application.conversation
                        .ConversationDirectoryPage(
                                java.util.List.of(), java.util.Optional.empty(), false),
                command -> com.fallingnight.chat.application.messaging
                        .MessageReactionResult.Rejected.NOT_AUTHORIZED,
                rejectingDevices(),
                Runnable::run,
                Runnable::run,
                AuthenticationAdmissionControl.allowAll(),
                AuthenticationEventSink.noop(),
                MessagingEventSink.noop(),
                DeviceManagementEventSink.noop(),
                new DeviceConnectionRegistry(),
                tls);
    }

    private static DeviceManagementService rejectingDevices() {
        return new DeviceManagementService(new com.fallingnight.chat.application.identity
                .DeviceManagementPort() {
            @Override public com.fallingnight.chat.application.identity.DeviceDirectoryResult
                    listActive(com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor) {
                return com.fallingnight.chat.application.identity.DeviceDirectoryResult.Rejected.INSTANCE;
            }
            @Override public com.fallingnight.chat.application.identity.DeviceRevocationResult
                    revokeOther(com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor,
                            java.util.UUID target) {
                return com.fallingnight.chat.application.identity.DeviceRevocationResult.Rejected.INSTANCE;
            }
        });
    }

    private GatewayRuntimeConfig config(int port) throws Exception {
        Path certificate = temporary.resolve("placeholder-certificate.pem");
        Path key = temporary.resolve("placeholder-private-key.pem");
        Files.writeString(certificate, "test certificate");
        Files.writeString(key, "test private key");
        Map<String, String> environment = new HashMap<>();
        environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(port));
        environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE", certificate.toString());
        environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY", key.toString());
        environment.put(
                "CHATROOM_POSTGRES_URL",
                "jdbc:postgresql://db.internal/chat?sslmode=verify-full");
        environment.put("CHATROOM_POSTGRES_USER", "chat_gateway");
        environment.put("CHATROOM_POSTGRES_PASSWORD", "required-test-password");
        environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "127.0.0.1:" + port);
        environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
        return GatewayRuntimeConfig.fromEnvironment(environment);
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static SSLContext trustAllTls() throws Exception {
        TrustManager[] trustManagers = {new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authenticationType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authenticationType) {
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context;
    }

    private static String upgrade(SSLSocket socket, int port, boolean includeSubprotocol)
            throws Exception {
        StringBuilder request = new StringBuilder()
                .append("GET /v2/windows HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: AAECAwQFBgcICQoLDA0ODw==\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
                .append("Content-Length: 0\r\n");
        if (includeSubprotocol) {
            request.append("Sec-WebSocket-Protocol: chat.v2\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
        String status = reader.readLine();
        String line;
        do {
            line = reader.readLine();
        } while (line != null && !line.isEmpty());
        return status == null ? "" : status;
    }
}
