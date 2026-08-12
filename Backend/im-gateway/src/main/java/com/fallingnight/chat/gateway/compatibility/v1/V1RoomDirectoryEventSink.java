package com.fallingnight.chat.gateway.compatibility.v1;

/** Fixed-cardinality diagnostics for the detached V1 room-directory boundary. */
public interface V1RoomDirectoryEventSink {
    void completed(int roomCount, long executionNanos);

    void failed();

    void saturated();

    static V1RoomDirectoryEventSink noop() {
        return new V1RoomDirectoryEventSink() {
            @Override public void completed(int roomCount, long executionNanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
