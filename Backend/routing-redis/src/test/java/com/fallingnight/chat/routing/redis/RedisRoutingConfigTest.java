package com.fallingnight.chat.routing.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class RedisRoutingConfigTest {
    @Test void requiresTlsAndAuthenticationOutsideExplicitLoopbackTests() {
        assertThrows(IllegalArgumentException.class, () -> new RedisRoutingConfig(
                "redis://cache.internal:6379", Duration.ofSeconds(1), 100, false));
        assertThrows(IllegalArgumentException.class, () -> new RedisRoutingConfig(
                "rediss://cache.internal:6379", Duration.ofSeconds(1), 100, false));
        var config = new RedisRoutingConfig("rediss://worker:secret@cache.internal:6379",
                Duration.ofSeconds(1), 100, false);
        assertFalse(config.toString().contains("secret"));
        new RedisRoutingConfig("redis://127.0.0.1:6379", Duration.ofSeconds(1), 100, true);
    }
}
