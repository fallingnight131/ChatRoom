package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GatewayRuntimeTest {
    @Test
    void startsAdminUnreadyThenProductAndClosesInReverseOwnershipOrder() {
        List<String> events = new ArrayList<>();
        AtomicBoolean readiness = new AtomicBoolean();
        GatewayRuntime runtime = GatewayRuntime.forTest(
                readiness,
                managed("admin", events, readiness),
                blocking("product", events, readiness, false),
                closeable("authentication-workers", events),
                closeable("messaging-workers", events),
                closeable("database", events));

        runtime.start();

        assertTrue(runtime.isReady());
        assertEquals(List.of("admin:start:false", "product:start:false"), events);
        runtime.awaitTermination();
        runtime.close();
        runtime.close();

        assertFalse(runtime.isReady());
        assertEquals(List.of(
                "admin:start:false",
                "product:start:false",
                "product:await",
                "product:stop-accepting:false",
                "product:await-drained:PT0S:false",
                "product:close",
                "admin:close",
                "messaging-workers:close",
                "authentication-workers:close",
                "database:close"), events);
        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    void failedProductStartClearsReadinessAndReleasesEveryResource() {
        List<String> events = new ArrayList<>();
        AtomicBoolean readiness = new AtomicBoolean();
        GatewayRuntime runtime = GatewayRuntime.forTest(
                readiness,
                managed("admin", events, readiness),
                blocking("product", events, readiness, true),
                closeable("authentication-workers", events),
                closeable("messaging-workers", events),
                closeable("database", events));

        assertThrows(IllegalStateException.class, runtime::start);

        assertFalse(runtime.isReady());
        assertEquals(List.of(
                "admin:start:false",
                "product:start:false",
                "product:close",
                "admin:close",
                "messaging-workers:close",
                "authentication-workers:close",
                "database:close"), events);
        assertThrows(IllegalStateException.class, runtime::awaitTermination);
    }

    private static GatewayRuntime.ManagedServer managed(
            String name, List<String> events, AtomicBoolean readiness) {
        return new GatewayRuntime.ManagedServer() {
            @Override
            public void start() {
                events.add(name + ":start:" + readiness.get());
            }

            @Override
            public void close() {
                events.add(name + ":close");
            }
        };
    }

    private static GatewayRuntime.BlockingServer blocking(
            String name,
            List<String> events,
            AtomicBoolean readiness,
            boolean failStart) {
        return new GatewayRuntime.BlockingServer() {
            @Override
            public void start() {
                events.add(name + ":start:" + readiness.get());
                if (failStart) {
                    throw new IllegalStateException("test failure");
                }
            }

            @Override
            public void awaitClose() {
                events.add(name + ":await");
            }

            @Override
            public void stopAccepting() {
                events.add(name + ":stop-accepting:" + readiness.get());
            }

            @Override
            public boolean awaitDrained(java.time.Duration timeout) {
                events.add(name + ":await-drained:" + timeout + ":" + readiness.get());
                return true;
            }

            @Override
            public void close() {
                events.add(name + ":close");
            }
        };
    }

    private static AutoCloseable closeable(String name, List<String> events) {
        return () -> events.add(name + ":close");
    }
}
