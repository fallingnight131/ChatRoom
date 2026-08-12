package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomCreationEventSink {
    enum Outcome { FIRST_CREATED, DUPLICATE, INVALID_INPUT, DENIED, CONFLICT }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();
    static V1RoomCreationEventSink noop() {
        return new V1RoomCreationEventSink() {
            @Override public void completed(Outcome outcome, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
