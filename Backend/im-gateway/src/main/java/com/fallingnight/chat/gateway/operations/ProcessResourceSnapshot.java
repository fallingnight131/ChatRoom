package com.fallingnight.chat.gateway.operations;

/** Fixed-cardinality JVM/process resource snapshot for the runnable gateway. */
public record ProcessResourceSnapshot(
        boolean cpuTimeAvailable,
        long processCpuTimeNanos,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaximumBytes,
        long uptimeMillis,
        int availableProcessors) {

    public ProcessResourceSnapshot {
        if (processCpuTimeNanos < 0 || heapUsedBytes < 0 || heapCommittedBytes < 0
                || heapMaximumBytes < 1 || uptimeMillis < 0 || availableProcessors < 1) {
            throw new IllegalArgumentException("process resource gauges are invalid");
        }
        if (!cpuTimeAvailable && processCpuTimeNanos != 0) {
            throw new IllegalArgumentException("unavailable CPU time must be zero");
        }
        if (heapUsedBytes > heapCommittedBytes || heapCommittedBytes > heapMaximumBytes) {
            throw new IllegalArgumentException("JVM heap gauges are inconsistent");
        }
    }
}
