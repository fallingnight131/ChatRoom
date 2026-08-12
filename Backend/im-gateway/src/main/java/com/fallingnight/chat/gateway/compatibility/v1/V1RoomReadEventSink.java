package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomReadEventSink {
    enum Outcome { ADVANCED, UNCHANGED, ACCESS_DENIED, INVALID_ROOM_ID }
    void completed(Outcome outcome, long advancedBy, long executionNanos);
    void failed();
    void saturated();
    static V1RoomReadEventSink noop() {
        return new V1RoomReadEventSink() {
            @Override public void completed(Outcome outcome, long advancedBy, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
