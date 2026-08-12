package com.fallingnight.chat.gateway.compatibility.v1;

/** Fixed-cardinality telemetry boundary for detached V1 direct history. */
public interface V1DirectHistoryEventSink {
    enum Outcome { PAGE, ACCESS_DENIED, INVALID_CURSOR, INVALID_REQUEST }

    void completed(Outcome outcome, int resultCount, boolean sequenceMode,
            long executionNanos);
    void failed();
    void saturated();

    static V1DirectHistoryEventSink noop() {
        return new V1DirectHistoryEventSink() {
            @Override public void completed(
                    Outcome outcome, int count, boolean sequence, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
