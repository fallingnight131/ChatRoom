package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1PendingFriendRequestEventSink {
    void completed(int requestCount, long executionNanos);
    void failed();
    void saturated();

    static V1PendingFriendRequestEventSink noop() {
        return new V1PendingFriendRequestEventSink() {
            @Override public void completed(int count, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
