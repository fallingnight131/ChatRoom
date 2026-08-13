package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.routing.redis.RedisRoutingConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Strict default-off environment policy for the M5 distributed routing slice. */
public final class DistributedGatewayRoutingConfig {
    public static final String ENABLED = "CHATROOM_GATEWAY_DISTRIBUTED_ROUTING_ENABLED";
    public static final String REDIS_URI = "CHATROOM_REDIS_ROUTING_URI";
    public static final String ALLOW_INSECURE_LOOPBACK =
            "CHATROOM_REDIS_ALLOW_INSECURE_LOOPBACK_FOR_TESTS";

    private final RedisRoutingConfig redis;

    private DistributedGatewayRoutingConfig(RedisRoutingConfig redis) {
        this.redis = redis;
    }

    public static DistributedGatewayRoutingConfig fromEnvironment(
            Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        if (!bool(environment, ENABLED, false)) {
            return new DistributedGatewayRoutingConfig(null);
        }
        String endpoint = required(environment, REDIS_URI);
        Duration commandTimeout = Duration.ofMillis(integer(environment,
                "CHATROOM_REDIS_COMMAND_TIMEOUT_MILLIS", 1_000, 100, 10_000));
        int requestQueueSize = integer(environment,
                "CHATROOM_REDIS_REQUEST_QUEUE_SIZE", 256, 16, 10_000);
        boolean allowInsecureLoopback = bool(environment, ALLOW_INSECURE_LOOPBACK, false);
        return new DistributedGatewayRoutingConfig(new RedisRoutingConfig(
                endpoint, commandTimeout, requestQueueSize, allowInsecureLoopback));
    }

    public boolean enabled() {
        return redis != null;
    }

    public Optional<RedisRoutingConfig> redis() {
        return Optional.ofNullable(redis);
    }

    @Override
    public String toString() {
        return enabled()
                ? "DistributedGatewayRoutingConfig[enabled=true,redis=" + redis + "]"
                : "DistributedGatewayRoutingConfig[enabled=false]";
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required when distributed routing is enabled");
        }
        return value.trim();
    }

    private static int integer(Map<String, String> environment, String name,
            int defaultValue, int minimum, int maximum) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) throw invalid(name + " outside range");
            return value;
        } catch (NumberFormatException exception) {
            throw invalid(name + " must be an integer");
        }
    }

    private static boolean bool(Map<String, String> environment,
            String name, boolean defaultValue) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw invalid(name + " must be true or false");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("invalid distributed routing configuration: " + message);
    }
}
