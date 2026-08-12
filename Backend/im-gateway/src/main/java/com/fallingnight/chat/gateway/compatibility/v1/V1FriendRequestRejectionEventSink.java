package com.fallingnight.chat.gateway.compatibility.v1;

/** Fixed-cardinality telemetry boundary for detached V1 request rejection. */
public interface V1FriendRequestRejectionEventSink {
    enum Outcome { FIRST_ACCEPT, DUPLICATE_ACCEPT, REJECTED }

    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();

    static V1FriendRequestRejectionEventSink noop() {
        return new V1FriendRequestRejectionEventSink() {
            @Override public void completed(Outcome outcome, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
