package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1FriendRequestCreationEventSink {
    enum Outcome {
        FIRST_ROUTE_SCHEDULED,
        FIRST_NO_LOCAL_ROUTE,
        DUPLICATE,
        USER_NOT_FOUND,
        SELF_REQUEST,
        ALREADY_FRIENDS,
        REVERSE_PENDING,
        INVALID_TARGET
    }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();

    static V1FriendRequestCreationEventSink noop() {
        return new V1FriendRequestCreationEventSink() {
            @Override public void completed(Outcome outcome, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
