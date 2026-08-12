package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomSearchEventSink {
    enum Outcome { FOUND, INPUT_REJECTED }
    void completed(Outcome outcome, int resultCount, long executionNanos);
    void failed();
    void saturated();
    static V1RoomSearchEventSink noop() {
        return new V1RoomSearchEventSink() {
            @Override public void completed(Outcome outcome, int count, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
