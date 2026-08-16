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
        assertEquals(2, config.adminWorkers());
        assertFalse(config.postgresAllowInsecureLocal());
        assertEquals(8, config.postgresPoolMaximum());
        assertEquals(1, config.postgresPoolMinimumIdle());
        assertEquals(Duration.ofSeconds(5), config.postgresConnectionTimeout());
        assertEquals(10_000, config.maximumConnections());
        assertEquals(65_536, config.writeBufferLowWaterMark());
        assertEquals(262_144, config.writeBufferHighWaterMark());
        assertEquals(4, config.authenticationWorkers());
        assertEquals(256, config.authenticationQueueCapacity());
        assertEquals(4, config.messagingWorkers());
        assertEquals(512, config.messagingQueueCapacity());
        assertEquals(Duration.ofSeconds(10), config.handshakeTimeout());
        assertEquals(Duration.ofSeconds(30), config.authenticationTimeout());
        assertEquals(Duration.ofSeconds(120), config.authenticatedIdleTimeout());
        assertEquals(Duration.ofSeconds(30), config.authenticatedHeartbeatInterval());
        assertEquals(Duration.ofSeconds(15), config.drainTimeout());
        assertEquals(Duration.ofSeconds(60), config.forwardAdmissionLimits().window());
        assertEquals(120, config.forwardAdmissionLimits().attemptsPerAccount());
        assertEquals(10_000, config.forwardAdmissionLimits().maximumTrackedAccounts());
        assertFalse(config.messageForwardingEnabled());
        assertFalse(config.messageSearchEnabled());
        assertFalse(config.accountBlockingEnabled());
        assertFalse(config.distributedRouting().enabled());
        assertEquals("development", config.releaseIdentity().releaseVersion());
        assertEquals("unknown", config.releaseIdentity().sourceRevision());
        Map<String, String> immediateDrain = requiredEnvironment();
        immediateDrain.put("CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS", "0");
        assertEquals(Duration.ZERO, GatewayRuntimeConfig.fromEnvironment(immediateDrain)
                .drainTimeout());
        Map<String, String> enabledForwarding = requiredEnvironment();
        enabledForwarding.put("CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED", "true");
        assertTrue(GatewayRuntimeConfig.fromEnvironment(enabledForwarding)
                .messageForwardingEnabled());
        Map<String, String> enabledSearch = requiredEnvironment();
        enabledSearch.put("CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED", "true");
        assertTrue(GatewayRuntimeConfig.fromEnvironment(enabledSearch)
                .messageSearchEnabled());
        Map<String, String> enabledBlocking = requiredEnvironment();
        enabledBlocking.put("CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED", "true");
        assertTrue(GatewayRuntimeConfig.fromEnvironment(enabledBlocking)
                .accountBlockingEnabled());
        Map<String, String> enabledRouting = requiredEnvironment();
        enabledRouting.put(DistributedGatewayRoutingConfig.ENABLED, "true");
        enabledRouting.put(DistributedGatewayRoutingConfig.REDIS_URI,
                "redis://127.0.0.1:6379/0");
        enabledRouting.put(DistributedGatewayRoutingConfig.ALLOW_INSECURE_LOOPBACK, "true");
        assertTrue(GatewayRuntimeConfig.fromEnvironment(enabledRouting)
                .distributedRouting().enabled());
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

        Map<String, String> messagingQueue = requiredEnvironment();
        messagingQueue.put("CHATROOM_GATEWAY_MESSAGING_QUEUE_CAPACITY", "0");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(messagingQueue));

        Map<String, String> forwardAttempts = requiredEnvironment();
        forwardAttempts.put("CHATROOM_GATEWAY_FORWARD_ATTEMPTS", "0");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(forwardAttempts));

        Map<String, String> forwardingFlag = requiredEnvironment();
        forwardingFlag.put("CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED", "TRUE");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(forwardingFlag));

        Map<String, String> searchFlag = requiredEnvironment();
        searchFlag.put("CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED", "TRUE");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(searchFlag));

        Map<String, String> blockingFlag = requiredEnvironment();
        blockingFlag.put("CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED", "TRUE");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(blockingFlag));

        Map<String, String> waterMarks = requiredEnvironment();
        waterMarks.put("CHATROOM_GATEWAY_WRITE_BUFFER_LOW_BYTES", "65536");
        waterMarks.put("CHATROOM_GATEWAY_WRITE_BUFFER_HIGH_BYTES", "65536");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(waterMarks));

        Map<String, String> csv = requiredEnvironment();
        csv.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "gateway.example.com,");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(csv));

        Map<String, String> heartbeat = requiredEnvironment();
        heartbeat.put("CHATROOM_GATEWAY_IDLE_TIMEOUT_SECONDS", "30");
        heartbeat.put("CHATROOM_GATEWAY_HEARTBEAT_INTERVAL_SECONDS", "30");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(heartbeat));

        Map<String, String> pool = requiredEnvironment();
        pool.put("CHATROOM_POSTGRES_POOL_MAXIMUM", "2");
        pool.put("CHATROOM_POSTGRES_POOL_MINIMUM_IDLE", "3");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(pool));

        Map<String, String> drain = requiredEnvironment();
        drain.put("CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS", "301");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(drain));
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

    @Test
    void requiresVerifiedDatabaseTlsExceptExplicitNumericLoopbackDevelopment() throws Exception {
        Map<String, String> insecureRemote = requiredEnvironment();
        insecureRemote.put("CHATROOM_POSTGRES_URL", "jdbc:postgresql://db.internal/chat");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(insecureRemote));

        Map<String, String> flagOnRemote = new HashMap<>(insecureRemote);
        flagOnRemote.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(flagOnRemote));

        Map<String, String> local = requiredEnvironment();
        local.put("CHATROOM_POSTGRES_URL", "jdbc:postgresql://127.0.0.1/chat");
        local.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
        assertTrue(GatewayRuntimeConfig.fromEnvironment(local).postgresAllowInsecureLocal());

        Map<String, String> invalidBoolean = requiredEnvironment();
        invalidBoolean.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "TRUE");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(invalidBoolean));

        Map<String, String> embeddedPassword = requiredEnvironment();
        embeddedPassword.put(
                "CHATROOM_POSTGRES_URL",
                "jdbc:postgresql://db.internal/chat?sslmode=verify-full&password=leak");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(embeddedPassword));

        Map<String, String> duplicateMode = requiredEnvironment();
        duplicateMode.put(
                "CHATROOM_POSTGRES_URL",
                "jdbc:postgresql://db.internal/chat?sslmode=verify-full&sslmode=disable");
        assertThrows(IllegalArgumentException.class,
                () -> GatewayRuntimeConfig.fromEnvironment(duplicateMode));
    }

    private Map<String, String> requiredEnvironment() throws Exception {
        Path certificate = temporary.resolve("certificate.pem");
        Path key = temporary.resolve("private-key.pem");
        Files.writeString(certificate, "test certificate");
        Files.writeString(key, "test private key");
        Map<String, String> environment = new HashMap<>();
        environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE", certificate.toString());
        environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY", key.toString());
        environment.put(
                "CHATROOM_POSTGRES_URL",
                "jdbc:postgresql://db.internal/chat?sslmode=verify-full");
        environment.put("CHATROOM_POSTGRES_USER", "chat_gateway");
        environment.put("CHATROOM_POSTGRES_PASSWORD", "required-test-password");
        environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "gateway.example.com");
        environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
        return environment;
    }
}
