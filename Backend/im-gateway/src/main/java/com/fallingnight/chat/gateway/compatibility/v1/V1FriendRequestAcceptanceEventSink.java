package com.fallingnight.chat.gateway.compatibility.v1;

/** Fixed-cardinality telemetry for detached V1 friend-request acceptance. */
public interface V1FriendRequestAcceptanceEventSink {
    enum Outcome { FIRST_ROUTE_SCHEDULED, FIRST_NO_LOCAL_ROUTE, DUPLICATE, REJECTED }

    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();

    static V1FriendRequestAcceptanceEventSink noop() {
        return new V1FriendRequestAcceptanceEventSink() {
            @Override public void completed(Outcome outcome, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
