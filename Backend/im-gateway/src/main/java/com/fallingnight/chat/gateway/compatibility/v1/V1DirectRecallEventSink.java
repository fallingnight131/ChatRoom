package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1DirectRecallEventSink {
    enum Outcome { FIRST_ROUTE_SCHEDULED, FIRST_NO_LOCAL_ROUTE, DUPLICATE, DENIED, INVALID_ID }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();
    static V1DirectRecallEventSink noop() {
        return new V1DirectRecallEventSink() {
            @Override public void completed(Outcome outcome, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
