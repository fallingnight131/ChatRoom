package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class DistributedGatewayRoutingRuntimeTest {
    @Test
    void startsLeaseBeforeConsumerAndRelayAndClosesInSafeOrder() {
        List<String> events = new ArrayList<>();
        AtomicBoolean leaseValid = new AtomicBoolean();
        var runtime = runtime(events, leaseValid,
                lifecycle(events, "relay"), lifecycle(events, "lease"),
                lifecycle(events, "consumer"), scheduler(events, true),
                () -> events.add("adapter-close"));

        assertFalse(runtime.readyForTraffic());
        runtime.start();
        assertFalse(runtime.readyForTraffic());
        leaseValid.set(true);
        assertTrue(runtime.readyForTraffic());
        runtime.close();

        assertFalse(runtime.readyForTraffic());
        assertEquals(List.of("lease-start", "consumer-start", "relay-start",
                "relay-close", "consumer-close", "lease-close", "scheduler-stop",
                "scheduler-await-PT1S", "gateway-release", "adapter-close"), events);
        assertThrows(IllegalStateException.class, runtime::start);
        runtime.close();
    }

    @Test
    void failedStartRollsBackAllOwnedResourcesAndPreservesCause() {
        List<String> events = new ArrayList<>();
        var consumer = new DistributedGatewayRoutingRuntime.Lifecycle() {
            @Override public void start() {
                events.add("consumer-start");
                throw new IllegalStateException("consumer failed");
            }
            @Override public void close() { events.add("consumer-close"); }
        };
        var runtime = runtime(events, new AtomicBoolean(),
                lifecycle(events, "relay"), lifecycle(events, "lease"), consumer,
                scheduler(events, true), () -> events.add("adapter-close"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::start);
        assertEquals("consumer failed", failure.getMessage());
        assertEquals(List.of("lease-start", "consumer-start", "relay-close",
                "consumer-close", "lease-close", "scheduler-stop",
                "scheduler-await-PT1S", "gateway-release", "adapter-close"), events);
        assertFalse(runtime.readyForTraffic());
    }

    @Test
    void continuesCleanupAndReportsSchedulerTimeout() {
        List<String> events = new ArrayList<>();
        var runtime = runtime(events, new AtomicBoolean(),
                lifecycle(events, "relay"), lifecycle(events, "lease"),
                lifecycle(events, "consumer"), scheduler(events, false),
                () -> events.add("adapter-close"));
        runtime.start();

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::close);
        assertEquals("distributed routing scheduler did not stop", failure.getMessage());
        assertTrue(events.contains("gateway-release"));
        assertEquals("adapter-close", events.getLast());
    }

    @Test
    void rejectsUnsafeShutdownTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new DistributedGatewayRoutingRuntime(
                lifecycle(new ArrayList<>(), "relay"), lifecycle(new ArrayList<>(), "lease"),
                lifecycle(new ArrayList<>(), "consumer"), () -> false, () -> true,
                scheduler(new ArrayList<>(), true), () -> { }, Duration.ofMillis(99)));
    }

    private static DistributedGatewayRoutingRuntime runtime(List<String> events,
            AtomicBoolean leaseValid, DistributedGatewayRoutingRuntime.Lifecycle relay,
            DistributedGatewayRoutingRuntime.Lifecycle lease,
            DistributedGatewayRoutingRuntime.Lifecycle consumer,
            DistributedGatewayRoutingRuntime.SchedulerOwner scheduler,
            AutoCloseable adapter) {
        return new DistributedGatewayRoutingRuntime(relay, lease, consumer, leaseValid::get,
                () -> { events.add("gateway-release"); return true; }, scheduler, adapter,
                Duration.ofSeconds(1));
    }

    private static DistributedGatewayRoutingRuntime.Lifecycle lifecycle(
            List<String> events, String name) {
        return new DistributedGatewayRoutingRuntime.Lifecycle() {
            @Override public void start() { events.add(name + "-start"); }
            @Override public void close() { events.add(name + "-close"); }
        };
    }

    private static DistributedGatewayRoutingRuntime.SchedulerOwner scheduler(
            List<String> events, boolean terminated) {
        return new DistributedGatewayRoutingRuntime.SchedulerOwner() {
            @Override public void shutdownNow() { events.add("scheduler-stop"); }
            @Override public boolean awaitTermination(Duration timeout) {
                events.add("scheduler-await-" + timeout); return terminated;
            }
        };
    }
}
