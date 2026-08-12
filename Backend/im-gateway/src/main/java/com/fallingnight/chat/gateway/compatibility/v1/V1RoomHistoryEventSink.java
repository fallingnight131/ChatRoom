package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomHistoryEventSink {
    enum Outcome { PAGE, ACCESS_DENIED, INVALID_CURSOR, INVALID_REQUEST }
    void completed(Outcome outcome, int itemCount, boolean sequenceMode, long executionNanos);
    void failed();
    void saturated();
    static V1RoomHistoryEventSink noop() {
        return new V1RoomHistoryEventSink() {
            @Override public void completed(Outcome outcome, int count, boolean sequence,
                    long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
