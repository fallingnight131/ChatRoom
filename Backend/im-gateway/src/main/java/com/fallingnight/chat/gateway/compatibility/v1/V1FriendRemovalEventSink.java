package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1FriendRemovalEventSink {
    enum Outcome {
        FIRST_ROUTE_SCHEDULED,
        FIRST_NO_LOCAL_ROUTE,
        DUPLICATE,
        TARGET_NOT_FOUND,
        SELF_REMOVAL,
        NOT_FRIENDS,
        INVALID_TARGET
    }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();

    static V1FriendRemovalEventSink noop() {
        return new V1FriendRemovalEventSink() {
            @Override public void completed(Outcome outcome, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
