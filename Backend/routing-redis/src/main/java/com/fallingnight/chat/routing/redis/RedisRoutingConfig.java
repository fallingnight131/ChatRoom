package com.fallingnight.chat.routing.redis;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Secret-redacting Redis endpoint policy. */
public final class RedisRoutingConfig {
    private final URI uri;
    private final Duration commandTimeout;
    private final int requestQueueSize;

    public RedisRoutingConfig(String endpoint, Duration commandTimeout,
            int requestQueueSize, boolean allowInsecureLoopbackForTests) {
        Objects.requireNonNull(endpoint, "endpoint");
        this.uri = URI.create(endpoint);
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
        this.requestQueueSize = requestQueueSize;
        boolean tls = "rediss".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (!tls && !(allowInsecureLoopbackForTests && loopback
                && "redis".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Redis routing requires TLS");
        }
        if (tls && (uri.getUserInfo() == null || uri.getUserInfo().isBlank())) {
            throw new IllegalArgumentException("Redis routing requires authentication");
        }
        if (commandTimeout.compareTo(Duration.ofMillis(100)) < 0
                || commandTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("commandTimeout outside reviewed range");
        }
        if (requestQueueSize < 16 || requestQueueSize > 10_000) {
            throw new IllegalArgumentException("requestQueueSize outside reviewed range");
        }
    }

    URI uri() { return uri; }
    Duration commandTimeout() { return commandTimeout; }
    int requestQueueSize() { return requestQueueSize; }

    @Override public String toString() {
        return "RedisRoutingConfig[scheme=" + uri.getScheme() + ",host=" + uri.getHost()
                + ",port=" + uri.getPort() + ",commandTimeout=" + commandTimeout
                + ",requestQueueSize=" + requestQueueSize + "]";
    }
}
