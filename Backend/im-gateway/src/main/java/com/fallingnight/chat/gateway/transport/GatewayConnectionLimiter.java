package com.fallingnight.chat.gateway.transport;

import java.util.concurrent.atomic.AtomicInteger;

/** Process-local hard cap for accepted gateway child channels. */
public final class GatewayConnectionLimiter {
    private final int maximum;
    private final AtomicInteger active = new AtomicInteger();

    public GatewayConnectionLimiter(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        this.maximum = maximum;
    }

    boolean tryAcquire() {
        while (true) {
            int current = active.get();
            if (current >= maximum) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void release() {
        active.decrementAndGet();
    }

    public int activeConnections() {
        return active.get();
    }
}
