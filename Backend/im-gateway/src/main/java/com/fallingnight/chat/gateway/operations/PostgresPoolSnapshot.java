package com.fallingnight.chat.gateway.operations;

/** Fixed-cardinality view of the gateway-owned PostgreSQL connection pool. */
public record PostgresPoolSnapshot(
        boolean available,
        int activeConnections,
        int idleConnections,
        int totalConnections,
        int threadsAwaitingConnection,
        int maximumConnections) {

    public PostgresPoolSnapshot {
        if (activeConnections < 0 || idleConnections < 0 || totalConnections < 0
                || threadsAwaitingConnection < 0 || maximumConnections < 1) {
            throw new IllegalArgumentException("PostgreSQL pool gauges are invalid");
        }
        if (activeConnections > maximumConnections
                || idleConnections > maximumConnections
                || totalConnections > maximumConnections) {
            throw new IllegalArgumentException("PostgreSQL pool exceeds configured maximum");
        }
    }

    public static PostgresPoolSnapshot unavailable(int maximumConnections) {
        return new PostgresPoolSnapshot(
                false, 0, 0, 0, 0, maximumConnections);
    }
}
