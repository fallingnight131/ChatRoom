package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomSettingsEventSink {
    enum Outcome { READ, INVALID_INPUT, ACCESS_DENIED }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();
    static V1RoomSettingsEventSink noop() {
        return new V1RoomSettingsEventSink() {
            @Override public void completed(Outcome outcome, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
