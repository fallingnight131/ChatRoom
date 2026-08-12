package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1DirectReadEventSink {
    enum Outcome {
        ADVANCED_ROUTE_SCHEDULED,
        ADVANCED_NO_LOCAL_ROUTE,
        UNCHANGED_ROUTE_SCHEDULED,
        UNCHANGED_NO_LOCAL_ROUTE,
        ACCESS_DENIED,
        INVALID_ID
    }
    void completed(Outcome outcome, long advancedBy, long executionNanos);
    void failed();
    void saturated();
    static V1DirectReadEventSink noop() {
        return new V1DirectReadEventSink() {
            @Override public void completed(Outcome outcome, long advancedBy, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
