package com.fallingnight.chat.gateway.operations;

/** Fixed-cardinality JVM/process resource snapshot for the runnable gateway. */
public record ProcessResourceSnapshot(
        boolean cpuTimeAvailable,
        long processCpuTimeNanos,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaximumBytes,
        long uptimeMillis,
        int availableProcessors,
        boolean gcMetricsAvailable,
        long gcCollectionCount,
        long gcCollectionTimeMillis,
        boolean directBufferMetricsAvailable,
        long directBufferCount,
        long directBufferMemoryUsedBytes,
        long directBufferTotalCapacityBytes) {

    public ProcessResourceSnapshot {
        if (processCpuTimeNanos < 0 || heapUsedBytes < 0 || heapCommittedBytes < 0
                || heapMaximumBytes < 1 || uptimeMillis < 0 || availableProcessors < 1) {
            throw new IllegalArgumentException("process resource gauges are invalid");
        }
        if (!cpuTimeAvailable && processCpuTimeNanos != 0) {
            throw new IllegalArgumentException("unavailable CPU time must be zero");
        }
        if (gcCollectionCount < 0 || gcCollectionTimeMillis < 0) {
            throw new IllegalArgumentException("GC resource gauges are invalid");
        }
        if (!gcMetricsAvailable
                && (gcCollectionCount != 0 || gcCollectionTimeMillis != 0)) {
            throw new IllegalArgumentException("unavailable GC metrics must be zero");
        }
        if (directBufferCount < 0 || directBufferMemoryUsedBytes < 0
                || directBufferTotalCapacityBytes < 0) {
            throw new IllegalArgumentException("direct-buffer gauges are invalid");
        }
        if (!directBufferMetricsAvailable
                && (directBufferCount != 0 || directBufferMemoryUsedBytes != 0
                || directBufferTotalCapacityBytes != 0)) {
            throw new IllegalArgumentException(
                    "unavailable direct-buffer metrics must be zero");
        }
        if (heapUsedBytes > heapCommittedBytes || heapCommittedBytes > heapMaximumBytes) {
            throw new IllegalArgumentException("JVM heap gauges are inconsistent");
        }
    }
}
