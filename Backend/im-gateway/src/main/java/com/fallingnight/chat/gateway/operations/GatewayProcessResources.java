package com.fallingnight.chat.gateway.operations;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.Optional;

/** Captures portable Java process and heap resource observations. */
public final class GatewayProcessResources {
    private GatewayProcessResources() {}

    public static ProcessResourceSnapshot snapshot() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Optional<Duration> cpu = ProcessHandle.current().info().totalCpuDuration();
        return new ProcessResourceSnapshot(
                cpu.isPresent(),
                cpu.map(Duration::toNanos).orElse(0L),
                heap.getUsed(),
                heap.getCommitted(),
                Runtime.getRuntime().maxMemory(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                Runtime.getRuntime().availableProcessors());
    }
}
