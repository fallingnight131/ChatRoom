package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.gateway.transport.PeerResolutionDecision;
import com.fallingnight.chat.gateway.transport.WebSocketEndpointPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayRuntimeConfigTest {
    @TempDir
    Path temporary;

    @Test
    void buildsStrictPoliciesAndDocumentedSafeDefaultsWithoutExposingSecrets() throws Exception {
        Map<String, String> environment = requiredEnvironment();
        environment.put("CHATROOM_GATEWAY_TRUSTED_PROXY_CIDRS", "10.0.0.0/8,2001:db8::/32");
        environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY_PASSWORD", "tls-secret");
        environment.put("CHATROOM_POSTGRES_PASSWORD", "database-secret");

        GatewayRuntimeConfig config = GatewayRuntimeConfig.fromEnvironment(environment);

        assertEquals(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9443),
                config.listenerAddress());
        assertTrue(config.adminAddress().getAddress().isLoopbackAddress());
        assertEquals(4, config.eventLoopWorkers());
        assertEquals(10_000, config.maximumConnections());
        assertEquals(65_536, config.writeBufferLowWaterMark());
        assertEquals(262_144, config.writeBufferHighWaterMark());
        assertEquals(4, config.authenticationWorkers());
        assertEquals(256, config.authenticationQueueCapacity());
        assertEquals(Duration.ofSeconds(10), config.handshakeTimeout());
        assertEquals(Duration.ofSeconds(30), config.authenticationTimeout());
        assertEquals(Duration.ofSeconds(120), config.authenticatedIdleTimeout());
        assertTrue(config.hostPolicy().allows(List.of("gateway.example.com:443")));
        assertEquals(ClientPlatform.WEB, config.endpointPolicy().expectedPlatform(
                WebSocketEndpointPolicy.WEB_PATH,
                List.of("https://chat.example.com")));
        assertEquals(PeerResolutionDecision.TRUSTED_FORWARDING,
                config.proxyPolicy().resolve(
                        new InetSocketAddress(InetAddress.getByName("10.0.0.2"), 443),
                        List.of("198.51.100.8"))
                        .decision());
        assertFalse(config.toString().contains("tls-secret"));
        assertFalse(config.toString().contains("database-secret"));
    }

    @Test
    void rejectsMissingSecretsDnsBindingAndUnsafeAdminOrNumericConfiguration() throws Exception {
        Map<String, String> missing = requiredEnvironment();
        missing.remove("CHATROOM_POSTGRES_URL");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(missing));

        Map<String, String> dns = requiredEnvironment();
        dns.put("CHATROOM_GATEWAY_BIND_ADDRESS", "gateway.example.com");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(dns));

        Map<String, String> admin = requiredEnvironment();
        admin.put("CHATROOM_GATEWAY_ADMIN_ADDRESS", "0.0.0.0");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(admin));

        Map<String, String> workers = requiredEnvironment();
        workers.put("CHATROOM_GATEWAY_AUTH_WORKERS", "65");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(workers));

        Map<String, String> waterMarks = requiredEnvironment();
        waterMarks.put("CHATROOM_GATEWAY_WRITE_BUFFER_LOW_BYTES", "65536");
        waterMarks.put("CHATROOM_GATEWAY_WRITE_BUFFER_HIGH_BYTES", "65536");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(waterMarks));

        Map<String, String> csv = requiredEnvironment();
        csv.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "gateway.example.com,");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(csv));
    }

    @Test
    void rejectsMissingOrAliasedTlsMaterialAndNonPostgresUrls() throws Exception {
        Map<String, String> aliased = requiredEnvironment();
        aliased.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY",
                aliased.get("CHATROOM_GATEWAY_TLS_CERTIFICATE"));
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(aliased));

        Map<String, String> wrongDatabase = requiredEnvironment();
        wrongDatabase.put("CHATROOM_POSTGRES_URL", "jdbc:sqlite:test.db");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(wrongDatabase));

        Map<String, String> missingFile = requiredEnvironment();
        missingFile.put("CHATROOM_GATEWAY_TLS_CERTIFICATE",
                temporary.resolve("missing.pem").toString());
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(missingFile));
    }

    private Map<String, String> requiredEnvironment() throws Exception {
        Path certificate = temporary.resolve("certificate.pem");
        Path key = temporary.resolve("private-key.pem");
        Files.writeString(certificate, "test certificate");
        Files.writeString(key, "test private key");
        Map<String, String> environment = new HashMap<>();
        environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE", certificate.toString());
        environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY", key.toString());
        environment.put("CHATROOM_POSTGRES_URL", "jdbc:postgresql://db.internal/chat");
        environment.put("CHATROOM_POSTGRES_USER", "chat_gateway");
        environment.put("CHATROOM_POSTGRES_PASSWORD", "required-test-password");
        environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "gateway.example.com");
        environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
        return environment;
    }
}
