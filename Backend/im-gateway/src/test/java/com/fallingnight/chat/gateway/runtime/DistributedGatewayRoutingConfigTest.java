package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DistributedGatewayRoutingConfigTest {
    @Test
    void isDisabledByDefaultWithoutRequiringRedis() {
        DistributedGatewayRoutingConfig config =
                DistributedGatewayRoutingConfig.fromEnvironment(Map.of());
        assertFalse(config.enabled());
        assertTrue(config.redis().isEmpty());
        assertEquals("DistributedGatewayRoutingConfig[enabled=false]", config.toString());
    }

    @Test
    void enabledProductionConfigRequiresTlsAuthenticationAndRedactsSecret() {
        Map<String, String> environment = new HashMap<>();
        environment.put(DistributedGatewayRoutingConfig.ENABLED, "true");
        environment.put(DistributedGatewayRoutingConfig.REDIS_URI,
                "rediss://gateway:redis-secret@redis.internal:6380/0");
        DistributedGatewayRoutingConfig config =
                DistributedGatewayRoutingConfig.fromEnvironment(environment);

        assertTrue(config.enabled());
        assertTrue(config.redis().isPresent());
        assertFalse(config.toString().contains("redis-secret"));
        assertTrue(config.toString().contains("redis.internal"));
    }

    @Test
    void rejectsMissingOrInsecureProductionEndpoint() {
        Map<String, String> environment = new HashMap<>();
        environment.put(DistributedGatewayRoutingConfig.ENABLED, "true");
        assertThrows(IllegalArgumentException.class,
                () -> DistributedGatewayRoutingConfig.fromEnvironment(environment));

        environment.put(DistributedGatewayRoutingConfig.REDIS_URI,
                "redis://redis.internal:6379/0");
        assertThrows(IllegalArgumentException.class,
                () -> DistributedGatewayRoutingConfig.fromEnvironment(environment));
    }

    @Test
    void permitsPlaintextOnlyForExplicitLoopbackTestMode() {
        Map<String, String> environment = new HashMap<>();
        environment.put(DistributedGatewayRoutingConfig.ENABLED, "true");
        environment.put(DistributedGatewayRoutingConfig.REDIS_URI,
                "redis://127.0.0.1:6379/0");
        assertThrows(IllegalArgumentException.class,
                () -> DistributedGatewayRoutingConfig.fromEnvironment(environment));

        environment.put(DistributedGatewayRoutingConfig.ALLOW_INSECURE_LOOPBACK, "true");
        assertTrue(DistributedGatewayRoutingConfig.fromEnvironment(environment).enabled());
    }

    @Test
    void rejectsMalformedFlagsAndUnsafeResourceBounds() {
        Map<String, String> malformed = new HashMap<>();
        malformed.put(DistributedGatewayRoutingConfig.ENABLED, "yes");
        assertThrows(IllegalArgumentException.class,
                () -> DistributedGatewayRoutingConfig.fromEnvironment(malformed));

        Map<String, String> queue = enabledLoopback();
        queue.put("CHATROOM_REDIS_REQUEST_QUEUE_SIZE", "10001");
        assertThrows(IllegalArgumentException.class,
                () -> DistributedGatewayRoutingConfig.fromEnvironment(queue));

        Map<String, String> timeout = enabledLoopback();
        timeout.put("CHATROOM_REDIS_COMMAND_TIMEOUT_MILLIS", "99");
        assertThrows(IllegalArgumentException.class,
                () -> DistributedGatewayRoutingConfig.fromEnvironment(timeout));
    }

    private static Map<String, String> enabledLoopback() {
        Map<String, String> environment = new HashMap<>();
        environment.put(DistributedGatewayRoutingConfig.ENABLED, "true");
        environment.put(DistributedGatewayRoutingConfig.REDIS_URI,
                "redis://localhost:6379/0");
        environment.put(DistributedGatewayRoutingConfig.ALLOW_INSECURE_LOOPBACK, "true");
        return environment;
    }
}
