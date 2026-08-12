package com.fallingnight.chat.gateway.compatibility.v1;

/** Fixed-cardinality diagnostics for the detached V1 friend-directory boundary. */
public interface V1FriendDirectoryEventSink {
    void completed(int friendCount, int pendingCount, long executionNanos);
    void failed();
    void saturated();

    static V1FriendDirectoryEventSink noop() {
        return new V1FriendDirectoryEventSink() {
            @Override public void completed(int friends, int pending, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
