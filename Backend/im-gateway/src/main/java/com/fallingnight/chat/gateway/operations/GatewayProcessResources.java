package com.fallingnight.chat.gateway.operations;

import java.lang.management.BufferPoolMXBean;
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
        long gcCollections = 0;
        long gcCollectionTimeMillis = 0;
        boolean gcMetricsAvailable = true;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long collections = collector.getCollectionCount();
            long collectionTimeMillis = collector.getCollectionTime();
            if (collections < 0 || collectionTimeMillis < 0) {
                gcMetricsAvailable = false;
                break;
            }
            gcCollections = Math.addExact(gcCollections, collections);
            gcCollectionTimeMillis = Math.addExact(
                    gcCollectionTimeMillis, collectionTimeMillis);
        }
        if (!gcMetricsAvailable) {
            gcCollections = 0;
            gcCollectionTimeMillis = 0;
        }
        boolean directBufferMetricsAvailable = false;
        long directBufferCount = 0;
        long directBufferMemoryUsedBytes = 0;
        long directBufferTotalCapacityBytes = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(
                BufferPoolMXBean.class)) {
            if (!"direct".equals(pool.getName())) continue;
            long count = pool.getCount();
            long memoryUsed = pool.getMemoryUsed();
            long totalCapacity = pool.getTotalCapacity();
            if (count < 0 || memoryUsed < 0 || totalCapacity < 0) {
                directBufferMetricsAvailable = false;
                directBufferCount = 0;
                directBufferMemoryUsedBytes = 0;
                directBufferTotalCapacityBytes = 0;
                break;
            }
            directBufferMetricsAvailable = true;
            directBufferCount = Math.addExact(directBufferCount, count);
            directBufferMemoryUsedBytes = Math.addExact(
                    directBufferMemoryUsedBytes, memoryUsed);
            directBufferTotalCapacityBytes = Math.addExact(
                    directBufferTotalCapacityBytes, totalCapacity);
        }
        return new ProcessResourceSnapshot(
                cpu.isPresent(),
                cpu.map(Duration::toNanos).orElse(0L),
                heap.getUsed(),
                heap.getCommitted(),
                Runtime.getRuntime().maxMemory(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                Runtime.getRuntime().availableProcessors(),
                gcMetricsAvailable, gcCollections, gcCollectionTimeMillis,
                directBufferMetricsAvailable, directBufferCount,
                directBufferMemoryUsedBytes, directBufferTotalCapacityBytes);
    }
}
