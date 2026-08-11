package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation") // Netty's certificate generator is confined to this test.
class GatewayRuntimePostgresIntegrationTest {
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

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
