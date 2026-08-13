package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomFilesEventSink {
    enum Outcome { READ, INVALID_INPUT, ADMIN_REQUIRED }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();

    static V1RoomFilesEventSink noop() {
        return new V1RoomFilesEventSink() {
            @Override public void completed(Outcome outcome, long executionNanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
